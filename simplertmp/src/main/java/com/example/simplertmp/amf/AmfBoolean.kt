package com.example.simplertmp.amf

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream


/**
 * @author francois
 */
class AmfBoolean(private var isValue: Boolean = false) : AmfData {

    @Throws(IOException::class)
    override fun writeTo(output: OutputStream) {
        output.write(AmfType.BOOLEAN.value.toInt())
        output.write(if (isValue) 0x01 else 0x00)
    }

    @Throws(IOException::class)
    override fun readFrom(input: InputStream) {
        isValue = input.read() == 0x01
    }

    override val size: Int
        get() = 2

    companion object {
        @Throws(IOException::class)
        fun readBooleanFrom(input: InputStream): Boolean {
            // Skip data type byte (we assume it's already read)
            return input.read() == 0x01
        }
    }
}