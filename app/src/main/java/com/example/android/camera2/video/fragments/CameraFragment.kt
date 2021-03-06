/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2.video.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.camera2.*
import android.media.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.*
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.arthenica.mobileffmpeg.Config
import com.arthenica.mobileffmpeg.FFmpeg
import com.example.android.camera.utils.AutoFitSurfaceView
import com.example.android.camera.utils.OrientationLiveData
import com.example.android.camera.utils.getPreviewOutputSize
import com.example.android.camera2.video.BuildConfig
import com.example.android.camera2.video.CameraActivity
import com.example.android.camera2.video.R
import com.example.simplertmp.io.ConnectCheckerRtmp
import kotlinx.android.synthetic.main.fragment_camera.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class CameraFragment : Fragment(), ConnectCheckerRtmp {

    /** AndroidX navigation arguments */
    private val args: CameraFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId)
    }

    /** File where the recording will be saved */
    private val outputFile: File by lazy { createFile(requireContext(), "mp4") }

    /**
     * Setup a persistent [Surface] for the recorder so we can use it as an output target for the
     * camera session without preparing the recorder
     */
    private val recorderSurface: Surface by lazy {

        // Get a persistent Surface from MediaCodec, don't forget to release when done
        val surface: Surface = MediaCodec.createPersistentInputSurface()

        // Prepare and release a dummy MediaRecorder with our new surface
        // Required to allocate an appropriately sized buffer before passing the Surface as the
        // output target to the capture session
        createRecorder(surface).apply {
            prepare()
            release()
        }

        surface
    }

    private val format: MediaFormat by lazy {
        MediaFormat.createVideoFormat("video/avc", 320, 240).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 3000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType("video/avc")

    /** Saves the video recording */
    private val recorder: MediaRecorder by lazy { createRecorder(recorderSurface) }

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** Performs recording animation of flashing screen */
    private val animationTask: Runnable by lazy {
        Runnable {
            // Flash white animation
            overlay.foreground = Color.argb(150, 255, 255, 255).toDrawable()
            // Wait for ANIMATION_FAST_MILLIS
            overlay.postDelayed({
                // Remove white flash animation
                overlay.foreground = null
                // Restart animation recursively
                overlay.postDelayed(animationTask, CameraActivity.ANIMATION_FAST_MILLIS)
            }, CameraActivity.ANIMATION_FAST_MILLIS)
        }
    }

    private var mBuffer = ByteArray(0)

    /** Where the camera preview is displayed */
    private lateinit var viewFinder: AutoFitSurfaceView

    /** Overlay on top of the camera preview */
    private lateinit var overlay: View

    /** Captures frames from a [CameraDevice] for our video recording */
    private lateinit var session: CameraCaptureSession

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Requests used for preview only in the [CameraCaptureSession] */
    private val previewRequest: CaptureRequest by lazy {
        // Capture request holds references to target surfaces
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            // Add the preview surface target
            addTarget(viewFinder.holder.surface)
        }.build()
    }

    /** Requests used for preview and recording in the [CameraCaptureSession] */
    private val recordRequest: CaptureRequest by lazy {
        // Capture request holds references to target surfaces
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            // Add the preview and recording surface targets
            addTarget(viewFinder.holder.surface)
            addTarget(recorderSurface)
            // Sets user requested FPS for all targets
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(args.fps, args.fps))
        }.build()

    }

    private var recordingStartMillis: Long = 0L
    private var recording = false

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) {
//            val publisher: RtmpPublisher = DefaultRtmpPublisher(this@CameraFragment)
//            if (publisher.connect("rtmp://10.0.2.2:1935/live/app")) {
//                if (publisher.publish("live")) {
//                    println("Time to send frames")
//                    val srcFd: AssetFileDescriptor = resources.openRawResourceFd(R.raw.video)
//
//                    var curFlag = 0
//                    codec.setCallback(object : MediaCodec.Callback() {
//                        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
//                            lifecycleScope.launch(Dispatchers.IO) {
//                                val outputBuffer: ByteBuffer = codec.getOutputBuffer(index)!!
//                                val bufferFormat = codec.getOutputFormat(index)
//
//                                if (info.flags != 0) {
//                                    curFlag = info.flags
//                                }
//
//                                outputBuffer.position(info.offset)
//                                outputBuffer.limit(info.offset + info.size)
//
//                                if (mBuffer.size < info.size) {
//                                    mBuffer = ByteArray(info.size)
//                                }
//
//                                outputBuffer.get(mBuffer, 0, info.size)
//
//                                when (curFlag) {
//                                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG -> {
//                                        publisher.publishVideoData(
//                                                mBuffer, info.size, (info.presentationTimeUs / 1000).toInt()
//                                        )
//                                    }
//                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM -> {
//                                    }
//                                    MediaCodec.BUFFER_FLAG_KEY_FRAME -> {
//                                        publisher.publishVideoData(
//                                                mBuffer, info.size, (info.presentationTimeUs / 1000).toInt()
//                                        )
//                                    }
//                                    MediaCodec.BUFFER_FLAG_PARTIAL_FRAME -> {
//                                        publisher.publishVideoData(
//                                                mBuffer, info.size, (info.presentationTimeUs / 1000).toInt()
//                                        )
//                                    }
//                                }
//
//                                codec.releaseOutputBuffer(index, false)
//                            }
//                        }
//
//                        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
//                            return
//                        }
//
//                        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
//                            return
//                        }
//
//                        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
//                            return
//                        }
//
//                    })


//                    val extractor = MediaExtractor()
//                    extractor.setDataSource(srcFd.fileDescriptor, srcFd.startOffset, srcFd.length)
//
//                    for (i in 0 until extractor.trackCount) {
//                        val format = extractor.getTrackFormat(i)
//                        val mime = format.getString(MediaFormat.KEY_MIME)
//                        if (mime == MediaFormat.MIMETYPE_VIDEO_AVC || mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
//                            extractor.selectTrack(i)
//                        }
//                    }
//
//                    val buf = ByteBuffer.allocate(2 * 1024 * 1024)
//                    val bufInfo = MediaCodec.BufferInfo()
//                    var framecount = 0
//                    var offset = 0
//                    var keyframeCount = 0
//                    try {
//                        while (extractor.readSampleData(buf, offset) > 0) {
//                            val trackIndex = extractor.sampleTrackIndex
//                            val presentationTimeUs = extractor.sampleTime
//                            bufInfo.offset = offset
//                            bufInfo.size = extractor.sampleSize.toInt()
//                            bufInfo.flags = extractor.sampleFlags
//
//
//                            if (bufInfo.size < 0) {
//                                Log.d(TAG, "Input EOS")
//                                bufInfo.size = 0
//                            } else {
//                                val keyframe = extractor.sampleFlags == MediaExtractor.SAMPLE_FLAG_SYNC
//                                bufInfo.presentationTimeUs = extractor.sampleTime
//                                // Send data
//                                when (extractor.getTrackFormat(extractor.sampleTrackIndex)
//                                        .getString(MediaFormat.KEY_MIME)) {
//                                    MediaFormat.MIMETYPE_AUDIO_AAC -> {
//                                        // Frame is audio
////                                        Log.d(
////                                                TAG,
////                                                "AUDIO Frame: $framecount, PresentatiomTime: ${bufInfo.presentationTimeUs}, Keyframe: ${bufInfo.flags}, TrackIndex: $trackIndex, Size(bytes): ${bufInfo.size}"
////                                        )
//                                        publisher.publishAudioData(
//                                                buf.array(), bufInfo.size, (bufInfo.presentationTimeUs / 1000).toInt()
//                                        )
//                                    }
//                                    MediaFormat.MIMETYPE_VIDEO_AVC -> {
//                                        // Frame is video
//                                        if (bufInfo.flags == 1) {
//                                            keyframeCount++
//                                        }
//                                        Log.d(
//                                                TAG,
//                                                "VIDEO Frame: $framecount, PresentatiomTime: ${bufInfo.presentationTimeUs}, Keyframe: ${bufInfo.flags}, TrackIndex: $trackIndex, Size(bytes): ${bufInfo.size}, Total keyframes: $keyframeCount"
//                                        )
//                                        publisher.publishVideoData(
//                                                buf.array(), bufInfo.size, (bufInfo.presentationTimeUs / 1000).toInt()
//                                        )
//
//                                        Thread.sleep(42)
//                                    }
//                                    else -> {
//                                        Log.d(TAG, "Unknown frame type)")
//                                    }
//                                }
//                                extractor.advance()
//                                framecount++
//                            }
//                        }
//                    } catch (e: Exception) {
//                        throw e
//                    }
//
//                    srcFd.close()
//                    val bmp = BitmapFactory.decodeResource(resources, R.raw.image)
//                    val output = ByteArrayOutputStream()
//                    bmp.compress(Bitmap.CompressFormat.JPEG, 0, output)
//                    val data = output.toByteArray()
//                    bmp.recycle()
//                    var dts = 0
//                    while (!Thread.interrupted()) {
//                        publisher.publishVideoData(
//                                data, data.size, dts
//                        )
//                        dts++
//                        Thread.sleep(17)
//                    }
//                }
//            }
        }
    }

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_camera, container, false)

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        overlay = view.findViewById(R.id.overlay)
        viewFinder = view.findViewById(R.id.view_finder)

        viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(
                    holder: SurfaceHolder, format: Int, width: Int, height: Int
            ) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {

                // Selects appropriate preview size and configures view finder
                val previewSize = getPreviewOutputSize(
                        viewFinder.display, characteristics, SurfaceHolder::class.java
                )
                Log.d(TAG, "View finder size: ${viewFinder.width} x ${viewFinder.height}")
                Log.d(TAG, "Selected preview size: $previewSize")
                viewFinder.setAspectRatio(previewSize.width, previewSize.height)

                // To ensure that size is set, initialize camera in the view's thread
                viewFinder.post { initializeCamera() }
            }
        })

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner, Observer { orientation ->
                Log.d(TAG, "Orientation changed: $orientation")
            })
        }
    }

    /** Creates a [MediaRecorder] instance using the provided [Surface] as input */
    private fun createRecorder(surface: Surface) = MediaRecorder().apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(outputFile.absolutePath)
        setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)
        if (args.fps > 0) setVideoFrameRate(args.fps)
        setVideoSize(args.width, args.height)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setInputSurface(surface)
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating request
     */
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {

        // Open the selected camera
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)

        // Creates list of Surfaces where the camera will output frames
        val targets = listOf(viewFinder.holder.surface, recorderSurface)

