package com.groupme.android.videokit.samples;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.groupme.android.videokit.MediaInfo;
import com.groupme.android.videokit.Transcoder;
import com.groupme.android.videokit.InputSurface;
import com.groupme.android.videokit.TrimmerActivity;

import android.net.Uri;
import android.opengl.GLES20;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;


public class MainActivity extends Activity implements Transcoder.OnVideoTranscodedListener {
    private static final int REQUEST_PICK_VIDEO = 0;
    private static final int REQUEST_PICK_VIDEO_FOR_TRIM = 1;
    private static final int REQUEST_TRIM_VIDEO = 2;

    // parameters for the video encoder
    private static final String OUTPUT_VIDEO_MIME_TYPE = "video/avc"; // H.264 Advanced Video Coding
    private static final int OUTPUT_VIDEO_BIT_RATE = 2000000; // 2Mbps
    private static final int OUTPUT_VIDEO_FRAME_RATE = 15; // 15fps
    private static final int OUTPUT_VIDEO_IFRAME_INTERVAL = 10; // 10 seconds between I-frames
    private static final int OUTPUT_VIDEO_COLOR_FORMAT =
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
    // parameters for the audio encoder
    private static final String OUTPUT_AUDIO_MIME_TYPE = "audio/mp4a-latm"; // Advanced Audio Coding
    private static final int OUTPUT_AUDIO_CHANNEL_COUNT = 2; // Must match the input stream.
    private static final int OUTPUT_AUDIO_BIT_RATE = 128 * 1024;
    private static final int OUTPUT_AUDIO_AAC_PROFILE =
            MediaCodecInfo.CodecProfileLevel.AACObjectHE;
    private static final int OUTPUT_AUDIO_SAMPLE_RATE_HZ = 44100; // Must match the input stream.
    private static final long TIMEOUT_USEC = 10000;

