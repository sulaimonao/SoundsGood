package com.example

import com.example.sync.BoundWifiNetwork
import com.example.sync.ManifestValidationResult
import com.example.sync.NetworkBoundOpener
import com.example.sync.PairingCredentials
import com.example.sync.PairingParseResult
import com.example.sync.PairingUriParser
import com.example.sync.PinnedSelfSignedTrustManager
import com.example.sync.PinnedTlsSyncTransport
import com.example.sync.SyncAuthExpiredException
import com.example.sync.SyncManifestValidator
import com.example.sync.SyncProtocolException
import com.example.sync.SyncRevisionConflictException
import com.example.sync.SyncTlsException
import com.example.sync.SyncTrackDto
import com.example.sync.SyncTrackTransferException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * JVM unit tests for:
 * - Strict `soundsgood://pair` URI validation and token redaction
 * - Manifest JSON validation, size bounds, ID/hash regexes, and extension/MIME pairing
 * - Real TLS (`SSLServerSocket` + X.509 v3 DER certificates) pin, SAN, signature, expiry,
 *   redirect refusal, header validation, and byte/SHA-256 failure checks
 */
class LocalSyncProtocolUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sampleToken = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef0123456789_" // 43 unpadded base64url chars
    private val sampleFingerprint = "0123456789abcdef".repeat(4) // 64 lowercase hex chars

    @Test
    fun `pairing URI parser accepts canonical RFC 1918 addresses and ports`() {
        val validHosts = listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.42")
        for (host in validHosts) {
            val uri = "soundsgood://pair?v=1&host=$host&port=8443&fingerprint=$sampleFingerprint&token=$sampleToken"
            val result = PairingUriParser.parse(uri)
            assertTrue("Expected valid for $host", result is PairingParseResult.Valid)
            val creds = (result as PairingParseResult.Valid).credentials
            assertEquals(host, creds.host)
            assertEquals(8443, creds.port)
            assertEquals(sampleFingerprint, creds.fingerprintHex)
            assertEquals(sampleToken, creds.token)
            assertFalse("toString must never expose token", creds.toString().contains(sampleToken))
        }
    }

    @Test
    fun `pairing URI parser rejects malformed, public, loopback, hostname, fragment, and duplicate params`() {
        val base = "soundsgood://pair?v=1&host=192.168.1.10&port=8443&fingerprint=$sampleFingerprint&token=$sampleToken"
        val invalidCases = listOf(
            "",
            "https://pair?v=1&host=192.168.1.10&port=8443&fingerprint=$sampleFingerprint&token=$sampleToken",
            "soundsgood://other?v=1&host=192.168.1.10&port=8443&fingerprint=$sampleFingerprint&token=$sampleToken",
            "soundsgood://user@pair?v=1&host=192.168.1.10&port=8443&fingerprint=$sampleFingerprint&token=$sampleToken",
            "soundsgood://pair:8443?v=1&host=192.168.1.10&port=8443&fingerprint=$sampleFingerprint&token=$sampleToken",
            "soundsgood://pair/extra?v=1&host=192.168.1.10&port=8443&fingerprint=$sampleFingerprint&token=$sampleToken",
            "$base#frag",
            "soundsgood://pair?v=2&host=192.168.1.10&port=8443&fingerprint=$sampleFingerprint&token=$sampleToken",
            "soundsgood://pair?v=1&host=8.8.8.8&port=8443&fingerprint=$sampleFingerprint&token=$sampleToken",
            "soundsgood://pair?v=1&host=127.0.0.1&port=8443&fingerprint=$sampleFingerprint&token=$sampleToken",
            "soundsgood://pair?v=1&host=169.254.1.5&port=8443&fingerprint=$sampleFingerprint&token=$sampleToken",
            "soundsgood://pair?v=1&host=172.15.255.1&port=8443&fingerprint=$sampleFingerprint&token=$sampleToken",
            "soundsgood://pair?v=1&host=172.32.0.1&port=8443&fingerprint=$sampleFingerprint&token=$sampleToken",
            "soundsgood://pair?v=1&host=192.168.01.10&port=8443&fingerprint=$sampleFingerprint&token=$sampleToken",
            "soundsgood://pair?v=1&host=macbook.local&port=8443&fingerprint=$sampleFingerprint&token=$sampleToken",
            "soundsgood://pair?v=1&host=192.168.1.10&port=1023&fingerprint=$sampleFingerprint&token=$sampleToken",
            "soundsgood://pair?v=1&host=192.168.1.10&port=65536&fingerprint=$sampleFingerprint&token=$sampleToken",
            "soundsgood://pair?v=1&host=192.168.1.10&port=8443&fingerprint=${sampleFingerprint.uppercase()}&token=$sampleToken",
            "soundsgood://pair?v=1&host=192.168.1.10&port=8443&fingerprint=abc&token=$sampleToken",
            "soundsgood://pair?v=1&host=192.168.1.10&port=8443&fingerprint=$sampleFingerprint&token=short",
            "soundsgood://pair?v=1&host=192.168.1.10&port=8443&fingerprint=$sampleFingerprint&token=${sampleToken.dropLast(1)}=",
            "soundsgood://pair?v=1&host=192.168.1.10&port=8443&fingerprint=$sampleFingerprint",
            "$base&extra=1",
            "$base&v=1"
        )
        for (bad in invalidCases) {
            val parsed = PairingUriParser.parse(bad)
            assertTrue("Expected Invalid for $bad", parsed is PairingParseResult.Invalid)
        }
        val redacted = PairingUriParser.redactSecrets("Failure using $sampleToken", sampleToken)
        assertFalse(redacted.contains(sampleToken))
        assertTrue(redacted.contains("***"))
    }

    @Test
    fun `manifest validator accepts unknown fields and repeated playlist entries while enforcing schema constraints`() {
        val trackHash = "a".repeat(64)
        val validJson = """
            {
              "protocolVersion": 1,
              "libraryId": "mac_lib_01",
              "libraryName": "Mac Studio Music",
              "revision": "rev-001",
              "futureField": "ignored",
              "tracks": [
                {
                  "id": "track_1",
                  "title": "Song One",
                  "artist": "Artist A",
                  "album": "Album X",
                  "genre": "Jazz",
                  "durationMs": 120000,
                  "sizeBytes": 1024,
                  "sha256": "$trackHash",
                  "mimeType": "audio/mpeg",
                  "extension": "mp3",
                  "extraNested": {"ok": true}
                }
              ],
              "playlists": [
                {
                  "id": "pl_1",
                  "name": "Repeats",
                  "trackIds": ["track_1", "track_1"]
                }
              ],
              "skipped": [
                {"title": "Protected.m4p", "reason": "DRM protected"}
              ]
            }
        """.trimIndent().encodeToByteArray()

        val validResult = SyncManifestValidator.parseAndValidate(validJson)
        assertTrue(validResult is ManifestValidationResult.Valid)
        val manifest = (validResult as ManifestValidationResult.Valid).manifest
        assertEquals(listOf("track_1", "track_1"), manifest.playlists.first().trackIds)

        val mismatchedExt = validJson.decodeToString().replace("\"audio/mpeg\"", "\"audio/flac\"").encodeToByteArray()
        assertTrue(SyncManifestValidator.parseAndValidate(mismatchedExt) is ManifestValidationResult.Invalid)

        val unknownRef = validJson.decodeToString().replace("[\"track_1\", \"track_1\"]", "[\"missing_id\"]").encodeToByteArray()
        assertTrue(SyncManifestValidator.parseAndValidate(unknownRef) is ManifestValidationResult.Invalid)

        val badVersion = validJson.decodeToString().replace("\"protocolVersion\": 1", "\"protocolVersion\": 2").encodeToByteArray()
        assertTrue(SyncManifestValidator.parseAndValidate(badVersion) is ManifestValidationResult.Invalid)

        val tooLongTitle = "X".repeat(1025)
        val oversizedField = validJson.decodeToString().replace("Song One", tooLongTitle).encodeToByteArray()
        assertTrue(SyncManifestValidator.parseAndValidate(oversizedField) is ManifestValidationResult.Invalid)

        val duplicateTrackId = validJson.decodeToString().replace(
            "\"playlists\": [",
            """
                ,
                {"id":"track_1","title":"Dup","artist":"A","album":"B","genre":"C","durationMs":10,"sizeBytes":10,"sha256":"$trackHash","mimeType":"audio/mpeg","extension":"mp3"}
              ],
              "playlists": [
            """.trimIndent()
        )
        // Fix double comma above cleanly using regex/valid array
        val dupJson = """
            {
              "protocolVersion": 1,
              "libraryId": "mac_lib_01",
              "libraryName": "Mac",
              "revision": "rev-1",
              "tracks": [
                {"id":"t1","title":"A","artist":"","album":"","genre":"","durationMs":0,"sizeBytes":10,"sha256":"$trackHash","mimeType":"audio/mp4","extension":"m4a"},
                {"id":"t1","title":"B","artist":"","album":"","genre":"","durationMs":0,"sizeBytes":10,"sha256":"$trackHash","mimeType":"audio/mp4","extension":"m4a"}
              ],
              "playlists": [],
              "skipped": []
            }
        """.trimIndent().encodeToByteArray()
        assertTrue(SyncManifestValidator.parseAndValidate(dupJson) is ManifestValidationResult.Invalid)
    }

    @Test
    fun `trust manager enforces DER pin, IPv4 SAN, validity window, and self-signed signature`() {
        val kp = generateEcKeyPair()
        val now = System.currentTimeMillis()
        val cert = buildSelfSignedCert(
            keyPair = kp,
            sanIpv4 = byteArrayOf(192.toByte(), 168.toByte(), 1.toByte(), 50.toByte()),
            notBefore = Date(now - 60_000L),
            notAfter = Date(now + 3_600_000L)
        )
        val pin = sha256(cert.encoded)
        val tm = PinnedSelfSignedTrustManager("192.168.1.50", pin)
        tm.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")

        // Wrong pin rejected
        val wrongPinTm = PinnedSelfSignedTrustManager("192.168.1.50", ByteArray(32) { 0x42 })
        assertThrowsCertificateException {
            wrongPinTm.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
        }

        // Wrong SAN IP rejected
        val wrongIpTm = PinnedSelfSignedTrustManager("192.168.1.51", pin)
        assertThrowsCertificateException {
            wrongIpTm.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
        }

        // Expired cert rejected
        val expiredCert = buildSelfSignedCert(
            keyPair = kp,
            sanIpv4 = byteArrayOf(192.toByte(), 168.toByte(), 1.toByte(), 50.toByte()),
            notBefore = Date(now - 7_200_000L),
            notAfter = Date(now - 3_600_000L)
        )
        val expiredPin = sha256(expiredCert.encoded)
        assertThrowsCertificateException {
            PinnedSelfSignedTrustManager("192.168.1.50", expiredPin)
                .checkServerTrusted(arrayOf(expiredCert), "ECDHE_ECDSA")
        }

        // Corrupted / forged signature with matching pin rejected
        val otherKeyPair = generateEcKeyPair()
        val invalidSigCert = buildSelfSignedCert(
            keyPair = kp,
            signingKey = otherKeyPair.private,
            sanIpv4 = byteArrayOf(192.toByte(), 168.toByte(), 1.toByte(), 50.toByte()),
            notBefore = Date(now - 60_000L),
            notAfter = Date(now + 3_600_000L)
        )
        val invalidSigPin = sha256(invalidSigCert.encoded)
        assertThrowsCertificateException {
            PinnedSelfSignedTrustManager("192.168.1.50", invalidSigPin)
                .checkServerTrusted(arrayOf(invalidSigCert), "ECDHE_ECDSA")
        }
    }

    @Test
    fun `real TLS server succeeds with pinned cert, sends expected headers, and handles manifest plus audio`() = runTest {
        val kp = generateEcKeyPair()
        val now = System.currentTimeMillis()
        val cert = buildSelfSignedCert(
            keyPair = kp,
            sanIpv4 = byteArrayOf(127, 0, 0, 1),
            notBefore = Date(now - 60_000L),
            notAfter = Date(now + 3_600_000L)
        )
        val certPin = sha256(cert.encoded)
        val audioBytes = "FAKE_SYNTHETIC_MP3_STREAM_BYTES_0123456789".encodeToByteArray()
        val audioSha = PairingUriParser.bytesToLowerHex(sha256(audioBytes))

        val manifestJson = """
            {
              "protocolVersion": 1,
              "libraryId": "mac_1",
              "libraryName": "Studio Mac",
              "revision": "rev-abc",
              "tracks": [
                {
                  "id": "tr_1",
                  "title": "TLS Audio",
                  "artist": "Synth",
                  "album": "Fixtures",
                  "genre": "Electronic",
                  "durationMs": 5000,
                  "sizeBytes": ${audioBytes.size},
                  "sha256": "$audioSha",
                  "mimeType": "audio/mpeg",
                  "extension": "mp3"
                }
              ],
              "playlists": [],
              "skipped": []
            }
        """.trimIndent().encodeToByteArray()

        val observedHeaders = mutableListOf<Map<String, String>>()
        val server = startLocalTlsServer(kp.private, cert) { path, headers, out ->
            observedHeaders.add(headers)
            when (path) {
                "/v1/manifest" -> {
                    writeHttpResponse(
                        out = out,
                        status = "200 OK",
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "Content-Length" to manifestJson.size.toString(),
                            "X-SoundsGood-Revision" to "rev-abc",
                            "Connection" to "close"
                        ),
                        body = manifestJson
                    )
                }
                "/v1/tracks/tr_1/audio" -> {
                    writeHttpResponse(
                        out = out,
                        status = "200 OK",
                        headers = mapOf(
                            "Content-Type" to "audio/mpeg",
                            "Content-Length" to audioBytes.size.toString(),
                            "X-SoundsGood-Revision" to "rev-abc",
                            "Connection" to "close"
                        ),
                        body = audioBytes
                    )
                }
                else -> writeHttpResponse(out, "404 Not Found", mapOf("Content-Length" to "0"), ByteArray(0))
            }
        }

        try {
            val creds = loopbackCreds(server.port, certPin)
            val transport = PinnedTlsSyncTransport(loopbackOpener())

            val manifest = transport.fetchManifest(creds)
            assertEquals("Studio Mac", manifest.libraryName)
            assertEquals("rev-abc", manifest.revision)

            val partFile = tempFolder.newFile("track.part")
            transport.downloadTrackAudio(
                credentials = creds,
                revision = manifest.revision,
                track = manifest.tracks.first(),
                partFile = partFile,
                onBytesProgress = {}
            )
            assertArrayEquals(audioBytes, partFile.readBytes())

            // Verify strict HTTP request headers sent to the companion
            assertEquals(2, observedHeaders.size)
            assertEquals("Bearer $sampleToken", observedHeaders[0]["authorization"])
            assertEquals("identity", observedHeaders[0]["accept-encoding"])
            assertEquals("close", observedHeaders[0]["connection"])
            assertNull(observedHeaders[0]["x-soundsgood-revision"])

            assertEquals("Bearer $sampleToken", observedHeaders[1]["authorization"])
            assertEquals("rev-abc", observedHeaders[1]["x-soundsgood-revision"])
        } finally {
            server.close()
        }
    }

    @Test
    fun `real TLS server rejects redirect without following, handles 401 and 409, and rejects gzip or truncated bodies`() = runTest {
        val kp = generateEcKeyPair()
        val now = System.currentTimeMillis()
        val cert = buildSelfSignedCert(
            keyPair = kp,
            sanIpv4 = byteArrayOf(127, 0, 0, 1),
            notBefore = Date(now - 60_000L),
            notAfter = Date(now + 3_600_000L)
        )
        val certPin = sha256(cert.encoded)
        var mode = "redirect"
        var redirectTargetHit = false

        val server = startLocalTlsServer(kp.private, cert) { path, _, out ->
            if (path == "/redirect-target") {
                redirectTargetHit = true
                writeHttpResponse(out, "200 OK", mapOf("Content-Length" to "2"), "{}".encodeToByteArray())
                return@startLocalTlsServer
            }
            when (mode) {
                "redirect" -> writeHttpResponse(
                    out, "302 Found",
                    mapOf("Location" to "/redirect-target", "Content-Length" to "0"),
                    ByteArray(0)
                )
                "401" -> writeHttpResponse(out, "401 Unauthorized", mapOf("Content-Length" to "0"), ByteArray(0))
                "409" -> writeHttpResponse(out, "409 Conflict", mapOf("Content-Length" to "0"), ByteArray(0))
                "gzip" -> writeHttpResponse(
                    out, "200 OK",
                    mapOf(
                        "Content-Type" to "application/json",
                        "Content-Encoding" to "gzip",
                        "X-SoundsGood-Revision" to "rev-1",
                        "Content-Length" to "2"
                    ),
                    "{}".encodeToByteArray()
                )
                "truncated_audio" -> writeHttpResponse(
                    out, "200 OK",
                    mapOf(
                        "Content-Type" to "audio/mpeg",
                        "X-SoundsGood-Revision" to "rev-1",
                        "Content-Length" to "100"
                    ),
                    "ONLY_14_BYTES!".encodeToByteArray()
                )
                "wrong_pin_server" -> writeHttpResponse(out, "200 OK", mapOf("Content-Length" to "2"), "{}".encodeToByteArray())
            }
        }

        try {
            val creds = loopbackCreds(server.port, certPin)
            val transport = PinnedTlsSyncTransport(loopbackOpener())

            // Redirect refused and never followed
            mode = "redirect"
            val redirectErr = runCatching { transport.fetchManifest(creds) }.exceptionOrNull()
            assertTrue(redirectErr is SyncProtocolException)
            assertFalse(redirectTargetHit)

            // 401 mapped to SyncAuthExpiredException
            mode = "401"
            val authErr = runCatching { transport.fetchManifest(creds) }.exceptionOrNull()
            assertTrue(authErr is SyncAuthExpiredException)

            // 409 mapped to SyncRevisionConflictException
            mode = "409"
            val conflictErr = runCatching { transport.fetchManifest(creds) }.exceptionOrNull()
            assertTrue(conflictErr is SyncRevisionConflictException)

            // Encoded body refused
            mode = "gzip"
            val gzipErr = runCatching { transport.fetchManifest(creds) }.exceptionOrNull()
            assertTrue(gzipErr is SyncProtocolException)

            // Truncated audio stream refused cleanly
            mode = "truncated_audio"
            val track = SyncTrackDto(
                id = "t1", title = "Broken", artist = "A", album = "B", genre = "C",
                durationMs = 1000, sizeBytes = 100, sha256 = "0".repeat(64),
                mimeType = "audio/mpeg", extension = "mp3"
            )
            val partFile = tempFolder.newFile("trunc.part")
            val truncErr = runCatching {
                transport.downloadTrackAudio(creds, "rev-1", track, partFile) {}
            }.exceptionOrNull()
            assertTrue("Expected a track transfer failure, got $truncErr", truncErr is SyncTrackTransferException)
            val transferError = truncErr as SyncTrackTransferException
            assertEquals("cause=${transferError.cause}", "unexpected_eof", transferError.reason)
            assertEquals(14L, transferError.receivedBytes)
            assertEquals("not_checked", transferError.hashStatus)

            // Wrong fingerprint rejected during TLS handshake BEFORE sending HTTP request
            mode = "wrong_pin_server"
            val badCreds = loopbackCreds(server.port, ByteArray(32) { 0x11 })
            val pinErr = runCatching { transport.fetchManifest(badCreds) }.exceptionOrNull()
            assertTrue("Expected SyncTlsException on TLS pin mismatch, got $pinErr", pinErr is SyncTlsException)
        } finally {
            server.close()
        }
    }

    private fun loopbackCreds(port: Int, pin: ByteArray): PairingCredentials {
        val addr = InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1)) as Inet4Address
        return PairingCredentials(
            host = "127.0.0.1",
            port = port,
            fingerprintHex = PairingUriParser.bytesToLowerHex(pin),
            fingerprintBytes = pin,
            token = sampleToken,
            address = addr
        )
    }

    private fun loopbackOpener(): NetworkBoundOpener = object : NetworkBoundOpener {
        override fun selectWifiNetworkForLocalHost(destAddress: Inet4Address): Result<BoundWifiNetwork> {
            return Result.success(
                BoundWifiNetwork(
                    network = null,
                    openConnection = { url: URL ->
                        url.openConnection(Proxy.NO_PROXY) as HttpsURLConnection
                    },
                    registerLossCallback = { AutoCloseable { } }
                )
            )
        }
    }

    private class LocalTlsServerHandle(
        val port: Int,
        private val serverSocket: SSLServerSocket,
        private val worker: Thread
    ) : AutoCloseable {
        override fun close() {
            runCatching { serverSocket.close() }
            worker.interrupt()
        }
    }

    private fun startLocalTlsServer(
        privateKey: PrivateKey,
        cert: X509Certificate,
        handler: (path: String, headers: Map<String, String>, out: OutputStream) -> Unit
    ): LocalTlsServerHandle {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("server", privateKey, charArrayOf(), arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, charArrayOf())
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, null)
        }
        val serverSocket = (sslContext.serverSocketFactory.createServerSocket(0, 16, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).apply {
            enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        }
        val thread = thread(isDaemon = true, name = "test-tls-server") {
            while (!serverSocket.isClosed) {
                val client = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    client.use { sock ->
                        val reader = sock.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val requestLine = reader.readLine() ?: return@use
                        val path = requestLine.split(' ').getOrNull(1) ?: "/"
                        val headers = LinkedHashMap<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val colon = line.indexOf(':')
                            if (colon > 0) {
                                headers[line.substring(0, colon).trim().lowercase()] =
                                    line.substring(colon + 1).trim()
                            }
                        }
                        val out = sock.getOutputStream()
                        handler(path, headers, out)
                        out.flush()
                    }
                } catch (_: Exception) {
                    // Closed during pin failure tests is expected
                }
            }
        }
        return LocalTlsServerHandle(serverSocket.localPort, serverSocket, thread)
    }

    private fun writeHttpResponse(
        out: OutputStream,
        status: String,
        headers: Map<String, String>,
        body: ByteArray
    ) {
        val headerBlock = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { (k, v) ->
                append(k).append(": ").append(v).append("\r\n")
            }
            append("\r\n")
        }.encodeToByteArray()
        out.write(headerBlock)
        if (body.isNotEmpty()) {
            out.write(body)
        }
    }

    private fun assertThrowsCertificateException(block: () -> Unit) {
        var threw = false
        try {
            block()
        } catch (_: CertificateException) {
            threw = true
        }
        assertTrue("Expected CertificateException", threw)
    }

    private fun generateEcKeyPair(): KeyPair {
        return KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /**
     * Minimal pure-Kotlin ASN.1 DER X.509 v3 self-signed certificate builder (ECDSA SHA-256 + IPv4 SAN).
     * Avoids internal sun.security.* APIs so unit tests compile cleanly across JDK toolchains.
     */
    private fun buildSelfSignedCert(
        keyPair: KeyPair,
        signingKey: PrivateKey = keyPair.private,
        sanIpv4: ByteArray,
        notBefore: Date,
        notAfter: Date
    ): X509Certificate {
        val oidEcdsaSha256 = derOid(intArrayOf(1, 2, 840, 10045, 4, 3, 2))
        val algId = derSeq(oidEcdsaSha256)
        val oidCn = derOid(intArrayOf(2, 5, 4, 3))
        val cnAttr = derSet(derSeq(oidCn, derUtf8String("SoundsGoodMac")))
        val name = derSeq(cnAttr)
        val validity = derSeq(derUtcTime(notBefore), derUtcTime(notAfter))

        // Extension 2.5.29.17 (SubjectAltName) -> GeneralNames SEQUENCE { [7] iPAddress OCTET STRING }
        val sanExtOid = derOid(intArrayOf(2, 5, 29, 17))
        val sanValueDer = derOctetString(derSeq(derPrimitive(0x87, sanIpv4)))
        val sanExt = derSeq(sanExtOid, sanValueDer)
        val extensions = derExplicit(3, derSeq(sanExt))

        val version = derExplicit(0, derInteger(BigInteger.valueOf(2))) // v3 = 2
        val serial = derInteger(BigInteger.ONE)

        val tbs = derSeq(
            version,
            serial,
            algId,
            name,
            validity,
            name,
            keyPair.public.encoded,
            extensions
        )

        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(signingKey)
            update(tbs)
        }.sign()

        val bitStringSig = derBitString(sig)
        val certDer = derSeq(tbs, algId, bitStringSig)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    private fun derSeq(vararg items: ByteArray): ByteArray = derTag(0x30, concat(*items))
    private fun derSet(vararg items: ByteArray): ByteArray = derTag(0x31, concat(*items))
    private fun derExplicit(tagNum: Int, inner: ByteArray): ByteArray = derTag(0xA0 or tagNum, inner)
    private fun derOctetString(bytes: ByteArray): ByteArray = derTag(0x04, bytes)
    private fun derUtf8String(text: String): ByteArray = derTag(0x0C, text.encodeToByteArray())
    private fun derPrimitive(tag: Int, bytes: ByteArray): ByteArray = derTag(tag, bytes)
    private fun derInteger(value: BigInteger): ByteArray = derTag(0x02, value.toByteArray())

    private fun derBitString(bytes: ByteArray): ByteArray {
        val out = ByteArray(bytes.size + 1)
        out[0] = 0
        System.arraycopy(bytes, 0, out, 1, bytes.size)
        return derTag(0x03, out)
    }

    private fun derUtcTime(date: Date): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return derTag(0x17, fmt.format(date).encodeToByteArray())
    }

    private fun derOid(parts: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) {
            var v = parts[i]
            val stack = IntArray(6)
            var len = 0
            stack[len++] = v and 0x7F
            v = v ushr 7
            while (v > 0) {
                stack[len++] = (v and 0x7F) or 0x80
                v = v ushr 7
            }
            for (j in len - 1 downTo 0) {
                out.write(stack[j])
            }
        }
        return derTag(0x06, out.toByteArray())
    }

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 128 -> out.write(len)
            len < 256 -> {
                out.write(0x81)
                out.write(len)
            }
            len < 65536 -> {
                out.write(0x82)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(0x83)
                out.write((len ushr 16) and 0xFF)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        arrays.forEach { out.write(it) }
        return out.toByteArray()
    }
}
