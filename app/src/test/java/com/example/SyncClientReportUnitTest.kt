package com.example

import com.example.sync.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class SyncClientReportUnitTest {
    private fun report(count: Int): SyncClientReport = SyncClientReport(
        libraryId = "l_fixture", revision = "a".repeat(64), reportId = "d1c2c843-9947-43ad-8296-07e50ac6bc73",
        results = (0 until count).map { SyncTrackResult("t_$it", "downloaded_verified_imported", 8, "verified", "none") },
        playlistResults = listOf(SyncPlaylistResult("p_fixture", "updated")),
        summary = SyncRunSummary("complete", "none", count, 1, count, 0, 0, 0, 1, 0, 0)
    )

    private fun credentials(): PairingCredentials = (PairingUriParser.parse(
        "soundsgood://pair?v=1&host=192.168.1.20&port=54321&fingerprint=${"1".repeat(64)}&token=${"a".repeat(43)}"
    ) as PairingParseResult.Valid).credentials

    private class RecordingTransport(private val respond: (Int) -> SyncClientReportPostResult) : SyncTransport {
        val bodies = mutableListOf<ByteArray>()
        override suspend fun fetchManifest(credentials: PairingCredentials): SyncManifest = error("Not used")
        override suspend fun downloadTrackAudio(credentials: PairingCredentials, revision: String, track: SyncTrackDto,
            partFile: File, onBytesProgress: (Long) -> Unit) = error("Not used")
        override fun abortActiveConnections() = Unit
        override suspend fun sendClientReportBatch(credentials: PairingCredentials, revision: String, body: ByteArray): SyncClientReportPostResult {
            bodies += body.copyOf()
            return respond(bodies.size)
        }
    }

    @Test fun batchesHaveExactLimitsAndOnlyFinalSummary() {
        val original = report(201)
        val batches = SyncClientReports.serializeBatches(original)
        assertEquals(3, batches.size)
        val ids = mutableListOf<String>()
        batches.forEachIndexed { index, bytes ->
            assertTrue(bytes.size <= 65_536)
            val obj = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            assertEquals(index.toString(), obj.getValue("batchIndex").jsonPrimitive.content)
            assertEquals("1", obj.getValue("protocolVersion").jsonPrimitive.content)
            val tracks = obj.getValue("results").jsonArray
            val playlists = obj.getValue("playlistResults").jsonArray
            assertTrue(tracks.size + playlists.size <= 100)
            assertEquals(index == batches.lastIndex, "summary" in obj)
            assertEquals((index == batches.lastIndex).toString(), obj.getValue("finalBatch").jsonPrimitive.content)
            tracks.forEach { ids += it.jsonObject.getValue("trackId").jsonPrimitive.content }
        }
        assertEquals(original.results.map { it.trackId }, ids)
        assertEquals(original, SyncClientReports.decodeLocal(SyncClientReports.encodeLocal(original)))
    }

    @Test fun invalidCountersDuplicateIdsAndFailureEvidenceAreRefused() {
        val base = report(2)
        val invalid = listOf(
            base.copy(summary = base.summary.copy(downloadedImported = 1)),
            base.copy(results = listOf(base.results[0], base.results[0])),
            base.copy(results = listOf(base.results[0].copy(trackId = "../outside"), base.results[1])),
            base.copy(results = listOf(base.results[0].copy(outcome = "failed", reason = "hash_mismatch", hashStatus = "not_checked"), base.results[1])),
            base.copy(results = listOf(base.results[0].copy(outcome = "failed", reason = "import_failed", hashStatus = "not_checked"), base.results[1]))
        )
        invalid.forEach {
            try { SyncClientReports.serializeBatches(it); fail("Invalid report accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertNull(SyncClientReports.decodeLocal("{\"unexpected\":true}"))
    }

    @Test fun maximumOpaqueIdsStillFitTheByteLimit() {
        val base = report(100)
        val large = base.copy(results = base.results.mapIndexed { index, row -> row.copy(trackId = "t_" + index.toString().padStart(98, 'a')) })
        assertTrue(SyncClientReports.serializeBatches(large).all { it.size <= 65_536 })
    }

    @Test fun transientReportRetryUsesIdenticalBytes() = runBlocking {
        val transport = RecordingTransport { call ->
            if (call == 1) throw SyncNetworkException("Synthetic connection loss")
            SyncClientReportPostResult.ACCEPTED
        }
        assertEquals(SyncReportDelivery.DELIVERED, SyncClientReports.deliver(report(1), credentials(), transport))
        assertEquals(2, transport.bodies.size)
        assertArrayEquals(transport.bodies[0], transport.bodies[1])
    }

    @Test fun unsupportedAndTrustFailuresStopReportDelivery() = runBlocking {
        val unsupported = RecordingTransport { SyncClientReportPostResult.UNSUPPORTED }
        assertEquals(SyncReportDelivery.UNSUPPORTED, SyncClientReports.deliver(report(201), credentials(), unsupported))
        assertEquals(1, unsupported.bodies.size)
        val tlsFailure = RecordingTransport { throw SyncTlsException("Synthetic trust failure") }
        assertEquals(SyncReportDelivery.INCOMPLETE, SyncClientReports.deliver(report(201), credentials(), tlsFailure))
        assertEquals(1, tlsFailure.bodies.size)
        val repeatedNetworkFailure = RecordingTransport { throw SyncNetworkException("Synthetic connection loss") }
        assertEquals(SyncReportDelivery.INCOMPLETE, SyncClientReports.deliver(report(201), credentials(), repeatedNetworkFailure))
        assertEquals(2, repeatedNetworkFailure.bodies.size)
    }
}
