package com.example.simplertmp

/**
 * Simple RTMP publisher, using vanilla Java networking (no NIO)
 * This was created primarily to address a NIO bug in Android 2.2 when
 * used with Apache Mina, but also to provide an easy-to-use way to access
 * RTMP streams
 *
 * @author francois, leo
 */
interface RtmpPublisher {
    /**
     * Issues an RTMP "connect" command and wait for the response.
     *
     * @param url specify the RTMP url
     * @return If succeeded return true else return false
     */
    fun connect(url: String): Boolean

    /**
     * Issues an RTMP "publish" command and write the media content stream packets (audio and video).
     *
     * @param publishType specify the way to publish raw RTMP packets among "live", "record" and
     * "append"
     * @return If succeeded return true else return false
     * @throws IllegalStateException if the client is not connected to a RTMP server
     */
    fun publish(publishType: String): Boolean

    /**
     * Stop and close the current RTMP streaming client.
     */
    fun close()

    /**
     * publish a video content packet to server
     *
     * @param data video stream byte array
     * @param size video stream byte size (not the whole length of byte array)
     * @param dts video stream decoding timestamp
     */
    fun publishVideoData(data: ByteArray, size: Int, dts: Int)

    /**
     * publish an audio content packet to server
     *
     * @param data audio stream byte array
     * @param size audio stream byte size (not the whole length of byte array)
     * @param dts audio stream decoding timestamp
     */
    fun publishAudioData(data: ByteArray, size: Int, dts: Int)

    /**
     * set video resolution
     *
     * @param width video width
     * @param height video height
     */
    fun setVideoResolution(width: Int, height: Int)
    fun setAuthorization(user: String, password: String)
}