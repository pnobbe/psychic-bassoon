package com.example.simplertmp.io

import com.example.simplertmp.RtmpPublisher
import com.example.simplertmp.Util.getChallenge
import com.example.simplertmp.Util.getOpaque
import com.example.simplertmp.Util.getSalt
import com.example.simplertmp.Util.stringToMD5BASE64
import com.example.simplertmp.amf.*
import com.example.simplertmp.packets.*
import java.io.*
import java.net.SocketException
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Level
import java.util.logging.Logger
import java.util.regex.Pattern
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.withLock


/**
 * Main RTMP connection implementation class
 *
 * @author francois, leoma, pedro
 */
class RtmpConnection(private val connectCheckerRtmp: ConnectCheckerRtmp) : RtmpPublisher {
    private var port = 0
    private lateinit var host: String
    private var appName: String? = null
    private var streamName: String? = null
    private var publishType: String? = null
    private var swfUrl: String? = null
    private var tcUrl: String? = null
    private var pageUrl: String? = null
    private var socket: SSLSocket? = null
    private var rtmpSessionInfo: RtmpSessionInfo? = null
    private var rtmpDecoder: RtmpDecoder? = null
    private lateinit var inputStream: BufferedInputStream
    private lateinit var outputStream: BufferedOutputStream
    private var rxPacketHandler: Thread? = null

    @Volatile
    private var connected = false

    @Volatile
    private var publishPermitted = false
    private val connectingLock = ReentrantLock()
    private val connectingLockCondition = connectingLock.newCondition()
    private val publishLock = ReentrantLock()
    private val publishLockCondition = publishLock.newCondition()
    private var currentStreamId = 0
    private var transactionIdCounter = 0
    private var videoWidth = 0
    private var videoHeight = 0

    //for secure transport
    private var tlsEnabled = false

    //for auth
    private var user: String? = null
    private var password: String? = null
    private var salt: String? = null
    private var challenge: String? = null
    private var opaque: String? = null
    private var onAuth = false
    private var netConnectionDescription: String? = null
    private val bitrateManager: BitrateManager = BitrateManager(connectCheckerRtmp)

    @Throws(IOException::class)
    private fun handshake(input: InputStream, output: OutputStream) {
        val handshake = Handshake()
        handshake.writeC0(output)
        handshake.writeC1(output) // Write C1 without waiting for S0
        output.flush()
        handshake.readS0(input)
        handshake.readS1(input)
        handshake.writeC2(output)
        output.flush()
        handshake.readS2(input)
    }

    override fun connect(url: String): Boolean {
        val rtmpMatcher = rtmpUrlPattern.matcher("rtmps://6079e764c259451f852f3c0351f4b584.channel.media.azure.net:2935/live/6ed11a02141b40788e293b4dad1f397c")
        tlsEnabled = if (rtmpMatcher.matches()) {
            rtmpMatcher.group(0).startsWith("rtmps")
        } else {
            connectCheckerRtmp.onConnectionFailedRtmp("Endpoint malformed, should be: rtmp://{ip}:{port}/{appname}/{streamname}")
            return false
        }
        swfUrl = ""
        pageUrl = ""
        host = rtmpMatcher.group(1)
        val portStr = rtmpMatcher.group(2)
        port = portStr?.toInt() ?: 1935
        appName = rtmpMatcher.group(3)
        streamName = rtmpMatcher.group(4)
        tcUrl = rtmpMatcher.group(0).substring(0, rtmpMatcher.group(0).length - streamName!!.length)

        // socket connection
        Logger.getLogger(TAG).log(Level.INFO, "connect() called. Host: $host, port: $port, appName: $appName, publishPath: $streamName")

        rtmpSessionInfo = RtmpSessionInfo().also {
            rtmpDecoder = RtmpDecoder(it)
        }
        try {
            socket = (SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket).apply {
                this.enabledProtocols = this.supporgittedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            }.also {
                inputStream = BufferedInputStream(it.inputStream)
                outputStream = BufferedOutputStream(it.outputStream)
                Logger.getLogger(TAG).log(Level.INFO, "connect(): socket connection established, doing handshake...")
                handshake(inputStream, outputStream)
                Logger.getLogger(TAG).log(Level.INFO, "connect(): handshake complete")
            }


        } catch (e: IOException) {
            Logger.getLogger(TAG).log(Level.SEVERE, e.message)
            connectCheckerRtmp.onConnectionFailedRtmp("Connect error, " + e.message)
            return false
        } catch (e: Exception) {
            Logger.getLogger(TAG).log(Level.SEVERE, e.message)
        }

        // Start the "main" handling thread
        rxPacketHandler = Thread(Runnable {
            Logger.getLogger(TAG).log(Level.INFO, "starting main rx handler loop")
            handleRxPacketLoop()
        }).also {
            it.start()
        }
        return rtmpConnect()
    }

