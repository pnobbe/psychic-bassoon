package com.example.simplertmp.amf

import com.example.simplertmp.Util
import java.io.IOException
import java.io.OutputStream
import java.io.UnsupportedEncodingException
import java.nio.charset.Charset
import java.util.logging.Level
import java.util.logging.Logger


/**
 * @author francois
 */
class AmfString : AmfData {
    lateinit var value: String
    private var isKey: Boolean = false
    override var size = 0
        get() {
            if (this::value.isInitialized) {
                field = try {
                    // Key bytes + 2 length bytes + size
                    3 + value.toByteArray(charset("ASCII")).size
                } catch (ex: UnsupportedEncodingException) {
                    Logger.getLogger(TAG).log(Level.SEVERE, ex.message)
                    throw RuntimeException(ex)
                }
            }
            return field
        }

    constructor(value: String, isKey: Boolean = false) {
        this.value = value
        this.isKey = isKey
    }

    constructor()

    override fun writeTo(output: OutputStream) {
        // Strings are ASCII encoded
        val byteValue = value.toByteArray(charset("ASCII"))
        // Write the STRING data type definition (except if this String is used as a key)
        if (!isKey) {
            output.write(AmfType.STRING.value.toInt())
        }
        // Write 2 bytes indicating string length
        Util.writeUnsignedInt16(output, byteValue.size)
        // Write string
        output.write(byteValue)
    }

    override fun readFrom(input: ByteArray) {
        val length: Int = Util.readUnsignedInt16(input)

        // Read string value
        value = String(input
                .drop(2)
                .take(length)
                .toByteArray(),
                Charset.forName("ASCII")
        )
    }

    companion object {
        private const val TAG = "AmfString"

        @Throws(IOException::class)
        fun readStringFrom(input: ByteArray): String {
            val length: Int = Util.readUnsignedInt16(input)
            // Read string value
            return String(input
                    .drop(2)
                    .dropLast(input.size - (2 + length))
                    .toByteArray(),
                    Charset.forName("ASCII"))
        }

        @Throws(IOException::class)
        fun writeStringTo(out: OutputStream, string: String, isKey: Boolean) {
            // Strings are ASCII encoded
            val byteValue = string.toByteArray(charset("ASCII"))
            // Write the STRING data type definition (except if this String is used as a key)
            if (!isKey) {
                out.write(AmfType.STRING.value.toInt())
            }
            // Write 2 bytes indicating string length
            Util.writeUnsignedInt16(out, byteValue.size)
            // Write string
            out.write(byteValue)
        }

        /** @return the byte size of the resulting AMF string of the specified value
         */
        fun sizeOf(string: String, isKey: Boolean): Int {
            return try {
                (if (isKey) 0 else 1) + 2 + string.toByteArray(charset("ASCII")).size
            } catch (ex: UnsupportedEncodingException) {
                Logger.getLogger(TAG).log(Level.SEVERE, ex.message)
                throw RuntimeException(ex)
            }
        }
    }
}