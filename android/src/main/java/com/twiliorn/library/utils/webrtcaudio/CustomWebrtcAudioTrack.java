package com.twiliorn.library.utils.webrtcaudio;

import android.util.Log;
import android.os.Process;
import android.os.Build.VERSION;
import android.content.Context;
import android.media.AudioTrack;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioAttributes.Builder;
import android.annotation.TargetApi;
import androidx.support.annotation.NonNull;
import androidx.support.annotation.Nullable;

import com.twilio.video.AudioDevice;
import com.twilio.video.AudioDeviceContext;

import java.nio.ByteBuffer;

import tvi.webrtc.ThreadUtils;
import tvi.webrtc.audio.JavaAudioDeviceModule.AudioTrackErrorCallback;
import tvi.webrtc.audio.JavaAudioDeviceModule.AudioTrackStartErrorCode;
import tvi.webrtc.audio.JavaAudioDeviceModule.AudioTrackStateCallback;

// This is a re-implementation of the code from twilio's sdk. Allowing us to re-use their audio device
// implementation for rendering audio data from their audio media engine

class CustomWebrtcAudioTrack {
    private static final String TAG = "CustomWebRtcAudioTrack";

    private static final long AUDIO_TRACK_THREAD_JOIN_TIMEOUT_MS = 2000L;

    private static final int CALLBACK_BUFFER_SIZE_MS = 10;
    private static final int BUFFERS_PER_SECOND = 1000 / CALLBACK_BUFFER_SIZE_MS;
    ;
    private static final int DEFAULT_USAGE = getDefaultUsageAttribute();
    private static final int AUDIO_TRACK_START = 0;
    private static final int AUDIO_TRACK_STOP = 1;

    // Default audio data format is PCM 16 bit per sample. Guaranteed to be supported by all devices.
    private static final short BITS_PER_SAMPLE = 16;


    private final Context context;
    private final AudioManager audioManager;
    @Nullable
    private final AudioTrackErrorCallback errorCallback;
    @Nullable
    private final AudioTrackStateCallback stateCallback;

    private ByteBuffer byteBuffer;

    private AudioDeviceContext audioDeviceContext;
    @Nullable
    private AudioTrack audioTrack;
    @Nullable
    private AudioTrackThread audioThread;
    private volatile boolean speakerMute;
    private byte[] emptyBytes;

    CustomWebrtcAudioTrack(Context context, AudioManager audioManager, @Nullable AudioTrackErrorCallback errorCallback, @Nullable AudioTrackStateCallback stateCallback) {
        this.context = context;
        this.audioManager = audioManager;
        this.errorCallback = errorCallback;
        this.stateCallback = stateCallback;
    }

    private static int getDefaultUsageAttribute() {
        return VERSION.SDK_INT >= 21 ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_UNKNOWN;
    }

    @TargetApi(21)
    private static AudioTrack createAudioTrackOnLollipopOrHigher(int sampleRateInHz, int channelConfig, int bufferSizeInBytes) {
        Log.d(TAG, "createAudioTrackOnLollipopOrHigher");
        int nativeOutputSampleRate = AudioTrack.getNativeOutputSampleRate(0);
        Log.d(TAG, "nativeOutputSampleRate: " + nativeOutputSampleRate);
        if (sampleRateInHz != nativeOutputSampleRate) {
            Log.w(TAG, "Unable to use fast mode since requested sample rate is not native");
        }

        return new AudioTrack((new Builder()).setUsage(DEFAULT_USAGE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(), (new android.media.AudioFormat.Builder()).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRateInHz).setChannelMask(channelConfig).build(), bufferSizeInBytes, AudioTrack.MODE_STREAM, 0);
    }

    private static AudioTrack createAudioTrackOnLowerThanLollipop(int sampleRateInHz, int channelConfig, int bufferSizeInBytes) {
        return new AudioTrack(0, sampleRateInHz, channelConfig, 2, bufferSizeInBytes, 1);
    }

