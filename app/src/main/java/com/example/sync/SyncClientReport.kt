package com.example.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Opaque protocol IDs and fixed codes only. Titles and paths never cross this API. */
@Serializable
data class SyncTrackResult(
    val trackId: String,
    val outcome: String,
    val receivedBytes: Long,
    val hashStatus: String,
    val reason: String
)

@Serializable
data class SyncPlaylistResult(val playlistId: String, val outcome: String)

@Serializable
data class SyncRunSummary(
    val outcome: String,
    val stopReason: String,
    val plannedTracks: Int,
    val plannedPlaylists: Int,
    val downloadedImported: Int,
    val reusedImported: Int,
    val failed: Int,
    val notAttempted: Int,
    val playlistsUpdated: Int,
    val playlistsPreserved: Int,
    val playlistsDeferred: Int
)

@Serializable
data class SyncClientReport(
    val libraryId: String,
    val revision: String,
    val reportId: String,
    val results: List<SyncTrackResult>,
    val playlistResults: List<SyncPlaylistResult>,
    val summary: SyncRunSummary
)

@Serializable
private data class SyncClientReportBatch(
    val protocolVersion: Int = 1,
    val libraryId: String,
    val revision: String,
    val reportId: String,
    val batchIndex: Int,
    val finalBatch: Boolean,
    val results: List<SyncTrackResult>,
    val playlistResults: List<SyncPlaylistResult>,
    val summary: SyncRunSummary? = null
)

enum class SyncClientReportPostResult { ACCEPTED, UNSUPPORTED }
enum class SyncReportDelivery { DELIVERED, UNSUPPORTED, INCOMPLETE }

