package com.groupme.android.videokit.util;

import android.annotation.SuppressLint;
import android.util.Log;

@SuppressLint("LogTagMismatch")
public class DefaultLogger implements Logger  {
    public static final String LOG_TAG = "VideoKit";

    @Override
    public void d(String message) {
        if (isLoggable(Log.DEBUG)) {
            Log.d(LOG_TAG, message);
        }
    }

    @Override
    public void v(String message) {
        if (isLoggable(Log.VERBOSE)) {
            Log.v(LOG_TAG, message);
        }
    }

    @Override
    public void v(Throwable e) {
        if (isLoggable(Log.VERBOSE)) {
            Log.v(LOG_TAG, Log.getStackTraceString(e));
        }
    }

    @Override
    public void e(String message) {
        if (isLoggable(Log.ERROR)) {
            Log.e(LOG_TAG, message);
        }
    }

    @Override
    public void e(Throwable e) {
        if (isLoggable(Log.ERROR)) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
        }
    }

    @Override
    public void i(String message) {
        if (isLoggable(Log.INFO)) {
            Log.i(LOG_TAG, message);
        }
    }

    @Override
    public void w(String message) {
        if (isLoggable(Log.WARN)) {
            Log.w(LOG_TAG, message);
        }
    }

    @Override
    public void w(Throwable e) {
        if (isLoggable(Log.WARN)) {
            Log.w(LOG_TAG, Log.getStackTraceString(e));
        }
    }

    private boolean isLoggable(int level) {
        return Log.isLoggable(LOG_TAG, level);
    }
}
