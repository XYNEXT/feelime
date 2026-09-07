package com.feelime.ime

/** Bounded in-memory PCM buffer; recorded speech is never written to disk. */
internal class FloatSampleBuffer(private val maxSize: Int) {
    private var values = FloatArray(minOf(INITIAL_CAPACITY, maxSize))
    var size: Int = 0
        private set

    fun append(source: FloatArray) {
        if (source.isEmpty() || size >= maxSize) return
        val count = minOf(source.size, maxSize - size)
        ensureCapacity(size + count)
        source.copyInto(values, destinationOffset = size, endIndex = count)
        size += count
    }

    fun takeAndClear(): FloatArray {
        val result = values.copyOf(size)
        size = 0
        return result
    }

    private fun ensureCapacity(required: Int) {
        if (required <= values.size) return
        var capacity = maxOf(values.size, 1)
        while (capacity < required) capacity = minOf(maxSize, capacity * 2)
        values = values.copyOf(capacity)
    }

    private companion object {
        const val INITIAL_CAPACITY = 16_000
    }
}
