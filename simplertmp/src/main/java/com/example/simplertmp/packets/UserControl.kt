package com.example.simplertmp.packets

import com.example.simplertmp.Util
import com.example.simplertmp.io.ChunkStreamInfo
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream


/**
 * User Control message, such as ping
 *
 * @author francois
 */
class UserControl : RtmpPacket {
    override var array: ByteArray? = null
    override var size = 0

    /**
     * Control message type
     * Docstring adapted from the official Adobe RTMP spec, section 3.7
     */
    enum class Type(val intValue: Int) {
        /**
         * Type: 0
         * The server sends this event to notify the client that a stream has become
         * functional and can be used for communication. By default, this event
         * is sent on ID 0 after the application connect command is successfully
         * received from the client.
         *
         * Event Data:
         * eventData[0] (int) the stream ID of the stream that became functional
         */
        STREAM_BEGIN(0),

        /**
         * Type: 1
         * The server sends this event to notify the client that the playback of
         * data is over as requested on this stream. No more data is sent without
         * issuing additional commands. The client discards the messages received
         * for the stream.
         *
         * Event Data:
         * eventData[0]: the ID of thestream on which playback has ended.
         */
        STREAM_EOF(1),

        /**
         * Type: 2
         * The server sends this event to notify the client that there is no
         * more data on the stream. If the server does not detect any message for
         * a time period, it can notify the subscribed clients that the stream is
         * dry.
         *
         * Event Data:
         * eventData[0]: the stream ID of the dry stream.
         */
        STREAM_DRY(2),

        /**
         * Type: 3
         * The client sends this event to inform the server of the buffer size
         * (in milliseconds) that is used to buffer any data coming over a stream.
         * This event is sent before the server starts  processing the stream.
         *
         * Event Data:
         * eventData[0]: the stream ID and
         * eventData[1]: the buffer length, in milliseconds.
         */
        SET_BUFFER_LENGTH(3),

        /**
         * Type: 4
         * The server sends this event to notify the client that the stream is a
         * recorded stream.
         *
         * Event Data:
         * eventData[0]: the stream ID of the recorded stream.
         */
        STREAM_IS_RECORDED(4),

        /**
         * Type: 6
         * The server sends this event to test whether the client is reachable.
         *
         * Event Data:
         * eventData[0]: a timestamp representing the local server time when the server dispatched the command.
         *
         * The client responds with PING_RESPONSE on receiving PING_REQUEST.
         */
        PING_REQUEST(6),

        /**
         * Type: 7
         * The client sends this event to the server in response to the ping request.
         *
         * Event Data:
         * eventData[0]: the 4-byte timestamp which was received with the PING_REQUEST.
         */
        PONG_REPLY(7),

        /**
         * Type: 31 (0x1F)
         *
         * This user control type is not specified in any official documentation, but
         * is sent by Flash Media Server 3.5. Thanks to the rtmpdump devs for their
         * explanation:
         *
         * Buffer Empty (unofficial name): After the server has sent a complete buffer, and
         * sends this Buffer Empty message, it will wait until the play
         * duration of that buffer has passed before sending a new buffer.
         * The Buffer Ready message will be sent when the new buffer starts.
         *
         * (see also: http://repo.or.cz/w/rtmpdump.git/blob/8880d1456b282ee79979adbe7b6a6eb8ad371081:/librtmp/rtmp.c#l2787)
         */
        BUFFER_EMPTY(31),

        /**
         * Type: 32 (0x20)
         *
         * This user control type is not specified in any official documentation, but
         * is sent by Flash Media Server 3.5. Thanks to the rtmpdump devs for their
         * explanation:
         *
         * Buffer Ready (unofficial name): After the server has sent a complete buffer, and
         * sends a Buffer Empty message, it will wait until the play
         * duration of that buffer has passed before sending a new buffer.
         * The Buffer Ready message will be sent when the new buffer starts.
         * (There is no BufferReady message for the very first buffer;
         * presumably the Stream Begin message is sufficient for that
         * purpose.)
         *
         * (see also: http://repo.or.cz/w/rtmpdump.git/blob/8880d1456b282ee79979adbe7b6a6eb8ad371081:/librtmp/rtmp.c#l2787)
         */
        BUFFER_READY(32);

    }

    lateinit var type: Type
    lateinit var eventData: IntArray
        private set

    constructor(header: RtmpHeader?) : super(header!!) {}
    constructor(channelInfo: ChunkStreamInfo) : super(RtmpHeader(
            if (channelInfo.canReusePrevHeaderTx(RtmpHeader.MessageType.USER_CONTROL_MESSAGE)) RtmpHeader.ChunkType.TYPE_2_RELATIVE_TIMESTAMP_ONLY else RtmpHeader.ChunkType.TYPE_0_FULL, ChunkStreamInfo.RTMP_CID_PROTOCOL_CONTROL.toInt(),
            RtmpHeader.MessageType.USER_CONTROL_MESSAGE)) {
    }

    /** Convenience construtor that creates a "pong" message for the specified ping  */
    constructor(replyToPing: UserControl, channelInfo: ChunkStreamInfo) : this(Type.PONG_REPLY, channelInfo) {
        eventData = replyToPing.eventData
    }

    constructor(type: Type, channelInfo: ChunkStreamInfo) : this(channelInfo) {
        this.type = type
    }

    /**
     * Convenience method for getting the first event data item, as most user control
     * message types only have one event data item anyway
     * This is equivalent to calling `getEventData()[0]`
     */
    val firstEventData: Int
        get() = eventData[0]

    /** Used to set (a single) event data for most user control message types  */
    fun setEventData(eventData: Int) {
        check(type != Type.SET_BUFFER_LENGTH) { "SET_BUFFER_LENGTH requires two event data values; use setEventData(int, int) instead" }
        this.eventData = intArrayOf(eventData)
    }

    /** Used to set event data for the SET_BUFFER_LENGTH user control message types  */
    fun setEventData(streamId: Int, bufferLength: Int) {
        check(type == Type.SET_BUFFER_LENGTH) {
            ("User control type "
                    + type
                    + " requires only one event data value; use setEventData(int) instead")
        }
        eventData = intArrayOf(streamId, bufferLength)
    }

    @Throws(IOException::class)
    override fun readBody(input: InputStream) {
        // Bytes 0-1: first parameter: ping type (mandatory)
        type = Type.values().first { it.intValue == Util.readUnsignedInt16(input) }
        var bytesRead = 2
        // Event data (1 for most types, 2 for SET_BUFFER_LENGTH)
        bytesRead += if (type == Type.SET_BUFFER_LENGTH) {
            setEventData(Util.readUnsignedInt32(input), Util.readUnsignedInt32(input))
            8
        } else {
            setEventData(Util.readUnsignedInt32(input))
            4
        }
        assert(header.packetLength == bytesRead)
    }

    @Throws(IOException::class)
    override fun writeBody(output: OutputStream) {
        // Write the user control message type
        Util.writeUnsignedInt16(output, type.intValue)
        // Now write the event data
        Util.writeUnsignedInt32(output, eventData[0])
        if (type == Type.SET_BUFFER_LENGTH) {
            Util.writeUnsignedInt32(output, eventData[1])
        }
    }

    override fun toString(): String {
        return "RTMP User Control (type: $type, event data: $eventData)"
    }
}