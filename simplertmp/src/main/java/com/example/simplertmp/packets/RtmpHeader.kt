package com.example.simplertmp.packets

import com.example.simplertmp.Util.readBytesUntilFull
import com.example.simplertmp.Util.readUnsignedInt24
import com.example.simplertmp.Util.readUnsignedInt32
import com.example.simplertmp.Util.toHexString
import com.example.simplertmp.Util.toUnsignedInt32LittleEndian
import com.example.simplertmp.Util.writeUnsignedInt24
import com.example.simplertmp.Util.writeUnsignedInt32
import com.example.simplertmp.Util.writeUnsignedInt32LittleEndian
import com.example.simplertmp.io.ChunkStreamInfo
import com.example.simplertmp.io.RtmpSessionInfo
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

/**
 * @author francois, leoma
 */
class RtmpHeader {
    /**
     * RTMP packet/message type definitions.
     * Note: docstrings are adapted from the official Adobe RTMP spec:
     * http://www.adobe.com/devnet/rtmp/
     */
    enum class MessageType(value: Int) {
        /**
         * Protocol control message 1
         * Set Chunk Size, is used to notify the peer a new maximum chunk size to use.
         */
        SET_CHUNK_SIZE(0x01),

        /**
         * Protocol control message 2
         * Abort Message, is used to notify the peer if it is waiting for chunks
         * to complete a message, then to discard the partially received message
         * over a chunk stream and abort processing of that message.
         */
        ABORT(0x02),

        /**
         * Protocol control message 3
         * The client or the server sends the acknowledgment to the peer after
         * receiving bytes equal to the window size. The window size is the
         * maximum number of bytes that the sender sends without receiving
         * acknowledgment from the receiver.
         */
        ACKNOWLEDGEMENT(0x03),

        /**
         * Protocol control message 4
         * The client or the server sends this message to notify the peer about
         * the user control events. This message carries Event type and Event
         * data.
         * Also known as a PING message in some RTMP implementations.
         */
        USER_CONTROL_MESSAGE(0x04),

        /**
         * Protocol control message 5
         * The client or the server sends this message to inform the peer which
         * window size to use when sending acknowledgment.
         * Also known as ServerBW ("server bandwidth") in some RTMP implementations.
         */
        WINDOW_ACKNOWLEDGEMENT_SIZE(0x05),

        /**
         * Protocol control message 6
         * The client or the server sends this message to update the output
         * bandwidth of the peer. The output bandwidth value is the same as the
         * window size for the peer.
         * Also known as ClientBW ("client bandwidth") in some RTMP implementations.
         */
        SET_PEER_BANDWIDTH(0x06),

        /**
         * RTMP audio packet (0x08)
         * The client or the server sends this message to send audio data to the peer.
         */
        AUDIO(0x08),

        /**
         * RTMP video packet (0x09)
         * The client or the server sends this message to send video data to the peer.
         */
        VIDEO(0x09),

        /**
         * RTMP message type 0x0F
         * The client or the server sends this message to send Metadata or any
         * user data to the peer. Metadata includes details about the data (audio, video etc.)
         * like creation time, duration, theme and so on.
         * This is the AMF3-encoded version.
         */
        DATA_AMF3(0x0F),

        /**
         * RTMP message type 0x10
         * A shared object is a Flash object (a collection of name value pairs)
         * that are in synchronization across multiple clients, instances, and
         * so on.
         * This is the AMF3 version: kMsgContainerEx=16 for AMF3.
         */
        SHARED_OBJECT_AMF3(0x10),

        /**
         * RTMP message type 0x11
         * Command messages carry the AMF-encoded commands between the client
         * and the server.
         * A command message consists of command name, transaction ID, and command object that
         * contains related parameters.
         * This is the AMF3-encoded version.
         */
        COMMAND_AMF3(0x11),

