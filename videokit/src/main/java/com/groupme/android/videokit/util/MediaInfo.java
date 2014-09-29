package com.groupme.android.videokit.util;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@TargetApi(16)
public class MediaInfo {
    public static int VALUE_NOT_AVAILABLE = -1;

    private final Context mContext;
    private final Uri mMediaUri;

    private MediaExtractor mMediaExtractor;

    private MediaFormat mAudioTrackFormat;
    private MediaFormat mVideoTrackFormat;

    /** Static Utility Methods **/

    public static boolean isVideoFormat(MediaFormat format) {
        return getMimeTypeFor(format).startsWith("video/");
    }

    public static boolean isAudioFormat(MediaFormat format) {
        return getMimeTypeFor(format).startsWith("audio/");
    }

    public static String getMimeTypeFor(MediaFormat format) {
        return format.getString(MediaFormat.KEY_MIME);
    }

    /** Instance Methods **/

    public MediaInfo(Context context, Uri uri) throws IOException {
        mContext = context.getApplicationContext();
        mMediaUri = uri;

        extract();
    }

    public boolean hasAudioTrack() {
        return mAudioTrackFormat != null;
    }

    public boolean hasVideoTrack() {
        return mVideoTrackFormat != null;
    }

    /**
     * Returns the track duration in seconds.
     * @return
     */
    public long getDuration() {
        long duration = -1;

        if (hasVideoTrack()) {
            duration = mVideoTrackFormat.getLong(MediaFormat.KEY_DURATION);
            duration = TimeUnit.MICROSECONDS.toSeconds(duration);
        } else if (hasAudioTrack()) {
            duration = mAudioTrackFormat.getLong(MediaFormat.KEY_DURATION);
            duration = TimeUnit.MICROSECONDS.toSeconds(duration);
        }

        return duration;
    }

    private void extract() throws IOException {
        mMediaExtractor = new MediaExtractor();
        mMediaExtractor.setDataSource(mContext, mMediaUri, null);

        int trackCount = mMediaExtractor.getTrackCount();
        for (int i = 0; i < trackCount; i++) {
            MediaFormat track = mMediaExtractor.getTrackFormat(i);

            if (isAudioFormat(track)) {
                mAudioTrackFormat = track;
            } else if (isVideoFormat(track)) {
                mVideoTrackFormat = track;
            }
        }
    }
}
