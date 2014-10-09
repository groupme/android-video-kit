package com.groupme.android.videokit.util;

import android.util.Log;

/**
 * Created by johnlotito on 10/9/14.
 */
public interface Logger {
    void d(String message);
    void v(String message);
    void v(Throwable e);
    void i(String message);
    void w(String message);
    void w(Throwable e);
    void e(String message);
    void e(Throwable e);
}
