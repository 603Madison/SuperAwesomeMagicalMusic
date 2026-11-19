package com.music.tuner.proc

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A program the tuner runs (rtl_fm, redsea, nrsc5, ffmpeg…). Its error output goes to [onLine]
 * line by line (and the last few lines are kept, to say why it stopped). Every child is listed
 * in [Children], so none outlives the tuner.
 */
class Child private constructor(val name: String, val process: Process, onLine: ((String) -> Unit)?) {
    private val recent = ArrayDeque<String>()

    val stdin: OutputStream get() = process.outputStream
    val stdout: InputStream get() = process.inputStream
    val alive: Boolean get() = process.isAlive

    init {
        Thread({
            try {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(recent) {
                            if (line.isNotBlank()) recent.addLast(line.take(300))
                            while (recent.size > 5) recent.removeFirst()
                        }
                        try {
                            onLine?.invoke(line) ?: log.debug("{}: {}", name, line)
                        } catch (e: Exception) {
                            log.warn("{}: couldn't follow its output: {}", name, e.toString())
                        }
                    }
                }
            } catch (e: IOException) {
                // It's gone.
            }
        }, "$name-log").apply { isDaemon = true }.start()
    }

    /** What it said last (for "stopped because…"). */
    fun lastWords(): String = synchronized(recent) { recent.lastOrNull().orEmpty() }

    /** Asks it to stop (closing its input, then SIGTERM), and makes it stop after [graceMs]. */
    fun stop(graceMs: Long = 2_000) {
        val descendants = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
        runCatching { process.outputStream.close() }
        process.destroy()
        try {
            if (!process.waitFor(graceMs, TimeUnit.MILLISECONDS)) {
                log.warn("{} didn't stop when asked; stopping it", name)
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
        }
        descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
        runCatching { process.inputStream.close() }
        Children.forget(this)
    }

    companion object {
        private val log = LoggerFactory.getLogger("Process")

        fun start(name: String, command: List<String>, dir: File? = null, onLine: ((String) -> Unit)? = null): Child {
            log.info("Starting {}: {}", name, command.joinToString(" "))
            val process = try {
                ProcessBuilder(command).apply { dir?.let { directory(it) } }.start()
            } catch (e: IOException) {
                throw IOException("Couldn't start ${command.first()}: ${e.message}", e)
            }
            return Child(name, process, onLine).also { child ->
                Children.add(child)
                process.onExit().thenRun { Children.forget(child) }
            }
        }
    }
}

/** Every child running, so they can all be stopped when the tuner stops. */
object Children {
    private val running = ConcurrentHashMap.newKeySet<Child>()

    fun add(child: Child) = running.add(child)
    fun forget(child: Child) = running.remove(child)
    fun count(): Int = running.count { it.alive }

    fun stopAll() {
        running.toList().forEach { runCatching { it.stop(graceMs = 1_000) } }
    }
}

/**
 * Keeps a child running while it's wanted: [body] runs with each one started (and returns when
 * it's done with it); when the child stops by itself, it's started again, waiting longer each
 * time it keeps failing (1 s, 2 s, 4 s… up to 30 s). [onStop] hears why it stopped.
 */
class Supervised(
    val name: String,
    private val command: () -> List<String>,
    private val body: (Child) -> Unit,
    private val dir: File? = null,
    private val onLine: ((String) -> Unit)? = null,
    private val onStop: (String) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger("Process")

    @Volatile
    private var running = false

    @Volatile
    private var child: Child? = null
    private var thread: Thread? = null

    @Volatile
    var starts = 0
        private set

    val alive: Boolean get() = child?.alive == true

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread(::supervise, name).apply { isDaemon = true; start() }
    }

    private fun supervise() {
        var failures = 0
        while (running) {
            val started = System.nanoTime()
            val why = try {
                val c = Child.start(name, command(), dir, onLine)
                child = c
                starts++
                try {
                    body(c)
                } finally {
                    if (running) c.process.waitFor(500, TimeUnit.MILLISECONDS)
                    c.stop()
                }
                val code = runCatching { c.process.exitValue() }.getOrNull()
                listOfNotNull(code?.let { "exit $it" }, c.lastWords().takeIf { it.isNotBlank() }).joinToString(": ").ifEmpty { "stopped" }
            } catch (e: IOException) {
                e.message ?: e.toString()
            } catch (e: Exception) {
                if (running) log.warn("{} failed", name, e)
                e.toString()
            }
            child = null
            if (!running) break
            onStop(why)
            val ranFor = (System.nanoTime() - started) / 1_000_000
            failures = if (ranFor > 60_000) 1 else failures + 1
            val wait = minOf(30_000L, 1_000L shl minOf(failures - 1, 5))
            log.warn("{} stopped ({}); starting it again in {} s", name, why, wait / 1000)
            sleepWhileRunning(wait)
        }
    }

    private fun sleepWhileRunning(ms: Long) {
        val until = System.nanoTime() + ms * 1_000_000
        while (running && System.nanoTime() < until) {
            try {
                Thread.sleep(minOf(100L, (until - System.nanoTime()) / 1_000_000).coerceAtLeast(1))
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    fun stop() {
        val t: Thread?
        synchronized(this) {
            running = false
            t = thread
            thread = null
        }
        child?.stop()
        t?.interrupt()
        t?.join(5_000)
    }
}

/**
 * A child fed from a queue (what to decode or encode), restarted when it stops. A slow child
 * never holds up whoever offers it data: when its queue is full the oldest is dropped. With
 * [silence], it's fed silence when nothing comes for [silenceAfterMs] (an encoder keeps
 * listeners' players going while a station drops out).
 */
class Feeder(
    name: String,
    command: () -> List<String>,
    private val onStdout: ((InputStream) -> Unit)? = null,
    onLine: ((String) -> Unit)? = null,
    dir: File? = null,
    capacity: Int = 64,
    private val silence: ByteArray? = null,
    private val silenceAfterMs: Long = 300,
) {
    private val queue = ArrayBlockingQueue<ByteArray>(capacity)

    @Volatile
    var dropped = 0L
        private set

    private val supervised = Supervised(name, command, body = ::feed, dir = dir, onLine = onLine)

    val alive: Boolean get() = supervised.alive
    val starts: Int get() = supervised.starts

    fun start() = supervised.start()
    fun stop() = supervised.stop()

    fun offer(bytes: ByteArray) {
        while (!queue.offer(bytes)) {
            queue.poll()
            dropped++
        }
    }

    private fun feed(child: Child) {
        val reader = onStdout?.let { read ->
            Thread({
                try {
                    read(child.stdout)
                } catch (e: IOException) {
                    // It stopped.
                }
            }, "${child.name}-out").apply { isDaemon = true; start() }
        }
        try {
            val out = child.stdin
            while (child.alive) {
                val bytes = queue.poll(if (silence != null) silenceAfterMs else 200, TimeUnit.MILLISECONDS)
                    ?: silence
                    ?: continue
                out.write(bytes)
                out.flush()
            }
        } catch (e: IOException) {
            // It stopped reading: it's gone, or going.
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { child.stdin.close() }
            try {
                reader?.join(2_000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
