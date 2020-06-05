package com.example.simplertmp.io

/**
 * Calculate video and audio bitrate per second
 */
class BitrateManager(connectCheckerRtsp: ConnectCheckerRtmp) {
    private var bitrate: Long = 0
    private var timeStamp = System.currentTimeMillis()
    private val connectCheckerRtmp: ConnectCheckerRtmp = connectCheckerRtsp

    @Synchronized
    fun calculateBitrate(size: Long) {
        bitrate += size
        val timeDiff = System.currentTimeMillis() - timeStamp
        if (timeDiff >= 1000) {
            connectCheckerRtmp.onNewBitrateRtmp((bitrate / (timeDiff / 1000f)).toLong())
            timeStamp = System.currentTimeMillis()
            bitrate = 0
        }
    }

}