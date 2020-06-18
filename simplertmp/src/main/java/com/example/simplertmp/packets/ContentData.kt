package com.example.simplertmp.packets

import com.example.simplertmp.Util
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Content (audio/video) data packet base
 *
 * @author francois
 */
abstract class ContentData(header: RtmpHeader) : RtmpPacket(header) {
    override var array: ByteArray? = null
    override var size = 0

    fun setData(array: ByteArray, size: Int) {
        this.array = array
        this.size = size
    }

    @Throws(IOException::class)
    override fun readBody(input: ByteArray) {
        array = input
    }

    /**
     * Method is public for content (audio/video)
     * Write this packet body without chunking;
     * useful for dumping audio/video streams
     */
    override fun writeBody(output: OutputStream) {}

}