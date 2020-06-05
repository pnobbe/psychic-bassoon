package com.example.simplertmp.amf

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream


/**
 * Base AMF data object. All other AMF data type instances derive from this
 * (including AmfObject)
 *
 * @author francois
 */
interface AmfData {
    /**
     * Write/Serialize this AMF data intance (Object/string/integer etc) to
     * the specified OutputStream
     */
    @Throws(IOException::class)
    fun writeTo(output: OutputStream)

    /**
     * Read and parse bytes from the specified input stream to populate this
     * AMFData instance (deserialize)
     *
     * @return the amount of bytes read
     */
    @Throws(IOException::class)
    fun readFrom(input: InputStream)

    /** @return the amount of bytes required for this object
     */
    val size: Int
}