    private static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected condition to be true");
        }
    }

    public boolean initRenderer(int sampleRate, int channels, double bufferSizeFactor) {
        Log.d(TAG, "initPlayout(sampleRate=" + sampleRate + ", channels=" + channels + ", bufferSizeFactor=" + bufferSizeFactor + ")");
        int bytesPerFrame = channels * (BITS_PER_SAMPLE / 8);
        this.byteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * (sampleRate / BUFFERS_PER_SECOND));
        Log.d(TAG, "byteBuffer.capacity: " + this.byteBuffer.capacity());
        this.emptyBytes = new byte[this.byteBuffer.capacity()];

        int channelConfig = this.channelCountToConfiguration(channels);
        int minBufferSizeInBytes = (int) ((double) AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT) * bufferSizeFactor);
        Log.d(TAG, "minBufferSizeInBytes: " + minBufferSizeInBytes);
        if (minBufferSizeInBytes < this.byteBuffer.capacity()) {
            this.reportWebRtcAudioTrackInitError("AudioTrack.getMinBufferSize returns an invalid value.");
            return false;
        } else if (this.audioTrack != null) {
            this.reportWebRtcAudioTrackInitError("Conflict with existing AudioTrack.");
            return false;
        } else {
            try {
                if (VERSION.SDK_INT >= 21) {
                    this.audioTrack = createAudioTrackOnLollipopOrHigher(sampleRate, channelConfig, minBufferSizeInBytes);
                } else {
                    this.audioTrack = createAudioTrackOnLowerThanLollipop(sampleRate, channelConfig, minBufferSizeInBytes);
                }
            } catch (IllegalArgumentException var9) {
                this.reportWebRtcAudioTrackInitError(var9.getMessage());
                this.releaseAudioResources();
                return false;
            }

            if (this.audioTrack != null && this.audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                this.logMainParameters();
                this.logMainParametersExtended();
                return true;
            } else {
                this.reportWebRtcAudioTrackInitError("Initialization of audio track failed.");
                this.releaseAudioResources();
                return false;
            }
        }
    }

    public boolean startRenderer(@NonNull AudioDeviceContext audioDeviceContext) {
        Log.d(TAG, "startRenderer");
        this.audioDeviceContext = audioDeviceContext;

        assertTrue(this.audioTrack != null);
        assertTrue(this.audioThread == null);

        try {
            this.audioTrack.play();
        } catch (IllegalStateException var2) {
            this.reportWebRtcAudioTrackStartError(AudioTrackStartErrorCode.AUDIO_TRACK_START_EXCEPTION, "AudioTrack.play failed: " + var2.getMessage());
            this.releaseAudioResources();
            return false;
        }

        if (this.audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            this.reportWebRtcAudioTrackStartError(AudioTrackStartErrorCode.AUDIO_TRACK_START_STATE_MISMATCH, "AudioTrack.play failed - incorrect state :" + this.audioTrack.getPlayState());
            this.releaseAudioResources();
            return false;
        } else {
            this.audioThread = new AudioTrackThread("AudioTrackJavaThread");
            this.audioThread.start();
            return true;
        }
    }

    public boolean stopRenderer() {
        Log.d(TAG, "stopPlayout");
        assertTrue(this.audioThread != null);
        this.logUnderrunCount();
        this.audioThread.stopThread();
        Log.d(TAG, "Stopping the AudioTrackThread...");
        this.audioThread.interrupt();
        if (!ThreadUtils.joinUninterruptibly(this.audioThread, AUDIO_TRACK_THREAD_JOIN_TIMEOUT_MS)) {
            Log.e(TAG, "Join of AudioTrackThread timed out.");
            WebrtcAudioUtils.logAudioState(TAG, this.context, this.audioManager);
        }

        Log.d(TAG, "AudioTrackThread has now been stopped.");
        this.audioThread = null;
        if (this.audioTrack != null) {
            Log.d(TAG, "Calling AudioTrack.stop...");

            try {
                this.audioTrack.stop();
                Log.d(TAG, "AudioTrack.stop is done.");
                this.doAudioTrackStateCallback(AUDIO_TRACK_STOP);
            } catch (IllegalStateException var2) {
                Log.e(TAG, "AudioTrack.stop failed: " + var2.getMessage());
            }
        }

        this.releaseAudioResources();
        return true;
    }

    public int getStreamMaxVolume() {
        Log.d(TAG, "getStreamMaxVolume");
        return this.audioManager.getStreamMaxVolume(0);
    }

    public boolean setStreamVolume(int volume) {
        Log.d(TAG, "setStreamVolume(" + volume + ")");
        if (this.isVolumeFixed()) {
            Log.e(TAG, "The device implements a fixed volume policy.");
            return false;
        } else {
            this.audioManager.setStreamVolume(0, volume, 0);
            return true;
        }
    }

    private boolean isVolumeFixed() {
        return VERSION.SDK_INT >= 21 && this.audioManager.isVolumeFixed();
    }

    public int getStreamVolume() {
        Log.d(TAG, "getStreamVolume");
        return this.audioManager.getStreamVolume(0);
    }

    public int GetPlayoutUnderrunCount() {
        if (VERSION.SDK_INT >= 24) {
            return this.audioTrack != null ? this.audioTrack.getUnderrunCount() : -1;
        } else {
            return -2;
        }
    }

    private void logMainParameters() {
        assertTrue(this.audioTrack != null);
        Log.d(TAG, "AudioTrack: session ID: " + this.audioTrack.getAudioSessionId() + ", channels: " + this.audioTrack.getChannelCount() + ", sample rate: " + this.audioTrack.getSampleRate() + ", max gain: " + AudioTrack.getMaxVolume());
    }

    private void logBufferSizeInFrames() {
        if (VERSION.SDK_INT >= 23 && this.audioTrack != null) {
            Log.d(TAG, "AudioTrack: buffer size in frames: " + this.audioTrack.getBufferSizeInFrames());
        }
    }

    private void logBufferCapacityInFrames() {
        if (VERSION.SDK_INT >= 24 && this.audioTrack != null) {
            Log.d(TAG, "AudioTrack: buffer capacity in frames: " + this.audioTrack.getBufferCapacityInFrames());
        }

    }

    private void logMainParametersExtended() {
        this.logBufferSizeInFrames();
        this.logBufferCapacityInFrames();
    }

    private void logUnderrunCount() {
        if (VERSION.SDK_INT >= 24 && this.audioTrack != null) {
            Log.d(TAG, "underrun count: " + this.audioTrack.getUnderrunCount());
        }
    }

    private int channelCountToConfiguration(int channels) {
        return channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
    }

    public void setSpeakerMute(boolean mute) {
        Log.w(TAG, "setSpeakerMute(" + mute + ")");
        this.speakerMute = mute;
    }

    private void releaseAudioResources() {
        Log.d(TAG, "releaseAudioResources");
        if (this.audioTrack != null) {
            this.audioTrack.release();
            this.audioTrack = null;
        }

    }

    private void reportWebRtcAudioTrackInitError(String errorMessage) {
        Log.e(TAG, "Init playout error: " + errorMessage);
        WebrtcAudioUtils.logAudioState(TAG, this.context, this.audioManager);
        if (this.errorCallback != null) {
            this.errorCallback.onWebRtcAudioTrackInitError(errorMessage);
        }

    }

    private void reportWebRtcAudioTrackStartError(AudioTrackStartErrorCode errorCode, String errorMessage) {
        Log.e(TAG, "Start playout error: " + errorCode + ". " + errorMessage);
        WebrtcAudioUtils.logAudioState(TAG, this.context, this.audioManager);
        if (this.errorCallback != null) {
            this.errorCallback.onWebRtcAudioTrackStartError(errorCode, errorMessage);
        }

    }

    private void reportWebRtcAudioTrackError(String errorMessage) {
        Log.e(TAG, "Run-time playback error: " + errorMessage);
        WebrtcAudioUtils.logAudioState(TAG, this.context, this.audioManager);
        if (this.errorCallback != null) {
            this.errorCallback.onWebRtcAudioTrackError(errorMessage);
        }

    }

    private void doAudioTrackStateCallback(int audioState) {
        Log.d(TAG, "doAudioTrackStateCallback: " + audioState);
        if (this.stateCallback != null) {
            if (audioState == AUDIO_TRACK_START) {
                this.stateCallback.onWebRtcAudioTrackStart();
            } else if (audioState == AUDIO_TRACK_STOP) {
                this.stateCallback.onWebRtcAudioTrackStop();
            } else {
                Log.e(TAG, "Invalid audio state");
            }
        }

    }

    private class AudioTrackThread extends Thread {
        private volatile boolean keepAlive = true;

        public AudioTrackThread(String name) {
            super(name);
        }

        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            Log.d(TAG, "AudioTrackThread" + WebrtcAudioUtils.getThreadInfo());
            assertTrue(audioTrack != null);
            assertTrue(audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING);
            assertTrue(audioDeviceContext != null);

            doAudioTrackStateCallback(AUDIO_TRACK_START);
            for (int sizeInBytes = byteBuffer.capacity(); this.keepAlive; byteBuffer.rewind()) {
                AudioDevice.audioDeviceReadRenderData(audioDeviceContext, byteBuffer);
                assertTrue(sizeInBytes <= byteBuffer.remaining());
                if (speakerMute) {
                    byteBuffer.clear();
                    byteBuffer.put(emptyBytes);
                    byteBuffer.position(0);
                }

                int bytesWritten = this.writeBytes(audioTrack, byteBuffer, sizeInBytes);
                if (bytesWritten != sizeInBytes) {
                    Log.e(TAG, "AudioTrack.write played invalid number of bytes: " + bytesWritten);
                    if (bytesWritten < 0) {
                        this.keepAlive = false;
                        reportWebRtcAudioTrackError("AudioTrack.write failed: " + bytesWritten);
                    }
                }
            }

        }

        private int writeBytes(AudioTrack audioTrack, ByteBuffer byteBuffer, int sizeInBytes) {
            return VERSION.SDK_INT >= 21 ? audioTrack.write(byteBuffer, sizeInBytes, AudioTrack.WRITE_BLOCKING)
                    : audioTrack.write(byteBuffer.array(), byteBuffer.arrayOffset(), sizeInBytes);
        }

        public void stopThread() {
            Log.d(TAG, "stopThread");
            this.keepAlive = false;
        }
    }
}
