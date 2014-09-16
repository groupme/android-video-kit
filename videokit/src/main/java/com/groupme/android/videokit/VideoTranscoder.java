package com.groupme.android.videokit;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import com.groupme.android.videokit.support.Component;
import com.groupme.android.videokit.support.InputSurface;
import com.groupme.android.videokit.support.OutputSurface;
import com.groupme.android.videokit.util.LogUtils;
import com.groupme.android.videokit.util.MediaInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class VideoTranscoder {
    private static final String KEY_ROTATION = "rotation";

    /** How long to wait for the next buffer to become available in microseconds. */
    private static final int TIMEOUT_USEC = 40000;

    private final Context mContext;
    private final Uri mSrcUri;

    private String mOutputFilePath;

    private boolean mIncludeAudio = true;

    private Component mInputVideoComponent;
    private Component mInputAudioComponent;

    private int mOutputVideoWidth;
    private int mOutputVideoHeight;
    private int mOrientationHint;

    private int mOutputVideoBitRate;
    private int mOutputVideoFrameRate;
    private int mOutputVideoIFrameInterval;

    private int mOutputAudioBitRate;

    private MediaFormat mOutputVideoFormat;
    private MediaFormat mOutputAudioFormat;

    private MediaCodec mVideoEncoder;
    private MediaCodec mVideoDecoder;

    private InputSurface mInputSurface;
    private OutputSurface mOutputSurface;

    private MediaCodec mAudioEncoder;
    private MediaCodec mAudioDecoder;
    private MediaMuxer mMuxer;

    private Stats mStats;

    private VideoTranscoder(Context context, Uri srcUri) {
        mContext = context;
        mSrcUri = srcUri;
    }

    private boolean shouldIncludeAudio() {
        return mIncludeAudio;
    }

    private void shouldIncludeAudio(boolean copyAudio) {
        mIncludeAudio = copyAudio;
    }

    private void setup() {
        try {
            createComponents();

            calculateOutputDimensions();
            setOrientationHint();

            createOutputFormats();
            createVideoEncoder();
            createVideoDecoder();

            if (shouldIncludeAudio()) {
                createAudioEncoder();
                createAudioDecoder();
            }

            createMuxer();
        } catch (IOException ex) {
            LogUtils.e(ex);
        }
    }

    private void transcode() {
        mStats = new Stats();

        boolean videoEncoderDone = false;
        boolean audioEncoderDone = false;

        boolean videoDecoderDone = false;

        boolean videoExtractorDone = false;
        boolean audioExtractorDone = false;

        boolean muxing = false;

        // We will get these from the decoders when notified of a format change.
        MediaFormat decoderOutputVideoFormat = null;
        MediaFormat decoderOutputAudioFormat = null;
        MediaFormat encoderOutputVideoFormat = null;
        MediaFormat encoderOutputAudioFormat = null;

        // loop until all the encoding is finished
        while (!videoEncoderDone || (shouldIncludeAudio() && !audioEncoderDone)) {

            // Extract video from file and feed to decoder.
            // Do not extract video if we have determined the output format but we are not yet
            // ready to mux the frames.
            if (!videoExtractorDone && (encoderOutputVideoFormat == null || muxing)) {
                videoExtractorDone = extractAndFeedDecoder(mVideoDecoder, mInputVideoComponent);
            }

            // Extract audio from file and feed to decoder.
            // Do not extract audio if we have determined the output format but we are not yet
            // ready to mux the frames.
            if (shouldIncludeAudio() && !audioExtractorDone && (encoderOutputAudioFormat == null || muxing)) {
                audioExtractorDone = extractAndFeedDecoder(mAudioDecoder, mInputAudioComponent);
            }

            // Poll output frames from the video decoder and feed the encoder
            if (!videoDecoderDone && (encoderOutputVideoFormat == null || muxing)) {
                videoDecoderDone = pollVideoFromDecoderAndFeedToEncoder();
            }
        }
    }

//    /**
//     * Extract video and feed to decoder.
//     *
//     * @return Finished. True when it extracts the last frame.
//     */
//    private boolean extractVideoAndFeedToDecoder() {
//        int decoderInputBufferIndex = mVideoDecoder.dequeueInputBuffer(TIMEOUT_USEC);
//        if (decoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
//            LogUtils.d("no video decoder input buffer");
//            return false;
//        }
//
//        LogUtils.d("video decoder: returned input buffer: %d", decoderInputBufferIndex);
//
//        ByteBuffer decoderInputBuffer = mVideoDecoder.getInputBuffers()[decoderInputBufferIndex];
//
//        MediaExtractor videoExtractor = mInputVideoComponent.getMediaExtractor();
//        int size = videoExtractor.readSampleData(decoderInputBuffer, 0);
//        long presentationTime = videoExtractor.getSampleTime();
//
//        LogUtils.d("video extractor: returned buffer of size %d", size);
//        LogUtils.d("video extractor: returned buffer for time %d", presentationTime);
//
//        if (size >= 0) {
//            mVideoDecoder.queueInputBuffer(
//                    decoderInputBufferIndex,
//                    0,
//                    size,
//                    presentationTime,
//                    videoExtractor.getSampleFlags());
//
//            mStats.videoExtractedFrameCount++;
//        }
//
//        if (!videoExtractor.advance()) {
//            LogUtils.d("video extractor: EOS");
//            mVideoDecoder.queueInputBuffer(
//                    decoderInputBufferIndex,
//                    0,
//                    0,
//                    0,
//                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
//            return true;
//        }
//
//        return false;
//    }

    /**
     * Extract and feed to decoder.
     *
     * @return Finished. True when it extracts the last frame.
     */
    private boolean extractAndFeedDecoder(MediaCodec decoder, Component component) {
        String type = component.getType() == Component.COMPONENT_TYPE_VIDEO ? "video" : "audio";

        int decoderInputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
        if (decoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            LogUtils.d("no %s decoder input buffer", type);
            return false;
        }

        LogUtils.d("%s decoder: returned input buffer: %d", type, decoderInputBufferIndex);

        ByteBuffer decoderInputBuffer = decoder.getInputBuffers()[decoderInputBufferIndex];

        MediaExtractor extractor = component.getMediaExtractor();
        int size = extractor.readSampleData(decoderInputBuffer, 0);
        long presentationTime = extractor.getSampleTime();

        LogUtils.d("%s extractor: returned buffer of size %d", type, size);
        LogUtils.d("%s extractor: returned buffer for time %d", type, presentationTime);

        if (size >= 0) {
            decoder.queueInputBuffer(
                    decoderInputBufferIndex,
                    0,
                    size,
                    presentationTime,
                    extractor.getSampleFlags());

            mStats.incrementExtractedFrameCount(component);
        }

        if (!extractor.advance()) {
            LogUtils.d("%s extractor: EOS", type);
            decoder.queueInputBuffer(
                    decoderInputBufferIndex,
                    0,
                    0,
                    0,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return true;
        }

        return false;
    }

    private boolean pollVideoFromDecoderAndFeedToEncoder(MediaCodec decoder) {
        int decoderOutputBufferIndex =
                decoder.dequeueOutputBuffer(
                        videoDecoderOutputBufferInfo, TIMEOUT_USEC);
        if (decoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            Log.d(TAG, "no video decoder output buffer");
            break;
        }
        if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            if (VERBOSE) Log.d(TAG, "video decoder: output buffers changed");
            videoDecoderOutputBuffers = videoDecoder.getOutputBuffers();
            break;
        }
        if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            decoderOutputVideoFormat = videoDecoder.getOutputFormat();
            Log.d(TAG, "video decoder: output format changed: "
                    + decoderOutputVideoFormat);
            break;
        }
        if (VERBOSE) {
            Log.d(TAG, "video decoder: returned output buffer: "
                    + decoderOutputBufferIndex);
            Log.d(TAG, "video decoder: returned buffer of size "
                    + videoDecoderOutputBufferInfo.size);
        }
    }

    /**
     * Returns the first codec capable of encoding the specified MIME type,
     * or null if no match was found.
     *
     * @param mimeType specified MIME type
     * @return
     */
    private MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

            if (!codecInfo.isEncoder()) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    LogUtils.d("Codec %s found for mime type %s", codecInfo.getName(), mimeType);
                    return codecInfo;
                }
            }
        }

        throw new RuntimeException("Unable to find an appropriate codec for " + mimeType);
    }

    private void createComponents() throws IOException {
        mInputVideoComponent = new Component(mContext, mSrcUri, Component.COMPONENT_TYPE_VIDEO);

        if (shouldIncludeAudio()) {
            mInputAudioComponent = new Component(mContext, mSrcUri, Component.COMPONENT_TYPE_AUDIO);
            if (mInputAudioComponent.getSelectedTrackIndex() == Component.NO_TRACK_AVAILABLE) {
                shouldIncludeAudio(false);
            }
        }
    }

    private void calculateOutputDimensions() {
        MediaFormat trackFormat = mInputVideoComponent.getTrackFormat();

        int inputWidth = trackFormat.getInteger(MediaFormat.KEY_WIDTH);
        int inputHeight = trackFormat.getInteger(MediaFormat.KEY_HEIGHT);

        if (inputWidth >= inputHeight) {
            float ratio = Math.min(Defaults.OUTPUT_MAX_WIDTH / inputWidth, Defaults.OUTPUT_MAX_HEIGHT / inputHeight);
            mOutputVideoHeight = (int) (ratio * inputHeight);
            mOutputVideoWidth = (int) (ratio * inputWidth);
        } else {
            float ratio = Math.min(Defaults.OUTPUT_MAX_WIDTH / inputHeight, Defaults.OUTPUT_MAX_HEIGHT / inputWidth);
            mOutputVideoHeight = (int) (ratio * inputWidth);
            mOutputVideoWidth = (int) (ratio * inputHeight);
        }
    }

    private void setOrientationHint() {
        MediaFormat trackFormat = mInputVideoComponent.getTrackFormat();

        if (trackFormat.containsKey(KEY_ROTATION)) {
            mOrientationHint = trackFormat.getInteger(KEY_ROTATION);
        } else {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(mContext, mSrcUri);
            String orientation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (!TextUtils.isEmpty(orientation)) {
                mOrientationHint = Integer.parseInt(orientation);
            }
        }
    }

    private void createOutputFormats() {
        createVideoOutputFormat();

        if (shouldIncludeAudio()) {
            createAudioOutputFormat();
        }
    }

    private void createVideoOutputFormat() {
        mOutputVideoFormat = MediaFormat.createVideoFormat(
                Defaults.OUTPUT_VIDEO_MIME_TYPE, mOutputVideoWidth, mOutputVideoHeight);

        // Set some properties. Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        mOutputVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, mOutputVideoBitRate);
        mOutputVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mOutputVideoFrameRate);
        mOutputVideoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, mOutputVideoIFrameInterval);
        mOutputVideoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
    }

    private void createVideoEncoder() {
        // Create a MediaCodec for the desired codec, then configure it as an encoder with
        // our desired properties. Request a Surface to use for input.
        AtomicReference<Surface> inputSurfaceReference = new AtomicReference<Surface>();

        MediaCodecInfo codecInfo = selectCodec(Defaults.OUTPUT_VIDEO_MIME_TYPE);

        mVideoEncoder = MediaCodec.createByCodecName(codecInfo.getName());
        mVideoEncoder.configure(mOutputVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurfaceReference.set(mVideoEncoder.createInputSurface());
        mVideoEncoder.start();

        mInputSurface = new InputSurface(inputSurfaceReference.get());
        mInputSurface.makeCurrent();
    }

    private void createVideoDecoder() {
        MediaFormat inputFormat = mInputVideoComponent.getTrackFormat();
        mOutputSurface = new OutputSurface();
        mVideoDecoder = MediaCodec.createDecoderByType(MediaInfo.getMimeTypeFor(inputFormat));
        mVideoDecoder.configure(inputFormat, mInputSurface.getSurface(), null, 0);
        mVideoDecoder.start();
    }

    private void createAudioOutputFormat() {
        MediaFormat inputFormat = mInputAudioComponent.getTrackFormat();

        int sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

        mOutputAudioFormat = MediaFormat.createAudioFormat(Defaults.OUTPUT_AUDIO_MIME_TYPE,
                sampleRate, channelCount);

        mOutputAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, mOutputAudioBitRate);
        mOutputAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, Defaults.OUTPUT_AUDIO_AAC_PROFILE);
    }

    private void createAudioEncoder() {
        MediaCodecInfo codecInfo = selectCodec(Defaults.OUTPUT_AUDIO_MIME_TYPE);

        mAudioEncoder = MediaCodec.createByCodecName(codecInfo.getName());
        mAudioEncoder.configure(mOutputAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioEncoder.start();
    }

    private void createAudioDecoder() {
        MediaFormat inputFormat = mInputAudioComponent.getTrackFormat();

        mAudioDecoder = MediaCodec.createDecoderByType(MediaInfo.getMimeTypeFor(inputFormat));
        mAudioEncoder.configure(inputFormat, null, null, 0);
        mAudioDecoder.start();
    }

    private void createMuxer() throws IOException {
        mMuxer = new MediaMuxer(mOutputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        mMuxer.setOrientationHint(mOrientationHint);
    }

    public interface Listener {
        void onSuccess();
        void onFailure();
    }

    public static final class Defaults {
        static final String OUTPUT_VIDEO_MIME_TYPE = "video/avc";       // H.264 Advanced Video Coding
        static final String OUTPUT_AUDIO_MIME_TYPE = "audio/MP4A-LATM"; // Advanced Audio Coding

        static final int OUTPUT_VIDEO_BIT_RATE = 2000 * 1024;       // 2 MBps
        static final int OUTPUT_AUDIO_BIT_RATE = 128 * 1024;        // 128 kbps

        static final int OUTPUT_VIDEO_FRAME_RATE = 30;              // 30fps
        static final int OUTPUT_VIDEO_IFRAME_INTERVAL = 10;         // 10 seconds between I-frames

        static final float OUTPUT_MAX_WIDTH = 1280;
        static final float OUTPUT_MAX_HEIGHT = 720;

        static final int OUTPUT_AUDIO_AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC;
    }

    public static final class Stats {
        int videoExtractedFrameCount;
        int audioExtractedFrameCount;

        void incrementExtractedFrameCount(Component component) {
            if (component.getType() == Component.COMPONENT_TYPE_VIDEO) {
                videoExtractedFrameCount++;
            } else {
                audioExtractedFrameCount++;
            }
        }
    }

    public static final class Builder {
        private final Uri mSrcUri;
        private final Uri mDestUri;

        private boolean mIncludeAudio = true;
        private int mAudioBitRate = Defaults.OUTPUT_AUDIO_BIT_RATE;

        private float mMaxFrameWidth = Defaults.OUTPUT_MAX_WIDTH;
        private float mMaxFrameHeight = Defaults.OUTPUT_MAX_HEIGHT;
        private int mRotation = -1;

        private int mVideoBitRate = Defaults.OUTPUT_VIDEO_BIT_RATE;
        private int mVideoFrameRate = Defaults.OUTPUT_VIDEO_FRAME_RATE;
        private int mVideoIFrameInterval = Defaults.OUTPUT_VIDEO_IFRAME_INTERVAL;

        private long mStartTime = 0;
        private long mEndTime = -1;

        public Builder(Uri srcUri, Uri destUri) {
            if (srcUri == null) {
               throw new NullPointerException("srcUri cannot be null");
            }

            if (destUri == null) {
                throw new NullPointerException("destUri cannot be null");
            }

            mSrcUri = srcUri;
            mDestUri = destUri;
        }

        public Builder includeAudio(boolean includeAudio) {
            mIncludeAudio = includeAudio;
            return this;
        }

        public Builder audioBitRate(int bitRate) {
            mAudioBitRate = bitRate;
            return this;
        }

        public Builder maxFrameWidth(float maxWidth) {
            mMaxFrameWidth = maxWidth;
            return this;
        }

        public Builder maxFrameHeight(float maxHeight) {
            mMaxFrameHeight = maxHeight;
            return this;
        }

        public Builder videoBitRate(int bitRate) {
            mVideoBitRate = bitRate;
            return this;
        }

        public Builder frameRate(int frameRate) {
            mVideoFrameRate = frameRate;
            return this;
        }

        public Builder iFrameInterval(int iFrameInterval) {
            mVideoIFrameInterval = iFrameInterval;
            return this;
        }

        public Builder rotation(int degrees) {
            mRotation = degrees;
            return this;
        }

        public Builder trim(long startTimeMillis, long endTimeMillis) {
            mStartTime = startTimeMillis;
            mEndTime = endTimeMillis;
            return this;
        }

        public VideoTranscoder build() {

            return new VideoTranscoder();
        }
    }
}
