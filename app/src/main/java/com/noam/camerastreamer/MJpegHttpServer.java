package com.noam.camerastreamer;

import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicBoolean;

public class MJpegHttpServer {
    public static final String TAG = ServerThread.class.getName() + "." + ServerThread.class.getName();
    private static final String BOUNDARY = "--gc0p4Jq0M2Yt08jU534c0p--";
    private static final String BOUNDARY_LINES = "\r\n--gc0p4Jq0M2Yt08jU534c0p--\r\n";
    private static final String HTTP_HEADER = "HTTP/1.0 200 OK\r\n" +
            "Server: LiveStream\r\n" +
            "Connection: close\r\n" +
            "Max-Age: 0\r\n" +
            "Expires: 0\r\n" +
            "Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n" +
            "Pragma: no-cache\r\n" +
            "Access-Control-Allow-Origin:*\r\nC" +
            "ontent-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n" +
            BOUNDARY_LINES;
    private final AtomicBoolean isRunning;
    private final Object lock;
    private MJpegBuffer buffer;
    private boolean isClosing;
    private ServerSocket server;
    private ServerThread thread;
    private OnConnectionListener listener = null;

    public MJpegHttpServer(MJpegBuffer buffer) throws IOException {
        this(buffer, 0);
    }

    public MJpegHttpServer(MJpegBuffer buffer, int port) throws IOException {
        this(buffer, port, 1000);
    }

    public MJpegHttpServer(MJpegBuffer buffer, int port, int timeout) throws IOException {
        isRunning = new AtomicBoolean(false);
        lock = new Object();
        isClosing = false;
        this.buffer = buffer;
        server = new ServerSocket(port);
        ;
        server.setSoTimeout(timeout);
    }


    public void startServer() {
        if (!isRunning()) {
            isRunning.set(true);
            thread = new ServerThread(server, buffer);
            ;
            thread.setConnectionListener(listener);
            thread.start();
            return;
        }
        throw new IllegalStateException("Already running");
    }

    public void close() {
        if (!isClosing) {
            isClosing = true;
            if (isRunning.get()) {
                thread.interrupt = true;
                while (!server.isClosed());
                buffer.close();
            }
            long startTime = SystemClock.uptimeMillis();
            while (thread.isAlive() && server.isClosed() && SystemClock.uptimeMillis() - startTime < 5000) {
                synchronized (lock) {
                    try {
                        lock.wait(0, 1);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
            try {
                server.close();
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
            isRunning.set(false);
            buffer = null;
            server = null;
        }
    }

    public synchronized boolean isRunning() {
        return isRunning.get();
    }

    public int getPort() {
        return server.getLocalPort();
    }

    public interface OnConnectionListener {
        boolean accept(InetAddress address);

        void disconnect(InetAddress address);
    }

    public void setConnectionListener(OnConnectionListener listener) {
        if (isRunning())
            throw new IllegalStateException("Server is already running");
        this.listener = listener;
    }

    private static final class ServerThread extends Thread {
        private MJpegBuffer buffer;
        private ServerSocket server;
        private OnConnectionListener listener;
        volatile boolean interrupt = false;
        private final Object lock = new Object();

        public ServerThread(ServerSocket server, MJpegBuffer buffer) {
            this.server = server;
            this.buffer = buffer;
        }

        public void run() {
            while (!interrupt) {
                Log.e(TAG, "run: " + interrupt);
                Socket client = null;
                while (client == null) {
                    Log.e(TAG, "run: " + interrupt);
                    try {
                        client = server.accept();
                    } catch (IOException ioe) {
                        //ioe.printStackTrace();
                    }
                    if (isInterrupted() || interrupt) {
                        if (client != null) {
                            try {
                                client.close();
                            } catch (IOException ioe) {
                                //ioe.printStackTrace();
                            }
                        }
                        if (interrupt) {
                            try {
                                server.close();
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        return;
                    }
                }
                DataOutputStream outStream = null;
                try {
                    outStream = new DataOutputStream(client.getOutputStream());
                    outStream.writeBytes(MJpegHttpServer.HTTP_HEADER);
                    outStream.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (listener == null || listener.accept(client.getInetAddress())) {
                    while (client.isBound() && !client.isClosed() && client.isConnected() && !interrupt) {
                        try {
                            Pair<byte[], Long> frame = buffer.acquire();
                            String sb = "Content-type: image/jpeg\r\n" +
                                    "Content-Length: " + frame.first.length +
                                    "\r\nX-Timestamp:" + frame.second +
                                    "\r\n\r\n";
                            outStream.writeBytes(sb);
                            outStream.write(frame.first);
                            outStream.writeBytes(MJpegHttpServer.BOUNDARY_LINES);
                            outStream.flush();
                        } catch (SocketException e) {
                            // client is disconnected
                            break;
                        } catch (IOException ioe) {
                            ioe.printStackTrace();
                        }
                    }

                    Log.e(TAG, "run: stopping");

                    if (listener != null) {
                        listener.disconnect(client.getInetAddress());
                    }
                    try {
                        client.shutdownOutput();
                        client.shutdownInput();
                        client.close();
                    } catch (IOException ioe) {
                        ioe.printStackTrace();
                    }
                    try {
                        outStream.close();
                    } catch (IOException | NullPointerException e) {
                        e.printStackTrace();
                    }
                    if (interrupt) {
                        try {
                            server.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        return;
                    }
                    synchronized (lock) {
                        try {
                            lock.wait(1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            try {
                server.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public void setConnectionListener(OnConnectionListener listener) {
            this.listener = listener;
        }
    }
}
