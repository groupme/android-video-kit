package com.groupme.android.videokit;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
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
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.MediaController;

import java.util.ArrayList;

@TargetApi(Build.VERSION_CODES.GINGERBREAD_MR1)
public class FilmRollView extends View {
    private static final String KEY_FRAME_WIDTH = "frame_width";
    private static final String KEY_FRAME = "frame";
    private static final String KEY_FRAME_COUNT = "frame_count";
    private static final String KEY_INSTANCE_STATE = "instance_state";
    private static final int SHOW_PROGRESS = 1;
    private static final int STOP = 2;
    private static final String KEY_LANDSCAPE_FRAME = "frame_land";
    private static final String KEY_LANDSCAPE_FRAME_COUNT = "frame_count_land";
    private static final String KEY_MIN_VALUE = "min_value";
    private static final String KEY_MAX_VALUE = "max_value";
    private static final String KEY_SEEK_VALUE = "seek_value";
    private static final String KEY_IS_PLAYING = "is_playing";
    private static final java.lang.String KEY_IS_SEEK_AT_BEGINNING = "seek_at_beginning";
    private static final String KEY_HAS_REACHED_END = "is_at_end";
    private static final String KEY_SAVED_BITMAP_SIZE = "bitmap_size";
    private static final String KEY_SAVED_MORE_BITMAP_SIZE = "more_bitmap_size";
    private static Paint sBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private static Paint sThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static Paint sWhite75Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static Paint sWhitePaint = new Paint();
    private static Paint sLinePaint = new Paint();
    private static Paint sBackgroundPaint = new Paint();
    private static Paint sBlackPaint = new Paint();

    private final int mLineWidth;
    private int mActivePointerId;
    private float mDownMotionX;
    private Thumb mPressedThumb;
    private boolean mIsDragging;
    private float mScaledTouchSlop;
    private MediaController.MediaPlayerControl mPlayer;
    // The maximum duration of the final, trimmed video in seconds
    private int mMaxDuration;
    private double mNormalizedSeekValue = 0d;
    private boolean mHasDragged;
    private boolean mHasReachedEnd;
    private boolean mSeekAtBeginning = true;
    private ArrayList<Bitmap> mBitmaps = new ArrayList<Bitmap>();
    private ArrayList<Bitmap> mMoreBitmaps = new ArrayList<Bitmap>();
    private MediaMetadataRetriever mMetaDataExtractor;
    private Rect mRectangle = new Rect(0, 0, 0, 0);
    private int mScaledFrameWidth;
    private int mFrameCount;
    private int mLandscapeFrameCount;
    private final float mThumbWidth = getResources().getDimensionPixelSize(R.dimen.handle_width);
    private final float mThumbHalfWidth = 0.5f * mThumbWidth;
    private final int mPadding = (int) mThumbHalfWidth * 3;
    private final float mPlayBarHeight = getResources().getDimensionPixelSize(R.dimen.play_bar_height);
    private double mNormalizedMinValue = 0d;
    private double mNormalizedMaxValue = 1d;
    private final Bitmap mPlayButton = BitmapFactory.decodeResource(getResources(), android.R.drawable.ic_media_play);
    private final Bitmap mPauseButton = BitmapFactory.decodeResource(getResources(), android.R.drawable.ic_media_pause);
    private int mDuration;
    private boolean mIsPlaying;
    private boolean mFrameRetrieverStarted;

    private static enum Thumb {
        MIN, MAX
    }

    public FilmRollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        sThumbPaint.setColor(getResources().getColor(R.color.gold));
        sWhite75Paint.setColor(getResources().getColor(R.color.white_75));
        sWhitePaint.setColor(getResources().getColor(android.R.color.white));
        sLinePaint.setStyle(Paint.Style.STROKE);
        mLineWidth = getResources().getDimensionPixelSize(R.dimen.line_height);
        sLinePaint.setStrokeWidth(mLineWidth);
        sLinePaint.setColor(getResources().getColor(R.color.gold));
        sBackgroundPaint.setColor(getResources().getColor(R.color.black85));
        sBlackPaint.setColor(Color.BLACK);

        mScaledTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        setClickable(true);
    }


    public void setMediaPlayer(MediaController.MediaPlayerControl player) {
        mPlayer = player;
    }

    public void setMaxDuration(int duration) {
        mMaxDuration = duration;
    }

    public void setVideoUri(Uri uri) {
        mMetaDataExtractor = new MediaMetadataRetriever();

        if (uri.getScheme().equals(ContentResolver.SCHEME_FILE)) {
            mMetaDataExtractor.setDataSource(uri.getPath());
        } else {
            mMetaDataExtractor.setDataSource(getContext(), uri);
        }

        mDuration = Integer.parseInt(mMetaDataExtractor.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        mNormalizedMaxValue = Math.min(mDuration, mMaxDuration * 1000d) / mDuration + mNormalizedMinValue;
    }

    public int getStartTime() {
        return (int) (mNormalizedMinValue * mDuration);
    }

    public int getEndTime() {
        return (int) (mNormalizedMaxValue * mDuration);
    }

    Thread mFrameRetriever = new Thread(new Runnable() {
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
            mFrameCount = (int) ((float) getFilmRollSmallerWidth() / mScaledFrameWidth);
            // The time between frames in micro-seconds
            int timeBetweenFrames = (int) (duration / mFrameCount) * 1000;
            int timeOffset = 0;

            for (int i = mBitmaps.size(); i < mFrameCount; i++) {
                if (mFrameRetriever.isInterrupted()) {
                    return;
                }

                Bitmap frame = mMetaDataExtractor.getFrameAtTime(timeOffset);
                mBitmaps.add(frame);
                timeOffset += timeBetweenFrames;

                if (!mIsPlaying) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            invalidate();
                        }
                    });
                }
            }

            mLandscapeFrameCount = (int) ((float) getFilmRollLargerWidth() / mScaledFrameWidth);
            timeBetweenFrames = (int) (duration / mLandscapeFrameCount) * 1000;
            timeOffset = 0;

            for (int i = mMoreBitmaps.size(); i < mLandscapeFrameCount; i++) {
                if (mFrameRetriever.isInterrupted()) {
                    return;
                }

                Bitmap frame = mMetaDataExtractor.getFrameAtTime(timeOffset);
                mMoreBitmaps.add(frame);
                timeOffset += timeBetweenFrames;

                if (!mIsPlaying) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            invalidate();
                        }
                    });
                }
            }
        }

    });

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SHOW_PROGRESS:
                    if (mIsPlaying) {
                        invalidate();
                        msg = obtainMessage(SHOW_PROGRESS);
                        sendMessageDelayed(msg, 200);
                    }
                    break;
                case STOP:
                    removeMessages(SHOW_PROGRESS);
                    break;
            }
        }
    };

    private int getFilmRollHeight() {
        return getResources().getDimensionPixelSize(R.dimen.film_roll_height);
    }

    private int getFilmRollWidth() {
        return getWidth() - 2 * mPadding;
    }

    private int getFilmRollLargerWidth() {
        WindowManager mWindowManager = (WindowManager) getContext().getSystemService(Activity.WINDOW_SERVICE);
        Display mDisplay = mWindowManager.getDefaultDisplay();

        if (mDisplay.getRotation() == Surface.ROTATION_0 || mDisplay.getRotation() == Surface.ROTATION_180) {
            return getHeight() - 2 * mPadding;
        } else {
            return getWidth() - 2 * mPadding;
        }
    }

    private int getFilmRollSmallerWidth() {
        WindowManager mWindowManager = (WindowManager) getContext().getSystemService(Activity.WINDOW_SERVICE);
        Display mDisplay = mWindowManager.getDefaultDisplay();

        if (mDisplay.getRotation() == Surface.ROTATION_0 || mDisplay.getRotation() == Surface.ROTATION_180) {
            return getWidth() - 2 * mPadding;
        } else {
            return getHeight() - 2 * mPadding;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (!mFrameRetrieverStarted) {
            mFrameRetrieverStarted = true;
            mFrameRetriever.start();
        }

        drawBorderFrame(canvas);

        if (mMetaDataExtractor != null) {
            canvas.save();
            canvas.translate(mPadding, 0);

            drawBitmaps(canvas);
            // draw minimum thumb
            drawLeftThumb(mNormalizedMinValue, canvas);

            // draw maximum thumb
            drawRightThumb(mNormalizedMaxValue, canvas);
            drawSeekBar(canvas);
            canvas.restore();

            drawPlayBar(canvas);
        }
    }

    private int normalizedValueToTime(double normalizedValue) {
        return (int) (normalizedValue * mDuration);
    }

    private void drawBorderFrame(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getFilmRollHeight() + mLineWidth * 2, sBackgroundPaint);
        canvas.drawRect(mPadding, mLineWidth, getWidth() - mPadding, getFilmRollHeight(), sBlackPaint);
    }

    private void drawBitmaps(Canvas canvas) {
        int horizontalOffset = 0;

        ArrayList<Bitmap> bitmaps = getBitmapsForOrientation();

        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null) {
                mRectangle.set(horizontalOffset, 0, mScaledFrameWidth + horizontalOffset, getFilmRollHeight());
                canvas.drawBitmap(bitmap, null, mRectangle, sBitmapPaint);
            }

            horizontalOffset += mScaledFrameWidth;
        }
    }

    private ArrayList<Bitmap> getBitmapsForOrientation() {
        WindowManager mWindowManager = (WindowManager) getContext().getSystemService(Activity.WINDOW_SERVICE);
        Display mDisplay = mWindowManager.getDefaultDisplay();

        if (mDisplay.getRotation() == Surface.ROTATION_0 || mDisplay.getRotation() == Surface.ROTATION_180) {
            return mBitmaps;
        } else {
            return mMoreBitmaps;
        }
    }

    private void drawLeftThumb(double minValue, Canvas canvas) {
        float screenCoord = (float) minValue * getFilmRollWidth();
        canvas.drawRect(0, 0, screenCoord - mThumbWidth, getFilmRollHeight(), sWhite75Paint);
        canvas.drawRect(screenCoord - mThumbWidth, 0, screenCoord, getFilmRollHeight(), sThumbPaint);
    }

    private void drawRightThumb(double maxValue, Canvas canvas) {
        float screenCoord = (float) maxValue * getFilmRollWidth();
        canvas.drawRect(screenCoord + mThumbHalfWidth, 0, getFilmRollWidth(), getFilmRollHeight(), sWhite75Paint);
        canvas.drawLine((float) mNormalizedMinValue * getFilmRollWidth(), getFilmRollHeight() - mLineWidth / 2L, screenCoord, getFilmRollHeight() - mLineWidth / 2L, sLinePaint);
        canvas.drawLine(screenCoord, mLineWidth / 2L, (float) mNormalizedMinValue * getFilmRollWidth(), mLineWidth / 2L, sLinePaint);
        canvas.drawRect(screenCoord, 0, screenCoord + mThumbWidth, getFilmRollHeight(), sThumbPaint);
    }

    private void drawSeekBar(Canvas canvas) {
        if (!mIsDragging && !mHasDragged && mPlayer.getCurrentPosition() != 0) {
            mNormalizedSeekValue = (float) mPlayer.getCurrentPosition() / mDuration;
        }

        if (mNormalizedSeekValue >= mNormalizedMaxValue && mIsPlaying && !mIsDragging) {
            mPlayer.pause();
            mIsPlaying = false;
            mHasReachedEnd = true;
        }

        if (!mHasReachedEnd && !mSeekAtBeginning) {
            canvas.drawRect((float) mNormalizedSeekValue * getFilmRollWidth() - mThumbHalfWidth / 2L, 0, (float) mNormalizedSeekValue * getFilmRollWidth() + mThumbHalfWidth / 2L, getFilmRollHeight(), sWhitePaint);
        }
    }

    private void drawPlayBar(Canvas canvas) {
        canvas.drawRect(0, getHeight() - mPlayBarHeight, getWidth(), getHeight(), sWhite75Paint);
        int left = (getWidth() - mPlayButton.getWidth()) / 2;
        float top = getHeight() - mPlayBarHeight / 2 - mPlayButton.getScaledHeight(canvas) / 2;

        if (mIsPlaying) {
            canvas.drawBitmap(mPauseButton, left, top, sBitmapPaint);
        } else {
            canvas.drawBitmap(mPlayButton, left, top, sBitmapPaint);
        }
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
                        mSeekAtBeginning = false;

                        if (mIsPlaying) {
                            mPlayer.pause();
                            mIsPlaying = false;
                        } else {
                            if (mHasReachedEnd) {
                                mHasReachedEnd = false;
                                mHasDragged = false;
                                mPlayer.seekTo(normalizedValueToTime(mNormalizedMinValue) + 1);
                            } else if (mHasDragged) {
                                 mHasDragged = false;
                                 mPlayer.seekTo((int) (mNormalizedSeekValue * mDuration));
                            } else {
                                mPlayer.seekTo((int) (mNormalizedSeekValue * mDuration));
                            }

                            mPlayer.start();
                            mIsPlaying = true;
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
                setNormalizedMinValue(leftHandleTouchToNormalized(x));
            }
            else if (Thumb.MAX.equals(mPressedThumb)) {
                setNormalizedMaxValue(rightThumbTouchToNormalized(x));
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

        boolean minThumbPressed = isInMinThumbRange(touchX, mNormalizedMinValue);
        boolean maxThumbPressed = isInMaxThumbRange(touchX, mNormalizedMaxValue);

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
    private boolean isInMinThumbRange(float touchX, double normalizedThumbValue) {
        return Math.abs(touchX - (mPadding + normalizedThumbValue * getFilmRollWidth() - mThumbHalfWidth)) <= mThumbHalfWidth * 3;
    }

    private boolean isInMaxThumbRange(float touchX, double normalizedThumbValue) {
        return Math.abs(touchX - (mPadding + normalizedThumbValue * getFilmRollWidth() + mThumbHalfWidth)) <= mThumbHalfWidth * 3;
    }

    /**
     * Sets normalized min value to value so that 0 <= value <= normalized max value <= 1. The View will get invalidated when calling this method.
     *
     * @param value
     *            The new normalized min value to set.
     */
    public void setNormalizedMinValue(double value) {
        double maxValue = mNormalizedMaxValue - (mThumbWidth * 2 / getFilmRollWidth());
        mNormalizedMinValue = Math.max(0d, Math.min(1d, Math.min(value, maxValue)));
        updatePlayerPreview(mNormalizedMinValue);

        if ((mNormalizedMaxValue - mNormalizedMinValue) * mDuration / 1000d > mMaxDuration) {
            mNormalizedMaxValue = (mMaxDuration * 1000d / mDuration + mNormalizedMinValue);
        }

        invalidate();
    }

    /**
     * Sets normalized max value to value so that 0 <= normalized min value <= value <= 1. The View will get invalidated when calling this method.
     *
     * @param value The new normalized max value to set.
     */
    public void setNormalizedMaxValue(double value) {
        double minValue = mNormalizedMinValue + (mThumbWidth * 2 / getFilmRollWidth());
        mNormalizedMaxValue = Math.max(0d, Math.min(1d, Math.max(value, minValue)));
        updatePlayerPreview(mNormalizedMaxValue);

        if ((mNormalizedMaxValue - mNormalizedMinValue) * mDuration / 1000d > mMaxDuration) {
            double newValue = (mMaxDuration * 1000d / mDuration - mNormalizedMaxValue) * -1;

            if (Math.abs(mNormalizedSeekValue - mNormalizedMinValue) < 0.01d) {
                mNormalizedSeekValue = newValue;
            }

            mNormalizedMinValue = newValue;
        }

        invalidate();
    }

    private void updatePlayerPreview(double progress) {
        double newPosition = mDuration * progress;
        mPlayer.seekTo((int) newPosition);
    }

    private double leftHandleTouchToNormalized(float screenCoord) {
        return Math.min(1d, Math.max(0d, (screenCoord - mPadding + mThumbHalfWidth) / getFilmRollWidth()));
    }

    private double rightThumbTouchToNormalized(float screenCoord) {
        return Math.min(1d, Math.max(0d, (screenCoord - mPadding - mThumbHalfWidth) / getFilmRollWidth()));
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
        mIsPlaying = false;
    }

    /**
     * This is called when the user either releases his touch or the touch is canceled.
     */
    void onStopTrackingTouch() {
        mIsDragging = false;
        mHasDragged = true;

        if (Thumb.MAX.equals(mPressedThumb) && mNormalizedSeekValue >= mNormalizedMaxValue) {
            mHasReachedEnd = true;
        } else if (Thumb.MIN.equals(mPressedThumb) && mNormalizedSeekValue <= mNormalizedMinValue) {
            mHasReachedEnd = true;
        }
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(KEY_INSTANCE_STATE, super.onSaveInstanceState());

        if (mFrameRetriever.isAlive()) {
            mFrameRetriever.interrupt();
        }

        int size = mBitmaps.size();
        bundle.putInt(KEY_SAVED_BITMAP_SIZE, size);

        for (int i = 0; i < size; i++) {
            bundle.putParcelable(String.format("%s_%s", KEY_FRAME, i), mBitmaps.get(i));
        }

        int moreSize = mMoreBitmaps.size();
        bundle.putInt(KEY_SAVED_MORE_BITMAP_SIZE, moreSize);

        for (int i = 0; i < moreSize; i++) {
            bundle.putParcelable(String.format("%s_%s", KEY_LANDSCAPE_FRAME, i), mMoreBitmaps.get(i));
        }

        bundle.putInt(KEY_FRAME_WIDTH, mScaledFrameWidth);
        bundle.putInt(KEY_FRAME_COUNT, mFrameCount);
        bundle.putInt(KEY_LANDSCAPE_FRAME_COUNT, mLandscapeFrameCount);
        bundle.putDouble(KEY_MIN_VALUE, mNormalizedMinValue);
        bundle.putDouble(KEY_MAX_VALUE, mNormalizedMaxValue);
        bundle.putDouble(KEY_SEEK_VALUE, mNormalizedSeekValue);
        bundle.putBoolean(KEY_IS_SEEK_AT_BEGINNING, mSeekAtBeginning);
        bundle.putBoolean(KEY_IS_PLAYING, mIsPlaying);
        bundle.putBoolean(KEY_HAS_REACHED_END, mHasReachedEnd);
        mHandler.sendEmptyMessage(STOP);

        return bundle;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            Bundle bundle = (Bundle) state;
            mFrameCount = bundle.getInt(KEY_FRAME_COUNT);
            mScaledFrameWidth = bundle.getInt(KEY_FRAME_WIDTH);
            mLandscapeFrameCount = bundle.getInt(KEY_LANDSCAPE_FRAME_COUNT);
            mNormalizedMinValue = bundle.getDouble(KEY_MIN_VALUE);
            mNormalizedMaxValue = bundle.getDouble(KEY_MAX_VALUE);
            mNormalizedSeekValue = bundle.getDouble(KEY_SEEK_VALUE);
            mSeekAtBeginning = bundle.getBoolean(KEY_IS_SEEK_AT_BEGINNING);
            mIsPlaying = bundle.getBoolean(KEY_IS_PLAYING);
            mHasReachedEnd = bundle.getBoolean(KEY_HAS_REACHED_END);

            if (mIsPlaying) {
                mHandler.sendEmptyMessage(SHOW_PROGRESS);
            }

            for (int i = 0; i < bundle.getInt(KEY_SAVED_BITMAP_SIZE); i++) {
                mBitmaps.add((Bitmap) bundle.getParcelable(String.format("%s_%s", KEY_FRAME, i)));
            }

            for (int i = 0; i < bundle.getInt(KEY_SAVED_MORE_BITMAP_SIZE); i++) {
                mMoreBitmaps.add((Bitmap) bundle.getParcelable(String.format("%s_%s", KEY_LANDSCAPE_FRAME, i)));
            }

            state = bundle.getParcelable(KEY_INSTANCE_STATE);
        }

        super.onRestoreInstanceState(state);
    }
}
