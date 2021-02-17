/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.gallery3d.app;
import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import com.groupme.android.videokit.R;

@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
public class TrimVideo extends Activity implements
        MediaPlayer.OnErrorListener,
        MediaPlayer.OnCompletionListener,
        ControllerOverlay.Listener {

    public static final String START_TIME = "com.groupme.android.videokit.START_TIME";
    public static final String END_TIME = "com.groupme.android.videokit.END_TIME";
    public static final String TOGGLE_SWITCH_STATE = "com.groupme.android.videokit.TOGGLE_SWITCH_STATE";
    public static final String EXTRA_MESSAGE = "com.groupme.android.videokit.extra.MESSAGE";
    public static final String EXTRA_MAX_DURATION = "com.groupme.android.videokit.extra.MAX_DURATION";
    public static final String EXTRA_ICON_RES_ID = "com.groupme.android.videokit.extra.ICON_RES_ID";
    public static final String EXTRA_TOGGLE_SWITCH_DESCRIPTION = "com.groupme.android.videokit.extra.TOGGLE_SWITCH_DESCRIPTION";
    public static final String EXTRA_TOGGLE_MAX_DURATION = "com.groupme.android.videokit.extra.TOGGLE_MAX_DURATION";
    public static final String EXTRA_TOGGLE_COACHMARK_MESSAGE = "com.groupme.android.videokit.extra.TOGGLE_COACHMARK_MESSAGE";
    private int mMaxDuration = 30 * 1000; // 30 seconds
    private VideoView mVideoView;
    private TrimControllerOverlay mController;
    private Context mContext;
    private Uri mUri;
    private final Handler mHandler = new Handler();
    public ProgressDialog mProgress;
    private int mTrimStartTime = 0;
    private int mTrimEndTime = 0;
    private boolean mToggleSwitchState = false;
    private int mVideoPosition = 0;
    public static final String KEY_TRIM_START = "trim_start";
    public static final String KEY_TRIM_END = "trim_end";
    public static final String KEY_VIDEO_POSITION = "video_pos";
    private int mDuration;

    protected int getTrimStartTime() {
        return mTrimStartTime;
    }

    protected int getTrimEndTime() {
        return mTrimEndTime;
    }

    protected int getVideoDuration() {
        return mDuration;
    }

    protected boolean getToggleSwitchState() {
        return mToggleSwitchState;
    }

    protected Uri getVideoUri() {
        return mUri;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        try {
            mContext = getApplicationContext();
            super.onCreate(savedInstanceState);
            requestWindowFeature(Window.FEATURE_ACTION_BAR);
            requestWindowFeature(Window.FEATURE_ACTION_BAR_OVERLAY);
            ActionBar actionBar = getActionBar();

            if (actionBar != null) {
                int displayOptions = ActionBar.DISPLAY_SHOW_HOME;
                actionBar.setDisplayOptions(0, displayOptions);
                displayOptions = ActionBar.DISPLAY_SHOW_CUSTOM;
                actionBar.setDisplayOptions(displayOptions, displayOptions);
                actionBar.setTitle(R.string.edit_video);
                actionBar.setDisplayShowHomeEnabled(true);
                actionBar.setDisplayHomeAsUpEnabled(true);

                mMaxDuration = getIntent().getIntExtra(EXTRA_MAX_DURATION, mMaxDuration);
                int iconResid = getIntent().getIntExtra(EXTRA_ICON_RES_ID, -1);

                if (iconResid != -1) {
                    actionBar.setIcon(iconResid);
                }
            }

            Intent intent = getIntent();
            mUri = intent.getData();
            setContentView(R.layout.trim_view);
            View rootView = findViewById(R.id.trim_view_root);
            mVideoView = (VideoView) rootView.findViewById(R.id.surface_view);
            mController = new TrimControllerOverlay(mContext, mMaxDuration);
            ((ViewGroup) rootView).addView(mController.getView());
            mController.setListener(this);
            mController.setCanReplay(true);

            MediaMetadataRetriever retriever = new MediaMetadataRetriever();

            if (ContentResolver.SCHEME_FILE.equals(mUri.getScheme())) {
                retriever.setDataSource(mUri.getPath());
            } else {
                retriever.setDataSource(mContext, mUri);
            }

            mDuration = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            mController.setTimes(0, mDuration, 0, 0);
            mTrimEndTime = Math.min(mDuration, mMaxDuration);
            mVideoView.setOnErrorListener(this);
            mVideoView.setOnCompletionListener(this);
            mVideoView.setVideoURI(mUri);

            String toggleSwitchDescription = intent.getStringExtra(EXTRA_TOGGLE_SWITCH_DESCRIPTION);
            if (!TextUtils.isEmpty(toggleSwitchDescription)) {
                mController.setSwitchText(toggleSwitchDescription);
                mController.showToggle();
                String coachMarkMessage = intent.getStringExtra(EXTRA_TOGGLE_COACHMARK_MESSAGE);
                if(!TextUtils.isEmpty(coachMarkMessage)) {
                    mController.showToggleCoachMark(coachMarkMessage);
                }
                mController.mToggleSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                        mToggleSwitchState = b;
                        if(mToggleSwitchState) {
                            mMaxDuration = getIntent().getIntExtra(EXTRA_TOGGLE_MAX_DURATION, mMaxDuration);
                        } else {
                            mMaxDuration = getIntent().getIntExtra(EXTRA_MAX_DURATION, mMaxDuration);
                        }
                        if(mTrimStartTime + mMaxDuration > mDuration) {
                            mTrimEndTime = mDuration;
                        } else {
                            mTrimEndTime = mTrimStartTime + mMaxDuration;
                        }
                        mTrimEndTime = mTrimStartTime + mMaxDuration;
                        mController.setMaxDuration(mMaxDuration);
                        mController.setTimes(mTrimStartTime, mDuration, mTrimStartTime, mTrimEndTime);
                    }
                });

            } else {
                mController.hideToggle();
            }
            if (savedInstanceState == null) {
                String message = getIntent().getStringExtra(EXTRA_MESSAGE);

                if (!TextUtils.isEmpty(message)) {
                    View layout = getLayoutInflater().inflate(R.layout.toast, null);
                    TextView text = (TextView) layout.findViewById(R.id.text);
                    text.setText(message);
                    Toast toast = new Toast(this);
                    toast.setDuration(Toast.LENGTH_LONG);
                    int toastPadding = getResources().getDimensionPixelSize(R.dimen.toast_padding);
                    toast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, toastPadding);
                    toast.setView(layout);
                    toast.show();
                }
            }

            playVideo();
        } catch (Exception e) {
            setResult(RESULT_FIRST_USER);
            finish();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mVideoView.seekTo(mVideoPosition);
        mVideoView.resume();
        mHandler.post(mProgressChecker);
    }
    @Override
    public void onPause() {
        mHandler.removeCallbacksAndMessages(null);
        mVideoPosition = mVideoView.getCurrentPosition();
        mVideoView.suspend();
        super.onPause();
    }
    @Override
    public void onStop() {
        if (mProgress != null) {
            mProgress.dismiss();
            mProgress = null;
        }
        super.onStop();
    }
    @Override
    public void onDestroy() {
        mVideoView.stopPlayback();
        super.onDestroy();
    }
    private final Runnable mProgressChecker = new Runnable() {
        @Override
        public void run() {
            int pos = setProgress();
            mHandler.postDelayed(mProgressChecker, 200 - (pos % 200));
        }
    };

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
            results.setData(mUri);
            results.putExtra(START_TIME, mTrimStartTime);
            results.putExtra(END_TIME, mTrimEndTime);
            results.putExtra(TOGGLE_SWITCH_STATE, mToggleSwitchState);
            setResult(Activity.RESULT_OK, results);
            finish();
            return true;
        } else if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        savedInstanceState.putInt(KEY_TRIM_START, mTrimStartTime);
        savedInstanceState.putInt(KEY_TRIM_END, mTrimEndTime);
        savedInstanceState.putInt(KEY_VIDEO_POSITION, mVideoPosition);
        super.onSaveInstanceState(savedInstanceState);
    }
    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mTrimStartTime = savedInstanceState.getInt(KEY_TRIM_START, 0);
        mTrimEndTime = savedInstanceState.getInt(KEY_TRIM_END, 0);
        mVideoPosition = savedInstanceState.getInt(KEY_VIDEO_POSITION, 0);
    }
    // This updates the time bar display (if necessary). It is called by
    // mProgressChecker and also from places where the time bar needs
    // to be updated immediately.
    private int setProgress() {
        mVideoPosition = mVideoView.getCurrentPosition();
        // If the video position is smaller than the starting point of trimming,
        // correct it.
        if (mVideoPosition < mTrimStartTime) {
            mVideoPosition = mTrimStartTime;
        }
        // If the position is bigger than the end point of trimming, show the
        // replay button and pause.
        if (mVideoPosition >= mTrimEndTime && mTrimEndTime > 0) {
            if (mVideoPosition > mTrimEndTime) {
                mVideoPosition = mTrimEndTime;
            }
            mController.showEnded();
            mVideoView.pause();
        }

        if (mVideoView.isPlaying()) {
            mController.setTimes(mVideoPosition, mDuration, mTrimStartTime, Math.min(mTrimStartTime + mMaxDuration, mTrimEndTime));
        }

        return mVideoPosition;
    }
    private void playVideo() {
        mVideoView.start();
        mController.showPlaying();
        setProgress();
    }
    private void pauseVideo() {
        mVideoView.pause();
        mController.showPaused();
    }

    @Override
    public void onPlayPause() {
        if (mVideoView.isPlaying()) {
            pauseVideo();
        } else {
            playVideo();
        }
    }
    @Override
    public void onSeekStart() {
        pauseVideo();
    }
    @Override
    public void onSeekMove(int time) {
        mVideoView.seekTo(time);
    }
    @Override
    public void onSeekEnd(int time, int start, int end) {
        mVideoView.seekTo(time);
        mTrimStartTime = start;
        mTrimEndTime = Math.min(mTrimStartTime + mMaxDuration, end);
        setProgress();
    }
    @Override
    public void onShown() {
    }
    @Override
    public void onHidden() {
    }
    @Override
    public void onReplay() {
        mVideoView.seekTo(mTrimStartTime);
        playVideo();
    }
    @Override
    public void onCompletion(MediaPlayer mp) {
        mController.showEnded();
    }
    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        return false;
    }
}