package com.groupme.android.videokit;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;

@TargetApi(Build.VERSION_CODES.GINGERBREAD_MR1)
public class FilmRollView extends View {
    private static final String KEY_FRAME_WIDTH = "frame_width";
    private static final Object KEY_FRAME = "frame";
    private static final String KEY_FRAME_COUNT = "frame_count";
    private static final String KEY_INSTANCE_STATE = "instance_state";
    private static Paint sBitmapPaint = new Paint();
    static {
        sBitmapPaint.setAntiAlias(true);
        sBitmapPaint.setFilterBitmap(true);
        sBitmapPaint.setDither(true);
    }

    private ArrayList<Bitmap> mBitmaps = new ArrayList<Bitmap>();
    private Uri mVideoUri;
    private MediaMetadataRetriever mMetaDataExtractor;
    private Rect mRectangle = new Rect(0, 0, 0, 0);
    private int mScaledFrameWidth;
    private boolean mBitmapsRetrieved;
    private int mFrameCount;

    public FilmRollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }


    public void setVideoUri(Uri uri) {
        mVideoUri = uri;
        mMetaDataExtractor = new MediaMetadataRetriever();

        if (uri.getScheme().equals(ContentResolver.SCHEME_FILE)) {
            mMetaDataExtractor.setDataSource(uri.getPath());
        } else {
            mMetaDataExtractor.setDataSource(getContext(), uri);
        }
    }

    Runnable mFrameRetriever = new Runnable() {
        @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
        @Override
        public void run() {
            float duration = Float.parseFloat(mMetaDataExtractor.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));

            int rotation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 ?
                    Integer.parseInt(mMetaDataExtractor.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)) :
                    0;
            float frameHeight = Float.parseFloat(mMetaDataExtractor.extractMetadata(
                    rotation == 90 || rotation == 270 ?
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH :
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            float frameWidth = Float.parseFloat(mMetaDataExtractor.extractMetadata(
                    rotation == 90 || rotation == 270 ?
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT :
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            mScaledFrameWidth = (int) (getHeight() * frameWidth / frameHeight);
            mFrameCount = (int) ((float) getWidth() / mScaledFrameWidth);
            // The time between frames in micro-seconds
            int timeBetweenFrames = (int) (duration / mFrameCount) * 1000;
            int timeOffset = 0;

            for (int i = 1; i <= mFrameCount; i++) {
                mBitmaps.add(mMetaDataExtractor.getFrameAtTime(timeOffset));
                timeOffset += timeBetweenFrames;
            }

            post(new Runnable() {
                @Override
                public void run() {
                    invalidate();
                }
            });
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mMetaDataExtractor != null && mBitmaps.isEmpty() && !mBitmapsRetrieved) {
            mBitmapsRetrieved = true;
            new Thread(mFrameRetriever).start();
        }

        if (mMetaDataExtractor != null) {
            int horizontalOffset = 0;

            for (Bitmap bitmap : mBitmaps) {
                if (bitmap != null) {
                    mRectangle.set(horizontalOffset, 0, mScaledFrameWidth + horizontalOffset, getHeight());
                    canvas.drawBitmap(bitmap, null, mRectangle, sBitmapPaint);
                }

                horizontalOffset += mScaledFrameWidth;
            }
        }
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(KEY_INSTANCE_STATE, super.onSaveInstanceState());

        for (int i = 0; i < mBitmaps.size(); i++) {
            bundle.putParcelable(String.format("%s_%s", KEY_FRAME, i), mBitmaps.get(i));
        }

        bundle.putInt(KEY_FRAME_WIDTH, mScaledFrameWidth);
        bundle.putInt(KEY_FRAME_COUNT, mFrameCount);

        return bundle;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            Bundle bundle = (Bundle) state;
            mFrameCount = bundle.getInt(KEY_FRAME_COUNT);
            mScaledFrameWidth = bundle.getInt(KEY_FRAME_WIDTH);

            for (int i = 0; i < mFrameCount; i++) {
                mBitmaps.add((Bitmap) bundle.getParcelable(String.format("%s_%s", KEY_FRAME, i)));
            }

            state = bundle.getParcelable(KEY_INSTANCE_STATE);
        }

        super.onRestoreInstanceState(state);
    }
}
