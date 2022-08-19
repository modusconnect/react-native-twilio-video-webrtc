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

        if (isFilePlaying) {
            isFilePlaying = false;
            this.webRtcAudioFile.stopFileReading();
        } else {
            this.webRtcAudioRecord.stopRecording();
        }

        this.webRtcAudioFile.init(
                format.getSampleRate(),
                format.getChannelCount(),
                path);

        isFilePlaying = true;

        this.webRtcAudioFile.startFileReading(this.capturingAudioDeviceContext);

        promise.resolve(null);
    }

    public void switchInputToMic(SafePromise promise) {
        AudioFormat format = getCapturerFormat();

        if (isFilePlaying) {
            isFilePlaying = false;
            this.webRtcAudioFile.stopFileReading();
        } else {
            this.webRtcAudioRecord.stopRecording();
        }

        this.webRtcAudioRecord.initRecording(
                format.getSampleRate(),
                format.getChannelCount());


        this.webRtcAudioRecord.startRecording(this.capturingAudioDeviceContext);

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
        Log.d(TAG, "stopRecording");
        boolean retV = false;
        if (isFilePlaying) {
            isFilePlaying = false;
            retV = this.webRtcAudioFile.stopFileReading();
        } else {
            retV = this.webRtcAudioRecord.stopRecording();
        }

        if(!retV) {
            promise.reject("-1", "Unable to stop recording");
        }
        promise.resolve(null);
    }
}