package com.example.simplertmp.packets

import com.example.simplertmp.io.ChunkStreamInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream


/**
 * @author francois, leo
 */
abstract class RtmpPacket(val header: RtmpHeader) {

    abstract var array: ByteArray?
    abstract var size: Int

    @Throws(IOException::class)
    abstract fun readBody(input: InputStream)

    @Throws(IOException::class)
    protected abstract fun writeBody(output: OutputStream)

    @Throws(IOException::class)
    fun writeTo(output: OutputStream, chunkSize: Int, chunkStreamInfo: ChunkStreamInfo?) {
        val baos = ByteArrayOutputStream()
        writeBody(baos)
        val body = if (this is ContentData) array!! else baos.toByteArray()
        var length = if (this is ContentData) size else body.size
        header.packetLength = length

        // Write header for first chunk
        if (chunkStreamInfo != null) {
            header.writeTo(output, RtmpHeader.ChunkType.TYPE_0_FULL, chunkStreamInfo)
        }

        var pos = 0
        while (length > chunkSize) {
            // Write packet for chunk
            output.write(body, pos, chunkSize)
            length -= chunkSize
            pos += chunkSize
            // Write header for remain chunk
            if (chunkStreamInfo != null) {
                header.writeTo(output, RtmpHeader.ChunkType.TYPE_3_RELATIVE_SINGLE_BYTE, chunkStreamInfo)
            }
        }
        output.write(body, pos, length)
    }
}