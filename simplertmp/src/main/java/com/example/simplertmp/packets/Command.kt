package com.example.simplertmp.packets

import com.example.simplertmp.amf.AmfNumber
import com.example.simplertmp.amf.AmfString
import com.example.simplertmp.io.ChunkStreamInfo
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream


/**
 * Encapsulates an command/"invoke" RTMP packet
 *
 * Invoke/command packet structure (AMF encoded):
 * (String) <commmand name>
 * (Number) <Transaction ID>
 * (Mixed) <Argument> ex. Null, String, Object: {key1:value1, key2:value2 ... }
 *
 * @author francois
</Argument></Transaction></commmand> */
class Command : VariableBodyRtmpPacket {
    var commandName: String = ""
    var transactionId: Int = 0

    override var array: ByteArray? = null
    override var size: Int = 0

    constructor(header: RtmpHeader) : super(header)

    constructor(commandName: String, transactionId: Int, channelInfo: ChunkStreamInfo) :
            super(
                    RtmpHeader(
                            if (channelInfo.canReusePrevHeaderTx(RtmpHeader.MessageType.COMMAND_AMF0))
                                RtmpHeader.ChunkType.TYPE_1_RELATIVE_LARGE
                            else
                                RtmpHeader.ChunkType.TYPE_0_FULL,
                            ChunkStreamInfo.RTMP_CID_OVER_CONNECTION.toInt(),
                            RtmpHeader.MessageType.COMMAND_AMF0
                    )
            )
    {
        this.commandName = commandName
        this.transactionId = transactionId
    }

    constructor(commandName: String, transactionId: Int) : super(RtmpHeader(RtmpHeader.ChunkType.TYPE_0_FULL, ChunkStreamInfo.RTMP_CID_OVER_CONNECTION.toInt(),
            RtmpHeader.MessageType.COMMAND_AMF0)) {
        this.commandName = commandName
        this.transactionId = transactionId
    }

    @Throws(IOException::class)
    override fun readBody(input: InputStream) {
        // The command name and transaction ID are always present (AMF string followed by number)
        commandName = AmfString.readStringFrom(input, false)
        transactionId = AmfNumber.readNumberFrom(input).toInt()
        val bytesRead: Int = AmfString.sizeOf(commandName, false) + AmfNumber.size
        readVariableData(input, bytesRead)
    }

    @Throws(IOException::class)
    override fun writeBody(output: OutputStream) {
        AmfString.writeStringTo(output, commandName, false)
        AmfNumber.writeNumberTo(output, transactionId.toDouble())
        // Write body data
        writeVariableData(output)
    }

    override fun toString(): String {
        return "RTMP Command (command: $commandName, transaction ID: $transactionId)"
    }

    companion object {
        private const val TAG = "Command"
    }
}