    private fun rtmpConnect(): Boolean {
        if (connected) {
            connectCheckerRtmp.onConnectionFailedRtmp("Already connected")
            return false
        }
        if (user != null && password != null) {
            sendConnect("?authmod=adobe&user=$user")
        } else {
            sendConnect("")
        }
        connectingLock.withLock {
            try {
                connectingLockCondition.await(5, TimeUnit.SECONDS)
            } catch (ex: InterruptedException) {
                // do nothing
            }
        }
        if (!connected) {
            shutdown(true)
            connectCheckerRtmp.onConnectionFailedRtmp("Fail to connect, time out")
        }
        return connected
    }

    private fun sendConnect(user: String) {
        ChunkStreamInfo.markSessionTimestampTx()
        Logger.getLogger(TAG).log(Level.INFO, "rtmpConnect(): Building 'connect' invoke packet")
        val chunkStreamInfo: ChunkStreamInfo = rtmpSessionInfo!!.getChunkStreamInfo(ChunkStreamInfo.RTMP_CID_OVER_STREAM.toInt())
        val invoke = Command("connect", ++transactionIdCounter, chunkStreamInfo)
        assert(transactionIdCounter == 1)
        invoke.header.messageStreamId = 0
        val args = AmfObject()
        args.setProperty("app", appName + user)
        args.setProperty("flashVer", "FMLE/3.0 (compatible; Lavf58.29.100)")
        args.setProperty("type", "nonprivate")
//        args.setProperty("swfUrl", swfUrl!!)
        args.setProperty("tcUrl", tcUrl + user)
//        args.setProperty("fpad", false)
//        args.setProperty("capabilities", 239)
//        args.setProperty("audioCodecs", 3191)
//        args.setProperty("videoCodecs", 252)
//        args.setProperty("videoFunction", 1)
//        args.setProperty("pageUrl", pageUrl!!)
//        args.setProperty("objectEncoding", 0)
        invoke.addData(args)
        sendRtmpPacket(invoke)
    }

    private fun getAuthUserResult(user: String, password: String, salt: String?,
                                  challenge: String?, opaque: String): String {
        val challenge2 = String.format("%08x", Random().nextInt())
        var response = stringToMD5BASE64(user + salt + password)
        if (!opaque.isEmpty()) {
            response += opaque
        } else if (!challenge!!.isEmpty()) {
            response += challenge
        }
        response = stringToMD5BASE64(response + challenge2)
        var result = "?authmod=adobe&user=$user&challenge=$challenge2&response=$response"
        if (!opaque.isEmpty()) {
            result += "&opaque=$opaque"
        }
        return result
    }

    override fun publish(publishType: String): Boolean {
        this.publishType = publishType
        return createStream()
    }

