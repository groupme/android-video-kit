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
import android.util.AttributeSet;
import android.widget.ImageView;

@TargetApi(Build.VERSION_CODES.GINGERBREAD_MR1)
public class FilmRollView extends ImageView {
    private static Paint sBitmapPaint = new Paint();

    static {
        sBitmapPaint.setAntiAlias(true);
        sBitmapPaint.setFilterBitmap(true);
        sBitmapPaint.setDither(true);
    }

    private Uri mVideoUri;
    private MediaMetadataRetriever mMetaDataExtractor;

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

        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mMetaDataExtractor != null) {
            float duration = Float.parseFloat(mMetaDataExtractor.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            float frameHeight = Float.parseFloat(mMetaDataExtractor.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            float frameWidth = Float.parseFloat(mMetaDataExtractor.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int scaledFrameWidth = (int) (getHeight() * frameWidth / frameHeight);
            int totalPreviewFrames = (int) ((float) getWidth() / scaledFrameWidth);
            // The time between frames in micro-seconds
            int timeBetweenFrames = (int) (duration / totalPreviewFrames) * 1000;
            int horizontalOffset = 0;
            int timeOffset = 0;

            for (int i = 1; i <= totalPreviewFrames; i++) {
                Bitmap bitmap = mMetaDataExtractor.getFrameAtTime(timeOffset);
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, null, new Rect(horizontalOffset, 0, scaledFrameWidth + horizontalOffset, getHeight()), sBitmapPaint);
                }

                timeOffset += timeBetweenFrames;
                horizontalOffset += scaledFrameWidth;
            }

        }
    }
}
