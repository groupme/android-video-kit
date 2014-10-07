package com.groupme.android.videokit.util;

import android.os.Build;

public class DeviceUtils {

    /* This is a blacklist of devices that cannot properly read a video file's bitrate using
     * MediaMetadataRetriever
     */
    private static final String[] DEVICE_BLACKLIST = new String[]{
            "d2", // Galaxy S3
            "m0",
            "m3",
            "golden", // Galaxy S3 mini
            "jflte", // Galaxy S4
            "ja3g",
            "jalte",
            "jfvelte",
            "ks01",
            "mproject", // Galaxy S4 Zoom
            "serrano", // Galaxy S4 mini
            "jactive", // Galaxy S4 Active
            "t0", // Note 2
            "SC-02E",
            "hlte", // Note 3
            "ha3g",
            "hl3g",
            "SCL22",
            "fresco",
            "SC-02F",
    };

    public static boolean deviceisOnBlacklist() {
        if (Build.DEVICE != null) {
            for (String device : DEVICE_BLACKLIST) {
                if (Build.DEVICE.startsWith(device)) {
                    return true;
                }
            }
        }

        return false;
    }
}