        /**
         * RTMP message type 0x12
         * The client or the server sends this message to send Metadata or any
         * user data to the peer. Metadata includes details about the data (audio, video etc.)
         * like creation time, duration, theme and so on.
         * This is the AMF0-encoded version.
         */
        DATA_AMF0(0x12),

        /**
         * RTMP message type 0x14
         * Command messages carry the AMF-encoded commands between the client
         * and the server.
         * A command message consists of command name, transaction ID, and command object that
         * contains related parameters.
         * This is the common AMF0 version, also known as INVOKE in some RTMP implementations.
         */
        COMMAND_AMF0(0x14),

        /**
         * RTMP message type 0x13
         * A shared object is a Flash object (a collection of name value pairs)
         * that are in synchronization across multiple clients, instances, and
         * so on.
         * This is the AMF0 version: kMsgContainer=19 for AMF0.
         */
        SHARED_OBJECT_AMF0(0x13),

        /**
         * RTMP message type 0x16
         * An aggregate message is a single message that contains a list of sub-messages.
         */
        AGGREGATE_MESSAGE(0x16);

        /** Returns the value of this chunk type  */
        val value: Byte = value.toByte()

        companion object {
            private val quickLookupMap: MutableMap<Byte, MessageType> = HashMap()
            fun valueOf(messageTypeId: Byte): MessageType? {
                return if (quickLookupMap.containsKey(messageTypeId)) {
                    quickLookupMap[messageTypeId]
                } else {
                    throw IllegalArgumentException(
                            "Unknown message type byte: " + toHexString(messageTypeId))
                }
            }

            init {
                for (messageTypId in values()) {
                    quickLookupMap[messageTypId.value] = messageTypId
                }
            }
        }

    }

    enum class ChunkType(byteValue: Int) {
        /** Full 12-byte RTMP chunk header  */
        TYPE_0_FULL(0x00),

        /** Relative 8-byte RTMP chunk header (message stream ID is not included)  */
        TYPE_1_RELATIVE_LARGE(0x01),

        /** Relative 4-byte RTMP chunk header (only timestamp delta)  */
        TYPE_2_RELATIVE_TIMESTAMP_ONLY(0x02),

        /** Relative 1-byte RTMP chunk header (no "real" header, just the 1-byte indicating chunk header type & chunk stream ID)  */
        TYPE_3_RELATIVE_SINGLE_BYTE(0x03);

        /** Returns the byte value of this chunk header type  */
        /** The byte value of this chunk header type  */
        val value: Byte = byteValue.toByte()

        companion object {
            /** The full size (in bytes) of this RTMP header (including the basic header byte)  */
            private val quickLookupMap: MutableMap<Byte, ChunkType> = HashMap()
            fun valueOf(chunkHeaderType: Byte): ChunkType {
                quickLookupMap[chunkHeaderType]?.let {
                    return it
                }
                        ?: throw IllegalArgumentException("Unknown chunk header type byte: " + toHexString(chunkHeaderType))
            }

            init {
                for (messageTypId in values()) {
                    quickLookupMap[messageTypId.value] = messageTypId
                }
            }
        }

    }

    var chunkType: ChunkType? = null
    /** @return the RTMP chunk stream ID (channel ID) for this chunk
     */
    /** Sets the RTMP chunk stream ID (channel ID) for this chunk  */
    var chunkStreamId = 0
    var absoluteTimestamp = 0
    var timestampDelta = -1
    var packetLength = 0
    lateinit var messageType: MessageType
    var messageStreamId = 0
    private var extendedTimestamp = 0

    constructor() {}
    constructor(chunkType: ChunkType, chunkStreamId: Int, messageType: MessageType) {
        this.chunkType = chunkType
        this.chunkStreamId = chunkStreamId
        this.messageType = messageType
    }

