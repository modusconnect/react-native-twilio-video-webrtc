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

import org.webrtc.voiceengine.WebRtcAudioUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import tvi.webrtc.ThreadUtils;

// This is a re-implementation of the code from twilio's sdk. Allowing us to re-use their audio device
// implementation for recording audio data from a file into their media engine

public class CustomWebrtcAudioFile {
    private static final String TAG = "CustomWebrtcAudioFile";

    private static final long AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS = 2000L;

    private static final int CALLBACK_BUFFER_SIZE_MS = 10;
    private static final short BITS_PER_SAMPLE = 16;
    private static final int BUFFERS_PER_SECOND = 1000 / CALLBACK_BUFFER_SIZE_MS;
    private static final int BUFFER_SIZE_FACTOR = 2;
    private int bufferSize = 0;
    @Nullable
    private ByteBuffer byteBuffer;

    public static final int DEFAULT_AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT;
    private static final int AUDIO_FILE_START = 0;
    private static final int AUDIO_FILE_STOP = 1;

    //Services
    private final AudioManager audioManager;

    private final int audioFormat;
    @Nullable
    private AudioFileThread audioThread;
    @Nullable
    private final AudioFileErrorCallback errorCallback;
    @Nullable
    private final AudioFileStateCallback stateCallback;

    //Contexts
    private AudioDeviceContext capturingAudioDeviceContext;
    private final Context context;

    //Streams
    private FileInputStream fileInputStream;


    CustomWebrtcAudioFile(Context context, AudioManager audioManager) {
        this(context, audioManager, DEFAULT_AUDIO_FORMAT, null, null);
    }

    public CustomWebrtcAudioFile(
            Context context,
            AudioManager audioManager,
            int audioFormat,
            @Nullable AudioFileErrorCallback errorCallback,
            @Nullable AudioFileStateCallback stateCallback) {
        this.context = context;
        this.audioManager = audioManager;
        this.audioFormat = audioFormat;
        this.errorCallback = errorCallback;
        this.stateCallback = stateCallback;
        Log.d(TAG, "ctor" + WebRtcAudioUtils.getThreadInfo());
    }

    public int init(int sampleRate, int channels, String path) {
        Log.d(TAG, "init(sampleRate=" + sampleRate + ", channels=" + channels + ")");
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
                Log.d(TAG, "byteBuffer.capacity: " + this.byteBuffer.capacity());
                int channelConfig = this.channelCountToConfiguration(channels);
                int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, this.audioFormat);
                if (minBufferSize != AudioRecord.ERROR && minBufferSize != AudioRecord.ERROR_BAD_VALUE) {
                    Log.d(TAG, "AudioRecord.getMinBufferSize: " + minBufferSize);
                    int bufferSizeInBytes = Math.max(BUFFER_SIZE_FACTOR * minBufferSize, this.byteBuffer.capacity());
                    this.bufferSize = this.byteBuffer.capacity();

                    Log.d(TAG, "bufferSizeInBytes: " + bufferSizeInBytes);

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
        Log.d(TAG, "startFileReading");
        assertTrue(this.audioThread == null);
        assertTrue(this.fileInputStream != null);

        this.capturingAudioDeviceContext = audioDeviceContext;

        this.audioThread = new AudioFileThread("AudioFileJavaThread");
        this.audioThread.start();
        return true;
    }

    public boolean stopFileReading() {
        Log.d(TAG, "stopFileReading");
        if(audioThread == null) {
            return true;
        }

        this.audioThread.stopThread();
        if (!ThreadUtils.joinUninterruptibly(this.audioThread, AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS)) {
            Log.e(TAG, "Join of AudioFileJavaThread timed out");
            WebrtcAudioUtils.logAudioState(TAG, this.context, this.audioManager);
        }

        this.audioThread = null;
        this.releaseResources();
        return true;
    }

    private void releaseResources() {
        Log.d(TAG, "releaseResources");
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
        Log.e(TAG, "Init file read error: " + errorMessage);
        WebrtcAudioUtils.logAudioState(TAG, this.context, this.audioManager);
        if (this.errorCallback != null) {
            this.errorCallback.onWebRtcAudioFileInitError(errorMessage);
        }
    }

    private void reportWebRtcAudioFileError(String errorMessage) {
        Log.e(TAG, "Run-time file read error: " + errorMessage);
        WebrtcAudioUtils.logAudioState(TAG, this.context, this.audioManager);
        if (this.errorCallback != null) {
            this.errorCallback.onWebRtcAudioFileError(errorMessage);
        }
    }

    private void doAudioFileStateCallback(int audioState) {
        Log.d(TAG, "doAudioRecordStateCallback: " + audioStateToString(audioState));
        if (this.stateCallback != null) {
            if (audioState == AUDIO_FILE_START) {
                this.stateCallback.onWebRtcAudioFileStart();
            } else if (audioState == AUDIO_FILE_STOP) {
                this.stateCallback.onWebRtcAudioFileStop();
            } else {
                Log.e(TAG, "Invalid audio state");
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
            Log.d(TAG, "AudioFileThread" + WebrtcAudioUtils.getThreadInfo());
            assertTrue(fileInputStream != null);
            assertTrue(byteBuffer != null);
            doAudioFileStateCallback(AUDIO_FILE_START);

            while(this.keepAlive) {
                flush(byteBuffer);

                try {
                    if (!readFully(
                            fileInputStream,
                            byteBuffer.array(),
                            byteBuffer.arrayOffset(),
                            bufferSize)) {
                        String errorMessage = "readFully failed - stopping thread";
                        Log.e(TAG, errorMessage);
                        this.keepAlive = false;
                        reportWebRtcAudioFileError(errorMessage);
                        continue;
                    }

                    AudioDevice.audioDeviceWriteCaptureData(
                            capturingAudioDeviceContext,
                            byteBuffer
                    );
                } catch (IOException e) {
                    String errorMessage = "readFully failed " + e.getMessage();
                    Log.e(TAG, errorMessage);
                    this.keepAlive = false;
                    reportWebRtcAudioFileError(errorMessage);
                }
            }

            try {
                doAudioFileStateCallback(AUDIO_FILE_STOP);
            } catch (IllegalStateException e) {
                Log.e(TAG, "doAudioFileStateCallback failed: " + e.getMessage());
            }

        }

        public void stopThread() {
            Log.d(TAG, "stopThread");
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

        void onWebRtcAudioFileError(String var1);
    }

    public interface AudioFileStateCallback {
        void onWebRtcAudioFileStart();

        void onWebRtcAudioFileStop();
    }
}
