package com.example

import com.example.sync.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.ProtocolException
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercise production HTTP classification and receipt handling without real music/network. */
class SyncTransferOutcomeUnitTest {
    private val revision = "a".repeat(64)
    private val payload = "eight123".toByteArray()
    private fun credentials() = (PairingUriParser.parse(
        "soundsgood://pair?v=1&host=192.168.1.20&port=54321&fingerprint=${"1".repeat(64)}&token=${"a".repeat(43)}"
    ) as PairingParseResult.Valid).credentials
    private fun track() = SyncTrackDto("t_fixture", "Fixture", "", "", "", 1000, payload.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }, "audio/mpeg", "mp3")

    private class Reply(
        val status: Int,
        val headers: Map<String, String>,
        val body: InputStream,
        val failure: Exception? = null
    ) : HttpsURLConnection(URL("https://192.168.1.20:54321/v1/manifest")) {
        val sent = ByteArrayOutputStream()
        var wasDisconnected = false
        override fun connect() = Unit
        override fun disconnect() { wasDisconnected = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "synthetic"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode(): Int { failure?.let { throw it }; return status }
        override fun getHeaderField(name: String?): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        override fun getInputStream(): InputStream = body
        override fun getErrorStream(): InputStream = body
        override fun getOutputStream() = sent
    }

    private fun reply(body: InputStream, status: Int = 200, length: Int = payload.size, type: String = "audio/mpeg", failure: Exception? = null) = Reply(
        status, mapOf("Content-Length" to "$length", "Content-Type" to type, "X-SoundsGood-Revision" to revision), body, failure
    )
    private fun transport(response: Reply) = PinnedTlsSyncTransport(object : NetworkBoundOpener {
        override fun selectWifiNetworkForLocalHost(destAddress: Inet4Address) = Result.success(
            BoundWifiNetwork(null, { response }, { AutoCloseable { } })
        )
    })

    @Test fun partialStreamFailureReportsObservedAudioBytes() = runBlocking {
        val prefix = payload.copyOf(3)
        val input = object : InputStream() {
            var offset = 0
            override fun read(): Int = if (offset < prefix.size) prefix[offset++].toInt() and 255 else throw ProtocolException("unexpected end of stream")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (this.offset == prefix.size) throw ProtocolException("unexpected end of stream")
                val count = minOf(length, prefix.size - this.offset)
                prefix.copyInto(buffer, offset, this.offset, this.offset + count)
                this.offset += count
                return count
            }
        }
        val response = reply(input)
        val part = Files.createTempFile("soundsgood-outcome", ".part").toFile()
        var progress = 0L
        try {
            try { transport(response).downloadTrackAudio(credentials(), revision, track(), part) { progress = it }; fail("Expected short stream") }
            catch (error: SyncTrackTransferException) {
                assertEquals("unexpected_eof", error.reason)
                assertEquals(3L, error.receivedBytes)
                assertEquals("not_checked", error.hashStatus)
                assertEquals(3L, progress)
            }
            assertTrue(response.wasDisconnected)
        } finally { part.delete() }
    }

    @Test fun sameLengthCorruptionIsAHashFailureAndTlsFailureIsFatal() = runBlocking {
        val part = Files.createTempFile("soundsgood-outcome", ".part").toFile()
        try {
            val corrupted = payload.copyOf().also { it[0] = 0 }
            try { transport(reply(ByteArrayInputStream(corrupted))).downloadTrackAudio(credentials(), revision, track(), part) { }; fail("Expected hash failure") }
            catch (error: SyncTrackTransferException) {
                assertEquals("hash_mismatch", error.reason)
                assertEquals("mismatch", error.hashStatus)
                assertEquals(payload.size.toLong(), error.receivedBytes)
            }
            try { transport(reply(ByteArrayInputStream(payload), failure = SSLHandshakeException("synthetic")))
                .downloadTrackAudio(credentials(), revision, track(), part) { }; fail("Expected trust failure") }
            catch (_: SyncTlsException) { }
        } finally { part.delete() }
    }

    @Test fun anUnwritableDestinationStopsInsteadOfPretendingNetworkFailure() = runBlocking {
        val directory = Files.createTempDirectory("soundsgood-no-file").toFile()
        try {
            try { transport(reply(ByteArrayInputStream(payload))).downloadTrackAudio(credentials(), revision, track(), directory) { }; fail("Expected storage failure") }
            catch (error: SyncStorageException) { assertEquals("local_storage_error", error.reason) }
        } finally { directory.delete() }
    }

    @Test fun malformedHttpStatusRemainsAFatalProtocolFailure() = runBlocking {
        val part = Files.createTempFile("soundsgood-outcome", ".part").toFile()
        try {
            try { transport(reply(ByteArrayInputStream(payload), failure = ProtocolException("Unexpected status line")))
                .downloadTrackAudio(credentials(), revision, track(), part) { }; fail("Expected protocol failure") }
            catch (_: SyncProtocolException) { }
        } finally { part.delete() }
    }

    @Test fun unconnectedSocketFactoryPathKeepsTls12OrNewer() {
        val socket = transport(reply(ByteArrayInputStream(payload))).createScopedSslSocketFactory(credentials()).createSocket()
        socket.use {
            assertTrue(it is SSLSocket)
            assertTrue((it as SSLSocket).enabledProtocols.isNotEmpty())
            assertTrue(it.enabledProtocols.all { protocol -> protocol == "TLSv1.2" || protocol == "TLSv1.3" })
        }
    }

    @Test fun audioErrorsCannotBypassTheRevisionGuard() = runBlocking {
        val part = Files.createTempFile("soundsgood-outcome", ".part").toFile()
        try {
            val base = reply(ByteArrayInputStream(ByteArray(0)), status = 404, length = 0, type = "text/plain")
            val changed = Reply(404, base.headers + ("X-SoundsGood-Revision" to "changed"), ByteArrayInputStream(ByteArray(0)))
            try { transport(changed).downloadTrackAudio(credentials(), revision, track(), part) { }; fail("Expected revision failure") }
            catch (_: SyncRevisionConflictException) { }
            val missing = Reply(404, base.headers - "X-SoundsGood-Revision", ByteArrayInputStream(ByteArray(0)))
            try { transport(missing).downloadTrackAudio(credentials(), revision, track(), part) { }; fail("Expected protocol failure") }
            catch (_: SyncProtocolException) { }
        } finally { part.delete() }
    }

    @Test fun wifiLossIsStoppedNetworkFailureRatherThanUserCancellation() = runBlocking {
        var onLost: (() -> Unit)? = null
        val input = object : ByteArrayInputStream(payload) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                onLost?.invoke()
                onLost = null
                return super.read(buffer, offset, length)
            }
        }
        val response = reply(input)
        val client = PinnedTlsSyncTransport(object : NetworkBoundOpener {
            override fun selectWifiNetworkForLocalHost(destAddress: Inet4Address) = Result.success(
                BoundWifiNetwork(null, { response }, { callback -> onLost = callback; AutoCloseable { onLost = null } })
            )
        })
        val part = Files.createTempFile("soundsgood-outcome", ".part").toFile()
        try {
            try { client.downloadTrackAudio(credentials(), revision, track(), part) { }; fail("Expected local Wi-Fi loss") }
            catch (_: SyncNetworkException) { }
        } finally { part.delete() }
    }

    @Test fun onlyExactSourceUnavailable409CanBeSkipped() = runBlocking {
        val part = Files.createTempFile("soundsgood-outcome", ".part").toFile()
        try {
            val sourceCode = "source_changed_or_unavailable".toByteArray()
            try { transport(reply(ByteArrayInputStream(sourceCode), 409, sourceCode.size, "text/plain; charset=utf-8"))
                .downloadTrackAudio(credentials(), revision, track(), part) { }; fail("Expected source failure") }
            catch (error: SyncTrackTransferException) { assertEquals("source_unavailable", error.reason) }
            val conflict = "snapshot_changed".toByteArray()
            try { transport(reply(ByteArrayInputStream(conflict), 409, conflict.size, "text/plain"))
                .downloadTrackAudio(credentials(), revision, track(), part) { }; fail("Expected global revision conflict") }
            catch (_: SyncRevisionConflictException) { }
        } finally { part.delete() }
    }

    @Test fun reportAcknowledgementMustConfirmFinalCompleteness() = runBlocking {
        val body = "{\"finalBatch\":true}".toByteArray()
        fun ack(complete: Boolean): Reply {
            val bytes = "{\"accepted\":true,\"duplicate\":false,\"reportComplete\":$complete}".toByteArray()
            return reply(ByteArrayInputStream(bytes), length = bytes.size, type = "application/json")
        }
        val response = ack(true)
        assertEquals(SyncClientReportPostResult.ACCEPTED, transport(response).sendClientReportBatch(credentials(), revision, body))
        assertArrayEquals(body, response.sent.toByteArray())
        assertEquals("POST", response.requestMethod)
        assertEquals("Bearer ${credentials().token}", response.getRequestProperty("Authorization"))
        assertFalse(response.instanceFollowRedirects)
        try { transport(ack(false)).sendClientReportBatch(credentials(), revision, body); fail("Expected incomplete rejection") }
        catch (_: SyncProtocolException) { }
        val quoted = "{\"accepted\":\"true\",\"duplicate\":false,\"reportComplete\":\"true\"}".toByteArray()
        try { transport(reply(ByteArrayInputStream(quoted), length = quoted.size, type = "application/json"))
            .sendClientReportBatch(credentials(), revision, body); fail("Quoted booleans must not confirm delivery") }
        catch (_: SyncProtocolException) { }
        assertEquals(SyncClientReportPostResult.UNSUPPORTED,
            transport(reply(ByteArrayInputStream(ByteArray(0)), status = 404, length = 0, type = "text/plain"))
                .sendClientReportBatch(credentials(), revision, body))
    }
}
