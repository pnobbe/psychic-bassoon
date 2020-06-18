package com.example.simplertmp.amf

import AmfArray
import java.io.IOException
import java.io.InputStream


/**
 * @author francois
 */
object AmfDecoder {
    @Throws(IOException::class)
    fun readFrom(input: ByteArray): AmfData {
        val amfTypeByte = input[0]
        val amfData: AmfData = when (val amfType: AmfType? = AmfType.valueOf(amfTypeByte)) {
            AmfType.NUMBER -> AmfNumber()
            AmfType.BOOLEAN -> AmfBoolean()
            AmfType.STRING -> AmfString()
            AmfType.OBJECT -> AmfObject()
            AmfType.NULL -> AmfNull()
            AmfType.UNDEFINED -> AmfUndefined()
            AmfType.ECMA_MAP -> AmfMap()
            AmfType.STRICT_ARRAY -> AmfArray()
            else -> throw IOException("Unknown/unimplemented AMF data type: $amfType")
        }
        amfData.readFrom(input.drop(1).toByteArray()) // Remove type byte, we don't need our data to parse it
        return amfData
    }
}
