package com.example.simplertmp.amf

import java.util.*


/**
 * AMF0 data type enum
 *
 * @author francois
 */
enum class AmfType(intValue: Int) {
    /** Number (encoded as IEEE 64-bit double precision floating point number)  */
    NUMBER(0x00),

    /** Boolean (Encoded as a single byte of value 0x00 or 0x01)  */
    BOOLEAN(0x01),

    /** String (ASCII encoded)  */
    STRING(0x02),

    /** Object - set of key/value pairs  */
    OBJECT(0x03),

    NULL(0x05),
    UNDEFINED(0x06),
    ECMA_MAP(0x08),
    STRICT_ARRAY(0x0A);

    val value: Byte = intValue.toByte()

    companion object {
        private val quickLookupMap: MutableMap<Byte, AmfType> = HashMap()
        fun valueOf(amfTypeByte: Byte): AmfType? {
            return quickLookupMap[amfTypeByte]
        }

        init {
            for (amfType in values()) {
                quickLookupMap[amfType.value] = amfType
            }
        }
    }

}