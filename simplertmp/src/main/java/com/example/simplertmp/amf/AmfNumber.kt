package com.example.simplertmp.amf

import com.example.simplertmp.Util
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream


/**
 * AMF0 Number data type
 *
 * @author francois
 */
class AmfNumber : AmfData {
    var value = 0.0
    override val size = 9

    constructor(value: Double) {
        this.value = value
    }

    constructor() {}

    @Throws(IOException::class)
    override fun writeTo(output: OutputStream) {
        output.write(AmfType.NUMBER.value.toInt())
        Util.writeDouble(output, value)
    }

    @Throws(IOException::class)
    override fun readFrom(input: InputStream) {
        // Skip data type byte (we assume it's already read)
        value = Util.readDouble(input)
    }

    companion object {
        /** Size of an AMF number, in bytes (including type bit)  */
        const val size = 9

        @Throws(IOException::class)
        fun readNumberFrom(input: InputStream): Double {
            // Skip data type byte
            input.read()
            return Util.readDouble(input)
        }

        @Throws(IOException::class)
        fun writeNumberTo(out: OutputStream, number: Double) {
            out.write(AmfType.NUMBER.value.toInt())
            Util.writeDouble(out, number)
        }
    }
}