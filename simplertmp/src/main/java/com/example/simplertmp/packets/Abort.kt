package com.example.simplertmp.packets

import com.example.simplertmp.Util
import com.example.simplertmp.io.ChunkStreamInfo
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream


/**
 * A "Abort" RTMP control message, received on chunk stream ID 2 (control channel)
 *
 * @author francois
 */
class Abort : RtmpPacket {
    /** @return the ID of the chunk stream to be aborted
     */
    /** Sets the ID of the chunk stream to be aborted  */
    var chunkStreamId = 0
    override var array: ByteArray? = null
    override var size: Int = 0

    constructor(header: RtmpHeader) : super(header)
    constructor(chunkStreamId: Int) : super(RtmpHeader(
            RtmpHeader.ChunkType.TYPE_1_RELATIVE_LARGE,
            ChunkStreamInfo.RTMP_CID_PROTOCOL_CONTROL.toInt(),
            RtmpHeader.MessageType.SET_CHUNK_SIZE)) {
        this.chunkStreamId = chunkStreamId
    }

    @Throws(IOException::class)
    override fun readBody(input: ByteArray) {
        // Value is received in the 4 bytes of the body
        chunkStreamId = Util.readUnsignedInt32(input)
    }

    @Throws(IOException::class)
    override fun writeBody(output: OutputStream) {
        Util.writeUnsignedInt32(output, chunkStreamId)
    }
}
