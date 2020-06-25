package com.example.simplertmp.amf

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream


/**
 * @author francois
 */
class AmfNull : AmfData {
    @Throws(IOException::class)
    override fun writeTo(output: OutputStream) {
        output.write(AmfType.NULL.value.toInt())
    }

    override fun readFrom(input: ByteArray) {}

    override var size: Int = 1

    companion object {
        @Throws(IOException::class)
        fun writeNullTo(out: OutputStream) {
            out.write(AmfType.NULL.value.toInt())
        }
    }
}