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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;

import com.groupme.android.videokit.R;

/**
 * The trim time bar view, which includes the current and total time, the progress
 * bar, and the scrubbers for current time, start and end time for trimming.
 */
public class TrimTimeBar extends TimeBar {

    public static final int SCRUBBER_NONE = 0;
    public static final int SCRUBBER_START = 1;
    public static final int SCRUBBER_CURRENT = 2;
    public static final int SCRUBBER_END = 3;

    private final Paint mTrimSelectionPaint;
    private int mPressedThumb = SCRUBBER_NONE;

    // On touch event, the setting order is Scrubber Position -> Time ->
    // PlayedBar. At the setTimes(), activity can update the Time directly, then
    // PlayedBar will be updated too.
    private int mTrimStartScrubberLeft;
    private int mTrimEndScrubberLeft;

    private int mTrimStartScrubberTop;
    private int mTrimEndScrubberTop;

    private int mTrimStartTime;
    private int mTrimEndTime;

    private final Bitmap mTrimStartScrubber;
    private final Bitmap mTrimEndScrubber;
    private int mMaxDuration;
    private int mProgressY;
    private Rect mSelectionBar;
    private int mMinDuration = 1 * 1000; // 1 second

    public TrimTimeBar(Context context, Listener listener) {
        super(context, listener);
        mSelectionBar = new Rect();
        mTrimSelectionPaint = new Paint();
        mTrimSelectionPaint.setColor(0xFFFFFFFF);
        mTrimStartTime = 0;
        mTrimEndTime = 0;
        mTrimStartScrubberLeft = 0;
        mTrimEndScrubberLeft = 0;
        mTrimStartScrubberTop = 0;
        mTrimEndScrubberTop = 0;

        mTrimStartScrubber = BitmapFactory.decodeResource(getResources(),
                R.drawable.trim_scrubber);
        mTrimEndScrubber = BitmapFactory.decodeResource(getResources(),
                R.drawable.trim_scrubber);
        // Increase the size of this trimTimeBar, but minimize the scrubber
        // touch padding since we have 3 scrubbers now.
        mScrubberPadding = 0;
        mVPaddingInPx = mVPaddingInPx * 3 / 2;
    }

    private int getBarPosFromTime(int time) {
        return mProgressBar.left +
                (int) ((mProgressBar.width() * (long) time) / mTotalTime);
    }

    private int trimStartScrubberTipOffset() {
        return mTrimStartScrubber.getWidth() / 2;
    }

    private int trimEndScrubberTipOffset() {
        return mTrimEndScrubber.getWidth() / 2;
    }

    // Based on all the time info (current, total, trimStart, trimEnd), we
    // decide the playedBar size.
    private void updatePlayedBarAndScrubberFromTime() {
        // According to the Time, update the Played Bar
        mPlayedBar.set(mProgressBar);
        mSelectionBar.set(mProgressBar);

        if (mTotalTime > 0) {
            // set playedBar according to the trim time.
            mPlayedBar.left = getBarPosFromTime(mTrimStartTime);
            mPlayedBar.right = getBarPosFromTime(mCurrentTime);
            mSelectionBar.left = getBarPosFromTime(mTrimStartTime);
            mSelectionBar.right = getBarPosFromTime(mTrimEndTime);

            if (!mScrubbing) {
                mScrubberLeft = mPlayedBar.right - mScrubber.getWidth() / 2;
                mTrimStartScrubberLeft = mPlayedBar.left - trimStartScrubberTipOffset();
                mTrimEndScrubberLeft = getBarPosFromTime(mTrimEndTime)
                        - trimEndScrubberTipOffset();
            }
        } else {
            // If the video is not prepared, just show the scrubber at the end
            // of progressBar
            mPlayedBar.right = mProgressBar.left;
            mScrubberLeft = mProgressBar.left - mScrubber.getWidth() / 2;
            mTrimStartScrubberLeft = mProgressBar.left - trimStartScrubberTipOffset();
            mTrimEndScrubberLeft = mProgressBar.right - trimEndScrubberTipOffset();
        }
    }

    private void initTrimTimeIfNeeded() {
        if (mTotalTime > 0 && mTrimEndTime == 0) {
            mTrimEndTime = Math.min(mTotalTime, mMaxDuration);
        }
    }

    private void update() {
        initTrimTimeIfNeeded();
        updatePlayedBarAndScrubberFromTime();
        invalidate();
    }

    @Override
    public void setTime(int currentTime, int totalTime,
            int trimStartTime, int trimEndTime) {
        if (mCurrentTime == currentTime && mTotalTime == totalTime
                && mTrimStartTime == trimStartTime && mTrimEndTime == trimEndTime) {
            return;
        }
        mCurrentTime = currentTime;
        mTotalTime = totalTime;
        mTrimStartTime = trimStartTime;
        mTrimEndTime = trimEndTime;
        update();
    }

