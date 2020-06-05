package com.example.simplertmp.amf

import AmfArray
import java.io.IOException
import java.io.InputStream


/**
 * @author francois
 */
object AmfDecoder {
    @Throws(IOException::class)
    fun readFrom(inputStream: InputStream): AmfData {
        val amfTypeByte = inputStream.read().toByte()
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
        amfData.readFrom(inputStream)
        return amfData
    }
}