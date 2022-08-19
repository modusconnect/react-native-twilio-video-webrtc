package com.twiliorn.library.utils.webrtcaudio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.twilio.video.AudioDevice;
import com.twilio.video.AudioDeviceContext;

import org.webrtc.audio.JavaAudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordErrorCallback;
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordStartErrorCode;
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordStateCallback;
import org.webrtc.voiceengine.WebRtcAudioUtils;

import java.nio.ByteBuffer;

import tvi.webrtc.ThreadUtils;

// This is a re-implementation of the code from twilio's sdk. Allowing us to re-use their audio device
// implementation for recording audio data from a Audio Record into their media engine

public class CustomWebrtcAudioRecord {
    private static final String TAG = "CustomWebrtcAudioRecord";

    private static final long AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS = 2000L;

    private static final int CALLBACK_BUFFER_SIZE_MS = 10;
    private static final short BITS_PER_SAMPLE = 16;
    private static final int BUFFERS_PER_SECOND = 1000 / CALLBACK_BUFFER_SIZE_MS;
    private static final int BUFFER_SIZE_FACTOR = 2;
    @Nullable
    private ByteBuffer byteBuffer;

    public static final int DEFAULT_AUDIO_SOURCE = 7;
    public static final int DEFAULT_AUDIO_FORMAT = 2;
    private static final int AUDIO_RECORD_START = 0;
    private static final int AUDIO_RECORD_STOP = 1;

    // Services
    private final AudioManager audioManager;

    private final int audioSource;
    private final int audioFormat;

    @Nullable
    private AudioRecord audioRecord;
    @Nullable
    private AudioRecordThread audioThread;
    private volatile boolean microphoneMute;
    private byte[] emptyBytes;
    @Nullable
    private final AudioRecordErrorCallback errorCallback;
    @Nullable
    private final AudioRecordStateCallback stateCallback;

    //Contexts
    private AudioDeviceContext capturingAudioDeviceContext;
    private final Context context;


    CustomWebrtcAudioRecord(Context context, AudioManager audioManager) {
        this(context, audioManager, DEFAULT_AUDIO_SOURCE, DEFAULT_AUDIO_FORMAT, null, null);
    }

    public CustomWebrtcAudioRecord(Context context, AudioManager audioManager, int audioSource, int audioFormat, @Nullable AudioRecordErrorCallback errorCallback, @Nullable AudioRecordStateCallback stateCallback) {
        this.context = context;
        this.audioManager = audioManager;
        this.audioSource = audioSource;
        this.audioFormat = audioFormat;
        this.errorCallback = errorCallback;
        this.stateCallback = stateCallback;
        Log.d(TAG, "ctor" + WebRtcAudioUtils.getThreadInfo());
    }

