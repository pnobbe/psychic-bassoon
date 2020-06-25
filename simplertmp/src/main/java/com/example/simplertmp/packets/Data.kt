package com.example.simplertmp.packets

import com.example.simplertmp.amf.AmfString
import com.example.simplertmp.io.ChunkStreamInfo
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream


/**
 * AMF Data packet
 *
 * Also known as NOTIFY in some RTMP implementations.
 *
 * The client or the server sends this message to send Metadata or any user data
 * to the peer. Metadata includes details about the data (audio, video etc.)
 * like creation time, duration, theme and so on.
 *
 * @author francois
 */
class Data : VariableBodyRtmpPacket {
    lateinit var type: String

    override var array: ByteArray? = null
    override var size: Int = 0

    constructor(header: RtmpHeader) : super(header)

    constructor(type: String) : super(RtmpHeader(RtmpHeader.ChunkType.TYPE_0_FULL, ChunkStreamInfo.RTMP_CID_OVER_CONNECTION.toInt(),
            RtmpHeader.MessageType.DATA_AMF0)) {
        this.type = type
    }

    @Throws(IOException::class)
    override fun readBody(input: ByteArray) {
        // Read notification type
        type = AmfString.readStringFrom(input.drop(1).toByteArray())
        val bytesRead = AmfString.sizeOf(type, false)
        // Read data body
        readVariableData(input, bytesRead)
    }

    /**
     * This method is public for Data to make it easy to dump its contents to
     * another output stream
     */
    @Throws(IOException::class)
    override fun writeBody(output: OutputStream) {
        AmfString.writeStringTo(output, type, false)
        writeVariableData(output)
    }
}