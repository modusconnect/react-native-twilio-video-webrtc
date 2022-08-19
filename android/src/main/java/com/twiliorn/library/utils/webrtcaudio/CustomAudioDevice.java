package com.twiliorn.library.utils.webrtcaudio;


import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.twilio.video.AudioDevice;
import com.twilio.video.AudioDeviceContext;
import com.twilio.video.AudioFormat;
import com.twiliorn.library.utils.SafePromise;


public class CustomAudioDevice implements AudioDevice {
    public static final String TAG = "CustomAudioDevice";

    // Ask for a buffer size of BUFFER_SIZE_FACTOR * (minimum required buffer size). The extra space
    // is allocated to guard against glitches under high load.
    private static final short BUFFER_SIZE_FACTOR = 2;

    private final CustomWebrtcAudioTrack webRtcAudioTrack;
    private final CustomWebrtcAudioFile webRtcAudioFile;
    private final CustomWebrtcAudioRecord webRtcAudioRecord;
    private final Context context;


    //Contexts
    private AudioDeviceContext capturingAudioDeviceContext;

    private boolean isFilePlaying;

    public CustomAudioDevice(Context context) {
        AudioManager audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        this.context = context;
        this.webRtcAudioTrack = new CustomWebrtcAudioTrack(
                context,
                audioManager,
                null,
                null);
        this.webRtcAudioFile = new CustomWebrtcAudioFile(context, audioManager);
        this.webRtcAudioRecord = new CustomWebrtcAudioRecord(context, audioManager);
    }

    public void switchInputToFile(String path, SafePromise promise) {
        AudioFormat format = getCapturerFormat();

        this.stopRecording(promise, false);

        int didInit = this.webRtcAudioFile.init(
                format.getSampleRate(),
                format.getChannelCount(),
                path);

        if(didInit < 0) {
            promise.reject(String.valueOf(didInit), "Unable to init webRtcAudioFile");
            return;
        }

        isFilePlaying = true;

        boolean didStart = this.webRtcAudioFile.startFileReading(this.capturingAudioDeviceContext);
        if(!didStart) {
            promise.reject("-2", "Unable to start webRtcAudioFile");
        }

        promise.resolve(null);
    }

    public void switchInputToMic(SafePromise promise) {
        AudioFormat format = getCapturerFormat();
        this.stopRecording(promise, false);

        int didInit = this.webRtcAudioRecord.initRecording(
                format.getSampleRate(),
                format.getChannelCount());
        if(didInit < 0) {
            promise.reject(String.valueOf(didInit), "Unable to init mic recording");
            return;
        }

        if(!this.webRtcAudioRecord.startRecording(this.capturingAudioDeviceContext)) {
            promise.reject("-2", "Unable to start mic recording");

        }

        promise.resolve(null);
    }


    @Override
    public AudioFormat getCapturerFormat() {
        return new AudioFormat(AudioFormat.AUDIO_SAMPLE_RATE_48000,
                AudioFormat.AUDIO_SAMPLE_MONO);
    }

    @Override
    public boolean onInitCapturer() {
        AudioFormat format = getCapturerFormat();
        int retV = this.webRtcAudioRecord.initRecording(
                format.getSampleRate(),
                format.getChannelCount());
        if(retV < 0) {
            return false;
        }
        return true;
    }

    @Override
    public boolean onStartCapturing(AudioDeviceContext audioDeviceContext) {
        // Initialize the AudioDeviceContext
        capturingAudioDeviceContext = audioDeviceContext;
        isFilePlaying = false;

        return this.webRtcAudioRecord.startRecording(audioDeviceContext);
    }

    @Override
    public boolean onStopCapturing() {
        if (isFilePlaying) {
            isFilePlaying = false;
            return this.webRtcAudioFile.stopFileReading();
        } else {
            return this.webRtcAudioRecord.stopRecording();
        }
    }

    @Override
    public AudioFormat getRendererFormat() {
        AudioManager audioManager = WebrtcAudioManagerUtils.getAudioManager(this.context);
        Log.d(TAG,
                "getRendererFormat - recommended sampleRate: " +
                        WebrtcAudioManagerUtils.getSampleRate(audioManager) +
                        " Actual: " + AudioFormat.AUDIO_SAMPLE_RATE_48000);
        return new AudioFormat(AudioFormat.AUDIO_SAMPLE_RATE_48000,
                AudioFormat.AUDIO_SAMPLE_MONO);
    }

    @Override
    public boolean onInitRenderer() {
        Log.d(TAG, "onInitRenderer invoked");
        AudioFormat audioFormat = getRendererFormat();
        return this.webRtcAudioTrack.initRenderer(
                audioFormat.getSampleRate(),
                audioFormat.getChannelCount(),
                BUFFER_SIZE_FACTOR);
    }

    @Override
    public boolean onStartRendering(@NonNull AudioDeviceContext audioDeviceContext) {
        Log.d(TAG, "onStartRendering invoked");
        return this.webRtcAudioTrack.startRenderer(audioDeviceContext);
    }

    @Override
    public boolean onStopRendering() {
        Log.d(TAG, "onStopRendering invoked");
        return this.webRtcAudioTrack.stopRenderer();
    }

    public void stopRecording(SafePromise promise) {
        this.stopRecording(promise, true);
    }

    public void stopRecording(SafePromise promise, boolean shouldResolve) {
        Log.d(TAG, "stopRecording");
        if (isFilePlaying) {
            isFilePlaying = false;
            if(!this.webRtcAudioFile.stopFileReading()) {
                promise.reject("-1", "Unable to stop file reading");
                return;
            }
        } else {
            if(!this.webRtcAudioRecord.stopRecording()) {
                promise.reject("-1", "Unable to stop mic recording");
                return;
            }
        }

        if(shouldResolve)
            promise.resolve(null);
    }
}