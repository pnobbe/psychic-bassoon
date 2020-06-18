package com.example.simplertmp.packets

import com.example.simplertmp.Util
import com.example.simplertmp.io.ChunkStreamInfo
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream


/**
 * (Window) Acknowledgement
 *
 * The client or the server sends the acknowledgment to the peer after
 * receiving bytes equal to the window size. The window size is the
 * maximum number of bytes that the sender sends without receiving
 * acknowledgment from the receiver. The server sends the window size to
 * the client after application connects. This message specifies the
 * sequence number, which is the number of the bytes received so far.
 *
 * @author francois
 */
class Acknowledgement : RtmpPacket {
    /** @return the sequence number, which is the number of the bytes received so far
     */
    /** Sets the sequence number, which is the number of the bytes received so far  */
    private var acknowledgementWindowSize = 0

    override var array: ByteArray? = null
    override var size: Int = 0

    constructor(header: RtmpHeader) : super(header)
    constructor(numBytesReadThusFar: Int) : super(
            RtmpHeader(RtmpHeader.ChunkType.TYPE_0_FULL, ChunkStreamInfo.RTMP_CID_PROTOCOL_CONTROL.toInt(),
                    RtmpHeader.MessageType.ACKNOWLEDGEMENT)) {
        acknowledgementWindowSize = numBytesReadThusFar
    }

    @Throws(IOException::class)
    override fun readBody(input: ByteArray) {
        acknowledgementWindowSize = Util.readUnsignedInt32(input)
    }

    @Throws(IOException::class)
    override fun writeBody(output: OutputStream) {
        Util.writeUnsignedInt32(output, acknowledgementWindowSize)
    }

    override fun toString(): String {
        return "RTMP Acknowledgment (sequence number: $acknowledgementWindowSize)"
    }
}
