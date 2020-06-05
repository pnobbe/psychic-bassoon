package com.example.simplertmp.io

import com.example.simplertmp.packets.*
import java.io.IOException
import java.io.InputStream
import java.util.logging.Level
import java.util.logging.Logger


/**
 * @author francois
 */
class RtmpDecoder(private val rtmpSessionInfo: RtmpSessionInfo) {
    @Throws(IOException::class)
    fun readPacket(input: InputStream): RtmpPacket? {
        var inp: InputStream? = input
        val header = RtmpHeader.readHeader(inp!!, rtmpSessionInfo)
        // Log.d(TAG, "readPacket(): header.messageType: " + header.getMessageType());
        val chunkStreamInfo = rtmpSessionInfo.getChunkStreamInfo(header.chunkStreamId)
        chunkStreamInfo.setPrevHeaderRx(header)
        if (header.packetLength > rtmpSessionInfo.rxChunkSize) {
            // If the packet consists of more than one chunk,
            // store the chunks in the chunk stream until everything is read
            inp = if (!chunkStreamInfo.storePacketChunk(inp, rtmpSessionInfo.rxChunkSize)) {
                // return null because of incomplete packet
                return null
            } else {
                // stored chunks complete packet, get the input stream of the chunk stream
                chunkStreamInfo.storedPacketInputStream
            }
        }
        val rtmpPacket: RtmpPacket
        when (header.messageType) {
            RtmpHeader.MessageType.SET_CHUNK_SIZE -> {
                val setChunkSize = SetChunkSize(header)
                setChunkSize.readBody(inp)
                Logger.getLogger(TAG).log(Level.INFO, "readPacket(): Setting chunk size to: ${setChunkSize.chunkSize}")
                rtmpSessionInfo.rxChunkSize = setChunkSize.chunkSize
                return null
            }
            RtmpHeader.MessageType.ABORT -> rtmpPacket = Abort(header)
            RtmpHeader.MessageType.USER_CONTROL_MESSAGE -> rtmpPacket = UserControl(header)
            RtmpHeader.MessageType.WINDOW_ACKNOWLEDGEMENT_SIZE -> rtmpPacket = WindowAckSize(header)
            RtmpHeader.MessageType.SET_PEER_BANDWIDTH -> rtmpPacket = SetPeerBandwidth(header)
            RtmpHeader.MessageType.AUDIO -> rtmpPacket = Audio(header)
            RtmpHeader.MessageType.VIDEO -> rtmpPacket = Video(header)
            RtmpHeader.MessageType.COMMAND_AMF0 -> rtmpPacket = Command(header)
            RtmpHeader.MessageType.DATA_AMF0 -> rtmpPacket = Data(header)
            RtmpHeader.MessageType.ACKNOWLEDGEMENT -> rtmpPacket = Acknowledgement(header)
            else -> throw IOException(
                    "No packet body implementation for message type: " + header.messageType)
        }
        rtmpPacket.readBody(inp)
        return rtmpPacket
    }

    companion object {
        private const val TAG = "RtmpDecoder"
    }

}