//        // Start a capture session using our open camera and list of Surfaces where frames will go
//        session = createCaptureSession(camera, targets, cameraHandler)
//
//        // Sends the capture request as frequently as possible until the session is torn down or
//        //  session.stopRepeating() is called
//        session.setRepeatingRequest(previewRequest, null, cameraHandler)

        // React to user touching the capture button
        capture_button.setOnClickListener { view ->
            if (!recording) {
                lifecycleScope.launch(Dispatchers.IO) {
                    recording = true
                    // Prevents screen rotation during the video recording
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
//
//                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
//                    codec.setInputSurface(recorderSurface)

//                    // Finalizes recorder setup and starts recording
//                    recorder.apply {
//                        // Sets output orientation based on current sensor value at start time
//                        relativeOrientation.value?.let { setOrientationHint(it) }
//                        prepare()
//                        start()
//                    }


                    recordingStartMillis = System.currentTimeMillis()
                    Log.d(TAG, "Recording started")

                    // Starts recording animation
//                    overlay.post(animationTask)
                }
            } else {
                lifecycleScope.launch(Dispatchers.IO) {

                    // Unlocks screen rotation after recording finished
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                    // Requires recording of at least MIN_REQUIRED_RECORDING_TIME_MILLIS
                    val elapsedTimeMillis = System.currentTimeMillis() - recordingStartMillis
                    if (elapsedTimeMillis < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
                        delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsedTimeMillis)
                    }

                    FFmpeg.cancel()

                    Log.d(TAG, "Recording stopped. Output file: $outputFile")
//                    codec.stop()
//                    recorder.stop()
//                    codec.release()
//                    recorder.release()

                    // Removes recording animation
//                    overlay.removeCallbacks(animationTask)

                    // Broadcasts the media file to the rest of the system
                    MediaScannerConnection.scanFile(
                            view.context, arrayOf(outputFile.absolutePath), null, null
                    )

                    // Launch external activity via intent to play video recorded using our provider
                    startActivity(Intent().apply {
                        action = Intent.ACTION_VIEW
                        type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(outputFile.extension)
                        val authority = "${BuildConfig.APPLICATION_ID}.provider"
                        data = FileProvider.getUriForFile(view.context, authority, outputFile)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    })

                    // Finishes our current camera screen
                    delay(CameraActivity.ANIMATION_SLOW_MILLIS)
                    navController.popBackStack()
                    recording = false
                }
            }
        }
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
            manager: CameraManager, cameraId: String, handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Creates a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine)
     */
    private suspend fun createCaptureSession(
            device: CameraDevice, targets: List<Surface>, handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Creates a capture session using the predefined targets, and defines a session state
        // callback which resumes the coroutine once the session is configured
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    override fun onStop() {
        super.onStop()
        try {
            camera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        recorder.release()
        recorderSurface.release()
    }

    override fun onConnectionSuccessRtmp() {
        Log.d(TAG, "Connected")
    }

    override fun onConnectionFailedRtmp(reason: String) {
        Log.d(TAG, "Connect failed: $reason")
    }

    override fun onNewBitrateRtmp(bitrate: Long) {
        Log.d(TAG, "New bitrate: $bitrate")
    }

    override fun onDisconnectRtmp() {
        Log.d(TAG, "Disconnected")
    }

    override fun onAuthErrorRtmp() {
        Log.d(TAG, "Auth error")
    }

    override fun onAuthSuccessRtmp() {
        Log.d(TAG, "Auth success")
    }

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        private const val RECORDER_VIDEO_BITRATE: Int = 3_500_000
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L

        /** Creates a [File] named with the current date and time */
        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.filesDir, "VID_${sdf.format(Date())}.$extension")
        }
    }
}
