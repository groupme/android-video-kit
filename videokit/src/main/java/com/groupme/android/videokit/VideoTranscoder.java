package com.groupme.android.videokit;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Surface;

import com.groupme.android.videokit.support.Component;
import com.groupme.android.videokit.support.InputSurface;
import com.groupme.android.videokit.support.OutputSurface;
import com.groupme.android.videokit.util.DefaultLogger;
import com.groupme.android.videokit.util.DeviceUtils;
import com.groupme.android.videokit.util.Logger;
import com.groupme.android.videokit.util.MediaInfo;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

public class VideoTranscoder {
    public static int TRIM_TIME_END = -1;

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

    private int mOutputAudioBitRate = Defaults.OUTPUT_AUDIO_BIT_RATE;

    private long mTrimStartTime = 0;
    private long mTrimEndTime = TRIM_TIME_END;

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
    private Logger mLogger;
    private int mRetryCount;

    // Buffers
    private ByteBuffer[] mVideoDecoderInputBuffers;
    private ByteBuffer[] mVideoEncoderOutputBuffers;
    private ByteBuffer[] mAudioDecoderInputBuffers;
    private ByteBuffer[] mAudioDecoderOutputBuffers;
    private ByteBuffer[] mAudioEncoderInputBuffers;
    private ByteBuffer[] mAudioEncoderOutputBuffers;

    // Media Formats from codecs
    private MediaFormat mDecoderOutputVideoFormat;
    private MediaFormat mDecoderOutputAudioFormat;
    private MediaFormat mEncoderOutputVideoFormat;
    private MediaFormat mEncoderOutputAudioFormat;

    private int mPendingAudioDecoderOutputBufferIndex = -1;

    private int mOutputVideoTrack = -1;
    private int mOutputAudioTrack = -1;

    private long mPreviousPresentationTime = 0L;

    private long mStartTime;

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

