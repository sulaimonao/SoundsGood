package com.example.domain

import kotlin.random.Random

/**
 * Provides unbiased uniform Fisher-Yates permutations for queue shuffle.
 * Intentionally independent of preference weights or play counts so shuffle remains uniform.
 */
object ShuffleEngine {

    /**
     * Produces a uniform Fisher-Yates permutation of `0 until size`.
     * If [anchorIndex] is in `0 until size`, places [anchorIndex] at index 0 of the returned
     * array and uniformly shuffles the remaining `size - 1` indices.
     */
    fun fisherYatesIndices(
        size: Int,
        anchorIndex: Int = -1,
        random: Random = Random.Default
    ): IntArray {
        if (size <= 0) return IntArray(0)
        val indices = IntArray(size) { it }
        if (anchorIndex in 0 until size) {
            // Move anchorIndex to position 0
            val tmp = indices[0]
            indices[0] = indices[anchorIndex]
            indices[anchorIndex] = tmp
            // Fisher-Yates shuffle on indices[1 until size]
            for (i in size - 1 downTo 2) {
                val j = random.nextInt(1, i + 1)
                val swap = indices[i]
                indices[i] = indices[j]
                indices[j] = swap
            }
        } else {
            // Standard Fisher-Yates shuffle on indices[0 until size]
            for (i in size - 1 downTo 1) {
                val j = random.nextInt(i + 1)
                val swap = indices[i]
                indices[i] = indices[j]
                indices[j] = swap
            }
        }
        return indices
    }

    /**
     * Produces a uniform Fisher-Yates permutation of [items], optionally keeping [anchorItem]
     * first when present.
     */
    fun <T> fisherYatesList(
        items: List<T>,
        anchorItem: T? = null,
        random: Random = Random.Default
    ): List<T> {
        if (items.size <= 1) return items.toList()
        val anchorIndex = if (anchorItem != null) items.indexOf(anchorItem) else -1
        val order = fisherYatesIndices(items.size, anchorIndex, random)
        return order.map { items[it] }
    }
}
