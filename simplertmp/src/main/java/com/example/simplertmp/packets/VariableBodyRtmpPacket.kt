package com.example.simplertmp.packets

import com.example.simplertmp.amf.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*


/**
 * RTMP packet with a "variable" body structure (i.e. the structure of the
 * body depends on some other state/parameter in the packet.
 *
 * Examples of this type of packet are Command and Data; this abstract class
 * exists mostly for code re-use.
 *
 * @author francois
 */
abstract class VariableBodyRtmpPacket(header: RtmpHeader) : RtmpPacket(header) {
    var data: MutableList<AmfData>? = null

    fun addData(string: String) {
        addData(AmfString(string))
    }

    fun addData(number: Double) {
        addData(AmfNumber(number))
    }

    fun addData(bool: Boolean) {
        addData(AmfBoolean(bool))
    }

    fun addData(input: AmfData?) {
        data = data ?: ArrayList<AmfData>()
        data?.add(input ?: AmfNull())
    }

    @Throws(IOException::class)
    protected fun readVariableData(input: ByteArray, bytesAlreadyRead: Int) {
        // ...now read in arguments (if any)
        var i = bytesAlreadyRead
        while (i < header.packetLength) {
            val dataItem: AmfData = AmfDecoder.readFrom(input.drop(i).toByteArray())
            addData(dataItem)
            i += dataItem.size
        }
    }

    @Throws(IOException::class)
    protected fun writeVariableData(output: OutputStream) {
        data?.let {
            it.forEach { data -> data.writeTo(output) }
        } ?: run {
            AmfNull.writeNullTo(output)
        }
    }
}
