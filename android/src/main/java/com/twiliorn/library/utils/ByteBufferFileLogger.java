package com.twiliorn.library.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class ByteBufferFileLogger {

    private final File file;
    private final FileChannel channel;

    ByteBufferFileLogger(String path, boolean append) throws FileNotFoundException {
        file = new File(path);
        channel = new FileOutputStream(file, append).getChannel();
    }

    public void write(ByteBuffer bb) throws IOException {
        ByteBuffer dup = bb.duplicate();
        channel.write(dup);
    }
}
