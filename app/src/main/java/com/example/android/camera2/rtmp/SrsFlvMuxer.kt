package net.ossrs.rtmp

import android.media.MediaCodec
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.example.android.camera2.rtmp.SrsAllocator
import com.example.simplertmp.DefaultRtmpPublisher
import com.example.simplertmp.RtmpPublisher
import com.example.simplertmp.io.ConnectCheckerRtmp
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.experimental.and
import kotlin.experimental.or

/**
 * Created by winlin on 5/2/15.
 * Updated by leoma on 4/1/16.
 * modified by pedro
 * to POST the h.264/avc annexb frame over RTMP.
 * modified by Troy
 * to accept any RtmpPublisher implementation.
 *
 * Usage:
 * muxer = new SrsRtmp("rtmp://ossrs.net/live/yasea");
 * muxer.start();
 *
 * MediaFormat aformat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate,
 * achannel);
 * // setup the aformat for audio.
 * atrack = muxer.addTrack(aformat);
 *
 * MediaFormat vformat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vsize.width,
 * vsize.height);
 * // setup the vformat for video.
 * vtrack = muxer.addTrack(vformat);
 *
 * // encode the video frame from camera by h.264 codec to es and bi,
 * // where es is the h.264 ES(element stream).
 * ByteBuffer es, MediaCodec.BufferInfo bi;
 * muxer.writeSampleData(vtrack, es, bi);
 *
 * // encode the audio frame from microphone by aac codec to es and bi,
 * // where es is the aac ES(element stream).
 * ByteBuffer es, MediaCodec.BufferInfo bi;
 * muxer.writeSampleData(atrack, es, bi);
 *
 * muxer.stop();
 * muxer.release();
 */
class SrsFlvMuxer @JvmOverloads constructor(private val connectCheckerRtmp: ConnectCheckerRtmp, private val publisher: RtmpPublisher = DefaultRtmpPublisher(connectCheckerRtmp)) {
    @Volatile
    var isConnected = false
        private set
    private var worker: Thread? = null
    private val flv = SrsFlv()
    private var needToFindKeyFrame = true
    private var mVideoSequenceHeader: SrsFlvFrame? = null
    private var mAudioSequenceHeader: SrsFlvFrame? = null
    private val mVideoAllocator = SrsAllocator(VIDEO_ALLOC_SIZE)
    private val mAudioAllocator = SrsAllocator(AUDIO_ALLOC_SIZE)

    @Volatile
    private var mFlvVideoTagCache: BlockingQueue<SrsFlvFrame> = LinkedBlockingQueue(30)

    @Volatile
    private var mFlvAudioTagCache: BlockingQueue<SrsFlvFrame> = LinkedBlockingQueue(30)
    private var sampleRate = 0
    private var isPpsSpsSend = false
    private var profileIop = ProfileIop.BASELINE
    private var url: String? = null

    //re connection
    private var numRetry = 0
    private var reTries = 0
    private val handler: Handler
    private var runnable: Runnable? = null
    var sentAudioFrames: Long = 0
        private set
    var sentVideoFrames: Long = 0
        private set
    var droppedAudioFrames: Long = 0
        private set
    var droppedVideoFrames: Long = 0
        private set

    fun setProfileIop(profileIop: Byte) {
        this.profileIop = profileIop
    }

    fun setSpsPPs(sps: ByteBuffer?, pps: ByteBuffer?) {
        flv.setSpsPPs(sps, pps)
    }

    fun setSampleRate(sampleRate: Int) {
        this.sampleRate = sampleRate
    }

    fun setIsStereo(isStereo: Boolean) {
        val channel = if (isStereo) 2 else 1
        flv.setAchannel(channel)
    }

    fun setAuthorization(user: String?, password: String?) {
        publisher.setAuthorization(user!!, password!!)
    }

    fun resizeFlvTagCache(newSize: Int) {
        synchronized(mFlvAudioTagCache) { mFlvAudioTagCache = resizeFlvTagCacheInternal(mFlvAudioTagCache, newSize) }
        synchronized(mFlvVideoTagCache) { mFlvVideoTagCache = resizeFlvTagCacheInternal(mFlvVideoTagCache, newSize) }
    }

    private fun resizeFlvTagCacheInternal(cache: BlockingQueue<SrsFlvFrame>, newSize: Int): BlockingQueue<SrsFlvFrame> {
        if (newSize < cache.size - cache.remainingCapacity()) {
            throw RuntimeException("Can't fit current cache inside new cache size")
        }
        val newQueue: BlockingQueue<SrsFlvFrame> = LinkedBlockingQueue(newSize)
        cache.drainTo(newQueue)
        return newQueue
    }

