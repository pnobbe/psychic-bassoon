package com.example.simplertmp.amf

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream


/**
 * @author leoma
 */
class AmfUndefined : AmfData {
    @Throws(IOException::class)
    override fun writeTo(output: OutputStream) {
        output.write(AmfType.UNDEFINED.value.toInt())
    }

    override fun readFrom(input: InputStream) {
        // Skip data type byte (we assume it's already read)
    }

    override val size: Int
        get() = 1

    companion object {
        @Throws(IOException::class)
        fun writeUndefinedTo(out: OutputStream) {
            out.write(AmfType.UNDEFINED.value.toInt())
        }
    }
}