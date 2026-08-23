package com.example.wolquicktile.watch.domain

import com.example.wolquicktile.watch.data.WatchDevice
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Sends a standard 6-byte prefix plus sixteen MAC repetitions. */
object WakeOnLanSender {
    private val macRegex = Regex("^(([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})|[0-9A-Fa-f]{12})$")

    fun isValidMac(macAddress: String): Boolean = macRegex.matches(macAddress.trim())

    fun wake(device: WatchDevice) {
        send(device.macAddress, device.broadcastAddress, device.port)
    }

    fun send(macAddress: String, broadcastAddress: String = "255.255.255.255", port: Int = 9) {
        require(isValidMac(macAddress)) { "MAC 地址格式不正确" }
        require(port in 1..65535) { "UDP 端口必须在 1-65535 之间" }

        val macBytes = parseMac(macAddress)
        val packetBytes = ByteArray(6 + 16 * macBytes.size)
        repeat(6) { packetBytes[it] = 0xFF.toByte() }
        repeat(16) { index ->
            macBytes.copyInto(packetBytes, destinationOffset = 6 + index * macBytes.size)
        }

        DatagramSocket().use { socket ->
            socket.broadcast = true
            val target = InetAddress.getByName(resolveBroadcastTarget(broadcastAddress))
            socket.send(DatagramPacket(packetBytes, packetBytes.size, target, port))
        }
    }

    private fun resolveBroadcastTarget(address: String): String {
        val value = address.trim().ifBlank { "255.255.255.255" }
        if (value == "255.255.255.255") return value
        val octets = parseIpv4(value) ?: return value
        return "${octets[0]}.${octets[1]}.${octets[2]}.255"
    }

    private fun parseIpv4(value: String): List<Int>? {
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.isBlank() }) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        return octets.takeIf { values -> values.all { it in 0..255 } }
    }

    private fun parseMac(macAddress: String): ByteArray {
        val clean = macAddress.replace(":", "").replace("-", "")
        require(clean.length == 12) { "MAC 地址格式不正确" }
        return ByteArray(6) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
