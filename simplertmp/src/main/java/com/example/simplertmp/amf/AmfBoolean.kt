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
    override fun readFrom(input: ByteArray) {
        isValue = input[0].toInt() == 0x01
    }

    // Key byte + bool byte
    override var size: Int = 2

}