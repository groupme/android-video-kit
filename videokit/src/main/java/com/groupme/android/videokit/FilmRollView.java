package com.groupme.android.videokit;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.MediaController;

import java.util.ArrayList;

@TargetApi(Build.VERSION_CODES.GINGERBREAD_MR1)
public class FilmRollView extends View {
    private static final String KEY_FRAME_WIDTH = "frame_width";
    private static final Object KEY_FRAME = "frame";
    private static final String KEY_FRAME_COUNT = "frame_count";
    private static final String KEY_INSTANCE_STATE = "instance_state";
    private static final int SHOW_PROGRESS = 1;
    private static Paint sBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private static Paint sThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static Paint sWhite75Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static Paint sWhitePaint = new Paint();
    private static Paint sLinePaint = new Paint();
    private final int mLineWidth;
    private int mActivePointerId;
    private float mDownMotionX;
    private Thumb mPressedThumb;
    private boolean mIsDragging;
    private float mScaledTouchSlop;
    private boolean notifyWhileDragging;
    private MediaController.MediaPlayerControl mPlayer;
    // The maximum duration of the final, trimmed video
    private int mMaxDuration = 15;
    private boolean mFirstPass = true;
    private float mSeekBarPosition;
    private boolean mHasDragged;
    private boolean mHasReachedEnd;

    /**
     * Callback listener interface to notify about changed range values.
     *
     * @author Stephan Tittel (stephan.tittel@kom.tu-darmstadt.de)
     *
     * @param <T>
     *            The Number type the RangeSeekBar has been declared with.
     */
    public interface OnRangeSeekBarChangeListener<T> {
        public void onRangeSeekBarValuesChanged(FilmRollView bar, double minValue, double maxValue);
    }

    private static enum Thumb {
        MIN, MAX
    }

    private ArrayList<Bitmap> mBitmaps = new ArrayList<Bitmap>();
    private MediaMetadataRetriever mMetaDataExtractor;
    private Rect mRectangle = new Rect(0, 0, 0, 0);
    private int mScaledFrameWidth;
    private boolean mBitmapsRetrieved;
    private int mFrameCount;

    private final float mThumbWidth = getResources().getDimensionPixelSize(R.dimen.handle_width);
    private final float mThumbHalfWidth = 0.5f * mThumbWidth;
    private final float mPadding = mThumbHalfWidth;
    private final float mPlayBarHeight = getResources().getDimensionPixelSize(R.dimen.play_bar_height);
    private double mNormalizedMinValue = 0d;
    private double mNormalizedMaxValue = 1d;
    private final Bitmap mPlayButton = BitmapFactory.decodeResource(getResources(), android.R.drawable.ic_media_play);
    private final Bitmap mPauseButton = BitmapFactory.decodeResource(getResources(), android.R.drawable.ic_media_pause);

    public FilmRollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        sThumbPaint.setColor(getResources().getColor(R.color.gold));
        sWhite75Paint.setColor(getResources().getColor(R.color.white_75));
        sWhitePaint.setColor(getResources().getColor(android.R.color.white));
        sLinePaint.setStyle(Paint.Style.STROKE);
        mLineWidth = getResources().getDimensionPixelSize(R.dimen.line_height);
        sLinePaint.setStrokeWidth(mLineWidth);
        sLinePaint.setColor(getResources().getColor(R.color.gold));

        mScaledTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        setClickable(true);
    }


    public void setMediaPlayer(MediaController.MediaPlayerControl player) {
        mPlayer = player;
    }

    public void setVideoUri(Uri uri) {
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
            mScaledFrameWidth = (int) (getFilmRollHeight() * frameWidth / frameHeight);
            mFrameCount = (int) ((float) getWidth() / mScaledFrameWidth);
            // The time between frames in micro-seconds
            int timeBetweenFrames = (int) (duration / mFrameCount) * 1000;
            int timeOffset = 0;

            for (int i = 1; i <= mFrameCount; i++) {
                mBitmaps.add(mMetaDataExtractor.getFrameAtTime(timeOffset));
                timeOffset += timeBetweenFrames;

                if (!mPlayer.isPlaying()) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            invalidate();
                        }
                    });
                }
            }
        }
    };

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SHOW_PROGRESS:
                    if (mPlayer.isPlaying()) {
                        invalidate();
                        msg = obtainMessage(SHOW_PROGRESS);
                        sendMessageDelayed(msg, 200);
                    }
                    break;
            }
        }
    };

    private int getFilmRollHeight() {
        return getResources().getDimensionPixelSize(R.dimen.film_roll_height);
    }

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
                    mRectangle.set(horizontalOffset, 0, mScaledFrameWidth + horizontalOffset, getFilmRollHeight());
                    canvas.drawBitmap(bitmap, null, mRectangle, sBitmapPaint);
                }

                horizontalOffset += mScaledFrameWidth;
            }

            drawSeekBar(canvas);

            // draw minimum thumb
            drawLeftThumb(normalizedToScreen(mNormalizedMinValue), canvas);

            // draw maximum thumb
            if (mFirstPass && mPlayer.getDuration() != -1) {
                mNormalizedMaxValue = Math.min(mPlayer.getDuration(), mMaxDuration * 1000d) / mPlayer.getDuration() + mNormalizedMinValue;
                mFirstPass = false;
            }

            drawRightThumb(normalizedToScreen(mNormalizedMaxValue), canvas);
            drawPlayBar(canvas);
        }
    }

    private int normalizedValueToTime(double normalizedValue) {
        return (int) (normalizedValue * mPlayer.getDuration());
    }

    private void drawLeftThumb(float screenCoord, Canvas canvas) {
        canvas.drawRect(0, 0, screenCoord - mThumbHalfWidth, getFilmRollHeight(), sWhite75Paint);
        canvas.drawRect(screenCoord - mThumbHalfWidth, 0, screenCoord + mThumbHalfWidth, getFilmRollHeight(), sThumbPaint);
    }

    private void drawRightThumb(float screenCoord, Canvas canvas) {
        canvas.drawRect(screenCoord + mThumbHalfWidth, 0, getWidth(), getFilmRollHeight(), sWhite75Paint);
        canvas.drawLine(normalizedToScreen(mNormalizedMinValue), getFilmRollHeight() - mLineWidth / 2L, screenCoord, getFilmRollHeight() - mLineWidth / 2L, sLinePaint);
        canvas.drawLine(screenCoord, mLineWidth / 2L, normalizedToScreen(mNormalizedMinValue), mLineWidth / 2L, sLinePaint);
        canvas.drawRect(screenCoord - mThumbHalfWidth, 0, screenCoord + mThumbHalfWidth, getFilmRollHeight(), sThumbPaint);
    }

    private void drawSeekBar(Canvas canvas) {
        if (!mIsDragging && !mHasDragged) {
            mSeekBarPosition = (float) mPlayer.getCurrentPosition() / (float) mPlayer.getDuration() * getWidth();
        }

        if (mPlayer.getCurrentPosition() >= normalizedValueToTime(mNormalizedMaxValue) && mPlayer.isPlaying() && !mIsDragging) {
            mPlayer.pause();
            mHasReachedEnd = true;
        }

        if (!mHasReachedEnd) {
            canvas.drawRect(mSeekBarPosition - mThumbHalfWidth / 2L, 0, mSeekBarPosition + mThumbHalfWidth / 2L, getFilmRollHeight(), sWhitePaint);
        }
    }

    private void drawPlayBar(Canvas canvas) {
        canvas.drawRect(0, getHeight() - mPlayBarHeight, getWidth(), getHeight(), sWhite75Paint);
        int left = (getWidth() - mPlayButton.getWidth()) / 2;
        float top = getHeight() - mPlayBarHeight / 2 - mPlayButton.getScaledHeight(canvas) / 2;

        if (mPlayer.isPlaying()) {
            canvas.drawBitmap(mPauseButton, left, top, sBitmapPaint);
        } else {
            canvas.drawBitmap(mPlayButton, left, top, sBitmapPaint);
        }
    }

    /**
     * Converts a normalized value into screen space.
     *
     * @param normalizedCoord
     *            The normalized value to convert.
     * @return The converted value in screen space.
     */
    private float normalizedToScreen(double normalizedCoord) {
        return (float) (mPadding + normalizedCoord * (getWidth() - 2 * mPadding));
    }

    /**
     * Handles thumb selection and movement. Notifies listener callback on certain events.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {

        if (!isEnabled())
            return false;

        int pointerIndex;

        final int action = event.getAction();
        switch (action & MotionEvent.ACTION_MASK) {

            case MotionEvent.ACTION_DOWN:
                // Remember where the motion event started
                mActivePointerId = event.getPointerId(event.getPointerCount() - 1);
                pointerIndex = event.findPointerIndex(mActivePointerId);
                mDownMotionX = event.getX(pointerIndex);

                mPressedThumb = evalPressedThumb(mDownMotionX, event.getY(pointerIndex));

                // Only handle thumb presses.
                if (mPressedThumb == null)
                    return super.onTouchEvent(event);

                setPressed(true);
                invalidate();
                onStartTrackingTouch();
                trackTouchEvent(event);
                attemptClaimDrag();

                break;
            case MotionEvent.ACTION_MOVE:
                if (mPressedThumb != null) {

                    if (mIsDragging) {
                        trackTouchEvent(event);
                    }
                    else {
                        // Scroll to follow the motion event
                        pointerIndex = event.findPointerIndex(mActivePointerId);
                        final float x = event.getX(pointerIndex);

                        if (Math.abs(x - mDownMotionX) > mScaledTouchSlop) {
                            setPressed(true);
                            invalidate();
                            onStartTrackingTouch();
                            trackTouchEvent(event);
                            attemptClaimDrag();
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (mIsDragging) {
                    trackTouchEvent(event);
                    onStopTrackingTouch();
                    setPressed(false);
                } else {
                    if (playBarTouched(event)) {
                        if (mPlayer.isPlaying()) {
                            mPlayer.pause();
                        } else {
                            if (mHasReachedEnd) {
                                mHasReachedEnd = false;
                                mHasDragged = false;
                                mPlayer.seekTo(normalizedValueToTime(mNormalizedMinValue));
                            } else if (mHasDragged) {
                                 mHasDragged = false;
                                 mPlayer.seekTo((int) (mSeekBarPosition * mPlayer.getDuration() / getWidth()));
                            }

                            mPlayer.start();
                            mHandler.sendEmptyMessage(SHOW_PROGRESS);
                        }
                    }
                }

                mPressedThumb = null;
                invalidate();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                final int index = event.getPointerCount() - 1;
                mDownMotionX = event.getX(index);
                mActivePointerId = event.getPointerId(index);
                invalidate();
                break;
            case MotionEvent.ACTION_CANCEL:
                if (mIsDragging) {
                    onStopTrackingTouch();
                    setPressed(false);
                }
                invalidate(); // see above explanation
                break;
        }
        return true;
    }

    private boolean playBarTouched(MotionEvent event) {
        return event.getY() >= getHeight() - mPlayBarHeight;
    }

    private final void trackTouchEvent(MotionEvent event) {
        final int pointerIndex = event.findPointerIndex(mActivePointerId);
        try {
            final float x = event.getX(pointerIndex);

            if (Thumb.MIN.equals(mPressedThumb)) {
                setNormalizedMinValue(screenToNormalized(x));
            }
            else if (Thumb.MAX.equals(mPressedThumb)) {
                setNormalizedMaxValue(screenToNormalized(x));
            }
        } catch (IllegalArgumentException e) {
         // Ignore pointerIndex out of range
        }
    }

    /**
     * Decides which (if any) thumb is touched by the given x-coordinate.
     *
     * @param touchX
     *            The x-coordinate of a touch event in screen space.
     * @return The pressed thumb or null if none has been touched.
     */
    private Thumb evalPressedThumb(float touchX, float touchY) {
        Thumb result = null;

        if (touchY > getFilmRollHeight()) {
            return null;
        }

        boolean minThumbPressed = isInThumbRange(touchX, mNormalizedMinValue);
        boolean maxThumbPressed = isInThumbRange(touchX, mNormalizedMaxValue);

        if (minThumbPressed) {
            result = Thumb.MIN;
        }
        else if (maxThumbPressed) {
            result = Thumb.MAX;
        }

        return result;
    }

    /**
     * Decides if given x-coordinate in screen space needs to be interpreted as "within" the normalized thumb x-coordinate.
     *
     * @param touchX
     *            The x-coordinate in screen space to check.
     * @param normalizedThumbValue
     *            The normalized x-coordinate of the thumb to check.
     * @return true if x-coordinate is in thumb range, false otherwise.
     */
    private boolean isInThumbRange(float touchX, double normalizedThumbValue) {
        return Math.abs(touchX - normalizedToScreen(normalizedThumbValue)) <= mThumbHalfWidth * 3;
    }

    /**
     * Sets normalized min value to value so that 0 <= value <= normalized max value <= 1. The View will get invalidated when calling this method.
     *
     * @param value
     *            The new normalized min value to set.
     */
    public void setNormalizedMinValue(double value) {
        float maxScreenCoord = normalizedToScreen(mNormalizedMaxValue) - mThumbWidth * 2;
        mNormalizedMinValue = Math.max(0d, Math.min(1d, Math.min(value, screenToNormalized(maxScreenCoord))));
        updatePlayerPreview(mNormalizedMinValue);

        if ((mNormalizedMaxValue - mNormalizedMinValue) * mPlayer.getDuration() / 1000d > mMaxDuration) {
            mNormalizedMaxValue = (mMaxDuration * 1000d / mPlayer.getDuration() + mNormalizedMinValue);
        }

        invalidate();
    }

    /**
     * Sets normalized max value to value so that 0 <= normalized min value <= value <= 1. The View will get invalidated when calling this method.
     *
     * @param value The new normalized max value to set.
     */
    public void setNormalizedMaxValue(double value) {
        float minScreenCoord = normalizedToScreen(mNormalizedMinValue) + mThumbWidth * 2;
        mNormalizedMaxValue = Math.max(0d, Math.min(1d, Math.max(value, screenToNormalized(minScreenCoord))));
        updatePlayerPreview(mNormalizedMaxValue);

        if ((mNormalizedMaxValue - mNormalizedMinValue) * mPlayer.getDuration() / 1000d > mMaxDuration) {
            mNormalizedMinValue = (mMaxDuration * 1000d / mPlayer.getDuration() - mNormalizedMaxValue) * -1;
        }

        invalidate();
    }

    private void updatePlayerPreview(double progress) {
        int duration = mPlayer.getDuration();
        double newPosition = duration * progress;
        mPlayer.seekTo((int) newPosition);
    }

    /**
     * Converts screen space x-coordinates into normalized values.
     *
     * @param screenCoord
     *            The x-coordinate in screen space to convert.
     * @return The normalized value.
     */
    private double screenToNormalized(float screenCoord) {
        int width = getWidth();
        if (width <= 2 * mPadding) {
            // prevent division by zero, simply return 0.
            return 0d;
        }
        else {
            double result = (screenCoord - mPadding) / (width - 2 * mPadding);
            return Math.min(1d, Math.max(0d, result));
        }
    }

    /**
     * Tries to claim the user's drag motion, and requests disallowing any ancestors from stealing events in the drag.
     */
    private void attemptClaimDrag() {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    /**
     * This is called when the user has started touching this widget.
     */
    void onStartTrackingTouch() {
        mIsDragging = true;
        mPlayer.pause();
    }

    /**
     * This is called when the user either releases his touch or the touch is canceled.
     */
    void onStopTrackingTouch() {
        mIsDragging = false;
        mHasDragged = true;

        if (Thumb.MAX.equals(mPressedThumb) && mSeekBarPosition >= normalizedToScreen(mNormalizedMaxValue)) {
            mHasReachedEnd = true;
        } else if (Thumb.MIN.equals(mPressedThumb) && mSeekBarPosition <= normalizedToScreen(mNormalizedMinValue)) {
            mHasReachedEnd = true;
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
