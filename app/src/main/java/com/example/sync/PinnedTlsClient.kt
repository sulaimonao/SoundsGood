package com.example.sync

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.ProtocolException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Timer
import java.util.TimerTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

open class SyncException(message: String, cause: Throwable? = null) : IOException(message, cause)
class SyncAuthExpiredException(message: String) : SyncException(message)
class SyncRevisionConflictException(message: String) : SyncException(message)
class SyncProtocolException(message: String, cause: Throwable? = null) : SyncException(message, cause)
class SyncNetworkException(message: String, cause: Throwable? = null) : SyncException(message, cause)
class SyncStorageException(message: String, cause: Throwable? = null, val reason: String = "local_storage_error") : SyncException(message, cause)
class SyncCancelledException(message: String = "Sync cancelled.") : SyncException(message)
class SyncTlsException(message: String, cause: Throwable? = null) : SyncException(message, cause)
/** Only this type permits the manager to continue with another audio file. */
class SyncTrackTransferException(
    val reason: String,
    val receivedBytes: Long,
    val hashStatus: String = "not_checked",
    message: String,
    cause: Throwable? = null
) : SyncException(message, cause)

interface SyncTransport {
    suspend fun fetchManifest(credentials: PairingCredentials): SyncManifest
    suspend fun downloadTrackAudio(
        credentials: PairingCredentials,
        revision: String,
        track: SyncTrackDto,
        partFile: File,
        onBytesProgress: (bytesRead: Long) -> Unit
    )
    suspend fun sendClientReportBatch(
        credentials: PairingCredentials,
        revision: String,
        body: ByteArray
    ): SyncClientReportPostResult = SyncClientReportPostResult.UNSUPPORTED
    fun abortActiveConnections()
}

/**
 * Scoped TLS 1.2+ TrustManager checking certificate validity, self-signed signature,
 * exact constant-time DER SHA-256 fingerprint pin, and IPv4 SAN (type 7) BEFORE any HTTP
 * request headers (including the bearer token) are transmitted.
 */