    @Throws(IOException::class)
    private fun readHeaderImpl(input: InputStream, rtmpSessionInfo: RtmpSessionInfo) {
        val basicHeaderByte = input.read()
        if (basicHeaderByte == -1) {
            throw EOFException("Unexpected EOF while reading RTMP packet basic header")
        }
        // Read byte 0: chunk type and chunk stream ID
        parseBasicHeader(basicHeaderByte.toByte())
        when (chunkType) {
            ChunkType.TYPE_0_FULL -> {
                //  b00 = 12 byte header (full header)
                // Read bytes 1-3: Absolute timestamp
                absoluteTimestamp = readUnsignedInt24(input)
                timestampDelta = 0
                // Read bytes 4-6: Packet length
                packetLength = readUnsignedInt24(input)
                // Read byte 7: Message type ID
                val type = input.read().toByte()
                messageType = MessageType.valueOf(type)
                        ?: throw IllegalArgumentException("No message type found with value $type")
                // Read bytes 8-11: Message stream ID (apparently little-endian order)
                val messageStreamIdBytes = ByteArray(4)
                readBytesUntilFull(input, messageStreamIdBytes)
                messageStreamId = toUnsignedInt32LittleEndian(messageStreamIdBytes)
                // Read bytes 1-4: Extended timestamp
                extendedTimestamp = if (absoluteTimestamp >= 0xffffff) readUnsignedInt32(input) else 0
                if (extendedTimestamp != 0) {
                    absoluteTimestamp = extendedTimestamp
                }
            }
            ChunkType.TYPE_1_RELATIVE_LARGE -> {
                // b01 = 8 bytes - like type 0. not including message stream ID (4 last bytes)
                // Read bytes 1-3: Timestamp delta
                timestampDelta = readUnsignedInt24(input)
                // Read bytes 4-6: Packet length
                packetLength = readUnsignedInt24(input)
                // Read byte 7: Message type ID
                val type = input.read().toByte()
                messageType = MessageType.valueOf(type)
                        ?: throw IllegalArgumentException("No message type found with value $type")
                // Read bytes 1-4: Extended timestamp delta
                extendedTimestamp = if (timestampDelta >= 0xffffff) readUnsignedInt32(input) else 0
                val prevHeader: RtmpHeader? = rtmpSessionInfo.getChunkStreamInfo(chunkStreamId).prevHeaderRx()
                if (prevHeader != null) {
                    messageStreamId = prevHeader.messageStreamId
                    absoluteTimestamp = if (extendedTimestamp != 0) extendedTimestamp else prevHeader.absoluteTimestamp + timestampDelta
                } else {
                    messageStreamId = 0
                    absoluteTimestamp = if (extendedTimestamp != 0) extendedTimestamp else timestampDelta
                }
            }
            ChunkType.TYPE_2_RELATIVE_TIMESTAMP_ONLY -> {
                // b10 = 4 bytes - Basic Header and timestamp (3 bytes) are included
                // Read bytes 1-3: Timestamp delta
                timestampDelta = readUnsignedInt24(input)
                // Read bytes 1-4: Extended timestamp delta
                extendedTimestamp = if (timestampDelta >= 0xffffff) readUnsignedInt32(input) else 0
                rtmpSessionInfo.getChunkStreamInfo(chunkStreamId).prevHeaderRx()?.let {
                    packetLength = it.packetLength
                    messageType = it.messageType
                    messageStreamId = it.messageStreamId
                    absoluteTimestamp = if (extendedTimestamp != 0) extendedTimestamp else it.absoluteTimestamp + timestampDelta
                }
            }
            ChunkType.TYPE_3_RELATIVE_SINGLE_BYTE -> {
                // b11 = 1 byte: basic header only
                rtmpSessionInfo.getChunkStreamInfo(chunkStreamId).prevHeaderRx()?.let {
                    // Read bytes 1-4: Extended timestamp
                    extendedTimestamp = if (it.timestampDelta >= 0xffffff) readUnsignedInt32(input) else 0
                    timestampDelta = if (extendedTimestamp != 0) 0xffffff else it.timestampDelta
                    packetLength = it.packetLength
                    messageType = it.messageType
                    messageStreamId = it.messageStreamId
                    absoluteTimestamp = if (extendedTimestamp != 0) extendedTimestamp else it.absoluteTimestamp + timestampDelta
                }
            }
            else -> throw IOException("Invalid chunk type; basic header byte was: " + toHexString(
                    basicHeaderByte.toByte()))
        }
    }

