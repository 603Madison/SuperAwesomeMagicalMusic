package com.music.tuner

import com.music.tuner.proc.Children
import com.music.tuner.radio.ImageStore
import com.music.tuner.radio.RealReceivers
import com.music.tuner.radio.ReceiverFactory
import com.music.tuner.radio.TunerPool
import com.music.tuner.scan.RealScanRadio
import com.music.tuner.scan.ScanRadio
import com.music.tuner.scan.Scanner
import java.io.File
import java.security.SecureRandom
import java.util.Properties

/** Everything the tuner is made of. */
class TunerApp(
    val config: TunerConfig,
    devices: List<String>,
    factory: ReceiverFactory = RealReceivers(config),
    scanRadio: ScanRadio = RealScanRadio(config),
    val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    val lineup = LineupStore(config.lineupFile, config.region)
    val images = ImageStore(File(config.dataDir, "images"))
    val pool = TunerPool(devices, lineup, factory, images, config.graceSeconds * 1000L, clock)
    val scanner = Scanner(config, pool, lineup, scanRadio, clock)

    /** An id for HDHomeRun-style discovery, kept so it stays the same. */
    val deviceId: String by lazy {
        config.deviceIdFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.matches(Regex("[0-9A-F]{8}")) }
            ?: ByteArray(4).also(SecureRandom()::nextBytes).joinToString("") { "%02X".format(it) }.also { id ->
                runCatching {
                    config.dataDir.mkdirs()
                    config.deviceIdFile.writeText(id)
                }
            }
    }

    fun start(): TunerApp {
        config.workDir.mkdirs()
        pool.start()
        return this
    }

    override fun close() {
        scanner.cancel()
        pool.close()
        Children.stopAll()
    }

    companion object {
        val version: String by lazy {
            runCatching {
                Properties().apply { TunerApp::class.java.getResourceAsStream("/tuner-version.properties")?.use(::load) }.getProperty("version")
            }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("\$") } ?: "dev"
        }
    }
}
