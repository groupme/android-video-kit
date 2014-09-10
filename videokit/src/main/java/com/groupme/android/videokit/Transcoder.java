/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.groupme.android.videokit;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test for the integration of MediaMuxer and MediaCodec's encoder.
 *
 * <p>It uses MediaExtractor to get frames from a test stream, decodes them to a surface, uses a
 * shader to edit them, encodes them from the resulting surface, and then uses MediaMuxer to write
 * them into a file.
 *
 * <p>It does not currently check whether the result file is correct, but makes sure that nothing
 * fails along the way.
 *
 * <p>It also tests the way the codec config buffers need to be passed from the MediaCodec to the
 * MediaMuxer.
 */
@TargetApi(18)
public class Transcoder {

    private static final String TAG = Transcoder.class.getSimpleName();
    private static final boolean VERBOSE = false; // lots of logging
    private boolean mCopyAudio = true;

    /** How long to wait for the next buffer to become available. */
    private static final int TIMEOUT_USEC = 40000;

    /** Where to output the test files. */
    private static final File OUTPUT_FILENAME_DIR = Environment.getExternalStorageDirectory();

    // parameters for the video encoder
    private static final String OUTPUT_VIDEO_MIME_TYPE = "video/avc"; // H.264 Advanced Video Coding
    private static final int OUTPUT_VIDEO_BIT_RATE = 2000 * 1000; // 2 MBps
    private static final int OUTPUT_VIDEO_FRAME_RATE = 30; // 30fps
    private static final int OUTPUT_VIDEO_IFRAME_INTERVAL = 10; // 10 seconds between I-frames
    private static final float OUTPUT_MAX_WIDTH = 1280;
    private static final float OUTPUT_MAX_HEIGHT = 720;
    private static final String KEY_ROTATION = "rotation";

    // parameters for the audio encoder
    private static final String OUTPUT_AUDIO_MIME_TYPE = "audio/MP4A-LATM"; // Advanced Audio Coding
    private static final int OUTPUT_AUDIO_BIT_RATE = 128 * 1024;
    private static final int OUTPUT_AUDIO_AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC;

    /** The uri used as the input file */
    private Uri mSourceVideoUri;

    /** The destination file for the encoded output. */
    private String mOutputFile;

    private long mStartTime;

    private int mOrientationHint;
    private OnVideoTranscodedListener mListener;

    private final Context mContext;

    public interface OnVideoTranscodedListener {
        public void onVideoTranscoded(String outputFile, double inputFileSize, double outputFileSize, double timeToEncode);
        public void onError();
    }

    private Transcoder(Context context) {
        mContext = context;
    }

    public static Transcoder with(Context context) {
        Transcoder videoDecoder = new Transcoder(context);
        return videoDecoder;
    }

    public Transcoder source(Uri videoUri) {
        mSourceVideoUri = videoUri;
        return this;
    }

    public Transcoder source(MediaInfo mediaInfo) {
        mSourceVideoUri = mediaInfo.getMediaUri();
        return this;
    }

    public Transcoder listener(OnVideoTranscodedListener listener) {
        mListener = listener;
        return this;
    }

