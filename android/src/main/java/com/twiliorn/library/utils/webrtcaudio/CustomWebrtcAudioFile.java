package com.twiliorn.library.utils.webrtcaudio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.twilio.video.AudioDevice;
import com.twilio.video.AudioDeviceContext;

import org.webrtc.audio.JavaAudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordErrorCallback;
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordStartErrorCode;
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordStateCallback;
import org.webrtc.voiceengine.WebRtcAudioUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import tvi.webrtc.Logging;
import tvi.webrtc.ThreadUtils;

public class CustomWebrtcAudioFile {
    private static final String TAG = "CustomWebrtcAudioFile";
    private static final int CALLBACK_BUFFER_SIZE_MS = 10;
    private static final long AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS = 2000L;


    private static final short BITS_PER_SAMPLE = 16;
    private static final int BUFFERS_PER_SECOND = 1000 / CALLBACK_BUFFER_SIZE_MS;
    private static final int BUFFER_SIZE_FACTOR = 2;

    public static final int DEFAULT_AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT;
    private static final int AUDIO_RECORD_START = 0;
    private static final int AUDIO_RECORD_STOP = 1;

    private int bufferSize = 0;

    private final Context context;
    private final AudioManager audioManager;
    private final int audioFormat;
    @Nullable
    private ByteBuffer byteBuffer;
    @Nullable
    private AudioFileThread audioThread;
    @Nullable
    private final AudioRecordErrorCallback errorCallback;
    @Nullable
    private final AudioRecordStateCallback stateCallback;

    //Contexts
    private AudioDeviceContext capturingAudioDeviceContext;
    private FileInputStream fileInputStream;


    CustomWebrtcAudioFile(Context context, AudioManager audioManager) {
        this(context, audioManager, DEFAULT_AUDIO_FORMAT, null, null);
    }

    public CustomWebrtcAudioFile(
            Context context,
            AudioManager audioManager,
            int audioFormat,
            @Nullable AudioRecordErrorCallback errorCallback,
            @Nullable AudioRecordStateCallback stateCallback) {
            this.context = context;
            this.audioManager = audioManager;
            this.audioFormat = audioFormat;
            this.errorCallback = errorCallback;
            this.stateCallback = stateCallback;
            Logging.d(TAG, "ctor" + WebRtcAudioUtils.getThreadInfo());
    }

