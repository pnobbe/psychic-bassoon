package com.example.simplertmp.packets

import com.example.simplertmp.Util
import com.example.simplertmp.io.ChunkStreamInfo
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*


/**
 * Set Peer Bandwidth
 *
 * Also known as ClientrBW ("client bandwidth") in some RTMP implementations.
 *
 * @author francois
 */
class SetPeerBandwidth : RtmpPacket {

    override var array: ByteArray? = null
    override var size = 0

    /**
     * Bandwidth limiting type
     */
    enum class LimitType(val intValue: Int) {
        /**
         * In a hard (0) request, the peer must send the data in the provided bandwidth.
         */
        HARD(0),

        /**
         * In a soft (1) request, the bandwidth is at the discretion of the peer
         * and the sender can limit the bandwidth.
         */
        SOFT(1),

        /**
         * In a dynamic (2) request, the bandwidth can be hard or soft.
         */
        DYNAMIC(2);
    }

    var acknowledgementWindowSize = 0
    lateinit var limitType: LimitType

    constructor(header: RtmpHeader) : super(header)
    constructor(acknowledgementWindowSize: Int, limitType: LimitType,
                channelInfo: ChunkStreamInfo) : super(RtmpHeader(if (channelInfo.canReusePrevHeaderTx(RtmpHeader.MessageType.SET_PEER_BANDWIDTH)) RtmpHeader.ChunkType.TYPE_2_RELATIVE_TIMESTAMP_ONLY else RtmpHeader.ChunkType.TYPE_0_FULL,
            ChunkStreamInfo.RTMP_CID_PROTOCOL_CONTROL.toInt(),
            RtmpHeader.MessageType.WINDOW_ACKNOWLEDGEMENT_SIZE)) {
        this.acknowledgementWindowSize = acknowledgementWindowSize
        this.limitType = limitType
    }

    @Throws(IOException::class)
    override fun readBody(input: InputStream) {
        acknowledgementWindowSize = Util.readUnsignedInt32(input)
        val index = input.read()
        limitType = LimitType.values().first { it.intValue == index }
    }

    @Throws(IOException::class)
    override fun writeBody(output: OutputStream) {
        Util.writeUnsignedInt32(output, acknowledgementWindowSize)
        output.write(limitType.intValue)
    }

    override fun toString(): String {
        return "RTMP Set Peer Bandwidth"
    }
}