package com.groupme.android.videokit;

import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.widget.VideoView;

public class VideoStateView extends VideoView {
    private static final String KEY_INSTANCE_STATE = "instance_state";
    private static final String KEY_IS_PLAYING = "is_playing";
    private static final String KEY_CURRENT_POSITION = "current_position";

    public VideoStateView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(KEY_INSTANCE_STATE, super.onSaveInstanceState());
        bundle.putInt(KEY_CURRENT_POSITION, getCurrentPosition());
        bundle.putBoolean(KEY_IS_PLAYING, isPlaying());

        return bundle;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            Bundle bundle = (Bundle) state;
            seekTo(bundle.getInt(KEY_CURRENT_POSITION));

            if (bundle.getBoolean(KEY_IS_PLAYING)) {
                start();
            }

            state = bundle.getParcelable(KEY_INSTANCE_STATE);
        }

        super.onRestoreInstanceState(state);
    }
}
