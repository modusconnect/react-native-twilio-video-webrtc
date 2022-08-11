package com.twiliorn.library.utils.webrtcaudio;


import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Build;

import kotlin.NotImplementedError;
import tvi.webrtc.Logging;

class WebrtcAudioManagerUtils {
    private static final String TAG = "WebRtcAudioManagerExternal";
    private static final int DEFAULT_SAMPLE_RATE_HZ = 16000;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int DEFAULT_FRAME_PER_BUFFER = 256;

    WebrtcAudioManagerUtils() {
    }

    static AudioManager getAudioManager(Context context) {
        return (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
    }

    static int getOutputBufferSize(Context context, AudioManager audioManager, int sampleRate, int numberOfOutputChannels) {
        return isLowLatencyOutputSupported(context) ? getLowLatencyFramesPerBuffer(audioManager) : getMinOutputFrameSize(sampleRate, numberOfOutputChannels);
    }

    static int getInputBufferSize(Context context, AudioManager audioManager, int sampleRate, int numberOfInputChannels) {
        return isLowLatencyInputSupported(context) ? getLowLatencyFramesPerBuffer(audioManager) : getMinInputFrameSize(sampleRate, numberOfInputChannels);
    }

    private static boolean isLowLatencyOutputSupported(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY);
    }

    private static boolean isLowLatencyInputSupported(Context context) {
        return Build.VERSION.SDK_INT >= 21 && isLowLatencyOutputSupported(context);
    }

    static int getSampleRate(AudioManager audioManager) {
        if (WebrtcAudioUtils.runningOnEmulator()) {
            Logging.d("WebRtcAudioManagerExternal", "Running emulator, overriding sample rate to 8 kHz.");
            return DEFAULT_SAMPLE_RATE_HZ;
        } else {
            int sampleRateHz = getSampleRateForApiLevel(audioManager);
            Logging.d("WebRtcAudioManagerExternal", "Sample rate is set to " + sampleRateHz + " Hz");
            return sampleRateHz;
        }
    }

    private static int getSampleRateForApiLevel(AudioManager audioManager) {
        if (Build.VERSION.SDK_INT < 17) {
            return DEFAULT_SAMPLE_RATE_HZ;
        } else {
            String sampleRateString = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            return sampleRateString == null ? DEFAULT_SAMPLE_RATE_HZ : Integer.parseInt(sampleRateString);
        }
    }

    private static int getChannelsForApiLevel(AudioManager audioManager) {
        throw new NotImplementedError();
    }

    private static int getLowLatencyFramesPerBuffer(AudioManager audioManager) {
        if (Build.VERSION.SDK_INT < 17) {
            return 256;
        } else {
            String framesPerBuffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            return framesPerBuffer == null ? DEFAULT_FRAME_PER_BUFFER : Integer.parseInt(framesPerBuffer);
        }
    }

    private static int getMinOutputFrameSize(int sampleRateInHz, int numChannels) {
        int bytesPerFrame = numChannels * (BITS_PER_SAMPLE / 8);
        int channelConfig = numChannels == 1 ?
                AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;

        return AudioTrack.getMinBufferSize(
                sampleRateInHz,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
        ) / bytesPerFrame;
    }

    private static int getMinInputFrameSize(int sampleRateInHz, int numChannels) {
        int bytesPerFrame = numChannels * (BITS_PER_SAMPLE / 8);
        int channelConfig = numChannels == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
        return AudioRecord.getMinBufferSize(
                sampleRateInHz,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
        ) / bytesPerFrame;
    }
}