    private int whichScrubber(float x, float y) {
        if (inScrubber(x, y, mTrimStartScrubberLeft, mTrimStartScrubberTop, mTrimStartScrubber)) {
            return SCRUBBER_START;
        } else if (inScrubber(x, y, mTrimEndScrubberLeft, mTrimEndScrubberTop, mTrimEndScrubber)) {
            return SCRUBBER_END;
        } else if (inScrubber(x, y, mScrubberLeft, mScrubberTop, mScrubber)) {
            return SCRUBBER_CURRENT;
        }
        return SCRUBBER_NONE;
    }

    private boolean inScrubber(float x, float y, int startX, int startY, Bitmap scrubber) {
        int scrubberRight = startX + scrubber.getWidth();
        int scrubberBottom = startY + scrubber.getHeight();
        return startX < x && x < scrubberRight && startY < y && y < scrubberBottom;
    }

    private int clampScrubber(int scrubberLeft, int offset, int lowerBound, int upperBound) {
        int max = upperBound - offset;
        int min = lowerBound - offset;
        return Math.min(max, Math.max(min, scrubberLeft));
    }

    private int getScrubberTime(int scrubberLeft, int offset) {
        return (int) ((long) (scrubberLeft + offset - mProgressBar.left)
                * mTotalTime / mProgressBar.width());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        if (!mShowTimes && !mShowScrubber) {
            mProgressBar.set(0, 0, w, h);
        } else {
            int margin = mScrubber.getWidth() / 3;
            if (mShowTimes) {
                margin += mTimeBounds.width();
            }
            mProgressY = h / 2;
            int scrubberY = mProgressY - mScrubber.getHeight() / 2 + 1;
            mScrubberTop = scrubberY;
            mTrimStartScrubberTop = mProgressY - mTrimStartScrubber.getHeight() / 2 + 1;
            mTrimEndScrubberTop = mProgressY - mTrimEndScrubber.getHeight() / 2 + 1;
            mProgressBar.set(
                    getPaddingLeft() + margin, mProgressY,
                    w - getPaddingRight() - margin, mProgressY + 4);
        }
        update();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // draw progress bars
        canvas.drawRect(mProgressBar, mProgressPaint);
        canvas.drawRect(mSelectionBar, mTrimSelectionPaint);
        canvas.drawRect(mPlayedBar, mPlayedPaint);

        if (mShowTimes) {
            canvas.drawText(
                    stringForTime(mCurrentTime),
                            mTimeBounds.width() / 2 + getPaddingLeft(),
                            mTimeBounds.height() / 2 +  mProgressY,
                    mTimeTextPaint);
            canvas.drawText(
                    stringForTime(mTotalTime),
                            getWidth() - getPaddingRight() - mTimeBounds.width() / 2,
                            mTimeBounds.height() / 2 +  mProgressY,
                    mTimeTextPaint);
        }

        // draw extra scrubbers
        if (mShowScrubber) {
            canvas.drawBitmap(mTrimStartScrubber, mTrimStartScrubberLeft,
                    mTrimStartScrubberTop, null);
            canvas.drawBitmap(mTrimEndScrubber, mTrimEndScrubberLeft,
                    mTrimEndScrubberTop, null);
            canvas.drawBitmap(mScrubber, mScrubberLeft, mScrubberTop, null);
        }
    }

