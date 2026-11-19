package com.music.tuner.audio

import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Splits an AAC stream in ADTS framing (what ffmpeg's `-f adts` writes) into its frames, so a
 * listener who tunes in starts at the beginning of one.
 */
class AdtsSplitter(private val onFrame: (ByteArray) -> Unit) {
    private val pending = ByteArrayOutputStream()

    fun feed(bytes: ByteArray, count: Int = bytes.size) {
        pending.write(bytes, 0, count)
        val data = pending.toByteArray()
        var at = 0
        while (true) {
            val start = findSync(data, at)
            if (start == null) {
                // Keep the last byte: it may be the first of a frame's header.
                at = maxOf(at, data.size - 1)
                break
            }
            if (start + 7 > data.size) {
                at = start
                break
            }
            val length = frameLength(data, start)
            if (length < 7) {
                at = start + 1
                continue
            }
            if (start + length > data.size) {
                at = start
                break
            }
            // A frame that isn't followed by another (when there's more) was a false start.
            if (start + length + 2 <= data.size && !isSync(data, start + length)) {
                at = start + 1
                continue
            }
            onFrame(data.copyOfRange(start, start + length))
            at = start + length
        }
        pending.reset()
        if (at < data.size) pending.write(data, at, data.size - at)
    }

    /** Reads [input] to its end, frame by frame. */
    fun pump(input: InputStream) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            if (read > 0) feed(buffer, read)
        }
    }

    companion object {
        fun isSync(data: ByteArray, at: Int) = at + 1 < data.size && data[at] == 0xFF.toByte() && (data[at + 1].toInt() and 0xF6) == 0xF0

        fun findSync(data: ByteArray, from: Int): Int? {
            for (i in from until data.size - 1) if (isSync(data, i)) return i
            return null
        }

        fun frameLength(data: ByteArray, at: Int): Int =
            ((data[at + 3].toInt() and 0x03) shl 11) or ((data[at + 4].toInt() and 0xFF) shl 3) or ((data[at + 5].toInt() and 0xE0) shr 5)

        /** A silent-looking ADTS frame of [length] bytes (for tests and fakes). */
        fun frame(length: Int, fill: Byte = 0): ByteArray {
            val b = ByteArray(length) { fill }
            b[0] = 0xFF.toByte()
            b[1] = 0xF1.toByte()
            b[2] = 0x4C.toByte()
            b[3] = (0x80 or ((length shr 11) and 0x03)).toByte()
            b[4] = ((length shr 3) and 0xFF).toByte()
            b[5] = (((length and 0x07) shl 5) or 0x1F).toByte()
            b[6] = 0xFC.toByte()
            return b
        }
    }
}

/** A listener of a [Broadcast]: the frames, as they come. */
class Listener internal constructor(capacity: Int) {
    val frames = Channel<ByteArray>(capacity)

    @Volatile
    var dropped = 0
        internal set
}

/**
 * One station's audio, sent to everyone listening. Each listener has a queue; one who falls
 * behind (a slow connection) loses frames rather than holding the others up. A new listener
 * gets the last [burstFrames] frames at once, so their player starts quickly.
 */
class Broadcast(private val burstFrames: Int = 70, private val listenerCapacity: Int = 400) {
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val recent = ArrayDeque<ByteArray>()

    @Volatile
    var frames = 0L
        private set

    val listenerCount: Int get() = listeners.size

    fun subscribe(): Listener {
        val listener = Listener(listenerCapacity + burstFrames)
        synchronized(recent) {
            recent.forEach { listener.frames.trySend(it) }
            listeners += listener
        }
        return listener
    }

    fun unsubscribe(listener: Listener) {
        listeners -= listener
        listener.frames.close()
    }

    fun publish(frame: ByteArray) {
        synchronized(recent) {
            recent.addLast(frame)
            while (recent.size > burstFrames) recent.removeFirst()
            frames++
        }
        for (listener in listeners) {
            if (!listener.frames.trySend(frame).isSuccess) listener.dropped++
        }
    }

    /** Ends every listener's stream (the station stopped). */
    fun closeAll() {
        listeners.toList().forEach(::unsubscribe)
        synchronized(recent) { recent.clear() }
    }
}
