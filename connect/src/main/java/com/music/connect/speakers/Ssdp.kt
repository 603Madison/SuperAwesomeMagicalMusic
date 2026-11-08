package com.music.connect.speakers

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Finds media players on the Wi-Fi (UPnP's SSDP): asks everyone listening on the network's
 * multicast address, and collects where each answering device describes itself.
 */
object Ssdp {
    private const val TAG = "Ssdp"
    private const val ADDRESS = "239.255.255.250"
    private const val PORT = 1900
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    fun searchMessage(target: String = MEDIA_RENDERER): String =
        "M-SEARCH * HTTP/1.1\r\nHOST: $ADDRESS:$PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\nUSER-AGENT: Android/1 UPnP/1.1 SuperAwesomeMagicalMusic/1\r\n\r\n"

    /** The LOCATION of an SSDP answer (where the device's description is). */
    fun locationOf(response: String): String? =
        response.lineSequence().firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }?.substringAfter(':')?.trim()?.takeIf { it.startsWith("http") }

    /** The descriptions' addresses of the media players that answered within [timeoutMs]. */
    suspend fun search(context: Context, timeoutMs: Long = 3_000): Set<String> = withContext(Dispatchers.IO) {
        // Android drops multicast traffic unless an app asks for it.
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("samm-speakers")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        val found = mutableSetOf<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 300
                val message = searchMessage().toByteArray()
                val group = InetAddress.getByName(ADDRESS)
                // UDP can be lost: ask twice.
                repeat(2) { socket.send(DatagramPacket(message, message.size, group, PORT)) }
                val until = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < until) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    locationOf(String(packet.data, 0, packet.length))?.let(found::add)
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "Searching the network failed: ${e.message}")
        } finally {
            runCatching { lock?.release() }
        }
        found
    }
}
