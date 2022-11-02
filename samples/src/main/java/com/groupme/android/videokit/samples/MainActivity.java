package com.groupme.android.videokit.samples;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.android.gallery3d.app.TrimVideo;
import com.groupme.android.videokit.VideoTranscoder;
import com.groupme.android.videokit.util.MediaInfo;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_PICK_VIDEO = 0;
    private static final int REQUEST_PICK_VIDEO_FOR_TRIM = 1;
    private static final int REQUEST_TRIM_VIDEO = 2;

    private static final int REQUEST_PERMISSION_EXTERNAL_STORAGE = 0x78;

    private ProgressDialog mProgressDialog;
    private TextView mInputFileSize;
    private TextView mOutputFileSize;
    private TextView mTimeToEncode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button testVideoEncode = findViewById(R.id.btn_encode_video);
        testVideoEncode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("video/*");
                startActivityForResult(intent, REQUEST_PICK_VIDEO);
            }
        });

        Button testVideoTrim = findViewById(R.id.btn_trim_video);
        testVideoTrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("video/*");
                startActivityForResult(intent, REQUEST_PICK_VIDEO_FOR_TRIM);
            }
        });

        mInputFileSize = findViewById(R.id.input_file_size);
        mOutputFileSize = findViewById(R.id.output_file_size);
        mTimeToEncode = findViewById(R.id.time_to_encode);
    }

    private void encodeVideo(final Uri videoUri) {
        try {
            MediaInfo mediaInfo = new MediaInfo(this, videoUri);
            transcode(videoUri, mediaInfo, 0, (int) mediaInfo.getDurationMilliseconds());
        } catch (IOException ex) {
            Log.e(MainActivity.class.getSimpleName(), "Error encoding");
        }
    }

    private Uri mSrcUri;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_PICK_VIDEO:
                if (resultCode == AppCompatActivity.RESULT_OK) {
                    mSrcUri = data.getData();
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this,
                                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                REQUEST_PERMISSION_EXTERNAL_STORAGE);
                    } else {
                        encodeVideo(mSrcUri);
                    }
                }
                break;
            case REQUEST_PICK_VIDEO_FOR_TRIM:
                if (resultCode == AppCompatActivity.RESULT_OK) {
                    mSrcUri = data.getData();

                    Intent i = new Intent(this, TrimVideo.class);
                    i.putExtra(TrimVideo.EXTRA_MESSAGE, "Welcome to the trimmer!");
                    i.putExtra(TrimVideo.EXTRA_ICON_RES_ID, R.drawable.ic_edit_video);
                    i.putExtra(TrimVideo.EXTRA_MAX_DURATION, 40 * 1000);
                    i.setData(mSrcUri);
                    startActivityForResult(i, REQUEST_TRIM_VIDEO);
                }
                break;
            case REQUEST_TRIM_VIDEO:
                data = getIntent();
                if (data != null) {
                    Log.d("TRIM", String.format("Start: %s End: %s", data.getIntExtra(TrimVideo.START_TIME, -1), data.getIntExtra(TrimVideo.END_TIME, -1)));

                    int start = data.getIntExtra(TrimVideo.START_TIME, -1);
                    int end =  data.getIntExtra(TrimVideo.END_TIME, -1);

                    try {
                        MediaInfo info = new MediaInfo(this, mSrcUri);
                        transcode(mSrcUri, info, start, end);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSION_EXTERNAL_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                encodeVideo(mSrcUri);
            }
        }
    }

    public void transcode(Uri videoUri, MediaInfo mediaInfo, int start, int end) throws IOException {
        final File outputFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "output.mp4");

        VideoTranscoder transcoder = new VideoTranscoder.Builder(videoUri, outputFile)
                .trim(start, end)
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

                Button playVideo = findViewById(R.id.btn_play);
                playVideo.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // Don't do this in real code, convert to a content URI
                        if(Build.VERSION.SDK_INT >= 24) {
                            try {
                                Method m = StrictMode.class.getMethod("disableDeathOnFileUriExposure");
                                m.invoke(null);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }

                        Intent intent = new Intent(android.content.Intent.ACTION_VIEW);
                        intent.setDataAndType(Uri.fromFile(outputFile), "video/*");
                        startActivity(intent);
                    }
                });
            }

            @Override
            public void onFailure() {
                if (mProgressDialog.isShowing()) {
                    mProgressDialog.dismiss();
                }

                Toast.makeText(MainActivity.this, "Error encoding video", Toast.LENGTH_LONG).show();
            }
        });

        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setIndeterminate(true);
        mProgressDialog.setCancelable(false);
        mProgressDialog.setMessage(String.format("Encoding Video.. (%d secs)", mediaInfo.getDuration()));
        mProgressDialog.show();
    }
}
