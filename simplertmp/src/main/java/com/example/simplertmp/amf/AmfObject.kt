package com.example.simplertmp.amf

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*


/**
 * AMF object
 *
 * @author francois
 */
open class AmfObject : AmfData {
    var properties: MutableMap<String, AmfData> = LinkedHashMap()
    override var size = -1
        get() {
            if (field == -1) {
                field = 1 // object marker
                for ((key, value) in properties) {
                    field += AmfString.sizeOf(key, true)
                    field += value.size
                }
                field += 3 // end of object marker
            }
            return field
        }

    fun getProperty(key: String): AmfData? {
        return properties[key]
    }

    fun setProperty(key: String, value: AmfData) {
        properties[key] = value
    }

    fun setProperty(key: String, value: Boolean) {
        properties[key] = AmfBoolean(value)
    }

    fun setProperty(key: String, value: String) {
        properties[key] = AmfString(value, false)
    }

    fun setProperty(key: String, value: Int) {
        properties[key] = AmfNumber(value.toDouble())
    }

    fun setProperty(key: String, value: Double) {
        properties[key] = AmfNumber(value)
    }

    @Throws(IOException::class)
    override fun writeTo(output: OutputStream) {
        // Begin the object
        output.write(AmfType.OBJECT.value.toInt())

        // Write key/value pairs in this object
        for ((key, value) in properties) {
            // The key must be a STRING type, and thus the "type-definition" byte is implied (not included in message)
            AmfString.writeStringTo(output, key, true)
            value.writeTo(output)
        }

        // End the object
        output.write(OBJECT_END_MARKER)
    }

    @Throws(IOException::class)
    override fun readFrom(input: InputStream) {
        // Skip data type byte (we assume it's already read)
        size = 1
        val markInputStream = if (input.markSupported()) input else BufferedInputStream(input)
        while (true) {
            // Look for the 3-byte object end marker [0x00 0x00 0x09]
            markInputStream.mark(3)
            val endMarker = ByteArray(3)
            markInputStream.read(endMarker)
            if (endMarker[0] == OBJECT_END_MARKER[0] && endMarker[1] == OBJECT_END_MARKER[1] && endMarker[2] == OBJECT_END_MARKER[2]) {
                // End marker found
                size += 3
                return
            } else {
                // End marker not found; reset the stream to the marked position and read an AMF property
                markInputStream.reset()
                // Read the property key...
                val key: String = AmfString.readStringFrom(input, true)
                size += AmfString.sizeOf(key, true)
                // ...and the property value
                val value = AmfDecoder.readFrom(markInputStream)
                size += value.size
                properties[key] = value
            }
        }
    }

    companion object {
        /** Byte sequence that marks the end of an AMF object  */
        private val OBJECT_END_MARKER = byteArrayOf(0x00, 0x00, 0x09)
    }
}