    private void updateTimeFromPos() {
        mCurrentTime = getScrubberTime(mScrubberLeft, mScrubber.getWidth() / 2);
        mTrimStartTime = getScrubberTime(mTrimStartScrubberLeft, trimStartScrubberTipOffset());
        mTrimEndTime = getScrubberTime(mTrimEndScrubberLeft, trimEndScrubberTipOffset());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mShowScrubber) {
            int x = (int) event.getX();
            int y = (int) event.getY();

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mPressedThumb = whichScrubber(x, y);
                    switch (mPressedThumb) {
                        case SCRUBBER_NONE:
                            break;
                        case SCRUBBER_CURRENT:
                            mScrubbing = true;
                            mScrubberCorrection = x - mScrubberLeft;
                            break;
                        case SCRUBBER_START:
                            mScrubbing = true;
                            mScrubberCorrection = x - mTrimStartScrubberLeft;
                            break;
                        case SCRUBBER_END:
                            mScrubbing = true;
                            mScrubberCorrection = x - mTrimEndScrubberLeft;
                            break;
                    }
                    if (mScrubbing == true) {
                        mListener.onScrubbingStart();
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mScrubbing) {
                        int seekToTime = -1;
                        int lowerBound = mTrimStartScrubberLeft + trimStartScrubberTipOffset();
                        int upperBound = mTrimEndScrubberLeft + trimEndScrubberTipOffset();
                        switch (mPressedThumb) {
                            case SCRUBBER_CURRENT:
                                mScrubberLeft = x - mScrubberCorrection;
                                mScrubberLeft =
                                        clampScrubber(mScrubberLeft,
                                                mScrubber.getWidth() / 2,
                                                lowerBound, upperBound);
                                seekToTime = getScrubberTime(mScrubberLeft,
                                        mScrubber.getWidth() / 2);
                                break;
                            case SCRUBBER_START:
                                mTrimStartScrubberLeft = x - mScrubberCorrection;
                                // Limit start <= end
                                if (mTrimStartScrubberLeft > mTrimEndScrubberLeft) {
                                    mTrimStartScrubberLeft = mTrimEndScrubberLeft;
                                }

                                upperBound = (int) (upperBound - (float) mMinDuration / mTotalTime * mProgressBar.width());
                                mTrimStartScrubberLeft =
                                        clampScrubber(mTrimStartScrubberLeft,
                                                trimStartScrubberTipOffset(),
                                                mProgressBar.left, upperBound);
                                seekToTime = getScrubberTime(mTrimStartScrubberLeft,
                                        trimStartScrubberTipOffset());

                                float minTime = mTrimEndTime - mMaxDuration;
                                lowerBound = (int) Math.max(mProgressBar.left, minTime / mTotalTime * mProgressBar.width() + mProgressBar.left);

                                if (mTrimStartScrubberLeft + trimStartScrubberTipOffset() <= lowerBound) {
                                    float maxTime = mTrimStartTime + mMaxDuration;
                                    upperBound = (int) Math.min(mProgressBar.right, maxTime / mTotalTime * mProgressBar.width() + mProgressBar.left);

                                    mTrimEndScrubberLeft = clampScrubber(mTrimEndScrubberLeft,
                                            trimEndScrubberTipOffset(),
                                            mTrimStartScrubberLeft + trimStartScrubberTipOffset(),
                                            upperBound);
                                }

                                break;
                            case SCRUBBER_END:
                                mTrimEndScrubberLeft = x - mScrubberCorrection;

                                lowerBound = (int) (lowerBound + (float) mMinDuration / mTotalTime * mProgressBar.width());
                                mTrimEndScrubberLeft =
                                        clampScrubber(mTrimEndScrubberLeft,
                                                trimEndScrubberTipOffset(),
                                                lowerBound, mProgressBar.right);
                                seekToTime = getScrubberTime(mTrimEndScrubberLeft,
                                        trimEndScrubberTipOffset());

                                float maxTime = mTrimStartTime + mMaxDuration;
                                upperBound = (int) Math.min(mProgressBar.right, maxTime / mTotalTime * mProgressBar.width() + mProgressBar.left);

                                if (mTrimEndScrubberLeft + trimEndScrubberTipOffset() >= upperBound) {
                                    minTime = mTrimEndTime - mMaxDuration;
                                    lowerBound = (int) Math.max(mProgressBar.left, minTime / mTotalTime * mProgressBar.width() + mProgressBar.left);

                                    mTrimStartScrubberLeft = clampScrubber(mTrimStartScrubberLeft,
                                            trimStartScrubberTipOffset(),
                                            lowerBound,
                                            mTrimEndScrubberLeft + trimEndScrubberTipOffset());
                                }

                                break;
                        }
                        updateTimeFromPos();
                        updatePlayedBarAndScrubberFromTime();
                        if (seekToTime != -1) {
                            mListener.onScrubbingMove(seekToTime);
                        }
                        invalidate();
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    if (mScrubbing) {
                        int seekToTime = 0;
                        switch (mPressedThumb) {
                            case SCRUBBER_CURRENT:
                                seekToTime = getScrubberTime(mScrubberLeft,
                                        mScrubber.getWidth() / 2);
                                break;
                            case SCRUBBER_START:
                                seekToTime = getScrubberTime(mTrimStartScrubberLeft,
                                        trimStartScrubberTipOffset());
                                mScrubberLeft = mTrimStartScrubberLeft +
                                        trimStartScrubberTipOffset() - mScrubber.getWidth() / 2;
                                break;
                            case SCRUBBER_END:
                                seekToTime = getScrubberTime(mTrimEndScrubberLeft,
                                        trimEndScrubberTipOffset());
                                mScrubberLeft = mTrimEndScrubberLeft +
                                        trimEndScrubberTipOffset() - mScrubber.getWidth() / 2;
                                break;
                        }
                        updateTimeFromPos();
                        updatePlayedBarAndScrubberFromTime();
                        mListener.onScrubbingEnd(seekToTime,
                                getScrubberTime(mTrimStartScrubberLeft,
                                        trimStartScrubberTipOffset()),
                                getScrubberTime(mTrimEndScrubberLeft, trimEndScrubberTipOffset()));
                        mScrubbing = false;
                        mPressedThumb = SCRUBBER_NONE;
                        return true;
                    }
                    break;
            }
        }
        return false;
    }

    public void setMaxDuration(int maxDuration) {
        mMaxDuration = maxDuration;
    }
}
