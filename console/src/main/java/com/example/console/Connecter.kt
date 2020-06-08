package com.example.console

import com.example.simplertmp.io.ConnectCheckerRtmp

class Connecter: ConnectCheckerRtmp {


    override fun onConnectionSuccessRtmp() {
    }

    override fun onConnectionFailedRtmp(reason: String) {
        println(reason)
    }

    override fun onNewBitrateRtmp(bitrate: Long) {
    }

    override fun onDisconnectRtmp() {
    }

    override fun onAuthErrorRtmp() {
    }

    override fun onAuthSuccessRtmp() {
    }
}