    val flvTagCacheSize: Int
        get() = mFlvVideoTagCache.size + mFlvAudioTagCache.size

    fun resetSentAudioFrames() {
        sentAudioFrames = 0
    }

    fun resetSentVideoFrames() {
        sentVideoFrames = 0
    }

    fun resetDroppedAudioFrames() {
        droppedAudioFrames = 0
    }

    fun resetDroppedVideoFrames() {
        droppedVideoFrames = 0
    }

    /**
     * set video resolution for publisher
     *
     * @param width width
     * @param height height
     */
    fun setVideoResolution(width: Int, height: Int) {
        publisher.setVideoResolution(width, height)
    }

    private fun disconnect(connectChecker: ConnectCheckerRtmp?) {
        try {
            publisher.close()
        } catch (e: IllegalStateException) {
            // Ignore illegal state.
        }
        isConnected = false
        mVideoSequenceHeader = null
        mAudioSequenceHeader = null
        resetSentAudioFrames()
        resetSentVideoFrames()
        resetDroppedAudioFrames()
        resetDroppedVideoFrames()
        if (connectChecker != null) {
            reTries = 0
            connectChecker.onDisconnectRtmp()
        }
        Log.i(TAG, "worker: disconnect ok.")
    }

    fun setReTries(reTries: Int) {
        numRetry = reTries
        this.reTries = reTries
    }

    fun shouldRetry(reason: String): Boolean {
        val validReason = !reason.contains("Endpoint malformed")
        return validReason && reTries > 0
    }

    fun reConnect(delay: Long) {
        reTries--
        stop(null)
        runnable = Runnable { start(url) }
        handler.postDelayed(runnable, delay)
    }

    private fun connect(url: String?): Boolean {
        this.url = url
        if (!isConnected) {
            Log.i(TAG, String.format("worker: connecting to RTMP server by url=%s\n", url))
            if (publisher.connect(url!!)) {
                isConnected = publisher.publish("live")
            }
            mVideoSequenceHeader = null
            mAudioSequenceHeader = null
        }
        return isConnected
    }

    private fun sendFlvTag(frame: SrsFlvFrame?) {
        if (!isConnected || frame == null) {
            return
        }
        if (frame.is_video()) {
            if (frame.is_keyframe()) {
                Log.i(TAG, String.format("worker: send frame type=%d, dts=%d, size=%dB", frame.type, frame.dts,
                        frame.flvTag!!.data))
            }
            publisher.publishVideoData(frame.flvTag!!.data, frame.flvTag!!.size, frame.dts)
            mVideoAllocator.release(frame.flvTag!!)
            sentVideoFrames++
        } else if (frame.is_audio()) {
            publisher.publishAudioData(frame.flvTag!!.data, frame.flvTag!!.size, frame.dts)
            mAudioAllocator.release(frame.flvTag!!)
            sentAudioFrames++
        }
    }

