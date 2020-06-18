package com.example.simplertmp

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.*

/**
 * Misc utility method
 *
 * @author francois, pedro
 */
object Util {
    private const val HEXES = "0123456789ABCDEF"

    @Throws(IOException::class)
    fun writeUnsignedInt32(output: OutputStream, value: Int) {
        output.write(value ushr 24)
        output.write(value ushr 16)
        output.write(value ushr 8)
        output.write(value)
    }

    @Throws(IOException::class)
    fun readUnsignedInt32(input: InputStream): Int {
        return input.read() and 0xff shl 24 or (input.read() and 0xff shl 16) or (input.read() and 0xff shl 8) or (input.read() and 0xff)
    }

    @Throws(IOException::class)
    fun readUnsignedInt32(input: ByteArray): Int {
        require(input.size >= 4) { "Not enough elements available to read a 32 bit integer" }
        return input[0].toInt() and 0xff shl 24 or (input[1].toInt() and 0xff shl 16) or (input[2].toInt() and 0xff shl 8) or (input[3].toInt() and 0xff)
    }

    @Throws(IOException::class)
    fun readUnsignedInt24(input: InputStream): Int {
        return input.read() and 0xff shl 16 or (input.read() and 0xff shl 8) or (input.read() and 0xff)
    }

    @Throws(IOException::class)
    fun readUnsignedInt24(input: ByteArray): Int {
        require(input.size >= 3) { "Not enough elements available to read a 24 bit integer" }
        return input[0].toInt() and 0xff shl 16 or (input[1].toInt() and 0xff shl 8) or (input[2].toInt() and 0xff)
    }

    @Throws(IOException::class)
    fun readUnsignedInt16(input: InputStream): Int {
        return input.read() and 0xff shl 8 or (input.read() and 0xff)
    }

    @Throws(IOException::class)
    fun readUnsignedInt16(input: ByteArray): Int {
        require(input.size >= 2) { "Not enough elements available to read a 16 bit integer" }
        return input[0].toInt() and 0xff shl 8 or (input[1].toInt() and 0xff)
    }

    @Throws(IOException::class)
    fun writeUnsignedInt24(out: OutputStream, value: Int) {
        out.write(value ushr 16)
        out.write(value ushr 8)
        out.write(value)
    }

    @Throws(IOException::class)
    fun writeUnsignedInt16(out: OutputStream, value: Int) {
        out.write(value ushr 8)
        out.write(value)
    }

    fun toUnsignedInt32(bytes: ByteArray): Int {
        return bytes[0].toInt() and 0xff shl 24 or (bytes[1].toInt() and 0xff shl 16) or ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
    }

    fun toUnsignedInt32LittleEndian(bytes: ByteArray): Int {
        return (bytes[3].toInt() and 0xff shl 24 or (bytes[2].toInt() and 0xff shl 16) or (bytes[1].toInt() and 0xff shl 8) or (bytes[0].toInt() and 0xff))
    }

    @Throws(IOException::class)
    fun writeUnsignedInt32LittleEndian(out: OutputStream, value: Int) {
        out.write(value)
        out.write(value ushr 8)
        out.write(value ushr 16)
        out.write(value ushr 24)
    }

    fun toUnsignedInt24(bytes: ByteArray): Int {
        return bytes[1].toInt() and 0xff shl 16 or (bytes[2].toInt() and 0xff shl 8) or (bytes[3].toInt() and 0xff)
    }

    fun toUnsignedInt16(bytes: ByteArray): Int {
        return bytes[2].toInt() and 0xff shl 8 or (bytes[3].toInt() and 0xff)
    }

    fun toHexString(raw: ByteArray?): String? {
        if (raw == null) {
            return null
        }
        val hex = StringBuilder(2 * raw.size)
        for (b in raw) {
            hex.append(HEXES[b.toInt() and 0xF0 shr 4]).append(HEXES[b.toInt() and 0x0F])
        }
        return hex.toString()
    }

