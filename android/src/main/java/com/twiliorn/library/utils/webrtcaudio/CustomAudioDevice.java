package com.twiliorn.library.utils.webrtcaudio;


import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;

import com.twilio.video.AudioDevice;
import com.twilio.video.AudioDeviceContext;
import com.twilio.video.AudioFormat;
import com.twiliorn.library.utils.SafePromise;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import tvi.webrtc.ThreadUtils;


public class CustomAudioDevice implements AudioDevice {
    public static final String TAG = CustomAudioDevice.class.getClass().getSimpleName();

    // TIMEOUT for rendererThread and capturerThread to wait for successful call to join()
    private static final long THREAD_JOIN_TIMEOUT_MS = 2000;

    // We want to get as close to 10 msec buffers as possible because this is what the media engine prefers.
    private static final short CALLBACK_BUFFER_SIZE_MS = 10;

    // Default audio data format is PCM 16 bit per sample. Guaranteed to be supported by all devices.
    private static final short BITS_PER_SAMPLE = 16;

    // Ask for a buffer size of BUFFER_SIZE_FACTOR * (minimum required buffer size). The extra space
    // is allocated to guard against glitches under high load.
    private static final short BUFFER_SIZE_FACTOR = 2;
    private static final short WAV_FILE_HEADER_SIZE = 44;


    private static final int BUFFERS_PER_SECOND = 1000 / CALLBACK_BUFFER_SIZE_MS;

    private final CustomWebrtcAudioTrack webRtcAudioTrack;
    private final Context context;

    private FileInputStream inputStream;

    private DataInputStream dataInputStream;

    // buffers
    private int writeBufferSize = 0;

    private ByteBuffer fileWriteByteBuffer;
    private ByteBuffer micWriteBuffer;

    // Handlers and Threads
    private Handler capturerHandler;
    private HandlerThread capturerThread;

    //Contexts
    private AudioDeviceContext capturingAudioDeviceContext;


    private AudioRecord audioRecord;

    private boolean isFilePlaying;

    public CustomAudioDevice(Context context) {
        AudioManager audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        this.context = context;
        this.webRtcAudioTrack = new CustomWebrtcAudioTrack(
                context,
                audioManager,
                null,
                null);
    }

    public void switchInputToFile(String path, SafePromise promise) {
        if (capturerHandler == null) {
            Log.d(TAG, "CapturerHandler is null - noop");
            return;
        }
        if(!initializeStreams(path)) {
            promise.reject("-1", "Could not initialize stream");
            return;
        }
        isFilePlaying = true;
        capturerHandler.removeCallbacks(microphoneCapturerRunnable);
        capturerHandler.removeCallbacks(fileCapturerRunnable);
        stopRecording();
        initializeStreams(path);

        capturerHandler.post(fileCapturerRunnable);
        promise.resolve(null);
    }

    public void switchInputToMic(SafePromise promise) {
        if (capturerHandler == null) {
            Log.d(TAG, "CapturerHandler is null - noop");
            return;
        }
        capturerHandler.removeCallbacks(fileCapturerRunnable);
        capturerHandler.post(microphoneCapturerRunnable);
        promise.resolve(null);

    }


    @Override
    public AudioFormat getCapturerFormat() {
        return new AudioFormat(AudioFormat.AUDIO_SAMPLE_RATE_48000,
                AudioFormat.AUDIO_SAMPLE_MONO);
    }

    @Override
    public boolean onInitCapturer() {
        int bytesPerFrame = 2 * (BITS_PER_SAMPLE / 8);
        AudioFormat capturerFormat = getCapturerFormat();
        int framesPerBuffer = capturerFormat.getSampleRate() / BUFFERS_PER_SECOND;
        // Calculate the minimum buffer size required for the successful creation of
        // an AudioRecord object, in byte units.
        int channelConfig = inChannelCountToConfiguration(capturerFormat.getChannelCount());
        int minBufferSize = AudioRecord.getMinBufferSize(capturerFormat.getSampleRate(),
                channelConfig, android.media.AudioFormat.ENCODING_PCM_16BIT);
        micWriteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * framesPerBuffer);