    private fun createStream(): Boolean {
        if (!connected || currentStreamId != 0) {
            connectCheckerRtmp.onConnectionFailedRtmp(
                    "Create stream failed, connected= $connected, StreamId= $currentStreamId")
            return false
        }
        netConnectionDescription = null
        Logger.getLogger(TAG).log(Level.INFO, "createStream(): Sending releaseStream command...")
        // transactionId == 2
        val releaseStream = Command("releaseStream", ++transactionIdCounter)
        releaseStream.header.chunkStreamId = ChunkStreamInfo.RTMP_CID_OVER_STREAM.toInt()
        releaseStream.addData(AmfNull()) // command object: null for "createStream"
        releaseStream.addData(streamName!!) // command object: null for "releaseStream"
        sendRtmpPacket(releaseStream)

        Logger.getLogger(TAG).log(Level.INFO, "createStream(): Sending FCPublish command...")
        // transactionId == 3
        val FCPublish = Command("FCPublish", ++transactionIdCounter)
        FCPublish.header.chunkStreamId = ChunkStreamInfo.RTMP_CID_OVER_STREAM.toInt()
        FCPublish.addData(AmfNull()) // command object: null for "FCPublish"
        FCPublish.addData(streamName!!)
        sendRtmpPacket(FCPublish)

        Logger.getLogger(TAG).log(Level.INFO, "createStream(): Sending createStream command...")
        val chunkStreamInfo: ChunkStreamInfo = rtmpSessionInfo!!.getChunkStreamInfo(ChunkStreamInfo.RTMP_CID_OVER_CONNECTION.toInt())
        // transactionId == 4
        val createStream = Command("createStream", ++transactionIdCounter, chunkStreamInfo)
        createStream.addData(AmfNull()) // command object: null for "createStream"
        sendRtmpPacket(createStream)

        // Waiting for "NetStream.Publish.Start" response.
        publishLock.withLock {
            try {
                publishLockCondition.await(5, TimeUnit.SECONDS)
            } catch (ex: InterruptedException) {
                // do nothing
            }
        }
        if (!publishPermitted) {
            shutdown(true)
            netConnectionDescription?.let {
                connectCheckerRtmp.onConnectionFailedRtmp(it)
            } ?: run {
                connectCheckerRtmp.onConnectionFailedRtmp("Error configure stream, publish permitted failed")
            }
        }
        return publishPermitted
    }

    private fun fmlePublish() {
        if (!connected || currentStreamId == 0) {
            Logger.getLogger(TAG).log(Level.INFO, "fmlePublish(): failed")
            return
        }

        Logger.getLogger(TAG).log(Level.INFO, "fmlePublish(): Sending publish command...")
        val publish = Command("publish", 0)
        publish.header.chunkStreamId = ChunkStreamInfo.RTMP_CID_OVER_STREAM.toInt()
        publish.header.messageStreamId = currentStreamId
        publish.addData(AmfNull()) // command object: null for "publish"
        publish.addData("default")
        publish.addData(publishType!!)
        sendRtmpPacket(publish)
    }

    private fun onMetaData() {
        if (!connected || currentStreamId == 0) {
            Logger.getLogger(TAG).log(Level.INFO, "onMetaData(): failed")
            return
        }
        Logger.getLogger(TAG).log(Level.INFO, "onMetaData(): Sending empty onMetaData...")
        val metadata = Data("@setDataFrame")
        metadata.header.messageStreamId = currentStreamId
        metadata.addData("onMetaData")
        val ecmaArray = AmfMap()
        ecmaArray.setProperty("duration", 0)
        ecmaArray.setProperty("width", videoWidth)
        ecmaArray.setProperty("height", videoHeight)
        ecmaArray.setProperty("videocodecid", 7)
        ecmaArray.setProperty("framerate", 30)
        ecmaArray.setProperty("videodatarate", 0)
        // @see FLV video_file_format_spec_v10_1.pdf
        // According to E.4.2.1 AUDIODATA
        // "If the SoundFormat indicates AAC, the SoundType should be 1 (stereo) and the SoundRate should be 3 (44 kHz).
        // However, this does not mean that AAC audio in FLV is always stereo, 44 kHz data. Instead, the Flash Player ignores
        // these values and extracts the channel and sample rate data is encoded in the AAC bit stream."
        ecmaArray.setProperty("audiocodecid", 10)
        ecmaArray.setProperty("audiosamplerate", 44100)
        ecmaArray.setProperty("audiosamplesize", 16)
        ecmaArray.setProperty("audiodatarate", 0)
        ecmaArray.setProperty("stereo", true)
        ecmaArray.setProperty("filesize", 0)
        metadata.addData(ecmaArray)
        sendRtmpPacket(metadata)
    }

    override fun close() {
        if (socket != null) {
            closeStream()
        }
        shutdown(true)
    }

    private fun closeStream() {
        if (!connected || currentStreamId == 0 || !publishPermitted) {
            Logger.getLogger(TAG).log(Level.INFO, "closeStream(): failed")
            return
        }

        Logger.getLogger(TAG).log(Level.INFO, "closeStream(): Setting current stream ID to 0...")

        val closeStream = Command("closeStream", 0)
        closeStream.header.chunkStreamId = ChunkStreamInfo.RTMP_CID_OVER_STREAM.toInt()
        closeStream.header.messageStreamId = currentStreamId
        closeStream.addData(AmfNull())
        sendRtmpPacket(closeStream)
    }