    public void start(final Listener listener) throws IOException {
        if (mContext == null) {
            throw new IllegalStateException("Context cannot be null");
        }

        if (mSrcUri == null) {
            throw new IllegalStateException("Source Uri cannot be null. Make sure to call source()");
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean success = startSync();

                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) {
                            if (success) {
                                listener.onSuccess(mStats);
                            } else {
                                listener.onFailure();
                            }
                        }
                    }
                });
            }
        }).start();
    }

    public boolean startSync() {
        if (mContext == null) {
            throw new IllegalStateException("Context cannot be null");
        }

        if (mSrcUri == null) {
            throw new IllegalStateException("Source Uri cannot be null. Make sure to call source()");
        }

        mStartTime = System.currentTimeMillis();

        boolean setupSuccess = false;
        boolean transcodeSuccess = false;
        boolean cleanupSuccess = false;

        try {
            setup();
            setupSuccess = true;
        } catch (Exception ex) {
            mLogger.e(String.format("Failed while setting up VideoTranscoder: %s", mSrcUri));
            mLogger.e(ex);
        }

        try {
            if (setupSuccess) {
                transcode();
                transcodeSuccess = true;
            }
        } catch (Exception ex) {
            mLogger.e(String.format("Failed while transcoding video: %s", mSrcUri));
            mLogger.e(ex);
        }

        try {
            cleanup();
            cleanupSuccess = true;
        } catch (Exception e) {
            mLogger.e("Failed while cleaning up transcoder");
        }

        return setupSuccess && transcodeSuccess && cleanupSuccess;
    }

    private void setup() throws IOException {
        createComponents();

        setOrientationHint();
        calculateOutputDimensions();

        createOutputFormats();
        createVideoEncoder();
        createVideoDecoder();

        if (shouldIncludeAudio()) {
            createAudioEncoder();
            createAudioDecoder();
        }

        createMuxer();
    }

    private void transcode() {
        mStats = new Stats();

        boolean videoEncoderDone = false;
        boolean audioEncoderDone = false;

        boolean videoDecoderDone = false;
        boolean audioDecoderDone = false;

        boolean videoExtractorDone = false;
        boolean audioExtractorDone = false;

        boolean muxing = false;

        mVideoDecoderInputBuffers = mVideoDecoder.getInputBuffers();
        mVideoEncoderOutputBuffers = mVideoEncoder.getOutputBuffers();

        if (shouldIncludeAudio()) {
            mAudioDecoderInputBuffers = mAudioDecoder.getInputBuffers();
            mAudioDecoderOutputBuffers = mAudioDecoder.getOutputBuffers();
            mAudioEncoderInputBuffers = mAudioEncoder.getInputBuffers();
            mAudioEncoderOutputBuffers = mAudioEncoder.getOutputBuffers();
        }

        MediaCodec.BufferInfo videoDecoderOutputBufferInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo videoEncoderOutputBufferInfo = new MediaCodec.BufferInfo();

        MediaCodec.BufferInfo audioDecoderOutputBufferInfo = null;
        MediaCodec.BufferInfo audioEncoderOutputBufferInfo = null;

        if (shouldIncludeAudio()) {
            audioDecoderOutputBufferInfo = new MediaCodec.BufferInfo();
            audioEncoderOutputBufferInfo = new MediaCodec.BufferInfo();
        }

        if (mTrimStartTime > 0) {
            mInputVideoComponent.getMediaExtractor().seekTo(mTrimStartTime * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            if (shouldIncludeAudio()) {
                mInputAudioComponent.getMediaExtractor().seekTo(mTrimStartTime * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            }
        }

        // loop until all the encoding is finished
        while (!videoEncoderDone || (shouldIncludeAudio() && !audioEncoderDone)) {

            // Extract video from file and feed to decoder.
            // Do not extract video if we have determined the output format but we are not yet
            // ready to mux the frames.
            if (!videoExtractorDone && (mEncoderOutputVideoFormat == null || muxing)) {
                videoExtractorDone = extractAndFeedDecoder(mVideoDecoder, mVideoDecoderInputBuffers, mInputVideoComponent);
            }

            // Extract audio from file and feed to decoder.
            // Do not extract audio if we have determined the output format but we are not yet
            // ready to mux the frames.
            if (shouldIncludeAudio() && !audioExtractorDone && (mEncoderOutputAudioFormat == null || muxing)) {
                audioExtractorDone = extractAndFeedDecoder(mAudioDecoder, mAudioDecoderInputBuffers, mInputAudioComponent);
            }

            // Poll output frames from the video decoder and feed the encoder
            if (!videoDecoderDone && (mEncoderOutputVideoFormat == null || muxing)) {
                videoDecoderDone = pollVideoFromDecoderAndFeedToEncoder(videoDecoderOutputBufferInfo);
            }

            // Poll output frames from the audio decoder.
            if (shouldIncludeAudio() && !audioDecoderDone && mPendingAudioDecoderOutputBufferIndex == -1
                    && (mEncoderOutputAudioFormat == null || muxing)) {
                pollAudioFromDecoder(audioDecoderOutputBufferInfo);
            }

            // Feed the pending audio buffer to the audio encoder
            if (shouldIncludeAudio() && mPendingAudioDecoderOutputBufferIndex != -1) {
                feedPendingAudioBufferToEncoder(audioDecoderOutputBufferInfo);
            }

            // Poll frames from video encoder and send them to the muxer
            if (!videoEncoderDone && (mEncoderOutputVideoFormat == null || muxing)) {
                videoEncoderDone = pollVideoFromEncoderAndFeedToMuxer(videoEncoderOutputBufferInfo);
            }

            // Poll frames from audio encoder and send them to the muxer
            if (shouldIncludeAudio() && !audioEncoderDone && (mEncoderOutputAudioFormat == null || muxing)) {
                audioEncoderDone = pollAudioFromEncoderAndFeedToMuxer(audioEncoderOutputBufferInfo);
            }

            // Setup muxer
            if (!muxing && (!shouldIncludeAudio() || mEncoderOutputAudioFormat != null) && (mEncoderOutputVideoFormat != null)) {
                setupMuxer();
                muxing = true;
            }
        }

        // Basic sanity checks
        sanityChecks();
    }

    /**
     * Performs a basic checks in an attempt to see if the transcode was successful.
     * Will throw an IllegalStateException if any checks fail.
     */
    private void sanityChecks() {
        if (mStats.videoDecodedFrameCount != mStats.videoEncodedFrameCount) {
            throw new IllegalStateException("encoded and decoded video frame counts should match");
        }

        if (mStats.videoDecodedFrameCount > mStats.videoExtractedFrameCount) {
            throw new IllegalStateException("decoded frame count should be less than extracted frame count");
        }

        if (shouldIncludeAudio()) {
            if (mPendingAudioDecoderOutputBufferIndex != -1) {
                throw new IllegalStateException("no frame should be pending");
            }

            mLogger.d(String.format("audioDecodedFrameCount: %s audioExtractedFrameCount: %s",
                    mStats.audioDecodedFrameCount, mStats.audioExtractedFrameCount));
        }
    }

    private void logResults() {
        if (mSrcUri.getScheme().equals(ContentResolver.SCHEME_FILE)) {
            mStats.inputFileSize = Math.round(new File(mSrcUri.getPath()).length() / 1024. / 1000 * 10) / 10.;
        } else {
            Cursor returnCursor =
                    mContext.getContentResolver().query(mSrcUri, null, null, null, null);
            int sizeIndex = returnCursor.getColumnIndex(OpenableColumns.SIZE);
            returnCursor.moveToFirst();

            mStats.inputFileSize = Math.round(returnCursor.getLong(sizeIndex) / 1024. / 1000 * 10) / 10.;
            returnCursor.close();
        }

        mStats.outputFileSize = Math.round(new File(mOutputFilePath).length() / 1024. / 1000 * 10) / 10.;
        mStats.timeToTranscode = Math.round(((System.currentTimeMillis() - mStartTime) / 1000.) * 10) / 10.;

        mLogger.w(String.format("Input file: %sMB", mStats.inputFileSize));
        mLogger.w(String.format("Output file: %sMB", mStats.outputFileSize));
        mLogger.w(String.format("Time to encode: %ss", mStats.timeToTranscode));
    }

    private void cleanup() throws Exception {
        mLogger.i("releasing extractor, decoder, encoder, and muxer");
        // Try to release everything we acquired, even if one of the releases fails, in which
        // case we save the first exception we got and re-throw at the end (unless something
        // other exception has already been thrown). This guarantees the first exception thrown
        // is reported as the cause of the error, everything is (attempted) to be released, and
        // all other exceptions appear in the logs.
        Exception exception = null;

        try {
            if (mInputVideoComponent != null) {
                mInputVideoComponent.release();
            }
        } catch(Exception e) {
            mLogger.e("error while releasing videoExtractor");
            mLogger.e(e);
            exception = e;
        }
        try {
            if (mInputAudioComponent != null) {
                mInputAudioComponent.release();
            }
        } catch(Exception e) {
            mLogger.e("error while releasing audioExtractor");
            mLogger.e(e);
            if (exception == null) {
                exception = e;
            }
        }
        try {
            if (mVideoDecoder != null) {
                mVideoDecoder.stop();
                mVideoDecoder.release();
            }
        } catch(Exception e) {
            mLogger.e("error while releasing videoDecoder");
            mLogger.e(e);
            if (exception == null) {
                exception = e;
            }
        }
        try {
            if (mOutputSurface != null) {
                mOutputSurface.release();
            }
        } catch(Exception e) {
            mLogger.e("error while releasing outputSurface");
            mLogger.e(e);
            if (exception == null) {
                exception = e;
            }
        }
        try {
            if (mVideoEncoder != null) {
                mVideoEncoder.stop();
                mVideoEncoder.release();
            }
        } catch(Exception e) {
            mLogger.e("error while releasing videoEncoder");
            mLogger.e(e);
            if (exception == null) {
                exception = e;
            }
        }
        try {
            if (mAudioDecoder != null) {
                mAudioDecoder.stop();
                mAudioDecoder.release();
            }
        } catch(Exception e) {
            mLogger.e("error while releasing audioDecoder");
            mLogger.e(e);
            if (exception == null) {
                exception = e;
            }
        }
        try {
            if (mAudioEncoder != null) {
                mAudioEncoder.stop();
                mAudioEncoder.release();
            }
        } catch(Exception e) {
            mLogger.e("error while releasing audioEncoder");
            mLogger.e(e);
            if (exception == null) {
                exception = e;
            }
        }
        try {
            if (mMuxer != null) {
                mMuxer.stop();
                mMuxer.release();
            }
        } catch(Exception e) {
            mLogger.e("error while releasing muxer");
            mLogger.e(e);
            if (exception == null) {
                exception = e;
            }
        }
        try {
            if (mInputSurface != null) {
                mInputSurface.release();
            }
        } catch(Exception e) {
            mLogger.e("error while releasing inputSurface");
            mLogger.e(e);
            if (exception == null) {
                exception = e;
            }
        }

        if (exception != null) {
            throw exception;
        }

        logResults();
    }

    /**
     * Extract and feed to decoder.
     *
     * @return Finished. True when it extracts the last frame.
     */
    private boolean extractAndFeedDecoder(MediaCodec decoder, ByteBuffer[] buffers, Component component) {
        String type = component.getType() == Component.COMPONENT_TYPE_VIDEO ? "video" : "audio";

        int decoderInputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
        if (decoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            mLogger.d(String.format("no %s decoder input buffer", type));
            return false;
        }

        mLogger.d(String.format("%s decoder: returned input buffer: %d", type, decoderInputBufferIndex));

        MediaExtractor extractor = component.getMediaExtractor();
        int size = extractor.readSampleData(buffers[decoderInputBufferIndex], 0);
        long presentationTime = extractor.getSampleTime();

        mLogger.d(String.format("%s extractor: returned buffer of size %d", type, size));
        mLogger.d(String.format("%s extractor: returned buffer for time %d", type, presentationTime));

        if (mTrimEndTime > 0 && presentationTime > (mTrimEndTime * 1000)) {
            mLogger.d("The current sample is over the trim time. Lets stop.");
            decoder.queueInputBuffer(
                    decoderInputBufferIndex,
                    0,
                    0,
                    0,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return true;
        }

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
            mLogger.d(String.format("%s extractor: EOS", type));
            try {
                decoder.queueInputBuffer(
                        decoderInputBufferIndex,
                        0,
                        0,
                        0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            } catch (Exception e) {
                mRetryCount++;
                if (mRetryCount < 5) {
                    this.extractAndFeedDecoder(decoder, buffers, component);
                } else {
                    mRetryCount = 0;
                    throw e;
                }
            }
            return true;
        }

        return false;
    }

    /**
     * Extract frame for decoder and feed to encoder.
     * @param videoDecoderOutputBufferInfo
     * @return
     */
    private boolean pollVideoFromDecoderAndFeedToEncoder(MediaCodec.BufferInfo videoDecoderOutputBufferInfo) {
        int decoderOutputBufferIndex = mVideoDecoder.dequeueOutputBuffer(videoDecoderOutputBufferInfo, TIMEOUT_USEC);

        if (decoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            mLogger.d("no video decoder output buffer");
            return false;
        }

        if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            mLogger.d("video decoder: output buffers changed");
            return false;
        }

        if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            mDecoderOutputVideoFormat = mVideoDecoder.getOutputFormat();
            mLogger.d(String.format("video decoder: output format changed: %s", mDecoderOutputVideoFormat));
            return false;
        }

        if ((videoDecoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            mLogger.d("video decoder: codec config buffer");
            mVideoDecoder.releaseOutputBuffer(decoderOutputBufferIndex, false);
            return false;
        }

        mLogger.d(String.format("video decoder: returned output buffer: %s", decoderOutputBufferIndex));
        mLogger.d(String.format("video decoder: returned buffer of size %s", videoDecoderOutputBufferInfo.size));
        mLogger.d(String.format("video decoder: returned buffer for time %d", videoDecoderOutputBufferInfo.presentationTimeUs));

        boolean render = videoDecoderOutputBufferInfo.size != 0;

        mVideoDecoder.releaseOutputBuffer(decoderOutputBufferIndex, render);

        if (render) {
            mOutputSurface.awaitNewImage();
            mOutputSurface.drawImage();
            mInputSurface.setPresentationTime(videoDecoderOutputBufferInfo.presentationTimeUs * 1000);
            mInputSurface.swapBuffers();
            mLogger.d("video encoder: notified of new frame");
        }

        if ((videoDecoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            mLogger.d("video decoder: EOS");

            mVideoEncoder.signalEndOfInputStream();
            return true;
        }

        mStats.videoDecodedFrameCount++;

        return false;
    }

    /**
     *
     * @param audioDecoderOutputBufferInfo
     */
    private void pollAudioFromDecoder(MediaCodec.BufferInfo audioDecoderOutputBufferInfo) {
        int decoderOutputBufferIndex = mAudioDecoder.dequeueOutputBuffer(audioDecoderOutputBufferInfo, TIMEOUT_USEC);

        if (decoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            mLogger.d("no audio decoder output buffer");
            return;
        }

        if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            mLogger.d("audio decoder: output buffers changed");
            mAudioDecoderOutputBuffers = mAudioDecoder.getOutputBuffers();
            return;
        }

        if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            mDecoderOutputAudioFormat = mAudioDecoder.getOutputFormat();
            mLogger.d(String.format("audio decoder: output format changed: %s", mDecoderOutputAudioFormat));
            return;
        }

        mLogger.d(String.format("audio decoder: returned output buffer: %d", decoderOutputBufferIndex));
        mLogger.d(String.format("audio decoder: returned buffer of size %d", audioDecoderOutputBufferInfo.size));

        if ((audioDecoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            mLogger.d("audio decoder: codec config buffer");
            mAudioDecoder.releaseOutputBuffer(decoderOutputBufferIndex, false);
            return;
        }

        mLogger.d(String.format("audio decoder: returned buffer for time %s", audioDecoderOutputBufferInfo.presentationTimeUs));
        mLogger.d(String.format("audio decoder: output buffer is now pending: %s", mPendingAudioDecoderOutputBufferIndex));

        mPendingAudioDecoderOutputBufferIndex = decoderOutputBufferIndex;
        mStats.audioDecodedFrameCount++;
    }

    /**
     *
     * @param audioDecoderOutputBufferInfo
     * @return
     */
    private boolean feedPendingAudioBufferToEncoder(MediaCodec.BufferInfo audioDecoderOutputBufferInfo) {
        mLogger.d(String.format("audio decoder: attempting to process pending buffer: %d", mPendingAudioDecoderOutputBufferIndex));

        int encoderInputBufferIndex = mAudioEncoder.dequeueInputBuffer(TIMEOUT_USEC);

        if (encoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            mLogger.d("no audio encoder input buffer");
            return false;
        }

        mLogger.d(String.format("audio encoder: returned input buffer: %d", encoderInputBufferIndex));

        ByteBuffer encoderInputBuffer = mAudioEncoderInputBuffers[encoderInputBufferIndex];

        int size = audioDecoderOutputBufferInfo.size;
        long presentationTime = audioDecoderOutputBufferInfo.presentationTimeUs;

        mLogger.d(String.format("audio decoder: processing pending buffer: %d", mPendingAudioDecoderOutputBufferIndex));
        mLogger.d(String.format("audio decoder: pending buffer of size %s", size));
        mLogger.d(String.format("audio decoder: pending buffer for time %s", presentationTime));

        if (size >= 0) {
            ByteBuffer decoderOutputBuffer = mAudioDecoderOutputBuffers[mPendingAudioDecoderOutputBufferIndex].duplicate();
            decoderOutputBuffer.position(audioDecoderOutputBufferInfo.offset);
            decoderOutputBuffer.limit(audioDecoderOutputBufferInfo.offset + size);
            encoderInputBuffer.position(0);
            encoderInputBuffer.put(decoderOutputBuffer);

            mAudioEncoder.queueInputBuffer(
                    encoderInputBufferIndex,
                    0,
                    size,
                    presentationTime,
                    audioDecoderOutputBufferInfo.flags);
        }

        mAudioDecoder.releaseOutputBuffer(mPendingAudioDecoderOutputBufferIndex, false);
        mPendingAudioDecoderOutputBufferIndex = -1;

        if ((audioDecoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            mLogger.d("audio decoder: EOS");
            return true;
        }

        return false;
    }

    /**
     *
     * @param videoEncoderOutputBufferInfo
     * @return
     */
    private boolean pollVideoFromEncoderAndFeedToMuxer(MediaCodec.BufferInfo videoEncoderOutputBufferInfo) {
        int encoderOutputBufferIndex = mVideoEncoder.dequeueOutputBuffer(videoEncoderOutputBufferInfo, TIMEOUT_USEC);

        if (encoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            mLogger.d("no video encoder output buffer");
            return false;
        }

        if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            mLogger.d("video encoder: output buffers changed");
            mVideoEncoderOutputBuffers = mVideoEncoder.getOutputBuffers();
            return false;
        }

        if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            mLogger.d("video encoder: output format changed");
            if (mOutputVideoTrack >= 0) {
                throw new IllegalStateException("Video encoder changed its output format again? What's going on?");
            }
            mEncoderOutputVideoFormat = mVideoEncoder.getOutputFormat();
            return false;
        }

        if ((videoEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            mLogger.d("video encoder: codec config buffer");
            // Simply ignore codec config buffers.
            mVideoEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
            return false;
        }

//        TODO: is this needed?
//        if (!mMuxing) {
//            throw new IllegalStateException("should have added track before processing output");
//        }

        mLogger.d(String.format("video encoder: returned output buffer: %d", encoderOutputBufferIndex));
        mLogger.d(String.format("video encoder: returned buffer of size %d", videoEncoderOutputBufferInfo.size));
        mLogger.d(String.format("video encoder: returned buffer for time %d", videoEncoderOutputBufferInfo.presentationTimeUs));

        ByteBuffer encoderOutputBuffer = mVideoEncoderOutputBuffers[encoderOutputBufferIndex];
        if (videoEncoderOutputBufferInfo.size != 0) {
            mMuxer.writeSampleData(mOutputVideoTrack, encoderOutputBuffer, videoEncoderOutputBufferInfo);
        }

        mVideoEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false);

        if ((videoEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            mLogger.d("video encoder: EOS");
            return true;
        }

        mStats.videoEncodedFrameCount++;

        return false;
    }

    private boolean pollAudioFromEncoderAndFeedToMuxer(MediaCodec.BufferInfo audioEncoderOutputBufferInfo) {
        int encoderOutputBufferIndex = mAudioEncoder.dequeueOutputBuffer(audioEncoderOutputBufferInfo, TIMEOUT_USEC);

        if (encoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            mLogger.d("no audio encoder output buffer");
            return false;
        }

        if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            mLogger.d("audio encoder: output buffers changed");
            mAudioEncoderOutputBuffers = mAudioEncoder.getOutputBuffers();
            return false;
        }

        if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            mLogger.d("audio encoder: output format changed");
            if (mOutputAudioTrack >= 0) {
                throw new IllegalStateException("audio encoder changed its output format again?");
            }

            mEncoderOutputAudioFormat = mAudioEncoder.getOutputFormat();
            return false;
        }

        if ((audioEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            mLogger.d("audio encoder: codec config buffer");
            // Simply ignore codec config buffers.
            mAudioEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
            return false;
        }

//        TODO: is this needed?
//        if (!muxing) {
//            throw new IllegalStateException("should have added track before processing output");
//        }

        mLogger.d(String.format("audio encoder: returned output buffer: %d", encoderOutputBufferIndex));
        mLogger.d(String.format("audio encoder: returned buffer of size %d", audioEncoderOutputBufferInfo.size));
        mLogger.d(String.format("audio encoder: returned buffer for time %d", audioEncoderOutputBufferInfo.presentationTimeUs));

        if (audioEncoderOutputBufferInfo.size != 0) {
            ByteBuffer encoderOutputBuffer = mAudioEncoderOutputBuffers[encoderOutputBufferIndex];

            if (audioEncoderOutputBufferInfo.presentationTimeUs >= mPreviousPresentationTime) {
                mPreviousPresentationTime = audioEncoderOutputBufferInfo.presentationTimeUs;
                mMuxer.writeSampleData(mOutputAudioTrack, encoderOutputBuffer, audioEncoderOutputBufferInfo);
            } else {
                mLogger.d(String.format("presentationTimeUs %s < previousPresentationTime %s",
                        audioEncoderOutputBufferInfo.presentationTimeUs, mPreviousPresentationTime));
            }
        }

        mAudioEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false);

        if ((audioEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            mLogger.d("audio encoder: EOS");
            return true;
        }

        mStats.audioEncodedFrameCount++;

        return false;
    }

    private void setupMuxer() {
        mLogger.d("muxer: adding video track.");
        mOutputVideoTrack = mMuxer.addTrack(mEncoderOutputVideoFormat);

        if (shouldIncludeAudio()) {
            mLogger.d("muxer: adding audio track.");
            mOutputAudioTrack = mMuxer.addTrack(mEncoderOutputAudioFormat);
        }

        mLogger.d("muxer: starting");
        mMuxer.setOrientationHint(mOrientationHint);
        mMuxer.start();
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
                    mLogger.d(String.format("Codec %s found for mime type %s", codecInfo.getName(), mimeType));
                    return codecInfo;
                }
            }
        }

        throw new RuntimeException("Unable to find an appropriate codec for " + mimeType);
    }

    private void createComponents() throws IOException {
        mInputVideoComponent = new Component(mContext, mSrcUri, Component.COMPONENT_TYPE_VIDEO);

        MediaFormat inputFormat = mInputVideoComponent.getTrackFormat();
        if (inputFormat.containsKey("rotation-degrees")) {
            // Decoded video is rotated automatically in Android 5.0 lollipop.
            // Turn off here because we don't want to encode rotated one.
            // refer: https://android.googlesource.com/platform/frameworks/av/+blame/lollipop-release/media/libstagefright/Utils.cpp
            inputFormat.setInteger("rotation-degrees", 0);
        }

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

        // If this is a portrait video taken by a device that supports orientation hints, the resolution will be swapped.
        // If its landscape, a screencap, or a device that doesn't support hints, it won't be.
        if (inputWidth >= inputHeight || mOrientationHint == 0 || mOrientationHint == 180) {
            mOutputVideoHeight = inputHeight;
            mOutputVideoWidth = inputWidth;

            if (inputWidth > Defaults.OUTPUT_MAX_WIDTH || inputHeight > Defaults.OUTPUT_MAX_HEIGHT) {
                float ratio = Math.min(Defaults.OUTPUT_MAX_WIDTH / (float) inputWidth, Defaults.OUTPUT_MAX_HEIGHT / (float) inputHeight);
                mOutputVideoHeight = getRoundedSize(ratio, inputHeight);
                mOutputVideoWidth = getRoundedSize(ratio, inputWidth);
            }

        } else {
            mOutputVideoHeight = inputWidth;
            mOutputVideoWidth = inputHeight;

            if (inputHeight > Defaults.OUTPUT_MAX_WIDTH || inputWidth > Defaults.OUTPUT_MAX_HEIGHT) {
                float ratio = Math.min(Defaults.OUTPUT_MAX_WIDTH / (float) inputHeight, Defaults.OUTPUT_MAX_HEIGHT / (float) inputWidth);
                mOutputVideoHeight = getRoundedSize(ratio, inputWidth);
                mOutputVideoWidth = getRoundedSize(ratio, inputHeight);
            }
        }
    }

    private int getRoundedSize(float ratio, int size) {
        // The transcoder can fail if the resolution isn't a multiple of 2. So, round it if not.
        int adjusted = (int) (ratio * size);
        return Math.round(adjusted / 4) * 2;
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
        mOutputVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, getOutputVideoBitRate());
        mOutputVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mOutputVideoFrameRate);
        mOutputVideoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, mOutputVideoIFrameInterval);
        mOutputVideoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
    }

    private void createVideoEncoder() throws IOException {
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

        mOutputSurface = new OutputSurface();
    }

    private void createVideoDecoder() throws IOException {
        MediaFormat inputFormat = mInputVideoComponent.getTrackFormat();
        mVideoDecoder = MediaCodec.createDecoderByType(MediaInfo.getMimeTypeFor(inputFormat));
        mVideoDecoder.configure(inputFormat, mOutputSurface.getSurface(), null, 0);
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

    private void createAudioEncoder() throws IOException {
        MediaCodecInfo codecInfo = selectCodec(Defaults.OUTPUT_AUDIO_MIME_TYPE);

        mAudioEncoder = MediaCodec.createByCodecName(codecInfo.getName());
        mAudioEncoder.configure(mOutputAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioEncoder.start();
    }

    private void createAudioDecoder() throws IOException {
        MediaFormat inputFormat = mInputAudioComponent.getTrackFormat();

        mAudioDecoder = MediaCodec.createDecoderByType(MediaInfo.getMimeTypeFor(inputFormat));
        mAudioDecoder.configure(inputFormat, null, null, 0);
        mAudioDecoder.start();
    }

    private void createMuxer() throws IOException {
        mMuxer = new MediaMuxer(mOutputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        mMuxer.setOrientationHint(mOrientationHint);
    }

    private int getOutputVideoBitRate() {
        int inputBitRate = mOutputVideoBitRate;

        if (mInputVideoComponent.getTrackFormat().containsKey(MediaFormat.KEY_BIT_RATE)) {
            inputBitRate = mInputVideoComponent.getTrackFormat().getInteger(MediaFormat.KEY_BIT_RATE);
        } else {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(mContext, mSrcUri);

            String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);

            if (bitrate != null) {
                inputBitRate = Integer.parseInt(bitrate);
            }
        }

        if (DeviceUtils.deviceisOnBlacklist()) {
            return mOutputVideoBitRate;
        } else {
            return Math.min(inputBitRate, mOutputVideoBitRate);
        }
    }

    public interface Listener {
        void onSuccess(Stats stats);
        void onFailure();
    }

    public static final class Defaults {
        static final String OUTPUT_VIDEO_MIME_TYPE = "video/avc";       // H.264 Advanced Video Coding
        static final String OUTPUT_AUDIO_MIME_TYPE = "audio/MP4A-LATM"; // Advanced Audio Coding

        static final int OUTPUT_VIDEO_BIT_RATE = 5000 * 1024;       // 2 MBps
        static final int OUTPUT_AUDIO_BIT_RATE = 128 * 1024;        // 128 kbps

        static final int OUTPUT_VIDEO_FRAME_RATE = 30;              // 30fps
        static final int OUTPUT_VIDEO_IFRAME_INTERVAL = 10;         // 10 seconds between I-frames

        static final int OUTPUT_MAX_WIDTH = 1920;
        static final int OUTPUT_MAX_HEIGHT = 1920;

        static final int OUTPUT_AUDIO_AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC;
    }

    public static final class Stats {
        public int videoExtractedFrameCount;
        public int audioExtractedFrameCount;

        public int videoDecodedFrameCount;
        public int audioDecodedFrameCount;
        public int videoEncodedFrameCount;
        public int audioEncodedFrameCount;

        public double timeToTranscode;
        public double inputFileSize;
        public double outputFileSize;

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
        private final File mDestFile;

        private boolean mIncludeAudio = true;

        private int mMaxFrameWidth = Defaults.OUTPUT_MAX_WIDTH;
        private int mMaxFrameHeight = Defaults.OUTPUT_MAX_HEIGHT;

        private int mVideoBitRate = Defaults.OUTPUT_VIDEO_BIT_RATE;
        private int mVideoFrameRate = Defaults.OUTPUT_VIDEO_FRAME_RATE;
        private int mVideoIFrameInterval = Defaults.OUTPUT_VIDEO_IFRAME_INTERVAL;

        private long mStartTime = 0;
        private long mEndTime = TRIM_TIME_END;

        private Logger mLogger;

        public Builder(Uri srcUri, File destFile) {
            if (srcUri == null) {
                throw new NullPointerException("srcUri cannot be null");
            }

            if (destFile == null) {
                throw new NullPointerException("destUri cannot be null");
            }

            mSrcUri = srcUri;
            mDestFile = destFile;
        }

        public Builder includeAudio(boolean includeAudio) {
            mIncludeAudio = includeAudio;
            return this;
        }

        public Builder maxFrameWidth(int maxWidth) {
            mMaxFrameWidth = maxWidth;
            return this;
        }

        public Builder maxFrameHeight(int maxHeight) {
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

        public Builder trim(long startTimeMillis, long endTimeMillis) {
            mStartTime = startTimeMillis;
            mEndTime = endTimeMillis;
            return this;
        }

        public Builder logger(Logger logger) {
            mLogger = logger;
            return this;
        }

        public VideoTranscoder build(Context context) {
            VideoTranscoder transcoder = new VideoTranscoder(context, mSrcUri);
            transcoder.mIncludeAudio = mIncludeAudio;
            transcoder.mOutputVideoWidth = mMaxFrameWidth;
            transcoder.mOutputVideoHeight = mMaxFrameHeight;
            transcoder.mOutputVideoBitRate = mVideoBitRate;
            transcoder.mOutputVideoFrameRate = mVideoFrameRate;
            transcoder.mOutputVideoIFrameInterval = mVideoIFrameInterval;
            transcoder.mOutputFilePath = mDestFile.getAbsolutePath();

            if (mStartTime > 0) {
                transcoder.mTrimStartTime = mStartTime;
            }

            if (mEndTime != -1) {
                transcoder.mTrimEndTime = mEndTime;
            }

            if (mLogger == null) {
                transcoder.mLogger = new DefaultLogger();
            } else {
                transcoder.mLogger = mLogger;
            }

            return transcoder;
        }
    }
}
