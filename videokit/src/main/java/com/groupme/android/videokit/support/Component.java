package com.groupme.android.videokit.support;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import com.groupme.android.videokit.util.MediaInfo;

import java.io.IOException;

public class Component {
    public static int COMPONENT_TYPE_AUDIO = 0;
    public static int COMPONENT_TYPE_VIDEO = 1;

    public static int NO_TRACK_AVAILABLE = -1;

    private Context mContext;
    private final Uri mSrcUri;
    private final int mType;

    private MediaExtractor mMediaExtractor;
    private MediaFormat mTrackFormat;
    private int mSelectedTrackIndex;

    /**
     *
     * @param context Context that this component is in
     * @param srcUri Source uri for the component
     * @param type Type of component - either audio or video
     * @throws IOException Thrown if the component type is invalid
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
     * @return The MediaExtractor instance to use to for this component.
     */
    public MediaExtractor getMediaExtractor() {
        return mMediaExtractor;
    }

    /**
     * @return The MediaFormat for the selected track of this component.
     */
    public MediaFormat getTrackFormat() {
        return mTrackFormat;
    }

    /**
     * @return The index of the selected track for this component.
     */
    public int getSelectedTrackIndex() {
        return mSelectedTrackIndex;
    }

    /**
     * @return The component type. Either COMPONENT_TYPE_AUDIO or COMPONENT_TYPE_VIDEO
     */
    public int getType() {
        return mType;
    }

    public void release() {
        mContext = null;
        mMediaExtractor.release();
        mMediaExtractor = null;
    }

    /**
     * create me!
     * @throws IOException If unable to create this component
     */
    private void init() throws IOException {
        createExtractor();
        selectTrackIndex();
    }

    /**
     * Creates an extractor that reads its frames from {@link #mSrcUri}
     *
     * @throws IOException If unable to create a media extractor for this component
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
                return;
            }
        }

        mSelectedTrackIndex = -1;
        mTrackFormat = null;
    }
}