/** A retained result is independent of whether its optional Mac delivery succeeds. */
@OptIn(ExperimentalSerializationApi::class)
object SyncClientReports {
    const val MAX_BATCH_BYTES = 65_536
    const val MAX_LOCAL_BYTES = 64 * 1024 * 1024
    private const val MAX_ROWS_PER_BATCH = 100
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
    }

    val reasons: Set<String> = setOf(
        "none", "source_unavailable", "connection_failed", "timeout", "unexpected_eof",
        "size_mismatch", "hash_mismatch", "storage_full", "unsupported_format", "cancelled",
        "manifest_changed", "verification_failed", "import_failed", "session_expired", "http_error",
        "not_attempted", "local_storage_error", "tls_failed", "protocol_error"
    )

    fun encodeLocal(report: SyncClientReport): String {
        validate(report)
        return json.encodeToString(report).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_LOCAL_BYTES) { "Local report is too large." }
        }
    }

    fun decodeLocal(text: String): SyncClientReport? {
        if (text.length > MAX_LOCAL_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_LOCAL_BYTES) return null
        return try {
            json.decodeFromString<SyncClientReport>(text).also { validate(it) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun serializeBatches(report: SyncClientReport): List<ByteArray> {
        validate(report)
        val batches = ArrayList<ByteArray>()
        var trackOffset = 0
        var playlistOffset = 0
        do {
            val trackEnd = minOf(report.results.size, trackOffset + MAX_ROWS_PER_BATCH)
            val tracks = report.results.subList(trackOffset, trackEnd)
            val playlistEnd = minOf(report.playlistResults.size, playlistOffset + MAX_ROWS_PER_BATCH - tracks.size)
            val playlists = report.playlistResults.subList(playlistOffset, playlistEnd)
            trackOffset += tracks.size
            playlistOffset += playlists.size
            val final = trackOffset == report.results.size && playlistOffset == report.playlistResults.size
            val bytes = json.encodeToString(
                SyncClientReportBatch(
                    libraryId = report.libraryId,
                    revision = report.revision,
                    reportId = report.reportId,
                    batchIndex = batches.size,
                    finalBatch = final,
                    results = tracks,
                    playlistResults = playlists,
                    summary = if (final) report.summary else null
                )
            ).toByteArray(Charsets.UTF_8)
            // Fixed allowlisted fields and 100-character opaque IDs ensure 100 rows
            // fit; retain the byte check so future schema changes fail closed.
            require(bytes.size <= MAX_BATCH_BYTES) { "Client report batch exceeds its byte limit." }
            require(batches.size < 2_000) { "Client report has too many batches." }
            batches.add(bytes)
        } while (!final)
        return batches
    }

    suspend fun deliver(
        report: SyncClientReport,
        credentials: PairingCredentials,
        transport: SyncTransport
    ): SyncReportDelivery {
        val batches = serializeBatches(report)
        for (body in batches) {
            currentCoroutineContext().ensureActive()
            var accepted = false
            for (attempt in 0..1) {
                try {
                    when (transport.sendClientReportBatch(credentials, report.revision, body)) {
                        SyncClientReportPostResult.ACCEPTED -> accepted = true
                        SyncClientReportPostResult.UNSUPPORTED -> return SyncReportDelivery.UNSUPPORTED
                    }
                    break
                } catch (error: CancellationException) {
                    throw error
                } catch (error: SyncCancelledException) {
                    throw error
                } catch (_: SyncNetworkException) {
                    currentCoroutineContext().ensureActive()
                    if (attempt == 1) return SyncReportDelivery.INCOMPLETE
                    // Exactly one retry, using the identical bytes and the same
                    // captured credentials. Do not refresh or silently re-pair.
                } catch (_: Exception) {
                    return SyncReportDelivery.INCOMPLETE
                }
            }
            if (!accepted) return SyncReportDelivery.INCOMPLETE
        }
        return SyncReportDelivery.DELIVERED
    }

    private fun validate(report: SyncClientReport) {
        val id = SyncManifestValidator.ID_REGEX
        require(id.matches(report.libraryId) && id.matches(report.revision) && id.matches(report.reportId))
        require(report.results.size <= SyncManifestValidator.MAX_TRACKS)
        require(report.playlistResults.size <= SyncManifestValidator.MAX_PLAYLISTS)
        require(report.results.map { it.trackId }.toSet().size == report.results.size)
        require(report.playlistResults.map { it.playlistId }.toSet().size == report.playlistResults.size)
        for (row in report.results) {
            require(id.matches(row.trackId) && row.reason in reasons)
            require(row.receivedBytes in 0..(SyncManifestValidator.MAX_AUDIO_FILE_BYTES + MAX_BATCH_BYTES))
            require(row.hashStatus in setOf("verified", "mismatch", "not_checked"))
            when (row.outcome) {
                "downloaded_verified_imported" -> require(row.hashStatus == "verified" && row.reason == "none")
                "reused_verified_imported" -> require(row.receivedBytes == 0L && row.hashStatus == "verified" && row.reason == "none")
                "failed" -> {
                    require(row.reason != "none" && row.reason != "not_attempted")
                    require((row.reason == "hash_mismatch") == (row.hashStatus == "mismatch"))
                    require(row.reason != "import_failed" || row.hashStatus == "verified")
                }
                "not_attempted" -> require(row.receivedBytes == 0L && row.hashStatus == "not_checked" && row.reason != "none")
                else -> throw IllegalArgumentException("Invalid client track outcome.")
            }
        }
        require(report.playlistResults.all { id.matches(it.playlistId) && it.outcome in setOf("updated", "preserved", "deferred") })
        val summary = report.summary
        val counters = listOf(
            summary.plannedTracks, summary.plannedPlaylists, summary.downloadedImported, summary.reusedImported,
            summary.failed, summary.notAttempted, summary.playlistsUpdated, summary.playlistsPreserved, summary.playlistsDeferred
        )
        require(counters.all { it in 0..110_000 })
        require(summary.plannedTracks == report.results.size && summary.plannedPlaylists == report.playlistResults.size)
        require(summary.downloadedImported == report.results.count { it.outcome == "downloaded_verified_imported" })
        require(summary.reusedImported == report.results.count { it.outcome == "reused_verified_imported" })
        require(summary.failed == report.results.count { it.outcome == "failed" })
        require(summary.notAttempted == report.results.count { it.outcome == "not_attempted" })
        require(summary.playlistsUpdated == report.playlistResults.count { it.outcome == "updated" })
        require(summary.playlistsPreserved == report.playlistResults.count { it.outcome == "preserved" })
        require(summary.playlistsDeferred == report.playlistResults.count { it.outcome == "deferred" })
        require(summary.stopReason in reasons)
        when (summary.outcome) {
            "complete" -> require(summary.stopReason == "none" && summary.failed == 0 && summary.notAttempted == 0 &&
                summary.playlistsPreserved == 0 && summary.playlistsDeferred == 0)
            "partial" -> require(summary.stopReason == "none")
            "stopped" -> require(summary.stopReason != "none")
            "cancelled" -> require(summary.stopReason == "cancelled")
            else -> throw IllegalArgumentException("Invalid client run outcome.")
        }
    }
}
