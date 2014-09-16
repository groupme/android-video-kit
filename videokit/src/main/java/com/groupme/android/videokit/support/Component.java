package com.groupme.android.videokit.support;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;

import com.groupme.android.videokit.util.LogUtils;
import com.groupme.android.videokit.util.MediaInfo;

import java.io.IOException;

@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class Component {
    public static int COMPONENT_TYPE_AUDIO = 0;
    public static int COMPONENT_TYPE_VIDEO = 1;

    public static int NO_TRACK_AVAILABLE = -1;

    private final Context mContext;
    private final Uri mSrcUri;
    private final int mType;

    private MediaExtractor mMediaExtractor;
    private MediaFormat mTrackFormat;
    private int mSelectedTrackIndex;

    /**
     *
     * @param context
     * @param srcUri
     * @param type
     * @throws IOException
     */
    public Component(Context context, Uri srcUri, int type) throws IOException {
        mContext = context;
        mSrcUri = srcUri;
        mType = type;

        if (type != COMPONENT_TYPE_AUDIO && type != COMPONENT_TYPE_VIDEO) {
            throw new IllegalArgumentException("Invalid component type. " +
                    "Must be one of COMPONENT_TYPE_AUDIO or COMPONENT_TYPE_VIDEO");
        }

        init();
    }

    /**
     * The MediaExtractor instance to use to for this component.
     * @return
     */
    public MediaExtractor getMediaExtractor() {
        return mMediaExtractor;
    }

    /**
     * The MediaFormat for the selected track of this component.
     * @return
     */
    public MediaFormat getTrackFormat() {
        return mTrackFormat;
    }

    /**
     * The index of the selected track for this component.
     * @return
     */
    public int getSelectedTrackIndex() {
        return mSelectedTrackIndex;
    }

    /**
     * The component type.
     * @return COMPONENT_TYPE_AUDIO or COMPONENT_TYPE_VIDEO
     */
    public int getType() {
        return mType;
    }

    /**
     * create me!
     * @throws IOException
     */
    private void init() throws IOException {
        createExtractor();
        selectTrackIndex();
    }

    /**
     * Creates an extractor that reads its frames from {@link #mSrcUri}
     *
     * @throws java.io.IOException
     */
    private void createExtractor() throws IOException {
        mMediaExtractor = new MediaExtractor();
        mMediaExtractor.setDataSource(mContext, mSrcUri, null);
    }

    /**
     * Searches for and selects the track for the extractor to work on.
     */
    private void selectTrackIndex() {
        for (int index = 0; index < mMediaExtractor.getTrackCount(); ++index) {
            MediaFormat trackFormat = mMediaExtractor.getTrackFormat(index);

            if (mType == COMPONENT_TYPE_VIDEO && MediaInfo.isVideoFormat(trackFormat) ||
                mType == COMPONENT_TYPE_AUDIO && MediaInfo.isAudioFormat(trackFormat)) {

                mMediaExtractor.selectTrack(index);
                mSelectedTrackIndex = index;
                mTrackFormat = trackFormat;

                LogUtils.d("selected track %d for %s", index, MediaInfo.getMimeTypeFor(trackFormat));

                return;
            }
        }

        mSelectedTrackIndex = -1;
        mTrackFormat = null;
    }
}
