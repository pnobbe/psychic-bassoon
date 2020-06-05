package com.example.simplertmp

import com.example.simplertmp.io.ConnectCheckerRtmp
import com.example.simplertmp.io.RtmpConnection

/**
 * Srs implementation of an RTMP publisher
 *
 * @author francois, leoma, pedro
 */
class DefaultRtmpPublisher(connectCheckerRtmp: ConnectCheckerRtmp) : RtmpPublisher {
    private val rtmpConnection: RtmpConnection = RtmpConnection(connectCheckerRtmp)
    override fun connect(url: String): Boolean {
        return rtmpConnection.connect(url)
    }

    override fun publish(publishType: String): Boolean {
        return rtmpConnection.publish(publishType)
    }

    override fun close() {
        rtmpConnection.close()
    }

    override fun publishVideoData(data: ByteArray, size: Int, dts: Int) {
        rtmpConnection.publishVideoData(data, size, dts)
    }

    override fun publishAudioData(data: ByteArray, size: Int, dts: Int) {
        rtmpConnection.publishAudioData(data, size, dts)
    }

    override fun setVideoResolution(width: Int, height: Int) {
        rtmpConnection.setVideoResolution(width, height)
    }

    override fun setAuthorization(user: String, password: String) {
        rtmpConnection.setAuthorization(user, password)
    }

}