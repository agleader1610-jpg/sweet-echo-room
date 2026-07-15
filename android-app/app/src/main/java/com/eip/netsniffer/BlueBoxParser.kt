package com.eip.netsniffer

import android.util.Base64
import java.nio.ByteBuffer

/**
 * Decoder for Spribe "BlueBox" WebSocket binary frames used by Aviator.
 *
 * Frame layout:
 *   byte0      = 0x80 (marker)
 *   byte1..2   = big-endian uint16 payload length (excludes the 3-byte header)
 *   byte3..    = value tree
 *
 * Type tags observed on the wire:
 *   0x02 = byte           (1 byte)
 *   0x03 = int16          (2 bytes BE)
 *   0x04 = int32          (4 bytes BE)
 *   0x07 = float64        (8 bytes BE, IEEE 754)
 *   0x08 = string         (uint16 length + UTF-8 bytes)
 *   0x11 = list           (uint16 count + N values, each with its own type tag)
 *   0x12 = map            (uint16 entryCount + N * (uint16 nameLen + name + value))
 */
object BlueBoxParser {

    fun parse(bytes: ByteArray): Any? {
        if (bytes.size < 4 || (bytes[0].toInt() and 0xff) != 0x80) return null
        val buf = ByteBuffer.wrap(bytes)
        buf.position(3)
        return try { readValue(buf) } catch (_: Throwable) { null }
    }

    /** Extracts the base64 blob that JsHook appends to binary WS previews. */
    fun extractBase64Bytes(preview: String?): ByteArray? {
        if (preview.isNullOrEmpty()) return null
        val marker = preview.indexOf("── BASE64")
        if (marker < 0) return null
        val ruleEnd = preview.indexOf("──\n", marker)
        if (ruleEnd < 0) return null
        val start = ruleEnd + 3
        var end = preview.indexOf("\n\n", start)
        if (end < 0) end = preview.length
        val b64 = preview.substring(start, end)
            .replace("\n", "").replace("\r", "").replace(" ", "").trim()
        if (b64.isEmpty()) return null
        return try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Throwable) { null }
    }

    private fun readValue(buf: ByteBuffer): Any? {
        if (buf.remaining() < 1) return null
        val type = buf.get().toInt() and 0xff
        return when (type) {
            0x02 -> (buf.get().toInt() and 0xff)
            0x03 -> buf.short.toInt()
            0x04 -> buf.int
            0x07 -> buf.double
            0x08 -> {
                val len = buf.short.toInt() and 0xffff
                if (len > buf.remaining()) return null
                val b = ByteArray(len); buf.get(b); String(b, Charsets.UTF_8)
            }
            0x11 -> {
                val n = buf.short.toInt() and 0xffff
                val list = ArrayList<Any?>(n)
                repeat(n) { list.add(readValue(buf)) }
                list
            }
            0x12 -> {
                val n = buf.short.toInt() and 0xffff
                val map = LinkedHashMap<String, Any?>(n)
                repeat(n) {
                    val nameLen = buf.short.toInt() and 0xffff
                    if (nameLen > buf.remaining()) return@repeat
                    val nb = ByteArray(nameLen); buf.get(nb)
                    val name = String(nb, Charsets.UTF_8)
                    map[name] = readValue(buf)
                }
                map
            }
            else -> null
        }
    }
}
