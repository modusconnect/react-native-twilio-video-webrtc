package com.twiliorn.library;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Build;

import androidx.annotation.RequiresApi;

import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.module.annotations.ReactModule;
import com.twiliorn.library.utils.SafePromise;
import com.twiliorn.library.utils.webrtcaudio.CustomAudioDevice;

import javax.annotation.Nonnull;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
@ReactModule(name = "RNTwilioModule")
public class TwilioModule extends ReactContextBaseJavaModule {
    static final String TAG = TwilioModule.class.getCanonicalName();
    final CameraManager cameraManager;

    @Nonnull
    @Override
    public String getName() {
        return "RNTwilioModule";
    }

    public TwilioModule(ReactApplicationContext context) {
        super(context);
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        if (CustomTwilioVideoView.getCustomAudioDevice() == null) {
            Log.d(TAG, "customAudioDevice is null - Initializing new custom audio device");
            CustomTwilioVideoView.setCustomAudioDevice(new CustomAudioDevice(context));
        }

    }

    @ReactMethod
    public void getAvailableCameras(Promise promise) {
        try {
            String[] cameras = this.cameraManager.getCameraIdList();
            WritableArray writableArray = Arguments.createArray();
            for (String camera : cameras) {
                writableArray.pushString(camera);
            }
            promise.resolve(writableArray);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            promise.reject(e);
        }
    }


    @ReactMethod
    public void getAvailableLocalTracks(Promise promise) {
        try {
            String[] tracks = CustomTwilioVideoView.getAvailableLocalTracks();
            WritableArray writableArray = Arguments.createArray();

            for (String str : tracks) {
                writableArray.pushString(str);
            }

            promise.resolve(writableArray);
        } catch (Exception e) {
            promise.reject("Create Event Error", e);
        }
    }

    @ReactMethod
    public void streamAudioFile(String path, Promise promise) {
        SafePromise safePromise = new SafePromise(promise);

        CustomTwilioVideoView.streamAudioFile(path, safePromise);
    }

    @ReactMethod
    public void streamDefaultMic(Promise promise) {
        SafePromise safePromise = new SafePromise(promise);
        CustomTwilioVideoView.streamDefaultMic(safePromise);
    }

    @ReactMethod
    public void stopMicRecording(Promise promise) {
        SafePromise safePromise = new SafePromise(promise);
        CustomTwilioVideoView.stopMicRecording(safePromise);
    }
}