        ByteBuffer tempMicWriteBuffer = micWriteBuffer;
        int bufferSizeInBytes = Math.max(BUFFER_SIZE_FACTOR * minBufferSize, tempMicWriteBuffer.capacity());
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, capturerFormat.getSampleRate(),
                android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, bufferSizeInBytes);
        fileWriteByteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * framesPerBuffer);
        ByteBuffer testFileWriteByteBuffer = fileWriteByteBuffer;
        writeBufferSize = testFileWriteByteBuffer.capacity();
        // Initialize the streams.
        return true;
    }

    @Override
    public boolean onStartCapturing(AudioDeviceContext audioDeviceContext) {
        // Initialize the AudioDeviceContext
        capturingAudioDeviceContext = audioDeviceContext;
        // Create the capturer thread and start
        capturerThread = new HandlerThread("CapturerThread");
        capturerThread.start();
        // Create the capturer handler that processes the capturer Runnables.
        capturerHandler = new Handler(capturerThread.getLooper());
        isFilePlaying = false;
        capturerHandler.post(microphoneCapturerRunnable);
        return true;
    }

    @Override
    public boolean onStopCapturing() {
        if (isFilePlaying) {
            isFilePlaying = false;
            closeStreams();
        } else {
            stopRecording();
        }
        /*
         * When onStopCapturing is called, the AudioDevice API expects that at the completion
         * of the callback the capturer has completely stopped. As a result, quit the capturer
         * thread and explicitly wait for the thread to complete.
         */
        capturerThread.quit();
        if (!ThreadUtils.joinUninterruptibly(capturerThread, THREAD_JOIN_TIMEOUT_MS)) {
            Log.e(TAG, "Join of capturerThread timed out");
            return false;
        }
        return true;
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
        AudioFormat audioFormat = getRendererFormat();
        return this.webRtcAudioTrack.initRenderer(
                audioFormat.getSampleRate(),
                audioFormat.getChannelCount(),
                BUFFER_SIZE_FACTOR);
    }

    @Override
    public boolean onStartRendering(@NonNull AudioDeviceContext audioDeviceContext) {
        return this.webRtcAudioTrack.startRenderer(audioDeviceContext);
    }

    @Override
    public boolean onStopRendering() {
        return this.webRtcAudioTrack.stopRenderer();
    }


    private void processRemaining(@NonNull ByteBuffer bb, int chunkSize) {
        bb.position(bb.limit()); // move at the end
        bb.limit(chunkSize); // get ready to pad with longs
        while (bb.position() < chunkSize) {
            bb.putLong(0);
        }
        bb.limit(chunkSize);
        bb.flip();
    }

    private void flush (ByteBuffer bb) {
        Arrays.fill(bb.array(), (byte) 0);
    }

    private int write(AudioTrack audioTrack, ByteBuffer byteBuffer, int sizeInBytes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return audioTrack.write(byteBuffer, sizeInBytes, AudioTrack.WRITE_BLOCKING);
        }
        return 0;
    }


    private boolean initializeStreams(String path) {
        try {
            if(path == null) {
                Log.d(TAG, "path is null");
                return false;
            }

            File cfile = new File(path);
            if (cfile.exists() && cfile.canRead()) {
                inputStream = new FileInputStream(cfile);
                return true;
            }

            Log.d(TAG, "cFile invalid - exists "
                    + (cfile.exists() ? "T" : "F")
                    + " readable " + (cfile.canRead() ? "T" : "F")
            );
            Log.d(TAG, "cFile path:  " + path);

            return false;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        return false;
    }

    private void closeStreams() {
        Log.d(TAG, "Remove any pending posts of fileCapturerRunnable that are in the message queue");
        capturerHandler.removeCallbacks(fileCapturerRunnable);
        try {
            if (dataInputStream != null)
                dataInputStream.close();
            if (inputStream != null)
                inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private void stopRecording() {
        Log.d(TAG, "Remove any pending posts of microphoneCapturerRunnable that are in the message queue ");
        capturerHandler.removeCallbacks(microphoneCapturerRunnable);
        try {
            audioRecord.stop();
        } catch (IllegalStateException e) {
            Log.e(TAG, "AudioRecord.stop failed: " + e.getMessage());
        }
    }


    public void stopRecording(SafePromise promise) {
        Log.d(TAG, "Remove any pending posts of microphoneCapturerRunnable that are in the message queue ");
        capturerHandler.removeCallbacks(microphoneCapturerRunnable);
        try {
            audioRecord.stop();
            promise.resolve(null);
        } catch (IllegalStateException e) {
            Log.e(TAG, "AudioRecord.stop failed: " + e.getMessage());
            promise.reject(e);
        }
    }

    private int outChannelCountToConfiguration(int channels) {
        if (channels == 1)
            return android.media.AudioFormat.CHANNEL_OUT_MONO;
        else
            return android.media.AudioFormat.CHANNEL_OUT_STEREO;
    }

    private int inChannelCountToConfiguration(int channels) {
        if (channels == 1)
            return android.media.AudioFormat.CHANNEL_IN_MONO;
        else
            return android.media.AudioFormat.CHANNEL_IN_STEREO;
    }

    /*
     * This Runnable reads a music file and provides the audio frames to the AudioDevice API via
     * AudioDevice.audioDeviceWriteCaptureData(..) until there is no more data to be read, the
     * capturer input switches to the microphone, or the call ends.
     */

    private Runnable fileCapturerRunnable = new Runnable() {
        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            try {
                if (inputStream == null) {
                    capturerHandler.postDelayed(this, CALLBACK_BUFFER_SIZE_MS);
                    return;
                }
                flush(fileWriteByteBuffer);

                boolean retV = readFully(
                        inputStream,
                        fileWriteByteBuffer.array(),
                        fileWriteByteBuffer.arrayOffset(),
                        writeBufferSize);

//                logger.write(fileWriteByteBuffer);

                if(!retV) {
                    flush(fileWriteByteBuffer);
                    return;
                }
                AudioDevice.audioDeviceWriteCaptureData(
                        capturingAudioDeviceContext,
                        fileWriteByteBuffer
                );
            } catch (IOException e) {
                e.printStackTrace();
            }
            capturerHandler.postDelayed(this, CALLBACK_BUFFER_SIZE_MS);
        }
    };


    /*
     * This Runnable reads data from the microphone and provides the audio frames to the AudioDevice
     * API via AudioDevice.audioDeviceWriteCaptureData(..) until the capturer input switches to
     * microphone or the call ends.
     */
    private Runnable microphoneCapturerRunnable = new Runnable() {
        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            if (audioRecord.getState() != AudioRecord.STATE_UNINITIALIZED) {
                audioRecord.startRecording();
                while (true) {
                    int bytesRead = audioRecord.read(micWriteBuffer, micWriteBuffer.capacity());
                    if (bytesRead == micWriteBuffer.capacity()) {
                        AudioDevice.audioDeviceWriteCaptureData(capturingAudioDeviceContext,
                                micWriteBuffer
                        );
                    } else {
                        String errorMessage = "AudioRecord.read failed " + bytesRead;
                        Log.e(TAG, errorMessage);
                        if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                            stopRecording();
                            Log.e(TAG, errorMessage);
                        }
                        break;
                    }
                }
            }
        }
    };


    private boolean readFully(FileInputStream in, byte b[], int off, int len) throws IOException {
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


    private void skipFully(FileInputStream in, long skip) throws IOException {
        int n = 0;
        while (n < skip) {
            long bytes = in.skip(skip - n);
            n += bytes;
            Log.d(TAG, "[skipFully]: Number of bytes skipped: " + bytes);
        }
    }
}