    @Synchronized
    private fun shutdown(reset: Boolean) {
        try {
            // It will raise EOFException in handleRxPacketThread
            socket?.shutdownInput()
            // It will raise SocketException in sendRtmpPacket
            socket?.shutdownOutput()
        } catch (e: IOException) {
            Logger.getLogger(TAG).log(Level.SEVERE, "Shutdown socket $e")
        } catch (e: UnsupportedOperationException) {
            Logger.getLogger(TAG).log(Level.SEVERE, "Shutdown socket $e")
        }


        // shutdown rxPacketHandler
        rxPacketHandler?.let {
            it.interrupt()
            try {
                it.join(100)
            } catch (ie: InterruptedException) {
                it.interrupt()
            }
        }
        rxPacketHandler = null

        // shutdown socket as well as its input and output stream
        try {
            socket?.close()
            Logger.getLogger(TAG).log(Level.INFO, "Socket closed")
        } catch (ex: IOException) {
            Logger.getLogger(TAG).log(Level.SEVERE, "shutdown(): failed to close socket: $ex")
        }

        if (reset) {
            reset()
        }
    }

    private fun reset() {
        connected = false
        publishPermitted = false
        netConnectionDescription = null
        tcUrl = null
        swfUrl = null
        pageUrl = null
        appName = null
        streamName = null
        publishType = null
        currentStreamId = 0
        transactionIdCounter = 0
        socket = null
        rtmpSessionInfo = null
        user = null
        password = null
        salt = null
        challenge = null
        opaque = null
    }

    override fun publishAudioData(data: ByteArray, size: Int, dts: Int) {
        if (data.isEmpty() || dts < 0 || !connected || currentStreamId == 0 || !publishPermitted) {
            return
        }
        val audio = Audio()
        audio.setData(data, size)
        audio.header.absoluteTimestamp = dts
        audio.header.messageStreamId = currentStreamId
        sendRtmpPacket(audio)
        //bytes to bits
        bitrateManager.calculateBitrate(size * 8.toLong())
    }

    override fun publishVideoData(data: ByteArray, size: Int, dts: Int) {
        if (data.isEmpty() || dts < 0 || !connected || currentStreamId == 0 || !publishPermitted) {
            return
        }
        val video = Video()
        video.setData(data, size)
        video.header.absoluteTimestamp = dts
        video.header.messageStreamId = currentStreamId
        sendRtmpPacket(video)
        //bytes to bits
        bitrateManager.calculateBitrate(size * 8.toLong())
    }

    private fun sendRtmpPacket(rtmpPacket: RtmpPacket) {
        try {
            val chunkStreamInfo: ChunkStreamInfo? = rtmpSessionInfo?.getChunkStreamInfo(rtmpPacket.header.chunkStreamId)
            chunkStreamInfo?.prevHeaderTx = rtmpPacket.header
            if (!(rtmpPacket is Video || rtmpPacket is Audio)) {
                rtmpPacket.header.absoluteTimestamp = chunkStreamInfo!!.markAbsoluteTimestampTx().toInt()
            }
            rtmpPacket.writeTo(outputStream, rtmpSessionInfo!!.txChunkSize, chunkStreamInfo)
            Logger.getLogger(TAG).log(Level.INFO, "write packet: $rtmpPacket, size: ${rtmpPacket.header.packetLength}")

            if (rtmpPacket is Command) {
                rtmpSessionInfo?.addInvokedCommand(rtmpPacket.transactionId, rtmpPacket.commandName)
            }
            outputStream.flush()
        } catch (ignored: SocketException) {
        } catch (ioe: IOException) {
            connectCheckerRtmp.onConnectionFailedRtmp("Error send packet: " + ioe.message)
            Logger.getLogger(TAG).log(Level.INFO, "Caught IOException during write loop, shutting down: ${ioe.message}")
            Thread.currentThread().interrupt()
        }
    }

