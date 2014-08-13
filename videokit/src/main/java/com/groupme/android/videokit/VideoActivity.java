package com.groupme.android.videokit;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.MediaStore;

public class VideoActivity extends Activity {
    private static final int REQUEST_CODE = 0x24;

    private String mAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAction = getIntent().getAction();

        if (!Intent.ACTION_GET_CONTENT.equals(mAction) && !MediaStore.ACTION_VIDEO_CAPTURE.equals(mAction)) {
            throw new IllegalStateException("You must set an action of " +
                    "Intent.ACTION_GET_CONTENT or MediaStore.ACTION_VIDEO_CAPTURE");
        }

        doSomething();
    }

    private void doSomething() {
        if (MediaStore.ACTION_VIDEO_CAPTURE.equals(mAction)) {
            startVideoCapture();
        }

        startGetContent();
    }

    private void startVideoCapture() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtras(getIntent());
        startActivityForResult(intent, REQUEST_CODE);
    }

    private void startGetContent() {

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE) {

        }
    }
}