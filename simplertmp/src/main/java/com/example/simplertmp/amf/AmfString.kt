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
    var isKey = false
    override var size = -1
        get() {
            if (field == -1) {
                field = try {
                    (if (isKey) 0 else 1) + 2 + (value.toByteArray(charset("ASCII")).size)
                } catch (ex: UnsupportedEncodingException) {
                    Logger.getLogger(TAG).log(Level.SEVERE, ex.message)
                    throw RuntimeException(ex)
                }
            }
            return field
        }

    @JvmOverloads
    constructor(value: String, isKey: Boolean = false) {
        this.value = value
        this.isKey = isKey
    }

    constructor()

    constructor(isKey: Boolean) {
        this.isKey = isKey
    }

    @Throws(IOException::class)
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

    @Throws(IOException::class)
    override fun readFrom(input: ByteArray) {
        val length: Int = Util.readUnsignedInt16(input)
        size = 3 + length // 1 + 2 + length

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
        fun readStringFrom(input: ByteArray, isKey: Boolean): String {
            var index = if (!isKey) 1 else 0
            val length: Int = Util.readUnsignedInt16(input.drop(index).toByteArray())
            index += 2
            // Read string value
            return String(input
                    .drop(index)
                    .dropLast(input.size - (index + length))
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
                (if (!isKey) 1 else 0) + 2 + string.toByteArray(charset("ASCII")).size
            } catch (ex: UnsupportedEncodingException) {
                Logger.getLogger(TAG).log(Level.SEVERE, ex.message)
                throw RuntimeException(ex)
            }
        }
    }
}