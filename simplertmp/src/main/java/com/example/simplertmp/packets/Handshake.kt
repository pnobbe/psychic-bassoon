package com.example.simplertmp.packets

import com.example.simplertmp.Crypto
import com.example.simplertmp.Util
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger


/**
 * Handles the RTMP handshake song 'n dance
 *
 * Thanks to http://thompsonng.blogspot.com/2010/11/rtmp-part-10-handshake.html for some very useful information on
 * the the hidden "features" of the RTMP handshake
 *
 * @author francois
 */
class Handshake {
    /** S1 as sent by the server  */
    private lateinit var s1: ByteArray

    /** Generates and writes the first handshake packet (C0)  */
    @Throws(IOException::class)
    fun writeC0(output: OutputStream) {
        Logger.getLogger(TAG).log(Level.INFO, "writeC0")
        output.write(PROTOCOL_VERSION)
    }

    @Throws(IOException::class)
    fun readS0(input: InputStream) {
        Logger.getLogger(TAG).log(Level.INFO, "readS0")
        val s0 = input.read().toByte()
        if (s0.toInt() != PROTOCOL_VERSION && s0.toInt() != 72) { //check 72 to fix Ant Media Server
            if (s0.toInt() == -1) {
                throw IOException("InputStream closed")
            } else {
                throw IOException("Invalid RTMP protocol version; expected $PROTOCOL_VERSION, got $s0")
            }
        }
    }

    /** Generates and writes the second handshake packet (C1)  */
    @Throws(IOException::class)
    fun writeC1(output: OutputStream) {
        Logger.getLogger(TAG).log(Level.INFO, "writeC1")
        Logger.getLogger(TAG).log(Level.INFO, "WriteC1(): Calculating digest offset")
        val random = Random()
        // Since we are faking a real Flash Player handshake, include a digest in C1
        // Choose digest offset point (scheme 1; that is, offset is indicated by bytes 772 - 775 (4 bytes) )
        val digestOffset = random.nextInt(HANDSHAKE_SIZE
                - DIGEST_OFFSET_INDICATOR_POS
                - 4
                - 8
                - SHA256_DIGEST_SIZE) //random.nextInt(DIGEST_OFFSET_INDICATOR_POS - SHA256_DIGEST_SIZE);
        val absoluteDigestOffset = digestOffset % 728 + DIGEST_OFFSET_INDICATOR_POS + 4
        Logger.getLogger(TAG).log(Level.INFO, "writeC1(): (real value of) digestOffset: $digestOffset")
        Logger.getLogger(TAG).log(Level.INFO, "writeC1(): recalculated digestOffset: $absoluteDigestOffset")

        var remaining = digestOffset
        val digestOffsetBytes = ByteArray(4)
        for (i in 3 downTo 0) {
            if (remaining > 255) {
                digestOffsetBytes[i] = 255.toByte()
                remaining -= 255
            } else {
                digestOffsetBytes[i] = remaining.toByte()
                remaining -= remaining
            }
        }

        // Calculate the offset value that will be written
        //inal byte[] digestOffsetBytes = Util.unsignedInt32ToByteArray(digestOffset);// //((digestOffset - DIGEST_OFFSET_INDICATOR_POS) % 728)); // Thanks to librtmp for the mod 728
        Logger.getLogger(TAG).log(Level.INFO, "writeC1(): digestOFfsetBytes: ${Util.toHexString(digestOffsetBytes)}") //Util.unsignedInt32ToByteArray((digestOffset % 728))));

        // Create random bytes up to the digest offset point
        val partBeforeDigest = ByteArray(absoluteDigestOffset)
        Logger.getLogger(TAG).log(Level.INFO, "partBeforeDigest(): size: ${partBeforeDigest.size}")
        random.nextBytes(partBeforeDigest)
        Logger.getLogger(TAG).log(Level.INFO, "writeC1(): Writing timestamp and Flash Player version")
        val timeStamp: ByteArray = Util.unsignedInt32ToByteArray((System.currentTimeMillis() / 1000).toInt())
        System.arraycopy(timeStamp, 0, partBeforeDigest, 0,
                4) // Bytes 0 - 3 bytes: current epoch timestamp
        System.arraycopy(byteArrayOf(0x80.toByte(), 0x00, 0x07, 0x02), 0, partBeforeDigest, 4,
                4) // Bytes 4 - 7: Flash player version: 11.2.202.233

        // Create random bytes for the part after the digest
        val partAfterDigest = ByteArray(HANDSHAKE_SIZE
                - absoluteDigestOffset
                - SHA256_DIGEST_SIZE) // subtract 8 because of initial 8 bytes already written
        Logger.getLogger(TAG).log(Level.INFO, "partAfterDigest(): size: ${partAfterDigest.size}")
        random.nextBytes(partAfterDigest)

        // Set the offset byte
        Logger.getLogger(TAG).log(Level.INFO, "Copying digest offset bytes in partBeforeDigest")
        System.arraycopy(digestOffsetBytes, 0, partBeforeDigest, 772, 4)

        Logger.getLogger(TAG).log(Level.INFO, "writeC1(): Calculating digest")
        val tempBuffer = ByteArray(HANDSHAKE_SIZE - SHA256_DIGEST_SIZE)
        System.arraycopy(partBeforeDigest, 0, tempBuffer, 0, partBeforeDigest.size)
        System.arraycopy(partAfterDigest, 0, tempBuffer, partBeforeDigest.size,
                partAfterDigest.size)
        val crypto = Crypto()
        val digest = crypto.calculateHmacSHA256(tempBuffer, GENUINE_FP_KEY, 30)

        // Now write the packet
        Logger.getLogger(TAG).log(Level.INFO, "writeC1(): writing C1 packet")
        output.write(partBeforeDigest)
        output.write(digest)
        output.write(partAfterDigest)
    }

