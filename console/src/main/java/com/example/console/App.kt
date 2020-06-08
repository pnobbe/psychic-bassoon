package com.example.console

import com.example.simplertmp.DefaultRtmpPublisher
import com.example.simplertmp.RtmpPublisher
import java.io.File
import java.nio.file.Paths

fun main() {
    val inputStream = File("${Paths.get("").toAbsolutePath()}/console/src/main/java/com/example/console/video.mp4")
        .inputStream()
    inputStream.bufferedReader().forEachLine { println(it) }

    val publisher: RtmpPublisher = DefaultRtmpPublisher(Connecter())
    if (publisher.connect("rtmp://74c2f990fb6744ac953525dcd2fa82a8.channel.media.azure" +
                       ".net:1935/live/6ed11a02141b40788e293b4dad1f397c")) {
        if (publisher.publish("live")) {
            println("Time to send frames")
            while (!Thread.interrupted()) {

//                try {
//                    SrsFlvFrame frame = mFlvAudioTagCache.poll(1, TimeUnit.MILLISECONDS);
//                    if (frame != null) {
//                        if (frame.is_sequenceHeader()) {
//                            mAudioSequenceHeader = frame;
//                        }
//                        sendFlvTag(frame);
//                    }
//
//                    frame = mFlvVideoTagCache.poll(1, TimeUnit.MILLISECONDS);
//                    if (frame != null) {
//                        // video
//                        if (frame.is_sequenceHeader()) {
//                            mVideoSequenceHeader = frame;
//                        }
//                        sendFlvTag(frame);
//                    }
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                }
            }
        }
    }
}
