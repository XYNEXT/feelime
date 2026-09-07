package com.feelime.ime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatSampleBufferTest {
    @Test
    fun accumulatesChunksAndClearsBetweenUtterances() {
        val buffer = FloatSampleBuffer(maxSize = 8)
        buffer.append(floatArrayOf(1f, 2f))
        buffer.append(floatArrayOf(3f, 4f, 5f))

        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f), buffer.takeAndClear(), 0f)
        assertEquals(0, buffer.size)

        buffer.append(floatArrayOf(6f))
        assertArrayEquals(floatArrayOf(6f), buffer.takeAndClear(), 0f)
    }

    @Test
    fun capsLongUtterances() {
        val buffer = FloatSampleBuffer(maxSize = 3)
        buffer.append(floatArrayOf(1f, 2f, 3f, 4f))
        buffer.append(floatArrayOf(5f))

        assertArrayEquals(floatArrayOf(1f, 2f, 3f), buffer.takeAndClear(), 0f)
    }
}
