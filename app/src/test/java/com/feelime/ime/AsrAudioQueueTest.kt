package com.feelime.ime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrAudioQueueTest {
    @Test
    fun fastProducerSlowConsumerPreservesEveryChunkInOrder() {
        val queue = AsrAudioQueue(capacityChunks = 8)
        val total = 240
        val received = mutableListOf<Int>()
        val failure = AtomicReference<Throwable?>(null)

        val consumer = thread(start = true, name = "asr-test-consumer") {
            try {
                while (true) {
                    val chunk = queue.take() ?: break
                    received += chunk[0].toInt()
                    Thread.sleep(1)
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        val producer = thread(start = true, name = "asr-test-producer") {
            try {
                for (index in 0 until total) {
                    val chunk = shortArrayOf(index.toShort(), (index * 2).toShort())
                    while (!queue.offer(chunk)) Thread.yield()
                }
                queue.finish()
            } catch (error: Throwable) {
                failure.set(error)
                queue.cancel()
            }
        }

        producer.join(5_000)
        consumer.join(5_000)
        assertFalse("producer did not finish", producer.isAlive)
        assertFalse("consumer did not finish", consumer.isAlive)
        assertNull(failure.get())
        assertEquals((0 until total).toList(), received)
    }

    @Test
    fun successfulFinishDrainsBufferedChunksThenReturnsNull() {
        val queue = AsrAudioQueue(capacityChunks = 3)
        assertTrue(queue.offer(shortArrayOf(1, 2)))
        assertTrue(queue.offer(shortArrayOf(3)))

        queue.finish()

        assertArrayEquals(shortArrayOf(1, 2), queue.take())
        assertArrayEquals(shortArrayOf(3), queue.take())
        assertNull(queue.take())
        assertFalse(queue.offer(shortArrayOf(4)))
    }

    @Test
    fun fullQueueRejectsWithoutDroppingExistingChunks() {
        val queue = AsrAudioQueue(capacityChunks = 2)
        assertTrue(queue.offer(shortArrayOf(1)))
        assertTrue(queue.offer(shortArrayOf(2)))
        assertFalse(queue.offer(shortArrayOf(3)))

        assertArrayEquals(shortArrayOf(1), queue.take())
        assertTrue(queue.offer(shortArrayOf(3)))
        assertArrayEquals(shortArrayOf(2), queue.take())
        assertArrayEquals(shortArrayOf(3), queue.take())
    }

    @Test
    fun finishErrorIsRethrownUnchangedAndQueuedAudioIsNotDrained() {
        val queue = AsrAudioQueue(capacityChunks = 2)
        val error = IllegalStateException("capture failed")
        assertTrue(queue.offer(shortArrayOf(7)))

        queue.finish(error)

        val first = runCatching { queue.take() }.exceptionOrNull()
        val second = runCatching { queue.take() }.exceptionOrNull()
        assertSame(error, first)
        assertSame(error, second)
        assertFalse(queue.offer(shortArrayOf(8)))
    }

    @Test
    fun cancelClearsQueueAndWakesBlockedConsumer() {
        val queue = AsrAudioQueue(capacityChunks = 2)
        assertTrue(queue.offer(shortArrayOf(9)))

        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val result = AtomicReference<ShortArray?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val consumer = thread(start = true, name = "asr-test-cancel-consumer") {
            try {
                // Consume the buffered item first, then block on the empty queue.
                assertArrayEquals(shortArrayOf(9), queue.take())
                started.countDown()
                result.set(queue.take())
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }

        assertTrue(started.await(1, TimeUnit.SECONDS))
        Thread.sleep(50)
        queue.cancel()
        assertTrue(done.await(1, TimeUnit.SECONDS))
        consumer.join(1_000)
        assertFalse(consumer.isAlive)
        assertNull(failure.get())
        assertNull(result.get())
        assertFalse(queue.offer(shortArrayOf(10)))
    }
}
