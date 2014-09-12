package com.groupme.android.videokit;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.widget.MediaController;
import android.widget.VideoView;

public class TrimmerActivity extends Activity {
    public static final String EXTRA_VIDEO_URI = "com.groupme.videokit.extra.VIDEO_URI";

    private Uri mVideoUri;
    private VideoView mVideoView;
    private FilmRollView mFilmRollView;
    private MediaController mController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mVideoUri = getIntent().getData();

        setContentView(R.layout.activity_trimmer);

        mVideoView = (VideoView) findViewById(R.id.video_view);
        mFilmRollView = (FilmRollView) findViewById(R.id.film_roll);
        mFilmRollView.setVideoUri(mVideoUri);
        mVideoView.setVideoURI(mVideoUri);

        mFilmRollView.setMediaPlayer(mVideoView);
        mVideoView.seekTo(1);
    }
}
