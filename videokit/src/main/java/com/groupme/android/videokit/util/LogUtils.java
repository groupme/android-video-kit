package com.groupme.android.videokit.util;

import android.util.Log;

public class LogUtils {
    public static final String LOG_TAG = "VideoKit";

    public static boolean isLoggable(int level) {
        return Log.isLoggable(LOG_TAG, level);
    }

    public static void d(String message) {
        if (isLoggable(Log.DEBUG)) {
            Log.d(LOG_TAG, message);
        }
    }

    public static void d(String message, Object... args) {
        if (isLoggable(Log.DEBUG)) {
            Log.d(LOG_TAG, String.format(message, args));
        }
    }

    public static void v(String message) {
        if (isLoggable(Log.VERBOSE)) {
            Log.v(LOG_TAG, message);
        }
    }

    public static void v(Throwable e) {
        if (isLoggable(Log.VERBOSE)) {
            Log.v(LOG_TAG, Log.getStackTraceString(e));
        }
    }

    public static void e(String message) {
        if (isLoggable(Log.ERROR)) {
            Log.e(LOG_TAG, message);
        }
    }

    public static void e(Throwable e) {
        if (isLoggable(Log.ERROR)) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
        }
    }

    public static void i(String message) {
        if (isLoggable(Log.INFO)) {
            Log.i(LOG_TAG, message);
        }
    }

    public static void w(String message) {
        if (isLoggable(Log.WARN)) {
            Log.w(LOG_TAG, message);
        }
    }

    public static void w(Throwable e) {
        if (isLoggable(Log.WARN)) {
            Log.w(LOG_TAG, Log.getStackTraceString(e));
        }
    }

    private LogUtils() {
        /* no need to create an instance of this class. */
    }
}