class PinnedSelfSignedTrustManager(
    private val expectedHost: String,
    private val expectedFingerprintSha256: ByteArray
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not supported.")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Server presented an empty certificate chain.")
        }
        val leaf = chain[0]
        // 1. Validity window
        leaf.checkValidity()

        // 2. Self-signed signature verification
        try {
            leaf.verify(leaf.publicKey)
        } catch (e: Exception) {
            throw CertificateException("Server certificate self-signed signature check failed.", e)
        }

        // 3. Exact DER SHA-256 fingerprint check using constant-time comparison
        val der = leaf.encoded
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(der)
        if (!MessageDigest.isEqual(actualDigest, expectedFingerprintSha256)) {
            throw CertificateException("Server certificate fingerprint mismatch.")
        }

        // 4. Require IPv4 SubjectAlternativeName (type 7) matching the paired IPv4 host
        val altNames = leaf.subjectAlternativeNames
            ?: throw CertificateException("Server certificate has no SubjectAlternativeName extension.")
        val hasMatchingIpSan = altNames.any { entry ->
            if (entry.size >= 2 && entry[0] == 7) {
                val sanIp = entry[1]?.toString()
                sanIp == expectedHost
            } else {
                false
            }
        }
        if (!hasMatchingIpSan) {
            throw CertificateException("Server certificate IPv4 SAN does not match $expectedHost.")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

class Tls12PlusSocketFactory(
    private val delegate: SSLSocketFactory
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(): Socket = configureTls(delegate.createSocket())

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        configureTls(delegate.createSocket(s, host, port, autoClose))

    override fun createSocket(host: String?, port: Int): Socket =
        configureTls(delegate.createSocket(host, port))

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        configureTls(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        configureTls(delegate.createSocket(host, port))

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        configureTls(delegate.createSocket(address, port, localAddress, localPort))

    private fun configureTls(socket: Socket): Socket {
        if (socket is SSLSocket) {
            val allowed = socket.supportedProtocols
                .filter { it == "TLSv1.2" || it == "TLSv1.3" }
                .toTypedArray()
            if (allowed.isNotEmpty()) {
                socket.enabledProtocols = allowed
            }
        }
        return socket
    }
}

class PinnedTlsSyncTransport(
    private val networkSelector: NetworkBoundOpener,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
    private val hostnameVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
) : SyncTransport {

    private val activeConnection = AtomicReference<HttpsURLConnection?>(null)
    @Volatile
    private var aborted = false
    @Volatile
    private var networkLost = false

    fun createScopedSslSocketFactory(credentials: PairingCredentials): SSLSocketFactory {
        val trustManager = PinnedSelfSignedTrustManager(
            expectedHost = credentials.host,
            expectedFingerprintSha256 = credentials.fingerprintBytes
        )
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        return Tls12PlusSocketFactory(sslContext.socketFactory)
    }

    override fun abortActiveConnections() {
        networkLost = false
        aborted = true
        activeConnection.getAndSet(null)?.let { conn ->
            runCatching { conn.disconnect() }
        }
    }

    private fun abortForNetworkLoss() {
        networkLost = true
        aborted = true
        activeConnection.getAndSet(null)?.let { conn -> runCatching { conn.disconnect() } }
    }

    private fun abortFailure(): SyncException = if (networkLost) {
        SyncNetworkException("Local Wi-Fi connection was lost. Verified completed music was retained.")
    } else SyncCancelledException()

    override suspend fun fetchManifest(credentials: PairingCredentials): SyncManifest {
        currentCoroutineContext().ensureActive()
        aborted = false
        networkLost = false
        val url = URL("https://${credentials.host}:${credentials.port}/v1/manifest")
        val boundNetwork = networkSelector.selectWifiNetworkForLocalHost(credentials.address)
            .getOrElse { throw it }

        val lossRegistration = boundNetwork.registerLossCallback { abortForNetworkLoss() }
        try {
            val conn = openConfiguredConnection(
                boundNetwork = boundNetwork,
                url = url,
                credentials = credentials,
                revisionHeader = null
            )
            activeConnection.set(conn)
            try {
                val contentLength = validateResponseHeaders(
                    conn = conn,
                    expectedRevision = null,
                    maxBytes = SyncManifestValidator.MAX_MANIFEST_BYTES,
                    isAudio = false
                )
                val out = ByteArrayOutputStream(contentLength.toInt().coerceIn(256, 65536))
                val bytesRead = streamExactBytes(
                    conn = conn,
                    expectedLength = contentLength,
                    maxBytes = SyncManifestValidator.MAX_MANIFEST_BYTES,
                    onChunk = { buf, len -> out.write(buf, 0, len) }
                )
                if (bytesRead != contentLength) {
                    throw SyncProtocolException("Truncated manifest response ($bytesRead of $contentLength bytes).")
                }
                return when (val parsed = SyncManifestValidator.parseAndValidate(out.toByteArray())) {
                    is ManifestValidationResult.Valid -> parsed.manifest
                    is ManifestValidationResult.Invalid -> throw SyncProtocolException(parsed.reason)
                }
            } finally {
                activeConnection.compareAndSet(conn, null)
                runCatching { conn.disconnect() }
            }
        } catch (e: SyncException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (aborted) throw abortFailure()
            if (isTlsFailure(e)) throw SyncTlsException("Mac TLS trust check failed. Pair again with the current sharing session.", e)
            val safeMsg = PairingUriParser.redactSecrets(e.message, credentials.token)
            throw SyncNetworkException("Unable to connect to Mac at ${credentials.displayAddress}: $safeMsg", e)
        } finally {
            runCatching { lossRegistration.close() }
        }
    }

    override suspend fun downloadTrackAudio(
        credentials: PairingCredentials,
        revision: String,
        track: SyncTrackDto,
        partFile: File,
        onBytesProgress: (bytesRead: Long) -> Unit
    ) {
        currentCoroutineContext().ensureActive()
        aborted = false
        networkLost = false
        var observedBytes = 0L
        if (!SyncManifestValidator.ID_REGEX.matches(track.id)) {
            throw SyncProtocolException("Invalid track ID.")
        }
        val url = URL("https://${credentials.host}:${credentials.port}/v1/tracks/${track.id}/audio")
        val boundNetwork = networkSelector.selectWifiNetworkForLocalHost(credentials.address)
            .getOrElse { throw it }

        val lossRegistration = boundNetwork.registerLossCallback { abortForNetworkLoss() }
        try {
            val conn = openConfiguredConnection(
                boundNetwork = boundNetwork,
                url = url,
                credentials = credentials,
                revisionHeader = revision
            )
            activeConnection.set(conn)
            try {
                val contentLength = validateResponseHeaders(
                    conn = conn,
                    expectedRevision = revision,
                    maxBytes = SyncManifestValidator.MAX_AUDIO_FILE_BYTES,
                    isAudio = true,
                    expectedAudioBytes = track.sizeBytes
                )
                val digest = MessageDigest.getInstance("SHA-256")
                val expectedHash = PairingUriParser.hexToBytes(track.sha256)

                val output = try { FileOutputStream(partFile, false) }
                catch (e: IOException) { throw storageFailure(e) }
                try {
                    val fos = output
                    val bytesRead = streamExactBytes(
                        conn = conn,
                        expectedLength = contentLength,
                        maxBytes = SyncManifestValidator.MAX_AUDIO_FILE_BYTES,
                        recoverableTrack = true,
                        onChunk = { buf, len ->
                            digest.update(buf, 0, len)
                            try { fos.write(buf, 0, len) }
                            catch (e: IOException) { throw storageFailure(e) }
                        },
                        onProgress = { bytes ->
                            observedBytes = bytes
                            onBytesProgress(bytes)
                        }
                    )
                    try { fos.fd.sync() }
                    catch (e: IOException) { throw storageFailure(e) }
                    if (bytesRead != track.sizeBytes) {
                        throw SyncTrackTransferException(
                            reason = "unexpected_eof", receivedBytes = bytesRead,
                            message = "Audio stream ended early ($bytesRead of ${track.sizeBytes} bytes)."
                        )
                    }
                } finally {
                    try { output.close() }
                    catch (e: IOException) { throw storageFailure(e) }
                }

                val actualHash = digest.digest()
                if (!MessageDigest.isEqual(actualHash, expectedHash)) {
                    throw SyncTrackTransferException(
                        reason = "hash_mismatch", receivedBytes = observedBytes, hashStatus = "mismatch",
                        message = "Audio integrity check (SHA-256) failed."
                    )
                }
            } finally {
                activeConnection.compareAndSet(conn, null)
                runCatching { conn.disconnect() }
            }
        } catch (e: SyncException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (aborted) throw abortFailure()
            if (isTlsFailure(e)) throw SyncTlsException("Mac TLS trust check failed. Pair again with the current sharing session.", e)
            if (e !is IOException) throw SyncProtocolException("Unexpected local transfer failure.", e)
            val unexpectedEof = e is EOFException || e.message.equals("Premature EOF", ignoreCase = true) || (e is ProtocolException &&
                e.message?.contains("unexpected end of stream", ignoreCase = true) == true)
            if (e is ProtocolException && !unexpectedEof) {
                throw SyncProtocolException("Malformed HTTP response from the Mac companion.", e)
            }
            val reason = when {
                e is SocketTimeoutException -> "timeout"
                unexpectedEof -> "unexpected_eof"
                else -> "connection_failed"
            }
            throw SyncTrackTransferException(
                reason = reason, receivedBytes = observedBytes,
                message = "Audio transfer failed: ${reason.replace('_', ' ')} ($observedBytes of ${track.sizeBytes} bytes).", cause = e
            )
        } finally {
            runCatching { lossRegistration.close() }
        }
    }

    override suspend fun sendClientReportBatch(
        credentials: PairingCredentials,
        revision: String,
        body: ByteArray
    ): SyncClientReportPostResult {
        currentCoroutineContext().ensureActive()
        require(body.isNotEmpty() && body.size <= SyncClientReports.MAX_BATCH_BYTES)
        require(SyncManifestValidator.ID_REGEX.matches(revision))
        val request = try { Json.parseToJsonElement(body.decodeToString(throwOnInvalidSequence = true)) as? JsonObject }
        catch (_: Exception) { null } ?: throw SyncProtocolException("Invalid local client-report payload.")
        val isFinal = request.booleanField("finalBatch")
            ?: throw SyncProtocolException("Client report final-batch flag is missing.")
        aborted = false
        networkLost = false
        val boundNetwork = networkSelector.selectWifiNetworkForLocalHost(credentials.address).getOrElse { throw it }
        val registration = boundNetwork.registerLossCallback { abortForNetworkLoss() }
        try {
            val conn = openConfiguredConnection(
                boundNetwork, URL("https://${credentials.host}:${credentials.port}/v1/client-report"), credentials, revision
            )
            activeConnection.set(conn)
            val expired = AtomicBoolean(false)
            // HttpsURLConnection has no separate write deadline. Disconnect the
            // exact report connection after a bounded overall interval as well.
            val deadline = Timer("SoundsGood-report-deadline", true)
            deadline.schedule(object : TimerTask() {
                override fun run() {
                    expired.set(true)
                    runCatching { conn.disconnect() }
                }
            }, (connectTimeoutMs.toLong() + readTimeoutMs.toLong()).coerceAtLeast(1_000L))
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
                currentCoroutineContext().ensureActive()
                if (expired.get()) throw SocketTimeoutException("Client report deadline expired.")
                val code = conn.responseCode
                if (code == 404 || code == 405) return SyncClientReportPostResult.UNSUPPORTED
                val length = validateResponseHeaders(conn, revision, 4_096L, isAudio = false)
                val output = ByteArrayOutputStream()
                streamExactBytes(conn, length, 4_096L, { buffer, count -> output.write(buffer, 0, count) })
                    .also { if (it != length) throw SyncProtocolException("Truncated client-report acknowledgement.") }
                if (expired.get()) throw SocketTimeoutException("Client report deadline expired.")
                val acknowledgement = try {
                    Json.parseToJsonElement(output.toByteArray().decodeToString(throwOnInvalidSequence = true)) as? JsonObject
                } catch (_: Exception) { null } ?: throw SyncProtocolException("Invalid client-report acknowledgement.")
                if (acknowledgement.keys != setOf("accepted", "duplicate", "reportComplete") ||
                    acknowledgement.booleanField("accepted") != true ||
                    acknowledgement.booleanField("duplicate") == null ||
                    acknowledgement.booleanField("reportComplete") == null ||
                    (isFinal && acknowledgement.booleanField("reportComplete") != true)
                ) throw SyncProtocolException("Mac did not confirm the client report.")
                return SyncClientReportPostResult.ACCEPTED
            } finally {
                deadline.cancel()
                activeConnection.compareAndSet(conn, null)
                runCatching { conn.disconnect() }
            }
        } catch (e: SyncException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (aborted) throw abortFailure()
            if (isTlsFailure(e)) throw SyncTlsException("Mac TLS trust check failed during report delivery.", e)
            if (e is IOException) throw SyncNetworkException("Client report could not reach the Mac.", e)
            throw SyncProtocolException("Invalid client-report response.", e)
        } finally {
            runCatching { registration.close() }
        }
    }

    private fun JsonObject.booleanField(name: String): Boolean? =
        (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    private fun isTlsFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        repeat(12) {
            val value = current ?: return false
            if (value is CertificateException || value is SSLHandshakeException ||
                value is SSLPeerUnverifiedException || value is SSLProtocolException) return true
            if (value.cause === value) return false
            current = value.cause
        }
        return false
    }

    private fun storageFailure(error: IOException): SyncStorageException {
        val full = generateSequence<Throwable>(error) { it.cause?.takeUnless { cause -> cause === it } }
            .take(12).any { it.message?.contains("ENOSPC", ignoreCase = true) == true ||
                it.message?.contains("No space left", ignoreCase = true) == true }
        return SyncStorageException(
            if (full) "Device storage is full. Verified completed music was retained." else "Could not write audio to device storage.",
            error, if (full) "storage_full" else "local_storage_error"
        )
    }

    private fun openConfiguredConnection(
        boundNetwork: BoundWifiNetwork,
        url: URL,
        credentials: PairingCredentials,
        revisionHeader: String?
    ): HttpsURLConnection {
        if (aborted) throw abortFailure()
        val conn = boundNetwork.openConnection(url)
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.sslSocketFactory = createScopedSslSocketFactory(credentials)
        conn.hostnameVerifier = hostnameVerifier
        conn.setRequestProperty("Authorization", "Bearer ${credentials.token}")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Connection", "close")
        if (revisionHeader != null) {
            conn.setRequestProperty("X-SoundsGood-Revision", revisionHeader)
        }
        return conn
    }

    private fun validateResponseHeaders(
        conn: HttpsURLConnection,
        expectedRevision: String?,
        maxBytes: Long,
        isAudio: Boolean,
        expectedAudioBytes: Long = -1L
    ): Long {
        val code = conn.responseCode
        when (code) {
            200 -> Unit
            401, 403 -> throw SyncAuthExpiredException("Mac pairing session unavailable or expired. Paste a fresh link to pair again.")
            409 -> {
                if (isAudio && expectedRevision != null && isUnavailableSourceResponse(conn, expectedRevision)) {
                    throw SyncTrackTransferException(
                        reason = "source_unavailable", receivedBytes = 0L,
                        message = "Mac could not safely open this source file; it may have changed since sharing began."
                    )
                }
                if (aborted) throw abortFailure()
                throw SyncRevisionConflictException("Mac library changed while syncing. Refresh the manifest or restart Mac sharing.")
            }
            in 300..399 -> throw SyncProtocolException("Server redirect (HTTP $code) is not permitted.")
            else -> if (isAudio) {
                val responseRevision = conn.getHeaderField("X-SoundsGood-Revision")?.trim()
                if (responseRevision.isNullOrEmpty() || !SyncManifestValidator.ID_REGEX.matches(responseRevision)) {
                    throw SyncProtocolException("Missing or invalid revision on an audio error response.")
                }
                if (responseRevision != expectedRevision) throw SyncRevisionConflictException("Server revision changed. Refresh the manifest.")
                throw SyncTrackTransferException(
                    reason = if (code == 404 || code == 410) "source_unavailable" else "http_error",
                    receivedBytes = 0L, message = "Mac could not serve this audio file (HTTP $code)."
                )
            } else throw SyncProtocolException("Mac companion returned HTTP $code.")
        }

        val contentEncoding = conn.getHeaderField("Content-Encoding")?.trim()
        if (!contentEncoding.isNullOrEmpty() && !contentEncoding.equals("identity", ignoreCase = true)) {
            throw SyncProtocolException("Encoded HTTP body ($contentEncoding) is not allowed.")
        }
        val transferEncoding = conn.getHeaderField("Transfer-Encoding")?.trim()
        if (!transferEncoding.isNullOrEmpty() && !transferEncoding.equals("identity", ignoreCase = true)) {
            throw SyncProtocolException("Chunked or encoded Transfer-Encoding ($transferEncoding) is not permitted.")
        }

        val contentType = conn.getHeaderField("Content-Type")?.trim()
        if (contentType.isNullOrEmpty()) {
            throw SyncProtocolException("Missing Content-Type response header.")
        }
        if (!isAudio && !contentType.lowercase().startsWith("application/json")) {
            throw SyncProtocolException("Unexpected manifest Content-Type: $contentType.")
        }

        val responseRevision = conn.getHeaderField("X-SoundsGood-Revision")?.trim()
        if (responseRevision.isNullOrEmpty() || !SyncManifestValidator.ID_REGEX.matches(responseRevision)) {
            throw SyncProtocolException("Missing or invalid X-SoundsGood-Revision response header.")
        }
        if (expectedRevision != null && responseRevision != expectedRevision) {
            throw SyncRevisionConflictException("Server revision changed ($responseRevision vs $expectedRevision). Refresh the manifest.")
        }

        val lengthHeader = conn.getHeaderField("Content-Length")?.trim()
            ?: throw SyncProtocolException("Missing Content-Length response header.")
        val contentLength = lengthHeader.toLongOrNull()
            ?: throw SyncProtocolException("Invalid Content-Length header.")
        if (contentLength < 0L || contentLength > maxBytes) {
            throw SyncProtocolException("Response Content-Length ($contentLength bytes) exceeds permitted limit.")
        }
        if (expectedAudioBytes >= 0L && contentLength != expectedAudioBytes) {
            throw SyncTrackTransferException(
                reason = "size_mismatch", receivedBytes = 0L,
                message = "Server Content-Length ($contentLength) does not match manifest size ($expectedAudioBytes)."
            )
        }
        return contentLength
    }

    private fun isUnavailableSourceResponse(conn: HttpsURLConnection, expectedRevision: String): Boolean {
        val expected = "source_changed_or_unavailable".toByteArray(Charsets.US_ASCII)
        if (conn.getHeaderField("X-SoundsGood-Revision")?.trim() != expectedRevision ||
            conn.getHeaderField("Content-Type")?.substringBefore(';')?.trim()?.lowercase() != "text/plain" ||
            conn.getHeaderField("Content-Length")?.trim() != expected.size.toString() ||
            !conn.getHeaderField("Transfer-Encoding").isNullOrBlank() ||
            !conn.getHeaderField("Content-Encoding").isNullOrBlank()
        ) return false
        return try {
            val input = conn.errorStream ?: return false
            input.use {
                val observed = ByteArrayOutputStream(expected.size)
                val buffer = ByteArray(128)
                while (true) {
                    if (aborted) return false
                    val count = it.read(buffer)
                    if (count == -1) break
                    if (observed.size() + count > expected.size) return false
                    observed.write(buffer, 0, count)
                }
                observed.toByteArray().contentEquals(expected)
            }
        } catch (_: IOException) { false }
    }

    private suspend fun streamExactBytes(
        conn: HttpsURLConnection,
        expectedLength: Long,
        maxBytes: Long,
        onChunk: (ByteArray, Int) -> Unit,
        onProgress: (Long) -> Unit = {},
        recoverableTrack: Boolean = false
    ): Long {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        conn.inputStream.use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                if (aborted) throw abortFailure()
                val read = input.read(buffer)
                if (read == -1) break
                total += read.toLong()
                // Count bytes actually observed, even if the subsequent local
                // write or size check fails. Headers are excluded.
                onProgress(total)
                if (total > expectedLength || total > maxBytes) {
                    if (!recoverableTrack) throw SyncProtocolException("Response body exceeded declared Content-Length.")
                    throw SyncTrackTransferException(
                        reason = "size_mismatch", receivedBytes = total,
                        message = "Response body exceeded declared Content-Length."
                    )
                }
                onChunk(buffer, read)
            }
        }
        return total
    }
}