    public int init(int sampleRate, int channels, String path) {
        Logging.d(TAG, "init(sampleRate=" + sampleRate + ", channels=" + channels + ")");
        if (this.fileInputStream != null) {
            this.reportWebRtcAudioFileInitError("Init called twice without stopFileReading.");
            return -1;
        } else {
            int bytesPerFrame = channels * (BITS_PER_SAMPLE / 8);

            int framesPerBuffer = sampleRate / BUFFERS_PER_SECOND;
            this.byteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * framesPerBuffer);
            if (!this.byteBuffer.hasArray()) {
                this.reportWebRtcAudioFileInitError("ByteBuffer does not have backing array.");
                return -1;
            } else {
                Logging.d(TAG, "byteBuffer.capacity: " + this.byteBuffer.capacity());
                int channelConfig = this.channelCountToConfiguration(channels);
                int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, this.audioFormat);
                if (minBufferSize != AudioRecord.ERROR && minBufferSize != AudioRecord.ERROR_BAD_VALUE) {
                    Logging.d(TAG, "AudioRecord.getMinBufferSize: " + minBufferSize);
                    int bufferSizeInBytes = Math.max(BUFFER_SIZE_FACTOR * minBufferSize, this.byteBuffer.capacity());
                    this.bufferSize = this.byteBuffer.capacity();

                    Logging.d(TAG, "bufferSizeInBytes: " + bufferSizeInBytes);

                    fileInputStream = openFile(path);
                    if (this.fileInputStream != null) {
                        return framesPerBuffer;
                    } else {
                        this.reportWebRtcAudioFileInitError("Creation or initialization of audio file stream failed.");
                        this.releaseResources();
                        return -1;
                    }
                } else {
                    this.reportWebRtcAudioFileInitError("AudioRecord.getMinBufferSize failed: " + minBufferSize);
                    return -1;
                }
            }
        }
    }

    public boolean startFileReading(@NonNull AudioDeviceContext audioDeviceContext) {
        Logging.d(TAG, "startFileReading");
        assertTrue(this.audioThread == null);
        assertTrue(this.byteBuffer != null);
        assertTrue(this.fileInputStream != null);
        assertTrue(this.byteBuffer.hasArray());

        this.capturingAudioDeviceContext = audioDeviceContext;

        this.audioThread = new AudioFileThread("AudioFileJavaThread");
        this.audioThread.start();
        return true;
    }

    public boolean stopFileReading() {
        Logging.d(TAG, "stopFileReading");
        if(audioThread == null) {
            return true;
        }

        this.audioThread.stopThread();
        if (!ThreadUtils.joinUninterruptibly(this.audioThread, AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS)) {
            Logging.e(TAG, "Join of AudioFileJavaThread timed out");
            WebrtcAudioUtils.logAudioState(TAG, this.context, this.audioManager);
        }

        this.audioThread = null;
        this.releaseResources();
        return true;
    }

    private void releaseResources() {
        Logging.d(TAG, "releaseResources");
        if (this.fileInputStream != null) {
            try {
                this.fileInputStream.close();
            } catch (IOException e) {
                Log.e(TAG, e.getMessage());
            }
            this.fileInputStream = null;
        }
    }

    private void reportWebRtcAudioFileInitError(String errorMessage) {
        Logging.e(TAG, "Init recording error: " + errorMessage);
        WebrtcAudioUtils.logAudioState(TAG, this.context, this.audioManager);
        if (this.errorCallback != null) {
            this.errorCallback.onWebRtcAudioRecordInitError(errorMessage);
        }
    }

    private void reportWebRtcAudioRecordStartError(AudioRecordStartErrorCode errorCode, String errorMessage) {
        Logging.e(TAG, "Start recording error: " + errorCode + ". " + errorMessage);
        WebrtcAudioUtils.logAudioState(TAG, this.context, this.audioManager);
        if (this.errorCallback != null) {
            this.errorCallback.onWebRtcAudioRecordStartError(errorCode, errorMessage);
        }
    }

    private void reportWebRtcAudioRecordError(String errorMessage) {
        Logging.e(TAG, "Run-time recording error: " + errorMessage);
        WebrtcAudioUtils.logAudioState(TAG, this.context, this.audioManager);
        if (this.errorCallback != null) {
            this.errorCallback.onWebRtcAudioRecordError(errorMessage);
        }
    }

    private void doAudioRecordStateCallback(int audioState) {
        Logging.d(TAG, "doAudioRecordStateCallback: " + audioStateToString(audioState));
        if (this.stateCallback != null) {
            if (audioState == AUDIO_RECORD_START) {
                this.stateCallback.onWebRtcAudioRecordStart();
            } else if (audioState == AUDIO_RECORD_STOP) {
                this.stateCallback.onWebRtcAudioRecordStop();
            } else {
                Logging.e(TAG, "Invalid audio state");
            }
        }
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

    private class AudioFileThread extends Thread {
        private volatile boolean keepAlive = true;

        public AudioFileThread(String name) {
            super(name);
        }

        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            Logging.d(TAG, "AudioFileThread" + WebrtcAudioUtils.getThreadInfo());
            assertTrue(fileInputStream != null);
            assertTrue(byteBuffer != null);
            doAudioRecordStateCallback(AUDIO_RECORD_START);

            while(this.keepAlive) {
                flush(byteBuffer);

                boolean retV = false;
                try {
                    retV = readFully(
                            fileInputStream,
                            byteBuffer.array(),
                            byteBuffer.arrayOffset(),
                            bufferSize);
                } catch (IOException e) {
                    Log.e(TAG,  "readFully failed: " + e.getMessage());
                }

                if(!retV) {
                    String errorMessage = "readFully failed: ";
                    Logging.e(TAG, errorMessage);
                    this.keepAlive = false;
                    reportWebRtcAudioRecordError(errorMessage);
                }

                AudioDevice.audioDeviceWriteCaptureData(
                        capturingAudioDeviceContext,
                        byteBuffer
                );
            }

            try {
                doAudioRecordStateCallback(AUDIO_RECORD_STOP);
            } catch (IllegalStateException var5) {
                Logging.e(TAG, "readFully failed: " + var5.getMessage());
            }

        }

        public void stopThread() {
            Logging.d(TAG, "stopThread");
            this.keepAlive = false;
        }
    }

    private void flush (ByteBuffer bb) {
        Arrays.fill(bb.array(), (byte) 0);
    }

    private boolean readFully(FileInputStream in, byte[] b, int off, int len) throws IOException {
        if (len < 0)
            throw new IndexOutOfBoundsException();
        int n = 0;
        while (n < len) {
            int count = in.read(b, off + n, len - n);
            if (count < 0) {
                return false;
            }
            n += count;
        }
        return true;
    }


    private static FileInputStream openFile(String path) {
        File file = new File(path);
        if(!file.canRead() || !file.exists()) {
            logFileParameters(file);
            return null;
        }
        try {
            return new FileInputStream(file);
        } catch (FileNotFoundException e) {
            Log.e(TAG, e.getMessage());
            return null;
        }
    }

    private static void logFileParameters(File file) {
        Log.d(TAG,"logFileParameters" );
        Log.d(TAG,
                "   file" + file.getName()
                        + "exists: " + (file.exists() ? " true" : " no")
                        + ", can read: " + (file.canRead() ? " true" : " no"));
        Log.d(TAG,
                "   file full path: " + file.getAbsolutePath());
    }

    private static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected condition to be true");
        }
    }

    public interface AudioFileErrorCallback {
        void onWebRtcAudioFileInitError(String var1);

        void onWebRtcAudioFileStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode var1, String var2);

        void onWebRtcAudioFileError(String var1);
    }

    public interface AudioFileStateCallback {
        void onWebRtcAudioFileStart();

        void onWebRtcAudioFileStop();
    }
}