    private fun handleRxPacketLoop() {
        // Handle all queued received RTMP packets
        while (!Thread.interrupted()) {
            try {
                // It will be blocked when no data in input stream buffer
                val rtmpPacket: RtmpPacket? = rtmpDecoder?.readPacket(inputStream)
                if (rtmpPacket != null) {
                    //Log.d(TAG, "handleRxPacketLoop(): RTMP rx packet message type: " + rtmpPacket.getHeader().getMessageType());
                    when (rtmpPacket.header.messageType) {
                        RtmpHeader.MessageType.ABORT -> rtmpSessionInfo!!.getChunkStreamInfo((rtmpPacket as Abort).chunkStreamId)
                                .clearStoredChunks()
                        RtmpHeader.MessageType.USER_CONTROL_MESSAGE -> {
                            val user: UserControl = rtmpPacket as UserControl
                            when (user.type) {
                                UserControl.Type.STREAM_BEGIN -> {
                                }
                                UserControl.Type.PING_REQUEST -> {
                                    val channelInfo: ChunkStreamInfo? = rtmpSessionInfo?.getChunkStreamInfo(ChunkStreamInfo.RTMP_CID_PROTOCOL_CONTROL.toInt())
                                    Logger.getLogger(TAG).log(Level.INFO, "handleRxPacketLoop(): Sending PONG reply...")
                                    val pong = UserControl(user, channelInfo!!)
                                    sendRtmpPacket(pong)
                                }
                                UserControl.Type.STREAM_EOF -> Logger.getLogger(TAG).log(Level.INFO, "handleRxPacketLoop(): Stream EOF reached, closing RTMP writer...")
                                else -> {
                                }
                            }
                        }
                        RtmpHeader.MessageType.WINDOW_ACKNOWLEDGEMENT_SIZE -> {
                            val windowAckSize: WindowAckSize = rtmpPacket as WindowAckSize
                            val size: Int = windowAckSize.acknowledgementWindowSize
                            Logger.getLogger(TAG).log(Level.INFO, "handleRxPacketLoop(): Setting acknowledgement window size: $size")
                            rtmpSessionInfo?.setAcknowledgmentWindowSize(size)
                        }
                        RtmpHeader.MessageType.SET_PEER_BANDWIDTH -> {
                            rtmpSessionInfo?.setAcknowledgmentWindowSize(socket!!.sendBufferSize)
                            val acknowledgementWindowsize: Int = rtmpSessionInfo!!.acknowledgementWindowSize
                            val chunkStreamInfo: ChunkStreamInfo = rtmpSessionInfo!!.getChunkStreamInfo(ChunkStreamInfo.RTMP_CID_PROTOCOL_CONTROL.toInt())
                            Logger.getLogger(TAG).log(Level.INFO, "handleRxPacketLoop(): Send acknowledgement window size: $acknowledgementWindowsize")
                            sendRtmpPacket(WindowAckSize(acknowledgementWindowsize, chunkStreamInfo))
                            // Set socket option. This line could produce bps calculation problems.
                            socket!!.sendBufferSize = acknowledgementWindowsize
                        }
                        RtmpHeader.MessageType.COMMAND_AMF0 -> handleRxInvoke(rtmpPacket as Command)
                        else -> Logger.getLogger(TAG).log(Level.INFO, "handleRxPacketLoop(): Not handling unimplemented/unknown packet of type: ${rtmpPacket.header.messageType}")
                    }
                }
            } catch (eof: EOFException) {
                Thread.currentThread().interrupt()
            } catch (e: IOException) {
                connectCheckerRtmp.onConnectionFailedRtmp("Error reading packet: " + e.message)
                Logger.getLogger(TAG).log(Level.SEVERE, "Caught SocketException while reading/decoding packet, shutting down: ${e.message}")
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun handleRxInvoke(invoke: Command) {
        when (invoke.commandName) {
            "_error" -> try {
                val description: String = ((invoke.array!![1] as AmfObject).getProperty(
                        "description") as AmfString).value

                Logger.getLogger(TAG).log(Level.INFO, description)

                if (description.contains("reason=authfailed")) {
                    connectCheckerRtmp.onAuthErrorRtmp()
                    connected = false
                    synchronized(connectingLock) { connectingLockCondition.signalAll() }
                } else if (user != null && password != null && description.contains("challenge=")
                        && description.contains("salt=")) {
                    onAuth = true
                    try {
                        shutdown(false)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    rtmpSessionInfo = RtmpSessionInfo()
                    rtmpDecoder = RtmpDecoder(rtmpSessionInfo!!)

                    socket = SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket

                    inputStream = BufferedInputStream(socket!!.inputStream)
                    outputStream = BufferedOutputStream(socket!!.outputStream)

                    Logger.getLogger(TAG).log(Level.INFO, "connect(): socket connection established, doing handshake...")
                    salt = getSalt(description)
                    challenge = getChallenge(description)
                    opaque = getOpaque(description)
                    handshake(inputStream, outputStream)
                    rxPacketHandler = Thread(Runnable { handleRxPacketLoop() })
                    rxPacketHandler!!.start()
                    sendConnect(getAuthUserResult(user!!, password!!, salt, challenge, opaque!!))
                } else if (description.contains("code=403") && user == null || password == null) {
                    connectCheckerRtmp.onAuthErrorRtmp()
                    connected = false
                    synchronized(connectingLock) { connectingLockCondition.signalAll() }
                } else {
                    connectCheckerRtmp.onConnectionFailedRtmp(description)
                    connected = false
                    synchronized(connectingLock) { connectingLockCondition.signalAll() }
                }
            } catch (e: Exception) {
                connectCheckerRtmp.onConnectionFailedRtmp(e.message!!)
                connected = false
                synchronized(connectingLock) { connectingLockCondition.signalAll() }
            }
            "_result" -> {
                // This is the result of one of the methods invoked by us
                val method: String? = rtmpSessionInfo?.takeInvokedCommand(invoke.transactionId)

                Logger.getLogger(TAG).log(Level.INFO, "handleRxInvoke: Got result for invoked method: $method")
                if ("connect" == method) {
                    if (onAuth) {
                        connectCheckerRtmp.onAuthSuccessRtmp()
                        onAuth = false
                    }
                    // Capture server ip/pid/id information if any
                    // We can now send createStream commands
                    connected = true
                    synchronized(connectingLock) { connectingLockCondition.signalAll() }
                } else if ("createStream".contains(method!!)) {
                    // Get stream id
                    currentStreamId = (invoke.array?.get(1) as AmfNumber).value.toInt()
                    Logger.getLogger(TAG).log(Level.INFO, "handleRxInvoke(): Stream ID to publish: $currentStreamId")
                    if (streamName != null && publishType != null) {
                        fmlePublish()
                    }
                } else if ("releaseStream".contains(method)) {
                    Logger.getLogger(TAG).log(Level.INFO, "handleRxInvoke(): 'releaseStream'")
                } else if ("FCPublish".contains(method)) {
                    Logger.getLogger(TAG).log(Level.INFO, "handleRxInvoke(): 'FCPublish'")
                } else {
                    Logger.getLogger(TAG).log(Level.INFO, "handleRxInvoke(): '_result0 message received for unknown method: $method'")
                }
            }
            "onBWDone" -> Logger.getLogger(TAG).log(Level.INFO, "handleRxInvoke(): 'onBWDone'")
            "onFCPublish" -> Logger.getLogger(TAG).log(Level.INFO, "handleRxInvoke(): 'onFCPublish'")
            "onStatus" -> {
                val code: String = ((invoke.array?.get(1) as AmfObject).getProperty("code") as AmfString).value
                Logger.getLogger(TAG).log(Level.INFO, "handleRxInvoke(): onStatus $code")
                if (code == "NetStream.Publish.Start") {
                    onMetaData()
                    // We can now publish AV data
                    publishPermitted = true
                    synchronized(publishLock) { publishLockCondition.signalAll() }
                } else if (code == "NetConnection.Connect.Rejected") {
                    netConnectionDescription = ((invoke.array?.get(1) as AmfObject).getProperty(
                            "description") as AmfString).value
                    publishPermitted = false
                    synchronized(publishLock) { publishLockCondition.signalAll() }
                }
            }

            else -> Logger.getLogger(TAG).log(Level.INFO, "handleRxInvoke(): Unknown/unhandled server invoke: $invoke")

        }
    }

    override fun setVideoResolution(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
    }

    override fun setAuthorization(user: String, password: String) {
        this.user = user
        this.password = password
    }

    companion object {
        private const val TAG = "RtmpConnection"
        private val rtmpUrlPattern = Pattern.compile("^rtmps?://([^/:]+)(?::(\\d+))*/([^/]+)/?([^*]*)$")
    }

}