    @Throws(IOException::class)
    fun readS1(input: InputStream) {
        // S1 == 1536 bytes. We do not bother with checking the content of it
        Logger.getLogger(TAG).log(Level.INFO, "readS1")
        s1 = ByteArray(HANDSHAKE_SIZE)

        // Read server time (4 bytes)
        var totalBytesRead = 0
        var read: Int
        while (totalBytesRead < HANDSHAKE_SIZE) {
            read = input.read(s1, totalBytesRead, HANDSHAKE_SIZE - totalBytesRead)
            if (read != -1) {
                totalBytesRead += read
            }
        }
        if (totalBytesRead != HANDSHAKE_SIZE) {
            throw IOException("Unexpected EOF while reading S1, expected $HANDSHAKE_SIZE bytes, but only read $totalBytesRead bytes")
        } else {
            Logger.getLogger(TAG).log(Level.INFO, "readS1(): S1 total bytes read OK")
        }
    }

    /** Generates and writes the third handshake packet (C2)  */
    @Throws(IOException::class)
    fun writeC2(output: OutputStream) {
        Logger.getLogger(TAG).log(Level.INFO, "writeC2")
        // C2 is an echo of S1
        output.write(s1)
    }

    @Throws(IOException::class)
    fun readS2(input: InputStream) {
        // S2 should be an echo of C1, but we are not too strict
        Logger.getLogger(TAG).log(Level.INFO, "readS2")
        val sr_serverTime = ByteArray(4)
        val s2_serverVersion = ByteArray(4)
        val s2_rest = ByteArray(HANDSHAKE_SIZE - 8) // subtract 4+4 bytes for time and version

        // Read server time (4 bytes)
        var totalBytesRead = 0
        var read: Int
        do {
            read = input.read(sr_serverTime, totalBytesRead, 4 - totalBytesRead)
            totalBytesRead += if (read == -1) {
                // End of stream reached - should not have happened at this point
                throw IOException("Unexpected EOF while reading S2 bytes 0-3")
            } else {
                read
            }
        } while (totalBytesRead < 4)

        // Read server version (4 bytes)
        totalBytesRead = 0
        do {
            read = input.read(s2_serverVersion, totalBytesRead, 4 - totalBytesRead)
            totalBytesRead += if (read == -1) {
                // End of stream reached - should not have happened at this point
                throw IOException("Unexpected EOF while reading S2 bytes 4-7")
            } else {
                read
            }
        } while (totalBytesRead < 4)

        // Read 1528 bytes (to make up S1 total size of 1536 bytes)
        val remainingBytes = HANDSHAKE_SIZE - 8
        totalBytesRead = 0
        do {
            read = input.read(s2_rest, totalBytesRead, remainingBytes - totalBytesRead)
            if (read != -1) {
                totalBytesRead += read
            }
        } while (totalBytesRead < remainingBytes && read != -1)
        if (totalBytesRead != remainingBytes) {
            throw IOException("Unexpected EOF while reading remainder of S2, expected $remainingBytes bytes, but only read $totalBytesRead bytes")
        } else {
            Logger.getLogger(TAG).log(Level.INFO, "readS2(): S2 total bytes read OK")
        }
        // Technically we should check that S2 == C1, but for now this is ignored
    }

    companion object {
        private const val TAG = "Handshake"
        private const val PROTOCOL_VERSION = 0x03
        private const val HANDSHAKE_SIZE = 1536
        private const val SHA256_DIGEST_SIZE = 32
        private const val DIGEST_OFFSET_INDICATOR_POS = 772 // should either be byte 772 or byte 8
        private val GENUINE_FP_KEY = byteArrayOf(
                0x47.toByte(), 0x65.toByte(), 0x6E.toByte(), 0x75.toByte(), 0x69.toByte(), 0x6E.toByte(), 0x65.toByte(),
                0x20.toByte(), 0x41.toByte(), 0x64.toByte(), 0x6F.toByte(), 0x62.toByte(), 0x65.toByte(), 0x20.toByte(),
                0x46.toByte(), 0x6C.toByte(), 0x61.toByte(), 0x73.toByte(), 0x68.toByte(), 0x20.toByte(), 0x50.toByte(),
                0x6C.toByte(), 0x61.toByte(), 0x79.toByte(), 0x65.toByte(), 0x72.toByte(), 0x20.toByte(), 0x30.toByte(),
                0x30.toByte(), 0x31.toByte(),  // Genuine Adobe Flash Player 001
                0xF0.toByte(), 0xEE.toByte(), 0xC2.toByte(), 0x4A.toByte(), 0x80.toByte(), 0x68.toByte(), 0xBE.toByte(),
                0xE8.toByte(), 0x2E.toByte(), 0x00.toByte(), 0xD0.toByte(), 0xD1.toByte(), 0x02.toByte(), 0x9E.toByte(),
                0x7E.toByte(), 0x57.toByte(), 0x6E.toByte(), 0xEC.toByte(), 0x5D.toByte(), 0x2D.toByte(), 0x29.toByte(),
                0x80.toByte(), 0x6F.toByte(), 0xAB.toByte(), 0x93.toByte(), 0xB8.toByte(), 0xE6.toByte(), 0x36.toByte(),
                0xCF.toByte(), 0xEB.toByte(), 0x31.toByte(), 0xAE.toByte()
        )
    }
}