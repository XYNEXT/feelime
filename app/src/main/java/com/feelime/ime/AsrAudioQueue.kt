package com.feelime.ime

import java.util.ArrayDeque
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/**
 * Bounded handoff between AudioRecord and the streaming decoder.
 *
 * Audio capture must never wait for decoding to catch up: [offer] returns
 * false when the queue is full or terminal.  The decoder blocks in [take]
 * only while the producer may still enqueue data.  A terminal error takes
 * precedence over queued data, so a failed producer cannot look like a clean
 * end of the recording.
 */
internal class AsrAudioQueue(capacityChunks: Int) {
    init {
        require(capacityChunks > 0) { "capacityChunks must be positive" }
    }

    private val lock = ReentrantLock()
    private val changed: Condition = lock.newCondition()
    private val chunks = ArrayDeque<ShortArray>(capacityChunks)
    private val capacity = capacityChunks
    private var finished = false
    private var failure: Throwable? = null

    /**
     * Enqueue one audio chunk without waiting for room.
     *
     * The array is copied because AudioRecord callers commonly reuse one
     * scratch buffer for every read.  The queue therefore owns the chunk
     * observed by the consumer.
     */
    fun offer(samples: ShortArray): Boolean {
        lock.lock()
        return try {
            if (finished || chunks.size >= capacity) {
                false
            } else {
                chunks.addLast(samples.copyOf())
                changed.signal()
                true
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Finish production.  A successful finish drains already queued chunks;
     * a failed finish drops them and makes every [take] throw the same error.
     * The first terminal call wins, which keeps a later cleanup call from
     * replacing the original producer failure.
     */
    fun finish(error: Throwable? = null) {
        lock.lock()
        try {
            if (finished) return
            finished = true
            failure = error
            if (error != null) chunks.clear()
            changed.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Cancel production and discard buffered audio.  Cancellation is a clean
     * terminal result for the consumer; it is used when the ASR engine is
     * released and must wake any decoder waiting for another chunk.
     */
    fun cancel() {
        lock.lock()
        try {
            // Preserve an already published producer failure.  Release-time
            // cleanup may race with finish(error), and must not turn that
            // diagnostic into a clean end-of-stream.
            if (!finished) {
                finished = true
                failure = null
            }
            chunks.clear()
            changed.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Take the oldest chunk, waiting while production is still active.
     * Returns null after a successful finish or cancellation and the queue is
     * empty.  A producer failure is rethrown unchanged.
     */
    fun take(): ShortArray? {
        lock.lock()
        try {
            while (true) {
                failure?.let { throw it }
                if (chunks.isNotEmpty()) return chunks.removeFirst()
                if (finished) return null
                try {
                    changed.await()
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        } finally {
            lock.unlock()
        }
    }
}