    @Throws(IOException::class)
    fun writeTo(out: OutputStream, chunkType: ChunkType, chunkStreamInfo: ChunkStreamInfo) {
        // Write basic header byte
        out.write((chunkType.value.toInt() shl 6) or chunkStreamId)
        when (chunkType) {
            ChunkType.TYPE_0_FULL -> {
                //  b00 = 12 byte header (full header)
                chunkStreamInfo.markDeltaTimestampTx()
                writeUnsignedInt24(out,
                        if (absoluteTimestamp >= 0xffffff) 0xffffff else absoluteTimestamp)
                writeUnsignedInt24(out, packetLength)
                out.write(messageType!!.value.toInt())
                writeUnsignedInt32LittleEndian(out, messageStreamId)
                if (absoluteTimestamp >= 0xffffff) {
                    extendedTimestamp = absoluteTimestamp
                    writeUnsignedInt32(out, extendedTimestamp)
                }
            }
            ChunkType.TYPE_1_RELATIVE_LARGE -> {
                // b01 = 8 bytes - like type 0. not including message ID (4 last bytes)
                timestampDelta = chunkStreamInfo.markDeltaTimestampTx().toInt()
                absoluteTimestamp = chunkStreamInfo.prevHeaderTx?.absoluteTimestamp?.plus(timestampDelta)
                        ?: 0
                writeUnsignedInt24(out, if (absoluteTimestamp >= 0xffffff) 0xffffff else timestampDelta)
                writeUnsignedInt24(out, packetLength)
                out.write(messageType!!.value.toInt())
                if (absoluteTimestamp >= 0xffffff) {
                    extendedTimestamp = absoluteTimestamp
                    writeUnsignedInt32(out, absoluteTimestamp)
                }
            }
            ChunkType.TYPE_2_RELATIVE_TIMESTAMP_ONLY -> {
                // b10 = 4 bytes - Basic Header and timestamp (3 bytes) are included
                timestampDelta = chunkStreamInfo.markDeltaTimestampTx().toInt()
                absoluteTimestamp = chunkStreamInfo.prevHeaderTx?.absoluteTimestamp?.plus(timestampDelta)
                        ?: 0
                writeUnsignedInt24(out, if (absoluteTimestamp >= 0xffffff) 0xffffff else timestampDelta)
                if (absoluteTimestamp >= 0xffffff) {
                    extendedTimestamp = absoluteTimestamp
                    writeUnsignedInt32(out, extendedTimestamp)
                }
            }
            ChunkType.TYPE_3_RELATIVE_SINGLE_BYTE -> {
                // b11 = 1 byte: basic header only
                if (extendedTimestamp > 0) {
                    writeUnsignedInt32(out, extendedTimestamp)
                }
            }
            else -> throw IOException("Invalid chunk type: $chunkType")
        }
    }

    private fun parseBasicHeader(basicHeaderByte: Byte) {
        chunkType = ChunkType.valueOf((0xff and basicHeaderByte.toInt() ushr 6).toByte()) // 2 most significant bits define the chunk type
        chunkStreamId = basicHeaderByte.toInt() and 0x3F // 6 least significant bits define chunk stream ID
    }

    companion object {
        private const val TAG = "RtmpHeader"

        @Throws(IOException::class)
        fun readHeader(input: InputStream, rtmpSessionInfo: RtmpSessionInfo): RtmpHeader {
            val rtmpHeader = RtmpHeader()
            rtmpHeader.readHeaderImpl(input, rtmpSessionInfo)
            return rtmpHeader
        }
    }
}