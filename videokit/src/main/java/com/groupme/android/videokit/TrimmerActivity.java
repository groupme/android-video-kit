package com.groupme.android.videokit;

import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.VideoView;

@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
public class TrimmerActivity extends Activity {
    public static final String EXTRA_TITLE = "com.groupme.videokit.extra.TITLE";
    public static final String EXTRA_MAX_DURATION = "com.groupme.videokit.extra.MAX_DURATION";
    public static final String START_TIME = "com.groupme.videokit.extra.START_TIME";
    public static final String END_TIME = "com.groupme.videokit.extra.END_TIME";
    private static final int DEFAULT_MAX_DURATION = 15;

    private Uri mVideoUri;
    private VideoView mVideoView;
    private FilmRollView mFilmRollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mVideoUri = getIntent().getData();

        setContentView(R.layout.activity_trimmer);

        mVideoView = (VideoView) findViewById(R.id.video_view);
        mFilmRollView = (FilmRollView) findViewById(R.id.film_roll);
        mFilmRollView.setMaxDuration(getIntent().getIntExtra(EXTRA_MAX_DURATION, DEFAULT_MAX_DURATION));
        mFilmRollView.setVideoUri(mVideoUri);
        mVideoView.setVideoURI(mVideoUri);
        mFilmRollView.setMediaPlayer(mVideoView);
        mVideoView.seekTo(1);

        ActionBar actionBar = getActionBar();

        if (actionBar != null) {
            actionBar.setTitle(getIntent().getStringExtra(EXTRA_TITLE));
        }

    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.trimmer, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.done) {
            Intent results = new Intent();
            results.putExtra(START_TIME, mFilmRollView.getStartTime());
            results.putExtra(END_TIME, mFilmRollView.getEndTime());
            setResult(Activity.RESULT_OK, results);
            finish();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }
}
