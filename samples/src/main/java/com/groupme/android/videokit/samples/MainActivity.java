package com.groupme.android.videokit.samples;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.groupme.android.videokit.VideoTranscoder;
import com.groupme.android.videokit.util.MediaInfo;
import com.groupme.android.videokit.Transcoder;
import com.groupme.android.videokit.support.InputSurface;

import android.net.Uri;
import android.opengl.GLES20;
import android.os.Bundle;
import android.os.Environment;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;


public class MainActivity extends Activity implements Transcoder.OnVideoTranscodedListener {
    private static final int REQUEST_PICK_VIDEO = 0;

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

        mInputFileSize = (TextView) findViewById(R.id.input_file_size);
        mOutputFileSize = (TextView) findViewById(R.id.output_file_size);
        mTimeToEncode = (TextView) findViewById(R.id.time_to_encode);
    }

    private void encodeVideo(final Uri videoUri) throws IOException {
        MediaInfo mediaInfo = new MediaInfo(this, videoUri);

        if (mediaInfo.hasVideoTrack()) {
//            Transcoder.with(this)
//                    .source(mediaInfo)
//                    .listener(this)
//                    .start(Transcoder.getDefaultOutputFilePath());

            final File outputFile = new File(Environment.getExternalStorageDirectory(), "output.mp4");

            VideoTranscoder transcoder = new VideoTranscoder.Builder(videoUri, outputFile)
                    .build(getApplicationContext());

            transcoder.start(new VideoTranscoder.Listener() {
                @Override
                public void onSuccess(VideoTranscoder.Stats stats) {
                    if (mProgressDialog.isShowing()) {
                        mProgressDialog.dismiss();
                    }

                    mInputFileSize.setText(String.format("Input file: %sMB", stats.inputFileSize));
                    mOutputFileSize.setText(String.format("Output file: %sMB", stats.outputFileSize));
                    mTimeToEncode.setText(String.format("Time to encode: %ss", stats.timeToTranscode));

                    Button playVideo = (Button) findViewById(R.id.btn_play);
                    playVideo.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Intent intent = new Intent(android.content.Intent.ACTION_VIEW);
                            intent.setDataAndType(Uri.fromFile(outputFile), "video/*");
                            startActivity(intent);
                        }
                    });
                }

                @Override
                public void onFailure() {

                }
            });

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
            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onVideoTranscoded(final String outputFile, double inputFileSize, double outputFileSize, double timeToEncode) {
        if (mProgressDialog.isShowing()) {
            mProgressDialog.dismiss();
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
