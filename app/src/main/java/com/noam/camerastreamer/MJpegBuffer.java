package com.noam.camerastreamer;

import android.util.Pair;
import java.io.IOException;

public class MJpegBuffer {
    private byte[] buffer = null;
    private boolean isClosed = false;
    private final Object lock = new Object();
    private Long timestamp = null;

    public void set(byte[] buffer, long timestamp) {
        this.buffer = buffer;
        this.timestamp = timestamp;
    }

    public Pair<byte[], Long> acquire() throws IOException {
        while (!this.isClosed) {
            if (this.buffer == null || this.timestamp == null) {
                synchronized (this.lock) {
                    try {
                        this.lock.wait(0, 1);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            } else {
                Pair<byte[], Long> result = new Pair<>(this.buffer.clone(), this.timestamp);
                this.buffer = null;
                this.timestamp = null;
                return result;
            }
        }
        throw new IOException("is closed");
    }

    public void close() {
        this.isClosed = true;
    }
}
