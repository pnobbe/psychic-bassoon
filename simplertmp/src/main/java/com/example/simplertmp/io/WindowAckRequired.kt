package com.example.simplertmp.io

import com.example.simplertmp.packets.RtmpPacket


/**
 * Thrown by RTMP read thread when an Acknowledgement packet needs to be sent
 * to acknowledge the RTMP window size. It contains the RTMP packet that was
 * read when this event occurred (if any).
 *
 * @author francois
 */
class WindowAckRequired
/**
 * Used when the window acknowledgement size was reached, whilst fully reading
 * an RTMP packet or not. If a packet is present, it should still be handled as if it was returned
 * by the RTMP decoder.
 *
 * @param bytesReadThusFar The (total) number of bytes received so far
 * @param rtmpPacket The packet that was read (and thus should be handled), can be `null`
 */(val bytesRead: Int,
    /**
     * @return The RTMP packet that should be handled, or `null` if no full packet is available
     */
    val rtmpPacket: RtmpPacket) : Exception()