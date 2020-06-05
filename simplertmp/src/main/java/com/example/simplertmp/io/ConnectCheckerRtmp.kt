package com.example.simplertmp.io

interface ConnectCheckerRtmp {
    fun onConnectionSuccessRtmp()
    fun onConnectionFailedRtmp(reason: String)
    fun onNewBitrateRtmp(bitrate: Long)
    fun onDisconnectRtmp()
    fun onAuthErrorRtmp()
    fun onAuthSuccessRtmp()
}