    public void start(String outputFile) {
        if (mContext == null) {
            throw new IllegalStateException("Context cannot be null");
        }

        if (mSourceVideoUri == null) {
            throw new IllegalStateException("Source Uri cannot be null. Make sure to call source()");
        }

        mStartTime = System.currentTimeMillis();
        mOutputFile = outputFile;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    extractDecodeEditEncodeMux();
                    notifyListener();
                } catch (Exception e) {
                    e.printStackTrace();
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (mListener != null) {
                                mListener.onError();
                            }
                        }
                    });
                }
            }
        }).start();
    }

    public boolean startSync(String outputFile) {
        if (mContext == null) {
            throw new IllegalStateException("Context cannot be null");
        }

        if (mSourceVideoUri == null) {
            throw new IllegalStateException("Source Uri cannot be null. Make sure to call source()");
        }

        mStartTime = System.currentTimeMillis();
        mOutputFile = outputFile;

        try {
            extractDecodeEditEncodeMux();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static String getDefaultOutputFilePath() {
        return OUTPUT_FILENAME_DIR.getAbsolutePath() + "/output.mp4";
    }

    /**
     * Tests encoding and subsequently decoding video from frames generated into a buffer.
     * <p>
     * We encode several frames of a video test pattern using MediaCodec, then decode the output
     * with MediaCodec and do some simple checks.
     */
    private void extractDecodeEditEncodeMux() throws Exception {
        // Exception that may be thrown during release.
        Exception exception = null;

        MediaCodecInfo videoCodecInfo = selectCodec(OUTPUT_VIDEO_MIME_TYPE);
        if (videoCodecInfo == null) {
            // Don't fail CTS if they don't have an AVC codec (not here, anyway).
            Log.e(TAG, "Unable to find an appropriate codec for " + OUTPUT_VIDEO_MIME_TYPE);
            throw new RuntimeException("Unable to find an appropriate codec for " + OUTPUT_VIDEO_MIME_TYPE);
        }
        if (VERBOSE) Log.d(TAG, "video found codec: " + videoCodecInfo.getName());

        MediaCodecInfo audioCodecInfo = selectCodec(OUTPUT_AUDIO_MIME_TYPE);
        if (audioCodecInfo == null) {
            // Don't fail CTS if they don't have an AAC codec (not here, anyway).
            Log.e(TAG, "Unable to find an appropriate codec for " + OUTPUT_AUDIO_MIME_TYPE);
            throw new RuntimeException("Unable to find an appropriate codec for " + OUTPUT_AUDIO_MIME_TYPE);
        }
        if (VERBOSE) Log.d(TAG, "audio found codec: " + audioCodecInfo.getName());

        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;
        OutputSurface outputSurface = null;
        MediaCodec videoDecoder = null;
        MediaCodec audioDecoder = null;
        MediaCodec videoEncoder = null;
        MediaCodec audioEncoder = null;
        MediaMuxer muxer = null;

        InputSurface inputSurface = null;

        try {

            videoExtractor = createExtractor();
            int videoInputTrack = getAndSelectVideoTrackIndex(videoExtractor);

            if (videoInputTrack == -1) {
                throw new IllegalArgumentException(String.format("No video track found for file: %s", mSourceVideoUri));
            }

            MediaFormat inputFormat = videoExtractor.getTrackFormat(videoInputTrack);

            // We avoid the device-specific limitations on width and height by using values
            // that are multiples of 16, which all tested devices seem to be able to handle.

            int inputWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
            int inputHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);

            int outputHeight;
            int outputWidth;

            if (inputWidth >= inputHeight) {
                float ratio = Math.min(OUTPUT_MAX_WIDTH / inputWidth, OUTPUT_MAX_HEIGHT / inputHeight);
                outputHeight = (int) (ratio * inputHeight);
                outputWidth = (int) (ratio * inputWidth);
            } else {
                float ratio = Math.min(OUTPUT_MAX_WIDTH / inputHeight, OUTPUT_MAX_HEIGHT / inputWidth);
                outputHeight = (int) (ratio * inputWidth);
                outputWidth = (int) (ratio * inputHeight);
            }

            mOrientationHint = inputFormat.containsKey(KEY_ROTATION) ? inputFormat.getInteger(KEY_ROTATION) : 0;

            MediaFormat outputVideoFormat =
                    MediaFormat.createVideoFormat(OUTPUT_VIDEO_MIME_TYPE,
                            outputWidth,
                            outputHeight);

            // Set some properties. Failing to specify some of these can cause the MediaCodec
            // configure() call to throw an unhelpful exception.
            outputVideoFormat.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

            MediaCodecInfo.CodecCapabilities capabilities = videoCodecInfo.getCapabilitiesForType(OUTPUT_VIDEO_MIME_TYPE);

            for (int i = 0; i < capabilities.profileLevels.length; i++) {
                MediaCodecInfo.CodecProfileLevel level = capabilities.profileLevels[i];
                Log.e(TAG, String.format("Using codec profile: %d level: %d ", level.profile, level.level));
            }

            outputVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_VIDEO_BIT_RATE);
            outputVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_VIDEO_FRAME_RATE);
            outputVideoFormat.setInteger(
                    MediaFormat.KEY_I_FRAME_INTERVAL, OUTPUT_VIDEO_IFRAME_INTERVAL);
            if (VERBOSE) Log.d(TAG, "video format: " + outputVideoFormat);

            // Create a MediaCodec for the desired codec, then configure it as an encoder with
            // our desired properties. Request a Surface to use for input.
            AtomicReference<Surface> inputSurfaceReference = new AtomicReference<Surface>();
            videoEncoder = createVideoEncoder(
                    videoCodecInfo, outputVideoFormat, inputSurfaceReference);
            inputSurface = new InputSurface(inputSurfaceReference.get());
            inputSurface.makeCurrent();
            // Create a MediaCodec for the decoder, based on the extractor's format.
            outputSurface = new OutputSurface();
            videoDecoder = createVideoDecoder(inputFormat, outputSurface.getSurface());

            audioExtractor = createExtractor();
            int audioInputTrack = getAndSelectAudioTrackIndex(audioExtractor);

            if (audioInputTrack == -1) {
                mCopyAudio = false;
            } else {
                MediaFormat audioInputFormat = audioExtractor.getTrackFormat(audioInputTrack);

                MediaFormat outputAudioFormat =
                        MediaFormat.createAudioFormat(
                                OUTPUT_AUDIO_MIME_TYPE, audioInputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                audioInputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                outputAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_AUDIO_BIT_RATE);
                outputAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, OUTPUT_AUDIO_AAC_PROFILE);

                // Create a MediaCodec for the desired codec, then configure it as an encoder with
                // our desired properties. Request a Surface to use for input.
                audioEncoder = createAudioEncoder(audioCodecInfo, outputAudioFormat);
                // Create a MediaCodec for the decoder, based on the extractor's format.
                audioDecoder = createAudioDecoder(audioInputFormat);
            }

            // Creates a muxer but do not start or add tracks just yet.
            muxer = createMuxer();

            doExtractDecodeEditEncodeMux(
                    videoExtractor,
                    audioExtractor,
                    videoDecoder,
                    videoEncoder,
                    audioDecoder,
                    audioEncoder,
                    muxer,
                    inputSurface,
                    outputSurface);
        }

        finally {
            if (VERBOSE) Log.d(TAG, "releasing extractor, decoder, encoder, and muxer");
            // Try to release everything we acquired, even if one of the releases fails, in which
            // case we save the first exception we got and re-throw at the end (unless something
            // other exception has already been thrown). This guarantees the first exception thrown
            // is reported as the cause of the error, everything is (attempted) to be released, and
            // all other exceptions appear in the logs.
            try {
                if (videoExtractor != null) {
                    videoExtractor.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing videoExtractor", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (audioExtractor != null) {
                    audioExtractor.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing audioExtractor", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (videoDecoder != null) {
                    videoDecoder.stop();
                    videoDecoder.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing videoDecoder", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (outputSurface != null) {
                    outputSurface.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing outputSurface", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (videoEncoder != null) {
                    videoEncoder.stop();
                    videoEncoder.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing videoEncoder", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (audioDecoder != null) {
                    audioDecoder.stop();
                    audioDecoder.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing audioDecoder", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (audioEncoder != null) {
                    audioEncoder.stop();
                    audioEncoder.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing audioEncoder", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (muxer != null) {
                    muxer.stop();
                    muxer.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing muxer", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (inputSurface != null) {
                    inputSurface.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing inputSurface", e);
                if (exception == null) {
                    exception = e;
                }
            }
        }

        if (exception != null) {
            throw exception;
        }
    }

    private void notifyListener() {
        final double inputFileSize;

        if (mSourceVideoUri.getScheme().equals(ContentResolver.SCHEME_FILE)) {
            inputFileSize = Math.round(new File(mSourceVideoUri.getPath()).length() / 1024. / 1000 * 10) / 10.;
        } else {
            Cursor returnCursor =
                    mContext.getContentResolver().query(mSourceVideoUri, null, null, null, null);
            int sizeIndex = returnCursor.getColumnIndex(OpenableColumns.SIZE);
            returnCursor.moveToFirst();

            inputFileSize = Math.round(returnCursor.getLong(sizeIndex) / 1024. / 1000 * 10) / 10.;
            returnCursor.close();
        }

        final double outputFileSize = Math.round(new File(mOutputFile).length() / 1024. / 1000 * 10) / 10.;
        final double timeToEncode = Math.round(((System.currentTimeMillis() - mStartTime) / 1000.) * 10) / 10.;

        Log.d(TAG, String.format("Input file: %sMB", inputFileSize));
        Log.d(TAG, String.format("Output file: %sMB", outputFileSize));
        Log.d(TAG, String.format("Time to encode: %ss", timeToEncode));

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (mListener != null) {
                    mListener.onVideoTranscoded(mOutputFile, inputFileSize, outputFileSize, timeToEncode);
                }
            }
        });
    }

    /**
     * Creates an extractor that reads its frames from {@link #mSourceVideoUri}
     */
    private MediaExtractor createExtractor() throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(mContext, mSourceVideoUri, null);
        return extractor;
    }

    /**
     * Creates a decoder for the given format, which outputs to the given surface.
     *
     * @param inputFormat the format of the stream to decode
     * @param surface into which to decode the frames
     */
    private MediaCodec createVideoDecoder(MediaFormat inputFormat, Surface surface) {
        MediaCodec decoder = MediaCodec.createDecoderByType(MediaInfo.getMimeTypeFor(inputFormat));
        decoder.configure(inputFormat, surface, null, 0);
        decoder.start();
        return decoder;
    }

    /**
     * Creates an encoder for the given format using the specified codec, taking input from a
     * surface.
     *
     * <p>The surface to use as input is stored in the given reference.
     *
     * @param codecInfo of the codec to use
     * @param format of the stream to be produced
     * @param surfaceReference to store the surface to use as input
     */
    private MediaCodec createVideoEncoder(
            MediaCodecInfo codecInfo,
            MediaFormat format,
            AtomicReference<Surface> surfaceReference) {
        MediaCodec encoder = MediaCodec.createByCodecName(codecInfo.getName());
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // Must be called before start() is.
        surfaceReference.set(encoder.createInputSurface());
        encoder.start();
        return encoder;
    }

    /**
     * Creates a decoder for the given format.
     *
     * @param inputFormat the format of the stream to decode
     */
    private MediaCodec createAudioDecoder(MediaFormat inputFormat) {
        MediaCodec decoder = MediaCodec.createDecoderByType(MediaInfo.getMimeTypeFor(inputFormat));
        decoder.configure(inputFormat, null, null, 0);
        decoder.start();
        return decoder;
    }

    /**
     * Creates an encoder for the given format using the specified codec.
     *
     * @param codecInfo of the codec to use
     * @param format of the stream to be produced
     */
    private MediaCodec createAudioEncoder(MediaCodecInfo codecInfo, MediaFormat format) {
        MediaCodec encoder = MediaCodec.createByCodecName(codecInfo.getName());
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        return encoder;
    }

    /**
     * Creates a muxer to write the encoded frames.
     *
     * <p>The muxer is not started as it needs to be started only after all streams have been added.
     */
    private MediaMuxer createMuxer() throws IOException {
        return new MediaMuxer(mOutputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    private int getAndSelectVideoTrackIndex(MediaExtractor extractor) {
        for (int index = 0; index < extractor.getTrackCount(); ++index) {
            if (VERBOSE) {
                Log.d(TAG, "format for track " + index + " is "
                        + MediaInfo.getMimeTypeFor(extractor.getTrackFormat(index)));
            }
            if (MediaInfo.isVideoFormat(extractor.getTrackFormat(index))) {
                extractor.selectTrack(index);
                return index;
            }
        }
        return -1;
    }

    private int getAndSelectAudioTrackIndex(MediaExtractor extractor) {
        for (int index = 0; index < extractor.getTrackCount(); ++index) {
            if (VERBOSE) {
                Log.d(TAG, "format for track " + index + " is "
                        + MediaInfo.getMimeTypeFor(extractor.getTrackFormat(index)));
            }
            if (MediaInfo.isAudioFormat(extractor.getTrackFormat(index))) {
                extractor.selectTrack(index);
                return index;
            }
        }
        return -1;
    }

    /**
     * Does the actual work for extracting, decoding, encoding and muxing.
     */
    private void doExtractDecodeEditEncodeMux(
            MediaExtractor videoExtractor,
            MediaExtractor audioExtractor,
            MediaCodec videoDecoder,
            MediaCodec videoEncoder,
            MediaCodec audioDecoder,
            MediaCodec audioEncoder,
            MediaMuxer muxer,
            InputSurface inputSurface,
            OutputSurface outputSurface) throws Exception {

        ByteBuffer[] videoDecoderInputBuffers = videoDecoder.getInputBuffers();
        ByteBuffer[] videoDecoderOutputBuffers = videoDecoder.getOutputBuffers();
        ByteBuffer[]videoEncoderOutputBuffers = videoEncoder.getOutputBuffers();
        MediaCodec.BufferInfo videoDecoderOutputBufferInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo videoEncoderOutputBufferInfo = new MediaCodec.BufferInfo();

        ByteBuffer[] audioDecoderInputBuffers = null;
        ByteBuffer[] audioDecoderOutputBuffers =  null;
        ByteBuffer[] audioEncoderInputBuffers = null;
        ByteBuffer[] audioEncoderOutputBuffers = null;
        MediaCodec.BufferInfo audioDecoderOutputBufferInfo = null;
        MediaCodec.BufferInfo audioEncoderOutputBufferInfo = null;

        if (mCopyAudio) {
            audioDecoderInputBuffers = audioDecoder.getInputBuffers();
            audioDecoderOutputBuffers =  audioDecoder.getOutputBuffers();
            audioEncoderInputBuffers = audioEncoder.getInputBuffers();
            audioEncoderOutputBuffers = audioEncoder.getOutputBuffers();
            audioDecoderOutputBufferInfo = new MediaCodec.BufferInfo();
            audioEncoderOutputBufferInfo = new MediaCodec.BufferInfo();
        }

        // We will get these from the decoders when notified of a format change.
        MediaFormat decoderOutputVideoFormat = null;
        MediaFormat decoderOutputAudioFormat = null;
        // We will get these from the encoders when notified of a format change.
        MediaFormat encoderOutputVideoFormat = null;
        MediaFormat encoderOutputAudioFormat = null;
        // We will determine these once we have the output format.
        int outputVideoTrack = -1;
        int outputAudioTrack = -1;
        // Whether things are done on the video side.
        boolean videoExtractorDone = false;
        boolean videoDecoderDone = false;
        boolean videoEncoderDone = false;
        // Whether things are done on the audio side.
        boolean audioExtractorDone = false;
        boolean audioDecoderDone = false;
        boolean audioEncoderDone = false;
        // The audio decoder output buffer to process, -1 if none.
        int pendingAudioDecoderOutputBufferIndex = -1;

        boolean muxing = false;

        int videoExtractedFrameCount = 0;
        int videoDecodedFrameCount = 0;
        int videoEncodedFrameCount = 0;

        int audioExtractedFrameCount = 0;
        int audioDecodedFrameCount = 0;
        int audioEncodedFrameCount = 0;

        long previousPresentationTime = 0L;

        while (!videoEncoderDone || (mCopyAudio && !audioEncoderDone)) {
            Log.d(TAG, String.format(
                    "loop: "

                            + "V {"
                            + "extracted:%d(done:%b) "
                            + "decoded:%d(done:%b) "
                            + "encoded:%d(done:%b)} "

                            + "A {"
                            + "extracted:%d(done:%b) "
                            + "decoded:%d(done:%b) "
                            + "encoded:%d(done:%b) "
                            + "pending:%d} "

                            + "muxing:%b(V:%d,A:%d)",

                    videoExtractedFrameCount, videoExtractorDone,
                    videoDecodedFrameCount, videoDecoderDone,
                    videoEncodedFrameCount, videoEncoderDone,

                    audioExtractedFrameCount, audioExtractorDone,
                    audioDecodedFrameCount, audioDecoderDone,
                    audioEncodedFrameCount, audioEncoderDone,
                    pendingAudioDecoderOutputBufferIndex,

                    muxing, outputVideoTrack, outputAudioTrack
            ));

            // Extract video from file and feed to decoder.
            // Do not extract video if we have determined the output format but we are not yet
            // ready to mux the frames.
            while (!videoExtractorDone && (encoderOutputVideoFormat == null || muxing)) {
                int decoderInputBufferIndex = videoDecoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (decoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    Log.d(TAG, "no video decoder input buffer");
                    break;
                }
                if (VERBOSE) {
                    Log.d(TAG, "video decoder: returned input buffer: " + decoderInputBufferIndex);
                }
                ByteBuffer decoderInputBuffer = videoDecoderInputBuffers[decoderInputBufferIndex];
                int size = videoExtractor.readSampleData(decoderInputBuffer, 0);
                long presentationTime = videoExtractor.getSampleTime();
                if (VERBOSE) {
                    Log.d(TAG, "video extractor: returned buffer of size " + size);
                    Log.d(TAG, "video extractor: returned buffer for time " + presentationTime);
                }
                if (size >= 0) {
                    videoDecoder.queueInputBuffer(
                            decoderInputBufferIndex,
                            0,
                            size,
                            presentationTime,
                            videoExtractor.getSampleFlags());
                }
                videoExtractorDone = !videoExtractor.advance();
                if (videoExtractorDone) {
                    Log.d(TAG, "video extractor: EOS");
                    videoDecoder.queueInputBuffer(
                            decoderInputBufferIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
                videoExtractedFrameCount++;
                // We extracted a frame, let's try something else next.
                break;
            }

            // Extract audio from file and feed to decoder.
            // Do not extract audio if we have determined the output format but we are not yet
            // ready to mux the frames.
            while (mCopyAudio && !audioExtractorDone && (encoderOutputAudioFormat == null || muxing)) {
                int decoderInputBufferIndex = audioDecoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (decoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    Log.d(TAG, "no audio decoder input buffer");
                    break;
                }
                if (VERBOSE) {
                    Log.d(TAG, "audio decoder: returned input buffer: " + decoderInputBufferIndex);
                }
                ByteBuffer decoderInputBuffer = audioDecoderInputBuffers[decoderInputBufferIndex];
                int size = audioExtractor.readSampleData(decoderInputBuffer, 0);
                long presentationTime = audioExtractor.getSampleTime();
                if (VERBOSE) {
                    Log.d(TAG, "audio extractor: returned buffer of size " + size);
                    Log.d(TAG, "audio extractor: returned buffer for time " + presentationTime);
                }
                if (size >= 0) {
                    audioDecoder.queueInputBuffer(
                            decoderInputBufferIndex,
                            0,
                            size,
                            presentationTime,
                            audioExtractor.getSampleFlags());
                }
                audioExtractorDone = !audioExtractor.advance();
                if (audioExtractorDone) {
                    Log.d(TAG, "audio extractor: EOS");
                    audioDecoder.queueInputBuffer(
                            decoderInputBufferIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
                audioExtractedFrameCount++;
                // We extracted a frame, let's try something else next.
                break;
            }

            // Poll output frames from the video decoder and feed the encoder.
            while (!videoDecoderDone && (encoderOutputVideoFormat == null || muxing)) {
                int decoderOutputBufferIndex =
                        videoDecoder.dequeueOutputBuffer(
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
                ByteBuffer decoderOutputBuffer =
                        videoDecoderOutputBuffers[decoderOutputBufferIndex];
                if ((videoDecoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        != 0) {
                    if (VERBOSE) Log.d(TAG, "video decoder: codec config buffer");
                    videoDecoder.releaseOutputBuffer(decoderOutputBufferIndex, false);
                    break;
                }
                if (VERBOSE) {
                    Log.d(TAG, "video decoder: returned buffer for time "
                            + videoDecoderOutputBufferInfo.presentationTimeUs);
                }
                boolean render = videoDecoderOutputBufferInfo.size != 0;
                videoDecoder.releaseOutputBuffer(decoderOutputBufferIndex, render);
                if (render) {
                    if (VERBOSE) Log.d(TAG, "output surface: await new image");
                    outputSurface.awaitNewImage();
                    // Edit the frame and send it to the encoder.
                    if (VERBOSE) Log.d(TAG, "output surface: draw image");
                    outputSurface.drawImage();
                    inputSurface.setPresentationTime(
                            videoDecoderOutputBufferInfo.presentationTimeUs * 1000);
                    if (VERBOSE) Log.d(TAG, "input surface: swap buffers");
                    inputSurface.swapBuffers();
                    if (VERBOSE) Log.d(TAG, "video encoder: notified of new frame");
                }
                if ((videoDecoderOutputBufferInfo.flags
                        & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (VERBOSE) Log.d(TAG, "video decoder: EOS");
                    videoDecoderDone = true;
                    videoEncoder.signalEndOfInputStream();
                }
                videoDecodedFrameCount++;
                // We extracted a pending frame, let's try something else next.
                break;
            }

            // Poll output frames from the audio decoder.
            // Do not poll if we already have a pending buffer to feed to the encoder.
            while (mCopyAudio && !audioDecoderDone && pendingAudioDecoderOutputBufferIndex == -1
                    && (encoderOutputAudioFormat == null || muxing)) {
                int decoderOutputBufferIndex =
                        audioDecoder.dequeueOutputBuffer(
                                audioDecoderOutputBufferInfo, TIMEOUT_USEC);
                if (decoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (VERBOSE) Log.d(TAG, "no audio decoder output buffer");
                    break;
                }
                if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    if (VERBOSE) Log.d(TAG, "audio decoder: output buffers changed");
                    audioDecoderOutputBuffers = audioDecoder.getOutputBuffers();
                    break;
                }
                if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    decoderOutputAudioFormat = audioDecoder.getOutputFormat();
                    Log.d(TAG, "audio decoder: output format changed: "
                            + decoderOutputAudioFormat);
                    break;
                }
                if (VERBOSE) {
                    Log.d(TAG, "audio decoder: returned output buffer: "
                            + decoderOutputBufferIndex);
                }
                if (VERBOSE) {
                    Log.d(TAG, "audio decoder: returned buffer of size "
                            + audioDecoderOutputBufferInfo.size);
                }
                ByteBuffer decoderOutputBuffer =
                        audioDecoderOutputBuffers[decoderOutputBufferIndex];
                if ((audioDecoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        != 0) {
                    if (VERBOSE) Log.d(TAG, "audio decoder: codec config buffer");
                    audioDecoder.releaseOutputBuffer(decoderOutputBufferIndex, false);
                    break;
                }
                if (VERBOSE) {
                    Log.d(TAG, "audio decoder: returned buffer for time "
                            + audioDecoderOutputBufferInfo.presentationTimeUs);
                }
                if (VERBOSE) {
                    Log.d(TAG, "audio decoder: output buffer is now pending: "
                            + pendingAudioDecoderOutputBufferIndex);
                }
                pendingAudioDecoderOutputBufferIndex = decoderOutputBufferIndex;
                audioDecodedFrameCount++;
                // We extracted a pending frame, let's try something else next.
                break;
            }

            // Feed the pending decoded audio buffer to the audio encoder.
            while (mCopyAudio && pendingAudioDecoderOutputBufferIndex != -1) {
                if (VERBOSE) {
                    Log.d(TAG, "audio decoder: attempting to process pending buffer: "
                            + pendingAudioDecoderOutputBufferIndex);
                }
                int encoderInputBufferIndex = audioEncoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (encoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (VERBOSE) Log.d(TAG, "no audio encoder input buffer");
                    break;
                }
                if (VERBOSE) {
                    Log.d(TAG, "audio encoder: returned input buffer: " + encoderInputBufferIndex);
                }
                ByteBuffer encoderInputBuffer = audioEncoderInputBuffers[encoderInputBufferIndex];
                int size = audioDecoderOutputBufferInfo.size;
                long presentationTime = audioDecoderOutputBufferInfo.presentationTimeUs;
                if (VERBOSE) {
                    Log.d(TAG, "audio decoder: processing pending buffer: "
                            + pendingAudioDecoderOutputBufferIndex);
                }
                if (VERBOSE) {
                    Log.d(TAG, "audio decoder: pending buffer of size " + size);
                    Log.d(TAG, "audio decoder: pending buffer for time " + presentationTime);
                }
                if (size >= 0) {
                    ByteBuffer decoderOutputBuffer =
                            audioDecoderOutputBuffers[pendingAudioDecoderOutputBufferIndex]
                                    .duplicate();
                    decoderOutputBuffer.position(audioDecoderOutputBufferInfo.offset);
                    decoderOutputBuffer.limit(audioDecoderOutputBufferInfo.offset + size);
                    encoderInputBuffer.position(0);
                    encoderInputBuffer.put(decoderOutputBuffer);

                    audioEncoder.queueInputBuffer(
                            encoderInputBufferIndex,
                            0,
                            size,
                            presentationTime,
                            audioDecoderOutputBufferInfo.flags);
                }
                audioDecoder.releaseOutputBuffer(pendingAudioDecoderOutputBufferIndex, false);
                pendingAudioDecoderOutputBufferIndex = -1;
                if ((audioDecoderOutputBufferInfo.flags
                        & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (VERBOSE) Log.d(TAG, "audio decoder: EOS");
                    audioDecoderDone = true;
                }
                // We enqueued a pending frame, let's try something else next.
                break;
            }

            // Poll frames from the video encoder and send them to the muxer.
            while (!videoEncoderDone && (encoderOutputVideoFormat == null || muxing)) {
                int encoderOutputBufferIndex = videoEncoder.dequeueOutputBuffer(
                        videoEncoderOutputBufferInfo, TIMEOUT_USEC);
                if (encoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (VERBOSE) Log.d(TAG, "no video encoder output buffer");
                    break;
                }
                if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    if (VERBOSE) Log.d(TAG, "video encoder: output buffers changed");
                    videoEncoderOutputBuffers = videoEncoder.getOutputBuffers();
                    break;
                }
                if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "video encoder: output format changed");
                    if (outputVideoTrack >= 0) {
                        throw new IllegalStateException("Video encoder changed its output format again? What's going on?");
                    }
                    encoderOutputVideoFormat = videoEncoder.getOutputFormat();
                    break;
                }

                if (!muxing) {
                    throw new IllegalStateException("should have added track before processing output");
                }

                if (VERBOSE) {
                    Log.d(TAG, "video encoder: returned output buffer: "
                            + encoderOutputBufferIndex);
                    Log.d(TAG, "video encoder: returned buffer of size "
                            + videoEncoderOutputBufferInfo.size);
                }

                ByteBuffer encoderOutputBuffer =
                        videoEncoderOutputBuffers[encoderOutputBufferIndex];
                if ((videoEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        != 0) {
                    if (VERBOSE) Log.d(TAG, "video encoder: codec config buffer");
                    // Simply ignore codec config buffers.
                    videoEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
                    break;
                }
                if (VERBOSE) {
                    Log.d(TAG, "video encoder: returned buffer for time "
                            + videoEncoderOutputBufferInfo.presentationTimeUs);
                }
                if (videoEncoderOutputBufferInfo.size != 0) {
                    muxer.writeSampleData(
                            outputVideoTrack, encoderOutputBuffer, videoEncoderOutputBufferInfo);
                }
                if ((videoEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        != 0) {
                    if (VERBOSE) Log.d(TAG, "video encoder: EOS");
                    videoEncoderDone = true;
                }
                videoEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
                videoEncodedFrameCount++;
                // We enqueued an encoded frame, let's try something else next.
                break;
            }

            // Poll frames from the audio encoder and send them to the muxer.
            while (mCopyAudio && !audioEncoderDone && (encoderOutputAudioFormat == null || muxing)) {
                int encoderOutputBufferIndex = audioEncoder.dequeueOutputBuffer(
                        audioEncoderOutputBufferInfo, TIMEOUT_USEC);
                if (encoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (VERBOSE) Log.d(TAG, "no audio encoder output buffer");
                    break;
                }
                if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    if (VERBOSE) Log.d(TAG, "audio encoder: output buffers changed");
                    audioEncoderOutputBuffers = audioEncoder.getOutputBuffers();
                    break;
                }
                if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "audio encoder: output format changed");
                    if (outputAudioTrack >= 0) {
                        throw new IllegalStateException("audio encoder changed its output format again?");
                    }

                    encoderOutputAudioFormat = audioEncoder.getOutputFormat();
                    break;
                }

                if (!muxing) {
                    throw new IllegalStateException("should have added track before processing output");
                }

                if (VERBOSE) {
                    Log.d(TAG, "audio encoder: returned output buffer: "
                            + encoderOutputBufferIndex);
                    Log.d(TAG, "audio encoder: returned buffer of size "
                            + audioEncoderOutputBufferInfo.size);
                }
                ByteBuffer encoderOutputBuffer =
                        audioEncoderOutputBuffers[encoderOutputBufferIndex];
                if ((audioEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        != 0) {
                    if (VERBOSE) Log.d(TAG, "audio encoder: codec config buffer");
                    // Simply ignore codec config buffers.
                    audioEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
                    break;
                }
                if (VERBOSE) {
                    Log.d(TAG, "audio encoder: returned buffer for time "
                            + audioEncoderOutputBufferInfo.presentationTimeUs);
                }
                if (audioEncoderOutputBufferInfo.size != 0) {
                    if (audioEncoderOutputBufferInfo.presentationTimeUs < previousPresentationTime) {
                        Log.e(TAG, String.format("presentationTimeUs %s < previousPresentationTime %s", audioEncoderOutputBufferInfo.presentationTimeUs, previousPresentationTime));
                    } else {
                        previousPresentationTime = audioEncoderOutputBufferInfo.presentationTimeUs;
                        muxer.writeSampleData(
                                outputAudioTrack, encoderOutputBuffer, audioEncoderOutputBufferInfo);
                    }
                }
                if ((audioEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        != 0) {
                    if (VERBOSE) Log.d(TAG, "audio encoder: EOS");
                    audioEncoderDone = true;
                }
                audioEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
                audioEncodedFrameCount++;
                // We enqueued an encoded frame, let's try something else next.
                break;
            }

            if (!muxing && (!mCopyAudio || encoderOutputAudioFormat != null) && (encoderOutputVideoFormat != null)) {
                Log.d(TAG, "muxer: adding video track.");
                outputVideoTrack = muxer.addTrack(encoderOutputVideoFormat);


                if (mCopyAudio) {
                    Log.d(TAG, "muxer: adding audio track.");
                    outputAudioTrack = muxer.addTrack(encoderOutputAudioFormat);
                }
                Log.d(TAG, "muxer: starting");
                muxer.setOrientationHint(mOrientationHint);
                muxer.start();
                muxing = true;
            }
        }

        // Basic sanity checks.
        if (videoDecodedFrameCount != videoEncodedFrameCount) {
            throw new IllegalStateException("encoded and decoded video frame counts should match");
        }

        if (videoDecodedFrameCount > videoExtractedFrameCount) {
            throw new IllegalStateException("decoded frame count should be less than extracted frame count");
        }

        if (mCopyAudio) {
            if (pendingAudioDecoderOutputBufferIndex != -1) {
                throw new IllegalStateException("no frame should be pending");
            }

            Log.d(TAG, String.format("audioDecodedFrameCount: %s audioExtractedFrameCount: %s", audioDecodedFrameCount, audioExtractedFrameCount));
        }
    }

    /**
     * Returns the first codec capable of encoding the specified MIME type, or null if no match was
     * found.
     */
    private static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

            if (!codecInfo.isEncoder()) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

}