    public int initRecording(int sampleRate, int channels) {
        Log.d(TAG, "initRecording(sampleRate=" + sampleRate + ", channels=" + channels + ")");
        if (this.audioRecord != null) {
            this.reportWebRtcAudioRecordInitError("InitRecording called twice without StopRecording.");
            if(!stopRecording()) {
                this.reportWebRtcAudioRecordInitError("InitRecording was unable to stop recording");
                return -1;
            }
        }

        int bytesPerFrame = channels * (BITS_PER_SAMPLE / 8);

        int framesPerBuffer = sampleRate / BUFFERS_PER_SECOND;
        this.byteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * framesPerBuffer);
        if (!this.byteBuffer.hasArray()) {
            this.reportWebRtcAudioRecordInitError("ByteBuffer does not have backing array.");
            return -1;
        } else {
            Log.d(TAG, "byteBuffer.capacity: " + this.byteBuffer.capacity());
            this.emptyBytes = new byte[this.byteBuffer.capacity()];
            int channelConfig = this.channelCountToConfiguration(channels);
            int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, this.audioFormat);
            if (minBufferSize != AudioRecord.ERROR && minBufferSize != AudioRecord.ERROR_BAD_VALUE) {
                Log.d(TAG, "AudioRecord.getMinBufferSize: " + minBufferSize);
                int bufferSizeInBytes = Math.max(BUFFER_SIZE_FACTOR * minBufferSize, this.byteBuffer.capacity());
                Log.d(TAG, "bufferSizeInBytes: " + bufferSizeInBytes);

                try {
                    if (Build.VERSION.SDK_INT >= 23) {
                        this.audioRecord = createAudioRecordOnMOrHigher(this.audioSource, sampleRate, channelConfig, this.audioFormat, bufferSizeInBytes);
                    } else {
                        this.audioRecord = createAudioRecordOnLowerThanM(this.audioSource, sampleRate, channelConfig, this.audioFormat, bufferSizeInBytes);
                    }
                } catch (UnsupportedOperationException | IllegalArgumentException var9) {
                    this.reportWebRtcAudioRecordInitError(var9.getMessage());
                    this.releaseAudioResources();
                    return -1;
                }

                if (this.audioRecord != null && this.audioRecord.getState() == 1) {
                    this.logMainParameters();
                    this.logMainParametersExtended();

                    return framesPerBuffer;
                } else {
                    this.reportWebRtcAudioRecordInitError("Creation or initialization of audio recorder failed.");
                    this.releaseAudioResources();
                    return -1;
                }
            } else {
                this.reportWebRtcAudioRecordInitError("AudioRecord.getMinBufferSize failed: " + minBufferSize);
                return -1;
            }
        }
    }

    public boolean startRecording(@NonNull AudioDeviceContext audioDeviceContext) {
        Log.d(TAG, "startRecording");
        assertTrue(this.audioRecord != null);
        assertTrue(this.audioThread == null);

        this.capturingAudioDeviceContext = audioDeviceContext;

        try {
            this.audioRecord.startRecording();
        } catch (IllegalStateException var2) {
            this.reportWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode.AUDIO_RECORD_START_EXCEPTION, "AudioRecord.startRecording failed: " + var2.getMessage());
            return false;
        }

        if (this.audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            this.reportWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode.AUDIO_RECORD_START_STATE_MISMATCH, "AudioRecord.startRecording failed - incorrect state: " + this.audioRecord.getRecordingState());
            return false;
        } else {
            this.audioThread = new AudioRecordThread("AudioRecordJavaThread");
            this.audioThread.start();
            return true;
        }
    }

    public boolean stopRecording() {
        Log.d(TAG, "stopRecording");

        if(this.audioThread == null) {
            return true;
        }

        this.audioThread.stopThread();
        if (!ThreadUtils.joinUninterruptibly(this.audioThread, AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS)) {
            Log.e(TAG, "Join of AudioRecordJavaThread timed out");
            WebrtcAudioUtils.logAudioState(TAG, this.context, this.audioManager);
        }

        this.audioThread = null;
        this.releaseAudioResources();
        return true;
    }

    public void setMicrophoneMute(boolean mute) {
        Log.w(TAG, "setMicrophoneMute(" + mute + ")");
        this.microphoneMute = mute;
    }

    private void releaseAudioResources() {
        Log.d(TAG, "releaseAudioResources");
        if (this.audioRecord != null) {
            this.audioRecord.release();
            this.audioRecord = null;
        }
    }

    private void logMainParameters() {
        if (this.audioRecord != null) {
            Log.d(TAG, "AudioRecord: session ID: " + this.audioRecord.getAudioSessionId() + ", channels: " + this.audioRecord.getChannelCount() + ", sample rate: " + this.audioRecord.getSampleRate());
        }
        Log.d(TAG, "AudioRecord: null");
    }

    private void logMainParametersExtended() {
        if (this.audioRecord != null && Build.VERSION.SDK_INT >= 23) {
            Log.d(TAG, "AudioRecord: buffer size in frames: " + this.audioRecord.getBufferSizeInFrames());
        }
    }


    private void reportWebRtcAudioRecordInitError(String errorMessage) {
        Log.e(TAG, "Init recording error: " + errorMessage);
        WebrtcAudioUtils.logAudioState(TAG, this.context, this.audioManager);
        if (this.errorCallback != null) {
            this.errorCallback.onWebRtcAudioRecordInitError(errorMessage);
        }
    }

    private void reportWebRtcAudioRecordStartError(AudioRecordStartErrorCode errorCode, String errorMessage) {
        Log.e(TAG, "Start recording error: " + errorCode + ". " + errorMessage);
        WebrtcAudioUtils.logAudioState(TAG, this.context, this.audioManager);
        if (this.errorCallback != null) {
            this.errorCallback.onWebRtcAudioRecordStartError(errorCode, errorMessage);
        }
    }

    private void reportWebRtcAudioRecordError(String errorMessage) {
        Log.e(TAG, "Run-time recording error: " + errorMessage);
        WebrtcAudioUtils.logAudioState(TAG, this.context, this.audioManager);
        if (this.errorCallback != null) {
            this.errorCallback.onWebRtcAudioRecordError(errorMessage);
        }
    }

    private void doAudioRecordStateCallback(int audioState) {
        Log.d(TAG, "doAudioRecordStateCallback: " + audioStateToString(audioState));
        if (this.stateCallback != null) {
            if (audioState == AUDIO_RECORD_START) {
                this.stateCallback.onWebRtcAudioRecordStart();
            } else if (audioState == AUDIO_RECORD_STOP) {
                this.stateCallback.onWebRtcAudioRecordStop();
            } else {
                Log.e(TAG, "Invalid audio state");
            }
        }
    }

    @RequiresApi(23)
    private static AudioRecord createAudioRecordOnMOrHigher(int audioSource, int sampleRate, int channelConfig, int audioFormat, int bufferSizeInBytes) {
        Log.d(TAG, "createAudioRecordOnMOrHigher");
        return (new AudioRecord.Builder()).setAudioSource(audioSource).setAudioFormat((new android.media.AudioFormat.Builder()).setEncoding(audioFormat).setSampleRate(sampleRate).setChannelMask(channelConfig).build()).setBufferSizeInBytes(bufferSizeInBytes).build();
    }

    private static AudioRecord createAudioRecordOnLowerThanM(int audioSource, int sampleRate, int channelConfig, int audioFormat, int bufferSizeInBytes) {
        Log.d(TAG, "createAudioRecordOnLowerThanM");
        return new AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSizeInBytes);
    }

    private static String audioStateToString(int state) {
        switch(state) {
            case 0:
                return "START";
            case 1:
                return "STOP";
            default:
                return "INVALID";
        }
    }

    private int channelCountToConfiguration(int channels) {
        return channels == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
    }

    private class AudioRecordThread extends Thread {
        private volatile boolean keepAlive = true;

        public AudioRecordThread(String name) {
            super(name);
        }

        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            Log.d(TAG, "AudioRecordThread" + WebrtcAudioUtils.getThreadInfo());
            assertTrue(audioRecord != null);
            assertTrue(byteBuffer != null);
            assertTrue(audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING);
            doAudioRecordStateCallback(AUDIO_RECORD_START);

            while(this.keepAlive) {
                int bytesRead = audioRecord.read(byteBuffer, byteBuffer.capacity());
                if (bytesRead == byteBuffer.capacity()) {
                    if (microphoneMute) {
                        byteBuffer.clear();
                        byteBuffer.put(emptyBytes);
                    }

                    if (this.keepAlive) {
                        AudioDevice.audioDeviceWriteCaptureData(capturingAudioDeviceContext, byteBuffer);
                    }
                } else {
                    String errorMessage = "AudioRecord.read failed: " + bytesRead;
                    Log.e(TAG, errorMessage);
                    if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                        this.keepAlive = false;
                        reportWebRtcAudioRecordError(errorMessage);
                    }
                }
            }

            try {
                if (audioRecord != null) {
                    audioRecord.stop();
                    doAudioRecordStateCallback(AUDIO_RECORD_STOP);
                }
            } catch (IllegalStateException var5) {
                Log.e(TAG, "AudioRecord.stop failed: " + var5.getMessage());
            }

        }

        public void stopThread() {
            Log.d(TAG, "stopThread");
            this.keepAlive = false;
        }
    }

    private static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected condition to be true");
        }
    }

}
