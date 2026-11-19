package com.example.domain

import com.example.data.db.BuiltInSmartPlaylistType
import com.example.data.db.SmartPlaylistRuleEntity
import com.example.data.db.SmartSortOrder
import com.example.data.db.TrackWithStats

object SmartPlaylistEvaluator {

    val DEFAULT_MOOD_TAGS = listOf("Chill", "Energetic", "Focus", "Late Night", "Workout", "Upbeat")

    fun defaultRules(nowMs: Long = System.currentTimeMillis()): List<SmartPlaylistRuleEntity> = listOf(
        SmartPlaylistRuleEntity(
            name = "Recently Added",
            description = "Tracks added most recently to your local library.",
            isBuiltIn = true,
            builtInType = BuiltInSmartPlaylistType.RECENTLY_ADDED,
            sortOrder = SmartSortOrder.RECENTLY_ADDED.name,
            limitCount = 50,
            createdAtMs = nowMs,
            updatedAtMs = nowMs
        ),
        SmartPlaylistRuleEntity(
            name = "Most Played",
            description = "Your most-played tracks ranked by listening count and preference score.",
            isBuiltIn = true,
            builtInType = BuiltInSmartPlaylistType.MOST_PLAYED,
            minPlayCount = 1,
            sortOrder = SmartSortOrder.MOST_PLAYED.name,
            limitCount = 50,
            createdAtMs = nowMs,
            updatedAtMs = nowMs
        ),
        SmartPlaylistRuleEntity(
            name = "Deep Focus Cuts",
            description = "Editable rule: tracks between 2:30 and 7:00 ranked by preference score.",
            isBuiltIn = false,
            builtInType = null,
            minDurationSec = 150L,
            maxDurationSec = 420L,
            sortOrder = SmartSortOrder.PREFERENCE_RANKED.name,
            limitCount = 40,
            createdAtMs = nowMs - 1000L,
            updatedAtMs = nowMs - 1000L
        ),
        SmartPlaylistRuleEntity(
            name = "Quick Spin",
            description = "Editable rule: shorter tracks under 3 minutes.",
            isBuiltIn = false,
            builtInType = null,
            minDurationSec = 10L,
            maxDurationSec = 180L,
            sortOrder = SmartSortOrder.PREFERENCE_RANKED.name,
            limitCount = 30,
            createdAtMs = nowMs - 2000L,
            updatedAtMs = nowMs - 2000L
        )
    )

    fun evaluate(
        rule: SmartPlaylistRuleEntity,
        allTracks: List<TrackWithStats>
    ): List<TrackWithStats> {
        val availableTracks = allTracks.filterNot { it.track.isMissingOrUnreadable }

        val filtered = when (rule.builtInType) {
            BuiltInSmartPlaylistType.RECENTLY_ADDED -> availableTracks
            BuiltInSmartPlaylistType.MOST_PLAYED -> {
                val minPlays = rule.minPlayCount ?: 1
                availableTracks.filter { it.playCount >= minPlays }
            }
            else -> availableTracks.filter { item ->
                matchesRule(item, rule)
            }
        }

        val sortOrder = runCatching { SmartSortOrder.valueOf(rule.sortOrder) }
            .getOrDefault(SmartSortOrder.PREFERENCE_RANKED)

        val sorted = when (sortOrder) {
            SmartSortOrder.PREFERENCE_RANKED -> filtered.sortedWith(
                compareByDescending<TrackWithStats> { it.preferenceScore }
                    .thenByDescending { it.playCount }
                    .thenBy { it.track.title.lowercase() }
            )
            SmartSortOrder.MOST_PLAYED -> filtered.sortedWith(
                compareByDescending<TrackWithStats> { it.playCount }
                    .thenByDescending { it.preferenceScore }
                    .thenByDescending { it.lastPlayedAtMs }
                    .thenBy { it.track.title.lowercase() }
            )
            SmartSortOrder.RECENTLY_ADDED -> filtered.sortedWith(
                compareByDescending<TrackWithStats> { it.track.dateAddedSec }
                    .thenBy { it.track.title.lowercase() }
            )
            SmartSortOrder.ALPHABETICAL -> filtered.sortedBy { it.track.title.lowercase() }
            SmartSortOrder.DURATION_ASC -> filtered.sortedWith(
                compareBy<TrackWithStats> { it.track.durationMs }
                    .thenBy { it.track.title.lowercase() }
            )
            SmartSortOrder.DURATION_DESC -> filtered.sortedWith(
                compareByDescending<TrackWithStats> { it.track.durationMs }
                    .thenBy { it.track.title.lowercase() }
            )
        }

        val limit = rule.limitCount?.takeIf { it > 0 }
        return if (limit != null) sorted.take(limit) else sorted
    }

    private fun matchesRule(item: TrackWithStats, rule: SmartPlaylistRuleEntity): Boolean {
        val durationSec = item.durationSec
        rule.minDurationSec?.let { minSec ->
            if (durationSec < minSec) return false
        }
        rule.maxDurationSec?.let { maxSec ->
            if (durationSec > maxSec) return false
        }
        rule.minPlayCount?.let { minPlays ->
            if (item.playCount < minPlays) return false
        }
        rule.artistFilter?.trim()?.takeIf { it.isNotEmpty() }?.let { artist ->
            if (!item.track.artist.contains(artist, ignoreCase = true)) return false
        }
        rule.albumFilter?.trim()?.takeIf { it.isNotEmpty() }?.let { album ->
            if (!item.track.album.contains(album, ignoreCase = true)) return false
        }
        rule.genreFilter?.trim()?.takeIf { it.isNotEmpty() }?.let { genre ->
            if (!item.track.genre.contains(genre, ignoreCase = true)) return false
        }
        rule.moodTagFilter?.trim()?.takeIf { it.isNotEmpty() }?.let { mood ->
            val trackMood = item.track.moodTag ?: return false
            if (!trackMood.equals(mood, ignoreCase = true)) return false
        }
        return true
    }

    fun summarizeRuleChips(rule: SmartPlaylistRuleEntity): List<String> {
        val chips = mutableListOf<String>()
        if (rule.minDurationSec != null || rule.maxDurationSec != null) {
            val minText = rule.minDurationSec?.let { formatSeconds(it) } ?: "0:00"
            val maxText = rule.maxDurationSec?.let { formatSeconds(it) } ?: "Any"
            chips.add("Duration $minText – $maxText")
        }
        rule.artistFilter?.takeIf { it.isNotBlank() }?.let { chips.add("Artist: $it") }
        rule.albumFilter?.takeIf { it.isNotBlank() }?.let { chips.add("Album: $it") }
        rule.genreFilter?.takeIf { it.isNotBlank() }?.let { chips.add("Genre: $it") }
        rule.moodTagFilter?.takeIf { it.isNotBlank() }?.let { chips.add("Mood: $it") }
        rule.minPlayCount?.takeIf { it > 0 }?.let { chips.add("Plays ≥ $it") }
        val sort = runCatching { SmartSortOrder.valueOf(rule.sortOrder).label }
            .getOrDefault(rule.sortOrder)
        chips.add("Sort: $sort")
        rule.limitCount?.takeIf { it > 0 }?.let { chips.add("Max $it") }
        return chips
    }

    private fun formatSeconds(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return "%d:%02d".format(mins, secs)
    }
}
