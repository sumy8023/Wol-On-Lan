package com.example.wolquicktile.domain.wol

import com.example.wolquicktile.data.entity.DeviceEntity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object WakeOnLanSender {
    private val macRegex = Regex("^(([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})|[0-9A-Fa-f]{12})$")

    fun isValidMac(macAddress: String): Boolean = macRegex.matches(macAddress.trim())

    fun wake(device: DeviceEntity) {
        send(device.macAddress, device.broadcastAddress, device.port)
    }

    fun send(macAddress: String, broadcastAddress: String, port: Int) {
        val macBytes = parseMac(macAddress)
        val packetBytes = ByteArray(6 + 16 * macBytes.size)
        repeat(6) { packetBytes[it] = 0xFF.toByte() }
        for (i in 0 until 16) {
            macBytes.copyInto(packetBytes, destinationOffset = 6 + i * macBytes.size)
        }

        DatagramSocket().use { socket ->
            socket.broadcast = true
            val address = InetAddress.getByName(resolveBroadcastTarget(broadcastAddress))
            val packet = DatagramPacket(packetBytes, packetBytes.size, address, port)
            socket.send(packet)
        }
    }

    private fun resolveBroadcastTarget(address: String): String {
        val value = address.trim().ifBlank { "255.255.255.255" }
        if (value == "255.255.255.255") return value
        val octets = parseIpv4(value) ?: return value
        return "${octets[0]}.${octets[1]}.${octets[2]}.255"
    }

    private fun parseIpv4(value: String): List<Int>? {
        val parts = value.split(".")
        if (parts.size != 4 || parts.any { it.isBlank() }) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        return octets.takeIf { values -> values.all { it in 0..255 } }
    }

    private fun parseMac(macAddress: String): ByteArray {
        val clean = macAddress.replace(":", "").replace("-", "")
        require(clean.length == 12) { "Invalid MAC address" }
        return ByteArray(6) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