    private ProgressDialog mProgressDialog;
    private TextView mInputFileSize;
    private TextView mOutputFileSize;
    private TextView mTimeToEncode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button testVideoEncode = (Button) findViewById(R.id.btn_encode_video);
        testVideoEncode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("video/*");
                startActivityForResult(intent, REQUEST_PICK_VIDEO);
            }
        });

        Button testVideoTrim = (Button) findViewById(R.id.btn_trim_video);
        testVideoTrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("video/*");
                startActivityForResult(intent, REQUEST_PICK_VIDEO_FOR_TRIM);
            }
        });

        mInputFileSize = (TextView) findViewById(R.id.input_file_size);
        mOutputFileSize = (TextView) findViewById(R.id.output_file_size);
        mTimeToEncode = (TextView) findViewById(R.id.time_to_encode);
    }

    private void encodeVideo(final Uri videoUri) throws IOException {
        MediaInfo mediaInfo = new MediaInfo(this, videoUri);

        if (mediaInfo.hasVideoTrack()) {
            Transcoder.with(this)
                    .source(mediaInfo)
                    .listener(this)
                    .start(Transcoder.getDefaultOutputFilePath());

            mProgressDialog = new ProgressDialog(this);
            mProgressDialog.setIndeterminate(true);
            mProgressDialog.setCancelable(false);
            mProgressDialog.setMessage(String.format("Encoding Video.. (%d secs)", mediaInfo.getDuration()));
            mProgressDialog.show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_PICK_VIDEO:
                try {
                    encodeVideo(data.getData());
                } catch (IOException e) {
                    e.printStackTrace();
                }
                break;
            case REQUEST_PICK_VIDEO_FOR_TRIM:
                if (resultCode == Activity.RESULT_OK) {
                    Intent i = new Intent(this, TrimmerActivity.class);
                    i.setData(data.getData());
                    startActivityForResult(i, REQUEST_TRIM_VIDEO);
                }
                break;
            case REQUEST_TRIM_VIDEO:
                if (data != null) {
                    Log.d("TRIM", String.format("Start: %s End: %s", data.getIntExtra(TrimmerActivity.START_TIME, -1), data.getIntExtra(TrimmerActivity.END_TIME, -1)));
                }
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setupComponents(Uri uri) throws IOException {
        MediaExtractor videoExtractor = createExtractor(uri);
        MediaExtractor audioExtractor = createExtractor(uri);

        int videoTrackIndex = getAndSelectVideoTrackIndex(videoExtractor);
        int audioTrackIndex = getAndSelectAudioTrackIndex(audioExtractor);

        if (videoTrackIndex == -1) {
            //TODO: take care of this error
            return;
        }

        if (audioTrackIndex == -1) {
            //TODO: take care of this error
            return;
        }

        MediaFormat videoInputFormat = videoExtractor.getTrackFormat(videoTrackIndex);
        MediaFormat audioInputFormat = audioExtractor.getTrackFormat(audioTrackIndex);

        MediaFormat videoOutputFormat = createOutputVideoFormat();
        MediaFormat audioOutputFormat = createOutputAudioFormat();

        MediaCodec videoDecoder = createDecoder(videoInputFormat);
//        MediaCodec audioDecoder = createDecoder(audioInputFormat);
        MediaCodec audioDecoder = null;

        AtomicReference<Surface> surfaceAtomicReference = new AtomicReference<Surface>();
        MediaCodec videoEncoder = createEncoder(selectCodec(OUTPUT_VIDEO_MIME_TYPE), videoOutputFormat, surfaceAtomicReference);
//        MediaCodec audioEncoder = createEncoder(selectCodec(OUTPUT_AUDIO_MIME_TYPE), audioOutputFormat);
        MediaCodec audioEncoder = null;

        InputSurface inputSurface = new InputSurface(surfaceAtomicReference.get());

        String outputFile = String.format("%s/output.mp4", Environment.getExternalStorageDirectory().getAbsolutePath());
        MediaMuxer muxer = new MediaMuxer(outputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        go(videoExtractor, audioExtractor, videoDecoder, audioDecoder, videoEncoder, inputSurface, audioEncoder, muxer);

        // Cleanup
        videoExtractor.release();
        videoDecoder.stop();
        videoDecoder.release();
        videoEncoder.stop();
        videoEncoder.release();
        muxer.stop();
        muxer.release();
    }

    private void go(MediaExtractor videoExtractor, MediaExtractor audioExtractor, MediaCodec videoDecoder, MediaCodec audioDecoder, MediaCodec videoEncoder, InputSurface inputSurface, MediaCodec audioEncoder, MediaMuxer muxer) {
        ByteBuffer[] videoDecoderInputBuffers = videoDecoder.getInputBuffers();
        ByteBuffer[] videoDecoderOutputBuffers = videoDecoder.getOutputBuffers();
//        ByteBuffer[] audioDecoderInputBuffers = audioDecoder.getInputBuffers();
//        ByteBuffer[] audioDecoderOutputBuffers = audioDecoder.getOutputBuffers();
        MediaCodec.BufferInfo videoDecoderOutputBufferInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo audioDecoderOutputBufferInfo = new MediaCodec.BufferInfo();

        ByteBuffer[] videoEncoderInputBuffers = videoEncoder.getInputBuffers();
        ByteBuffer[] videoEncoderOutputBuffers = videoEncoder.getOutputBuffers();
//        ByteBuffer[] audioEncoderInputBuffers = audioEncoder.getInputBuffers();
//        ByteBuffer[] audioEncoderOutputBuffers = audioEncoder.getOutputBuffers();
        MediaCodec.BufferInfo videoEncoderOutputBufferInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo audioEncoderOutputBufferInfo = new MediaCodec.BufferInfo();

        // We will get these from the encoders when notified of a format change.
        MediaFormat decoderOutputVideoFormat = null;
        MediaFormat decoderOutputAudioFormat = null;
        MediaFormat encoderOutputVideoFormat = null;
        MediaFormat encoderOutputAudioFormat = null;
        // Whether things are done on the video side.
        boolean videoExtractorDone = false;
        boolean videoDecoderDone = false;
        boolean videoEncoderDone = false;
        // Whether things are done on the audio side.
        boolean audioExtractorDone = false;
        boolean audioDecoderDone = false;
        boolean audioEncoderDone = false;
        // The decoder output buffer to process, -1 if none.
        int pendingVideoDecoderOutputBufferIndex = -1;
        int pendingAudioDecoderOutputBufferIndex = -1;

        int outputVideoTrack = -1;

        boolean muxing = false;

        while (!videoEncoderDone) {
            while (!videoExtractorDone && (encoderOutputVideoFormat == null || muxing)) {
                int decoderInputBufferIndex = videoDecoder.dequeueInputBuffer(TIMEOUT_USEC);

                if (decoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                }

                ByteBuffer decoderInputBuffer = videoDecoderInputBuffers[decoderInputBufferIndex];
                int size = videoExtractor.readSampleData(decoderInputBuffer, 0);
                long presentationTime = videoExtractor.getSampleTime();

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
                    videoDecoder.queueInputBuffer(
                            decoderInputBufferIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }

                // We extracted a frame, let's try something else next.
                break;
            }

//            while(!audioExtractorDone && (encoderOutputAudioFormat == null || muxing)) {
//                int decoderInputBufferIndex = videoDecoder.dequeueInputBuffer(TIMEOUT_USEC);
//
//                if (decoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
//                    break;
//                }
//
//                ByteBuffer decoderInputBuffer = audioDecoderInputBuffers[decoderInputBufferIndex];
//                int size = audioExtractor.readSampleData(decoderInputBuffer, 0);
//                long presentationTime = audioExtractor.getSampleTime();
//
//                if (size >= 0) {
//                    audioDecoder.queueInputBuffer(
//                            decoderInputBufferIndex,
//                            0,
//                            size,
//                            presentationTime,
//                            audioExtractor.getSampleFlags());
//                }
//
//                audioExtractorDone = !audioExtractor.advance();
//                if (audioExtractorDone) {
//                    audioDecoder.queueInputBuffer(
//                            decoderInputBufferIndex,
//                            0,
//                            0,
//                            0,
//                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
//                }
//
//                // We extracted a frame, let's try something else next.
//                break;
//            }

            while (!videoDecoderDone && pendingVideoDecoderOutputBufferIndex == -1
                    && (encoderOutputVideoFormat == null || muxing)) {
                int decoderOutputBufferIndex = videoDecoder.dequeueOutputBuffer(videoDecoderOutputBufferInfo, TIMEOUT_USEC);

                if (decoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                }

                if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    videoDecoderOutputBuffers = videoDecoder.getOutputBuffers();
                    break;
                }

                if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    decoderOutputVideoFormat = videoDecoder.getOutputFormat();
                    break;
                }

                pendingVideoDecoderOutputBufferIndex = decoderOutputBufferIndex;

                boolean render = videoDecoderOutputBufferInfo.size != 0;
                videoDecoder.releaseOutputBuffer(decoderOutputBufferIndex, render);

                if (render) {
                    inputSurface.setPresentationTime(videoDecoderOutputBufferInfo.presentationTimeUs * 1000);
                    inputSurface.swapBuffers();
                }

                if ((videoDecoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    videoDecoderDone = true;
                    videoEncoder.signalEndOfInputStream();
                }

                // We extracted a pending frame, let's try something else next.
                break;
            }

//            while (pendingVideoDecoderOutputBufferIndex != -1) {
//                int encoderInputBufferIndex = videoEncoder.dequeueInputBuffer(TIMEOUT_USEC);
//
//                if (encoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
//                    break;
//                }
//
//                ByteBuffer encoderInputBuffer =
//                        videoEncoderInputBuffers[encoderInputBufferIndex];
//                int size = videoDecoderOutputBufferInfo.size;
//                long presentationTime = videoDecoderOutputBufferInfo.presentationTimeUs;
//
//                if (size >= 0) {
//                    ByteBuffer decoderOutputBuffer = videoDecoderOutputBuffers[pendingVideoDecoderOutputBufferIndex].duplicate();
//                    decoderOutputBuffer.position(videoDecoderOutputBufferInfo.offset);
//                    decoderOutputBuffer.limit(encoderInputBuffer.capacity());
//                    encoderInputBuffer.position(0);
//                    encoderInputBuffer.put(decoderOutputBuffer);
//                    videoEncoder.queueInputBuffer(
//                            encoderInputBufferIndex,
//                            0,
//                            encoderInputBuffer.capacity(),
//                            presentationTime,
//                            videoDecoderOutputBufferInfo.flags);
//                }
//
////                videoDecoder.releaseOutputBuffer(pendingAudioDecoderOutputBufferIndex, false);
//                pendingVideoDecoderOutputBufferIndex = -1;
//
//                if ((videoDecoderOutputBufferInfo.flags
//                        & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
//                    videoDecoderDone = true;
//                }
//                // We enqueued a pending frame, let's try something else next.
//                break;
//            }

            while (!videoEncoderDone && (encoderOutputVideoFormat == null || muxing)) {
                int encoderOutputBufferIndex = videoEncoder.dequeueOutputBuffer(videoEncoderOutputBufferInfo, TIMEOUT_USEC);

                if (encoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                }

                if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    videoEncoderOutputBuffers = videoEncoder.getOutputBuffers();
                    break;
                }

                if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    encoderOutputVideoFormat = videoEncoder.getOutputFormat();
                    break;
                }

                ByteBuffer encoderOutputBuffer = videoEncoderOutputBuffers[encoderOutputBufferIndex];

                if ((videoEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // Simply ignore codec config buffers.
                    videoEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
                    break;
                }

                if (videoEncoderOutputBufferInfo.size != 0) {
                    muxer.writeSampleData(
                            outputVideoTrack, encoderOutputBuffer, videoEncoderOutputBufferInfo);
                }

                if ((videoEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        != 0) {
                    videoEncoderDone = true;
                }

                videoEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
                // We enqueued an encoded frame, let's try something else next.
                break;
            }

            if (!muxing && encoderOutputAudioFormat != null && encoderOutputVideoFormat != null) {
                outputVideoTrack = muxer.addTrack(encoderOutputVideoFormat);
//                outputAudioTrack = muxer.addTrack(encoderOutputAudioFormat);
                muxer.start();
                muxing = true;
            }
        }
    }

    /**
     * Creates an extractor that reads its frames from {@link #mSourceResId}.
     */
    private MediaExtractor createExtractor(Uri fileUri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(MainActivity.this, fileUri, null);
        return extractor;
    }

    private int getAndSelectVideoTrackIndex(MediaExtractor extractor) {
        for (int index = 0; index < extractor.getTrackCount(); ++index) {
            if (isVideoFormat(extractor.getTrackFormat(index))) {
                extractor.selectTrack(index);
                return index;
            }
        }
        return -1;
    }
    private int getAndSelectAudioTrackIndex(MediaExtractor extractor) {
        for (int index = 0; index < extractor.getTrackCount(); ++index) {
            if (isAudioFormat(extractor.getTrackFormat(index))) {
                extractor.selectTrack(index);
                return index;
            }
        }
        return -1;
    }

    private static boolean isVideoFormat(MediaFormat format) {
        return getMimeTypeFor(format).startsWith("video/");
    }
    private static boolean isAudioFormat(MediaFormat format) {
        return getMimeTypeFor(format).startsWith("audio/");
    }

    private static String getMimeTypeFor(MediaFormat format) {
        return format.getString(MediaFormat.KEY_MIME);
    }

    private MediaFormat createOutputVideoFormat() {
        MediaFormat outputVideoFormat =
                MediaFormat.createVideoFormat(OUTPUT_VIDEO_MIME_TYPE, 320, 176);
        outputVideoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT, OUTPUT_VIDEO_COLOR_FORMAT);
        outputVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_VIDEO_BIT_RATE);
        outputVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_VIDEO_FRAME_RATE);
        outputVideoFormat.setInteger(
                MediaFormat.KEY_I_FRAME_INTERVAL, OUTPUT_VIDEO_IFRAME_INTERVAL);

        return outputVideoFormat;
    }

    private MediaFormat createOutputAudioFormat() {
        MediaFormat outputAudioFormat =
                MediaFormat.createAudioFormat(
                        OUTPUT_AUDIO_MIME_TYPE, OUTPUT_AUDIO_SAMPLE_RATE_HZ,
                        OUTPUT_AUDIO_CHANNEL_COUNT);
        outputAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_AUDIO_BIT_RATE);
        outputAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, OUTPUT_AUDIO_AAC_PROFILE);

        return outputAudioFormat;
    }

    private MediaCodec createDecoder(MediaFormat inputFormat) {
        MediaCodec decoder = MediaCodec.createDecoderByType(getMimeTypeFor(inputFormat));
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        decoder.configure(inputFormat, new Surface(new SurfaceTexture(textures[0])), null, 0);
        decoder.start();
        return decoder;
    }


    /**
     * Creates an encoder for the given format using the specified codec
     * @param codecInfo of the codec to use
     * @param format of the stream to be produced
     */
    private MediaCodec createEncoder(MediaCodecInfo codecInfo, MediaFormat format, AtomicReference<Surface> reference) {
        if (codecInfo == null) {
            // TODO: check for null codecInfo if the codec isn't avaiable on the device
        }

        MediaCodec encoder = MediaCodec.createByCodecName(codecInfo.getName());
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        reference.set(encoder.createInputSurface());
        encoder.start();
        return encoder;
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

    @Override
    public void onVideoTranscoded(final String outputFile, double inputFileSize, double outputFileSize, double timeToEncode) {
        if (mProgressDialog.isShowing()) {
            mProgressDialog.dismiss();

//            Intent intent = new Intent(android.content.Intent.ACTION_VIEW);
//            intent.setDataAndType(Uri.fromFile(new File(outputFile)), "video/*");
//            startActivity(intent);
        }

        mInputFileSize.setText(String.format("Input file: %sMB", inputFileSize));
        mOutputFileSize.setText(String.format("Output file: %sMB", outputFileSize));
        mTimeToEncode.setText(String.format("Time to encode: %ss", timeToEncode));


        Button playVideo = (Button) findViewById(R.id.btn_play);
        playVideo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(android.content.Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.fromFile(new File(outputFile)), "video/*");
                startActivity(intent);

            }
        });

    }

    @Override
    public void onError() {
        if (mProgressDialog.isShowing()) {
            mProgressDialog.dismiss();
        }

        Toast.makeText(this, "Error encoding video :(", Toast.LENGTH_LONG).show();
    }
}