    fun toHexString(b: Byte): String {
        return StringBuilder().append(HEXES[b.toInt() and 0xF0 shr 4]).append(HEXES[b.toInt() and 0x0F]).toString()
    }

    /**
     * Reads bytes from the specified inputstream into the specified target buffer until it is filled up
     */
    @Throws(IOException::class)
    fun readBytesUntilFull(input: InputStream, targetBuffer: ByteArray) {
        var totalBytesRead = 0
        var read: Int
        val targetBytes = targetBuffer.size
        do {
            read = input.read(targetBuffer, totalBytesRead, targetBytes - totalBytesRead)
            totalBytesRead += if (read != -1) {
                read
            } else {
                throw IOException("Unexpected EOF reached before read buffer was filled")
            }
        } while (totalBytesRead < targetBytes)
    }

    fun toByteArray(d: Double): ByteArray {
        val l = java.lang.Double.doubleToRawLongBits(d)
        return byteArrayOf(
            (l shr 56 and 0xff).toByte(),
            (l shr 48 and 0xff).toByte(),
            (l shr 40 and 0xff).toByte(),
            (l shr 32 and 0xff).toByte(),
            (l shr 24 and 0xff).toByte(),
            (l shr 16 and 0xff).toByte(),
            (l shr 8 and 0xff).toByte(),
            (l and 0xff).toByte()
        )
    }

    @Throws(IOException::class)
    fun unsignedInt32ToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()
        )
    }

    @Throws(IOException::class)
    fun readDouble(input: ByteArray): Double {
        val bits =
            ((input[0].toInt() and 0xff).toLong() shl 56 or ((input[1].toInt() and 0xff).toLong() shl 48) or ((input[2].toInt() and 0xff).toLong() shl 40) or ((input[3].toInt() and 0xff).toLong() shl 32) or (input[4].toInt() and 0xff shl 24).toLong() or (input[5].toInt() and 0xff shl 16).toLong() or (input[6].toInt() and 0xff shl 8).toLong() or (input[7].toInt() and 0xff).toLong())
        return java.lang.Double.longBitsToDouble(bits)
    }

    @Throws(IOException::class)
    fun writeDouble(out: OutputStream, d: Double) {
        val l = java.lang.Double.doubleToRawLongBits(d)
        out.write(
            byteArrayOf(
                (l shr 56 and 0xff).toByte(),
                (l shr 48 and 0xff).toByte(),
                (l shr 40 and 0xff).toByte(),
                (l shr 32 and 0xff).toByte(),
                (l shr 24 and 0xff).toByte(),
                (l shr 16 and 0xff).toByte(),
                (l shr 8 and 0xff).toByte(),
                (l and 0xff).toByte()
            )
        )
    }

    fun getSalt(description: String): String? {
        var salt: String? = null
        val data = description.split("&".toRegex()).toTypedArray()
        for (s in data) {
            if (s.contains("salt=")) {
                salt = s.substring(5)
                break
            }
        }
        return salt
    }

    fun getChallenge(description: String): String? {
        var challenge: String? = null
        val data = description.split("&".toRegex()).toTypedArray()
        for (s in data) {
            if (s.contains("challenge=")) {
                challenge = s.substring(10)
                break
            }
        }
        return challenge
    }

    fun getOpaque(description: String): String {
        var opaque = ""
        val data = description.split("&".toRegex()).toTypedArray()
        for (s in data) {
            if (s.contains("opaque=")) {
                opaque = s.substring(7)
                break
            }
        }
        return opaque
    }

    fun stringToMD5BASE64(s: String): String? {
        return try {
            val md = MessageDigest.getInstance("MD5")
            md.update(s.toByteArray(charset("UTF-8")), 0, s.length)
            val md5hash = md.digest()
            Base64.getEncoder().encode(md5hash).toString()
        } catch (e: Exception) {
            null
        }
    }
}