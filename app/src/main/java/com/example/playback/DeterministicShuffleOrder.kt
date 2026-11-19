package com.example.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import com.example.domain.ShuffleEngine

/**
 * Deterministic ShuffleOrder backed by an explicit unbiased Fisher-Yates permutation.
 * Ensures next/previous traversal follows a single consistent order without preference bias.
 */
@OptIn(UnstableApi::class)
class DeterministicShuffleOrder(
    private val shuffled: IntArray
) : ShuffleOrder {

    private val indexInShuffled: IntArray = IntArray(shuffled.size).also { lookup ->
        for (pos in shuffled.indices) {
            val itemIndex = shuffled[pos]
            if (itemIndex in lookup.indices) {
                lookup[itemIndex] = pos
            }
        }
    }

    fun asList(): List<Int> = shuffled.toList()

    override fun getLength(): Int = shuffled.size

    override fun getNextIndex(index: Int): Int {
        if (index !in indexInShuffled.indices) return C.INDEX_UNSET
        val pos = indexInShuffled[index] + 1
        return if (pos in shuffled.indices) shuffled[pos] else C.INDEX_UNSET
    }

    override fun getPreviousIndex(index: Int): Int {
        if (index !in indexInShuffled.indices) return C.INDEX_UNSET
        val pos = indexInShuffled[index] - 1
        return if (pos in shuffled.indices) shuffled[pos] else C.INDEX_UNSET
    }

    override fun getLastIndex(): Int =
        if (shuffled.isNotEmpty()) shuffled[shuffled.size - 1] else C.INDEX_UNSET

    override fun getFirstIndex(): Int =
        if (shuffled.isNotEmpty()) shuffled[0] else C.INDEX_UNSET

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        val newSize = shuffled.size + insertionCount
        if (newSize <= 0) return DeterministicShuffleOrder(IntArray(0))
        // Preserve existing relative order shifted by insertionCount, insert new items uniformly
        val shiftedExisting = shuffled.map { oldIdx ->
            if (oldIdx >= insertionIndex) oldIdx + insertionCount else oldIdx
        }.toMutableList()
        val newItems = (insertionIndex until insertionIndex + insertionCount).toList()
        val combined = (shiftedExisting + newItems)
        // Insert new items at uniform random positions while keeping existing items in order
        val result = shiftedExisting.toMutableList()
        val rng = kotlin.random.Random.Default
        for (item in newItems) {
            val pos = if (result.isEmpty()) 0 else rng.nextInt(result.size + 1)
            result.add(pos, item)
        }
        return DeterministicShuffleOrder(result.toIntArray())
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val removedCount = indexToExclusive - indexFrom
        if (removedCount <= 0) return this
        val newArray = IntArray((shuffled.size - removedCount).coerceAtLeast(0))
        var out = 0
        for (idx in shuffled) {
            if (idx < indexFrom || idx >= indexToExclusive) {
                newArray[out++] = if (idx >= indexToExclusive) idx - removedCount else idx
            }
        }
        return DeterministicShuffleOrder(newArray)
    }

    override fun cloneAndClear(): ShuffleOrder = DeterministicShuffleOrder(IntArray(0))

    companion object {
        fun createFisherYates(size: Int, anchorIndex: Int = -1): DeterministicShuffleOrder {
            return DeterministicShuffleOrder(ShuffleEngine.fisherYatesIndices(size, anchorIndex))
        }

        fun fromOrderedTrackKeys(
            queueKeys: List<String>,
            persistedShuffledKeys: List<String>,
            anchorIndex: Int = -1
        ): DeterministicShuffleOrder {
            if (queueKeys.isEmpty()) return DeterministicShuffleOrder(IntArray(0))
            val keyToIndex = queueKeys.withIndex().associate { it.value to it.index }
            val mapped = persistedShuffledKeys.mapNotNull { keyToIndex[it] }.distinct()
            return if (mapped.size == queueKeys.size) {
                DeterministicShuffleOrder(mapped.toIntArray())
            } else {
                createFisherYates(queueKeys.size, anchorIndex)
            }
        }
    }
}