    /**
     * start to the remote SRS for remux.
     */
    fun start(rtmpUrl: String?) {
        worker = Thread(Runnable {
            Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
            if (!connect(rtmpUrl)) {
                return@Runnable
            }
            reTries = numRetry
            connectCheckerRtmp.onConnectionSuccessRtmp()
            while (!Thread.interrupted()) {
                try {
                    var frame = mFlvAudioTagCache.poll(1, TimeUnit.MILLISECONDS)
                    if (frame != null) {
                        if (frame.is_sequenceHeader()) {
                            mAudioSequenceHeader = frame
                        }
                        sendFlvTag(frame)
                    }
                    frame = mFlvVideoTagCache.poll(1, TimeUnit.MILLISECONDS)
                    if (frame != null) {
                        // video
                        if (frame.is_sequenceHeader()) {
                            mVideoSequenceHeader = frame
                        }
                        sendFlvTag(frame)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        })
        worker!!.start()
    }

    fun stop() {
        stop(connectCheckerRtmp)
    }

    /**
     * stop the muxer, disconnect RTMP connection.
     */
    private fun stop(connectCheckerRtmp: ConnectCheckerRtmp?) {
        handler.removeCallbacks(runnable)
        if (worker != null) {
            worker!!.interrupt()
            try {
                worker!!.join(100)
            } catch (e: InterruptedException) {
                worker!!.interrupt()
            }
            worker = null
        }
        mFlvAudioTagCache.clear()
        mFlvVideoTagCache.clear()
        flv.reset()
        needToFindKeyFrame = true
        Log.i(TAG, "SrsFlvMuxer closed")
        Thread(Runnable { disconnect(connectCheckerRtmp) }).start()
    }

    fun sendVideo(byteBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        flv.writeVideoSample(byteBuffer, bufferInfo)
    }

    fun sendAudio(byteBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        flv.writeAudioSample(byteBuffer, bufferInfo)
    }

    // E.4.3.1 VIDEODATA
    // Frame Type UB [4]
    // Type of video frame. The following values are defined:
    //     1 = key frame (for AVC, a seekable frame)
    //     2 = inter frame (for AVC, a non-seekable frame)
    //     3 = disposable inter frame (H.263 only)
    //     4 = generated key frame (reserved for server use only)
    //     5 = video info/command frame
    private object SrsCodecVideoAVCFrame {
        const val KeyFrame = 1
        const val InterFrame = 2
    }

    // AVCPacketType IF CodecID == 7 UI8
    // The following values are defined:
    //     0 = AVC sequence header
    //     1 = AVC NALU
    //     2 = AVC end of sequence (lower level NALU sequence ender is
    //         not required or supported)
    private object SrsCodecVideoAVCType {
        const val SequenceHeader = 0
        const val NALU = 1
    }

    /**
     * E.4.1 FLV Tag, page 75
     */
    private object SrsCodecFlvTag {
        // 8 = audio
        const val Audio = 8

        // 9 = video
        const val Video = 9
    }

    private object AudioSampleRate {
        const val R11025 = 11025
        const val R12000 = 12000
        const val R16000 = 16000
        const val R22050 = 22050
        const val R24000 = 24000
        const val R32000 = 32000
        const val R44100 = 44100
        const val R48000 = 48000
        const val R64000 = 64000
        const val R88200 = 88200
        const val R96000 = 96000
    }

    // E.4.3.1 VIDEODATA
    // CodecID UB [4]
    // Codec Identifier. The following values are defined:
    //     2 = Sorenson H.263
    //     3 = Screen video
    //     4 = On2 VP6
    //     5 = On2 VP6 with alpha channel
    //     6 = Screen video version 2
    //     7 = AVC
    private object SrsCodecVideo {
        const val AVC = 7
    }

    /**
     * the aac object type, for RTMP sequence header
     * for AudioSpecificConfig, @see aac-mp4a-format-ISO_IEC_14496-3+2001.pdf, page 33
     * for audioObjectType, @see aac-mp4a-format-ISO_IEC_14496-3+2001.pdf, page 23
     */
    private object SrsAacObjectType {
        const val AacLC = 2
    }

    /**
     * Table 7-1 – NAL unit type codes, syntax element categories, and NAL unit type classes
     * H.264-AVC-ISO_IEC_14496-10-2012.pdf, page 83.
     */
    private object SrsAvcNaluType {
        // Unspecified
        const val Reserved = 0

        // Coded slice of a non-IDR picture slice_layer_without_partitioning_rbsp( )
        const val NonIDR = 1

        // Coded slice data partition A slice_data_partition_a_layer_rbsp( )
        const val DataPartitionA = 2

        // Coded slice data partition B slice_data_partition_b_layer_rbsp( )
        const val DataPartitionB = 3

        // Coded slice data partition C slice_data_partition_c_layer_rbsp( )
        const val DataPartitionC = 4

        // Coded slice of an IDR picture slice_layer_without_partitioning_rbsp( )
        const val IDR = 5

        // Supplemental enhancement information (SEI) sei_rbsp( )
        const val SEI = 6

        // Sequence parameter set seq_parameter_set_rbsp( )
        const val SPS = 7

        // Picture parameter set pic_parameter_set_rbsp( )
        const val PPS = 8

        // Access unit delimiter access_unit_delimiter_rbsp( )
        const val AccessUnitDelimiter = 9

        // End of sequence end_of_seq_rbsp( )
        const val EOSequence = 10

        // End of stream end_of_stream_rbsp( )
        const val EOStream = 11

        // Filler data filler_data_rbsp( )
        const val FilterData = 12

        // Sequence parameter set extension seq_parameter_set_extension_rbsp( )
        const val SPSExt = 13

        // Prefix NAL unit prefix_nal_unit_rbsp( )
        const val PrefixNALU = 14

        // Subset sequence parameter set subset_seq_parameter_set_rbsp( )
        const val SubsetSPS = 15

        // Coded slice of an auxiliary coded picture without partitioning slice_layer_without_partitioning_rbsp( )
        const val LayerWithoutPartition = 19

        // Coded slice extension slice_layer_extension_rbsp( )
        const val CodedSliceExt = 20
    }

    /**
     * the search result for annexb.
     */
    private inner class SrsAnnexbSearch {
        var nb_start_code = 0
        var match = false
    }

    /**
     * the demuxed tag frame.
     */
    private inner class SrsFlvFrameBytes {
        var data: ByteBuffer? = null
        var size = 0
    }

    /**
     * the muxed flv frame.
     */
    private inner class SrsFlvFrame {
        // the tag bytes.
        var flvTag: SrsAllocator.Allocation? = null

        // the codec type for audio/aac and video/avc for instance.
        var avc_aac_type = 0

        // the frame type, keyframe or not.
        var frame_type = 0

        // the tag type, audio, video or data.
        var type = 0

        // the dts in ms, tbn is 1000.
        var dts = 0
        fun is_keyframe(): Boolean {
            return is_video() && frame_type == SrsCodecVideoAVCFrame.KeyFrame
        }

        fun is_sequenceHeader(): Boolean {
            return avc_aac_type == 0
        }

        fun is_video(): Boolean {
            return type == SrsCodecFlvTag.Video
        }

        fun is_audio(): Boolean {
            return type == SrsCodecFlvTag.Audio
        }
    }

    /**
     * the raw h.264 stream, in annexb.
     */
    private inner class SrsRawH264Stream {
        private val annexb = SrsAnnexbSearch()
        private val nalu_header = SrsFlvFrameBytes()
        private val seq_hdr = SrsFlvFrameBytes()
        private val sps_hdr = SrsFlvFrameBytes()
        private val sps_bb = SrsFlvFrameBytes()
        private val pps_hdr = SrsFlvFrameBytes()
        private val pps_bb = SrsFlvFrameBytes()
        fun isSps(frame: SrsFlvFrameBytes): Boolean {
            return frame.size >= 1 && frame.data!![0].toInt() and 0x1f == SrsAvcNaluType.SPS
        }

        fun isPps(frame: SrsFlvFrameBytes): Boolean {
            return frame.size >= 1 && frame.data!![0].toInt() and 0x1f == SrsAvcNaluType.PPS
        }

        fun muxNaluHeader(frame: SrsFlvFrameBytes): SrsFlvFrameBytes {
            if (nalu_header.data == null) {
                nalu_header.data = ByteBuffer.allocate(4)
                nalu_header.size = 4
            }
            nalu_header.data!!.rewind()

            // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
            // lengthSizeMinusOne, or NAL_unit_length, always use 4bytes size
            val NAL_unit_length = frame.size

            // mux the avc NALU in "ISO Base Media File Format"
            // from H.264-AVC-ISO_IEC_14496-15.pdf, page 20
            // NALUnitLength
            nalu_header.data!!.putInt(NAL_unit_length)

            // reset the buffer.
            nalu_header.data!!.rewind()
            return nalu_header
        }

        fun muxSequenceHeader(sps: ByteBuffer, pps: ByteBuffer,
                              frames: ArrayList<SrsFlvFrameBytes>) {
            // 5bytes sps/pps header:
            //      configurationVersion, AVCProfileIndication, profile_compatibility,
            //      AVCLevelIndication, lengthSizeMinusOne
            // 3bytes size of sps:
            //      numOfSequenceParameterSets, sequenceParameterSetLength(2B)
            // Nbytes of sps.
            //      sequenceParameterSetNALUnit
            // 3bytes size of pps:
            //      numOfPictureParameterSets, pictureParameterSetLength
            // Nbytes of pps:
            //      pictureParameterSetNALUnit

            // decode the SPS:
            // @see: 7.3.2.1.1, H.264-AVC-ISO_IEC_14496-10-2012.pdf, page 62
            if (seq_hdr.data == null) {
                seq_hdr.data = ByteBuffer.allocate(5)
                seq_hdr.size = 5
            }
            seq_hdr.data!!.rewind()
            // @see: Annex A Profiles and levels, H.264-AVC-ISO_IEC_14496-10.pdf, page 205
            //      Baseline profile profile_idc is 66(0x42).
            //      Main profile profile_idc is 77(0x4d).
            //      Extended profile profile_idc is 88(0x58).
            val profile_idc = sps[1]
            //u_int8_t constraint_set = frame[2];
            val level_idc = sps[3]

            // generate the sps/pps header
            // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
            // configurationVersion
            seq_hdr.data!!.put(0x01.toByte())
            // AVCProfileIndication
            seq_hdr.data!!.put(profile_idc)
            // profile_compatibility
            seq_hdr.data!!.put(profileIop)
            // AVCLevelIndication
            seq_hdr.data!!.put(level_idc)
            // lengthSizeMinusOne, or NAL_unit_length, always use 4bytes size,
            // so we always set it to 0x03.
            seq_hdr.data!!.put(0x03.toByte())

            // reset the buffer.
            seq_hdr.data!!.rewind()
            frames.add(seq_hdr)

            // sps
            if (sps_hdr.data == null) {
                sps_hdr.data = ByteBuffer.allocate(3)
                sps_hdr.size = 3
            }
            sps_hdr.data!!.rewind()
            // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
            // numOfSequenceParameterSets, always 1
            sps_hdr.data!!.put(0x01.toByte())
            // sequenceParameterSetLength
            sps_hdr.data!!.putShort(sps.array().size.toShort())
            sps_hdr.data!!.rewind()
            frames.add(sps_hdr)

            // sequenceParameterSetNALUnit
            sps_bb.size = sps.array().size
            sps_bb.data = sps.duplicate()
            frames.add(sps_bb)

            // pps
            if (pps_hdr.data == null) {
                pps_hdr.data = ByteBuffer.allocate(3)
                pps_hdr.size = 3
            }
            pps_hdr.data!!.rewind()
            // 5.3.4.2.1 Syntax, H.264-AVC-ISO_IEC_14496-15.pdf, page 16
            // numOfPictureParameterSets, always 1
            pps_hdr.data!!.put(0x01.toByte())
            // pictureParameterSetLength
            pps_hdr.data!!.putShort(pps.array().size.toShort())
            pps_hdr.data!!.rewind()
            frames.add(pps_hdr)

            // pictureParameterSetNALUnit
            pps_bb.size = pps.array().size
            pps_bb.data = pps.duplicate()
            frames.add(pps_bb)
        }

        fun muxFlvTag(frames: ArrayList<SrsFlvFrameBytes>, frame_type: Int,
                      avc_packet_type: Int): SrsAllocator.Allocation? {
            // for h264 in RTMP video payload, there is 5bytes header:
            //      1bytes, FrameType | CodecID
            //      1bytes, AVCPacketType
            //      3bytes, CompositionTime, the cts.
            // @see: E.4.3 Video Tags, video_file_format_spec_v10_1.pdf, page 78
            var size = 5
            for (i in frames.indices) {
                size += frames[i].size
            }
            val allocation = mVideoAllocator.allocate(size)

            // @see: E.4.3 Video Tags, video_file_format_spec_v10_1.pdf, page 78
            // Frame Type, Type of video frame.
            // CodecID, Codec Identifier.
            // set the rtmp header
            allocation!!.put((frame_type shl 4 or SrsCodecVideo.AVC).toByte())

            // AVCPacketType
            allocation.put(avc_packet_type.toByte())

            // CompositionTime
            // pts = dts + cts, or
            // cts = pts - dts.
            // where cts is the header in rtmp video packet payload header.
            val cts = 0
            allocation.put((cts shr 16).toByte())
            allocation.put((cts shr 8).toByte())
            allocation.put(cts.toByte())

            // h.264 raw data.
            for (i in frames.indices) {
                val frame = frames[i]
                frame.data!!.rewind()
                frame.data!![allocation.data, allocation.size, frame.size]
                allocation.appendOffset(frame.size)
            }
            return allocation
        }

        private fun searchStartcode(bb: ByteBuffer, size: Int): SrsAnnexbSearch {
            annexb.match = false
            annexb.nb_start_code = 0
            if (size - 4 > 0) {
                if (bb[0].toInt() == 0x00 && bb[1].toInt() == 0x00 && bb[2].toInt() == 0x00 && bb[3].toInt() == 0x01) {
                    // match N[00] 00 00 00 01, where N>=0
                    annexb.match = true
                    annexb.nb_start_code = 4
                } else if (bb[0].toInt() == 0x00 && bb[1].toInt() == 0x00 && bb[2].toInt() == 0x01) {
                    // match N[00] 00 00 01, where N>=0
                    annexb.match = true
                    annexb.nb_start_code = 3
                }
            }
            return annexb
        }

        private fun searchAnnexb(bb: ByteBuffer, size: Int): SrsAnnexbSearch {
            annexb.match = false
            annexb.nb_start_code = 0
            for (i in bb.position() until size - 4) {
                // not match.
                if (bb[i].toInt() != 0x00 || bb[i + 1].toInt() != 0x00) {
                    continue
                }
                // match N[00] 00 00 01, where N>=0
                if (bb[i + 2].toInt() == 0x01) {
                    annexb.match = true
                    annexb.nb_start_code = i + 3 - bb.position()
                    break
                }
                // match N[00] 00 00 00 01, where N>=0
                if (bb[i + 2].toInt() == 0x00 && bb[i + 3].toInt() == 0x01) {
                    annexb.match = true
                    annexb.nb_start_code = i + 4 - bb.position()
                    break
                }
            }
            return annexb
        }

        fun demuxAnnexb(bb: ByteBuffer, size: Int, isOnlyChkHeader: Boolean): SrsFlvFrameBytes {
            val tbb = SrsFlvFrameBytes()
            if (bb.position() < size - 4) {
                // each frame must prefixed by annexb format.
                // about annexb, @see H.264-AVC-ISO_IEC_14496-10.pdf, page 211.
                val tbbsc = if (isOnlyChkHeader) searchStartcode(bb, size) else searchAnnexb(bb, size)
                // tbbsc.nb_start_code always 4 , after 00 00 00 01
                if (!tbbsc.match || tbbsc.nb_start_code < 3) {
                    Log.e(Companion.TAG, "annexb not match.")
                } else {
                    // the start codes.
                    for (i in 0 until tbbsc.nb_start_code) {
                        bb.get()
                    }

                    // find out the frame size.
                    tbb.data = bb.slice()
                    tbb.size = size - bb.position()
                }
            }
            return tbb
        }
    }

    /**
     * remux the annexb to flv tags.
     */
    private inner class SrsFlv {
        private val avc = SrsRawH264Stream()
        private val ipbs = ArrayList<SrsFlvFrameBytes>()
        private var audio_tag: SrsAllocator.Allocation? = null
        private var video_tag: SrsAllocator.Allocation? = null
        private var Sps: ByteBuffer? = null
        private var Pps: ByteBuffer? = null
        private var aac_specific_config_got = false
        private var achannel = 0
        fun setAchannel(achannel: Int) {
            this.achannel = achannel
        }

        fun reset() {
            Sps = null
            Pps = null
            isPpsSpsSend = false
            aac_specific_config_got = false
        }

        fun writeAudioSample(bb: ByteBuffer, bi: MediaCodec.BufferInfo) {
            val dts = (bi.presentationTimeUs / 1000).toInt()
            audio_tag = mAudioAllocator.allocate(bi.size + 2)
            var aac_packet_type: Byte = 1 // 1 = AAC raw
            if (!aac_specific_config_got) {
                // @see aac-mp4a-format-ISO_IEC_14496-3+2001.pdf
                // AudioSpecificConfig (), page 33
                // 1.6.2.1 AudioSpecificConfig
                // audioObjectType; 5 bslbf
                var ch = (if (bi.flags == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) bb[0].toInt() and 0xf8 else (bb[0].toInt() and 0xf8) / 2).toByte()
                // 3bits left.

                // samplingFrequencyIndex; 4 bslbf
                // For the values refer to https://wiki.multimedia.cx/index.php/MPEG-4_Audio#Sampling_Frequencies
                val samplingFrequencyIndex: Byte = when (sampleRate) {
                    AudioSampleRate.R96000 -> 0x00
                    AudioSampleRate.R88200 -> 0x01
                    AudioSampleRate.R64000 -> 0x02
                    AudioSampleRate.R48000 -> 0x03
                    AudioSampleRate.R44100 -> 0x04
                    AudioSampleRate.R32000 -> 0x05
                    AudioSampleRate.R24000 -> 0x06
                    AudioSampleRate.R22050 -> 0x07
                    AudioSampleRate.R16000 -> 0x08
                    AudioSampleRate.R12000 -> 0x09
                    AudioSampleRate.R11025 -> 0x0a
                    else ->             // 44100 Hz shall be the fallback value when sampleRate is irregular.
                        // not implemented: other sample rates might be possible with samplingFrequencyIndex = 0x0f.
                        0x04 // 4: 44100 Hz
                }
                ch = ch or ((samplingFrequencyIndex.toInt() shr 1 and 0x07).toByte())
                audio_tag!!.put(ch, 2)
                ch = ((samplingFrequencyIndex.toInt() shl 7 and 0x80).toByte())
                // 7bits left.

                // channelConfiguration; 4 bslbf
                var channelConfiguration: Byte = 1
                if (achannel == 2) {
                    channelConfiguration = 2
                }
                ch = ch or ((channelConfiguration.toInt() shl 3 and 0x78).toByte())
                // 3bits left.

                // GASpecificConfig(), page 451
                // 4.4.1 Decoder configuration (GASpecificConfig)
                // frameLengthFlag; 1 bslbf
                // dependsOnCoreCoder; 1 bslbf
                // extensionFlag; 1 bslbf
                audio_tag!!.put(ch, 3)
                aac_specific_config_got = true
                aac_packet_type = 0 // 0 = AAC sequence header
                writeAdtsHeader(audio_tag!!.data, 4)
                audio_tag!!.appendOffset(7)
            } else {
                bb[audio_tag!!.data, 2, bi.size]
                audio_tag!!.appendOffset(bi.size + 2)
            }
            val sound_format: Byte = 10 // AAC
            var sound_type: Byte = 0 // 0 = Mono sound
            if (achannel == 2) {
                sound_type = 1 // 1 = Stereo sound
            }
            val sound_size: Byte = 1 // 1 = 16-bit samples
            var sound_rate: Byte = 3 // 44100, 22050, 11025
            if (sampleRate == 22050) {
                sound_rate = 2
            } else if (sampleRate == 11025) {
                sound_rate = 1
            }

            // for audio frame, there is 1 or 2 bytes header:
            //      1bytes, SoundFormat|SoundRate|SoundSize|SoundType
            //      1bytes, AACPacketType for SoundFormat == 10, 0 is sequence header.
            var audio_header = (sound_type and 0x01) as Byte
            audio_header = audio_header or ((sound_size.toInt() shl 1 and 0x02).toByte())
            audio_header = audio_header or ((sound_rate.toInt() shl 2 and 0x0c).toByte())
            audio_header = audio_header or ((sound_format.toInt() shl 4 and 0xf0).toByte())
            audio_tag!!.put(audio_header, 0)
            audio_tag!!.put(aac_packet_type, 1)
            writeRtmpPacket(SrsCodecFlvTag.Audio, dts, 0, aac_packet_type.toInt(), audio_tag)
        }

        private fun writeAdtsHeader(frame: ByteArray, offset: Int) {
            // adts sync word 0xfff (12-bit)
            frame[offset] = 0xff.toByte()
            frame[offset + 1] = 0xf0.toByte()
            // version 0 for MPEG-4, 1 for MPEG-2 (1-bit)
            frame[offset + 1] = frame[offset + 1] or (0 shl 3)
            // layer 0 (2-bit)
            frame[offset + 1] = frame[offset + 1] or (0 shl 1)
            // protection absent: 1 (1-bit)
            frame[offset + 1] = frame[offset + 1] or 1
            // profile: audio_object_type - 1 (2-bit)
            frame[offset + 2] = (SrsAacObjectType.AacLC - 1 shl 6).toByte()
            // sampling frequency index: 4 (4-bit)
            frame[offset + 2] = (frame[offset + 2] or (4 and 0xf shl 2))
            // channel configuration (3-bit)
            frame[offset + 2] = (frame[offset + 2] or ((2 and 0x4 shr 2).toByte()))
            frame[offset + 3] = (2 and 0x03 shl 6).toByte()
            // original: 0 (1-bit)
            frame[offset + 3] = frame[offset + 3] or (0 shl 5)
            // home: 0 (1-bit)
            frame[offset + 3] = frame[offset + 3] or (0 shl 4)
            // copyright id bit: 0 (1-bit)
            frame[offset + 3] = frame[offset + 3] or (0 shl 3)
            // copyright id start: 0 (1-bit)
            frame[offset + 3] = frame[offset + 3] or (0 shl 2)
            // frame size (13-bit)
            frame[offset + 3] = frame[offset + 3] or ((frame.size - 2 and 0x1800 shr 11).toByte())
            frame[offset + 4] = (frame.size - 2 and 0x7f8 shr 3).toByte()
            frame[offset + 5] = (frame.size - 2 and 0x7 shl 5).toByte()
            // buffer fullness (0x7ff for variable bitrate)
            frame[offset + 5] = frame[offset + 5] or 0x1f.toByte()
            frame[offset + 6] = 0xfc.toByte()
            // number of data block (nb - 1)
            frame[offset + 6] = frame[offset + 6] or 0x0
        }

        fun writeVideoSample(bb: ByteBuffer, bi: MediaCodec.BufferInfo) {
            if (bi.size < 4) return
            bb.rewind() //Sometimes the position is not 0.
            val pts = (bi.presentationTimeUs / 1000).toInt()
            var type = SrsCodecVideoAVCFrame.InterFrame
            val frame = avc.demuxAnnexb(bb, bi.size, true)
            val nal_unit_type: Int = frame.data!![0].toInt() and 0x1f
            if (nal_unit_type == SrsAvcNaluType.IDR || bi.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME) {
                type = SrsCodecVideoAVCFrame.KeyFrame
            } else if (nal_unit_type == SrsAvcNaluType.SPS || nal_unit_type == SrsAvcNaluType.PPS) {
                val frame_pps = avc.demuxAnnexb(bb, bi.size, false)
                frame.size = frame.size - frame_pps.size - 4 // 4 ---> 00 00 00 01 pps
                if (frame.data != Sps) {
                    val sps = ByteArray(frame.size)
                    frame.data!![sps]
                    isPpsSpsSend = false
                    Sps = ByteBuffer.wrap(sps)
                }
                val frame_sei = avc.demuxAnnexb(bb, bi.size, false)
                if (frame_sei.size > 0) {
                    if (SrsAvcNaluType.SEI == frame_sei.data!![0].toInt() and 0x1f) {
                        frame_pps.size = frame_pps.size - frame_sei.size - 3 // 3 ---> 00 00 01 SEI
                    }
                }
                if (frame_pps.size > 0 && frame_pps.data != Pps) {
                    val pps = ByteArray(frame_pps.size)
                    frame_pps.data!![pps]
                    isPpsSpsSend = false
                    Pps = ByteBuffer.wrap(pps)
                    writeH264SpsPps(pts)
                }
                return
            } else if (nal_unit_type != SrsAvcNaluType.NonIDR) {
                return
            }
            ipbs.add(avc.muxNaluHeader(frame))
            ipbs.add(frame)
            writeH264IpbFrame(ipbs, type, pts)
            ipbs.clear()
        }

        fun setSpsPPs(sps: ByteBuffer?, pps: ByteBuffer?) {
            Sps = sps
            Pps = pps
        }

        private fun writeH264SpsPps(pts: Int) {
            // when not got sps/pps, wait.
            if (Pps == null || Sps == null || isPpsSpsSend) {
                return
            }

            // h264 raw to h264 packet.
            val frames = ArrayList<SrsFlvFrameBytes>()
            avc.muxSequenceHeader(Sps!!, Pps!!, frames)

            // h264 packet to flv packet.
            val frame_type = SrsCodecVideoAVCFrame.KeyFrame
            val avc_packet_type = SrsCodecVideoAVCType.SequenceHeader
            video_tag = avc.muxFlvTag(frames, frame_type, avc_packet_type)
            isPpsSpsSend = true
            // the timestamp in rtmp message header is dts.
            writeRtmpPacket(SrsCodecFlvTag.Video, pts, frame_type, avc_packet_type, video_tag)
            Log.i(TAG, String.format("flv: h264 sps/pps sent, sps=%dB, pps=%dB", Sps!!.array().size,
                    Pps!!.array().size))
        }

        private fun writeH264IpbFrame(frames: ArrayList<SrsFlvFrameBytes>, frame_type: Int, dts: Int) {
            // when sps or pps not sent, ignore the packet.
            // @see https://github.com/simple-rtmp-server/srs/issues/203
            if (Pps == null || Sps == null) {
                return
            }
            video_tag = avc.muxFlvTag(frames, frame_type, SrsCodecVideoAVCType.NALU)
            // the timestamp in rtmp message header is dts.
            writeRtmpPacket(SrsCodecFlvTag.Video, dts, frame_type, SrsCodecVideoAVCType.NALU, video_tag)
        }

        private fun writeRtmpPacket(type: Int, dts: Int, frame_type: Int, avc_aac_type: Int,
                                    tag: SrsAllocator.Allocation?) {
            val frame = SrsFlvFrame()
            frame.flvTag = tag
            frame.type = type
            frame.dts = dts
            frame.frame_type = frame_type
            frame.avc_aac_type = avc_aac_type
            if (frame.is_video()) {
                if (needToFindKeyFrame) {
                    if (frame.is_keyframe()) {
                        needToFindKeyFrame = false
                        flvFrameCacheAdd(frame)
                    }
                } else {
                    flvFrameCacheAdd(frame)
                }
            } else if (frame.is_audio()) {
                flvFrameCacheAdd(frame)
            }
        }

        private fun flvFrameCacheAdd(frame: SrsFlvFrame) {
            try {
                if (frame.is_video()) {
                    mFlvVideoTagCache.add(frame)
                } else {
                    mFlvAudioTagCache.add(frame)
                }
            } catch (e: IllegalStateException) {
                Log.i(TAG, "frame discarded")
                if (frame.is_video()) {
                    droppedVideoFrames++
                } else {
                    droppedAudioFrames++
                }
            }
        }

        init {
            reset()
        }
    }

    companion object {
        private const val TAG = "SrsFlvMuxer"
        private const val VIDEO_ALLOC_SIZE = 128 * 1024
        private const val AUDIO_ALLOC_SIZE = 4 * 1024
    }

    /**
     * constructor.
     */
    init {
        handler = Handler(Looper.getMainLooper())
    }

    object ProfileIop {
        const val BASELINE: Byte = 0x00
        const val CONSTRAINED = 0xC0.toByte()
    }
}