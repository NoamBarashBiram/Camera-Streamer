package com.noam.camerastreamer;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraDevice.StateCallback;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureRequest.Builder;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.Size;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;

public class MainActivity extends AppCompatActivity implements OnSharedPreferenceChangeListener, TextureView.SurfaceTextureListener {
    private static final int CODE_PERMISSIONS_REQUEST = 42;
    private static final String[] neededPermissions = {"android.permission.CAMERA", "android.permission.INTERNET", "android.permission.WAKE_LOCK"};
    public static String[] possibleSizes = new String[0];
    private static int recreationsOnReady = 0;
    private final Size defaultSize = new Size(640, 480);
    private TextView addrText;
    private Handler backgroundHandler = new Handler(Looper.getMainLooper());
    private MJpegBuffer buffer = new MJpegBuffer();
    private CameraCaptureSession curSession = null;
    private Size frameSize;
    private CameraDevice mCamera = null;
    private CameraCharacteristics mChar = null;
    private SharedPreferences preferences;
    private ImageReader reader;
    private Handler readerHandler = new Handler(Looper.getMainLooper());
    private MJpegHttpServer server = null;
    private boolean flashEnabled = false;

    private StateCallback cameraStateCallback = new StateCallback() {
        public void onOpened(@NonNull CameraDevice camera) {
            mCamera = camera;
            createSession();
        }

        public void onDisconnected(CameraDevice camera) {
            camera.close();
        }

        public void onError(CameraDevice camera, int error) {
            toast("There was an error with the camera:", error);
            camera.close();
            mCamera = null;
        }
    };
    private WakeLock wakeLock;
    private CameraCharacteristics backChar;
    private CameraCharacteristics frontChar;
    private CameraManager manager;
    private String mCamId;
    private TextView logView;

    private void createSession() {
        reader = ImageReader.newInstance(frameSize.getWidth(), frameSize.getHeight(),
                ImageFormat.JPEG, 1);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                        new ArrayList<OutputConfiguration>() {{
                            add(new OutputConfiguration(reader.getSurface()));
                        }},
                        command -> backgroundHandler.post(command),
                        new CameraCaptureSession.StateCallback() {
                            public void onConfigured(@NonNull CameraCaptureSession session) {
                                curSession = session;
                                buildRequest();
                            }

                            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                toast("Problem creating session");
                            }
                        });
                mCamera.createCaptureSession(config);
            } else {
                mCamera.createCaptureSession(new ArrayList<Surface>() {
                    {
                        add(reader.getSurface());
                    }
                }, new CameraCaptureSession.StateCallback() {
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        curSession = session;
                        buildRequest();
                    }

                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        toast("Problem creating session");
                    }
                }, backgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        reader.setOnImageAvailableListener(imageReader -> {
            Image img = imageReader.acquireLatestImage();
            MJpegHttpServer mJpegHttpServer = server;
            if (mJpegHttpServer != null && mJpegHttpServer.isRunning()) {
                ByteBuffer byBuf = img.getPlanes()[0].getBuffer();
                byte[] buff = new byte[byBuf.capacity()];
                byBuf.get(buff, 0, buff.length);
                buffer.set(buff, img.getTimestamp());
            }
            img.close();
        }, readerHandler);
    }

    private void buildRequest() {
        byte jpegQuality = (byte) (preferences.getInt(getString(R.string.quality_key), 100) & 255);
        try {
            Builder requestBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            requestBuilder.addTarget(reader.getSurface());
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);
            requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f);
            requestBuilder.set(CaptureRequest.FLASH_MODE,
                    flashEnabled ? CaptureRequest.FLASH_MODE_TORCH :
                            CaptureRequest.FLASH_MODE_OFF);

            requestBuilder.set(CaptureRequest.JPEG_QUALITY, jpegQuality);
            curSession.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        addrText = findViewById(R.id.serverAddr);
        logView = findViewById(R.id.log);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);
        manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        ArrayList<String> permissionsToAsk = new ArrayList<>();
        for (String permission : neededPermissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToAsk.add(permission);
            }
        }
        if (permissionsToAsk.size() != 0) {
            String[] permissionsToRequest = new String[permissionsToAsk.size()];
            permissionsToAsk.toArray(permissionsToRequest);
            requestPermissions(permissionsToRequest, CODE_PERMISSIONS_REQUEST);
        } else {
            readyCamera();
        }

        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.noam.cameraStreamer:cameraWakeLock");
    }


    @SuppressLint("MissingPermission")
    // This method will NEVER be called before checking the permission
    private void readyCamera() {
        try {
            String[] cameraIdList = manager.getCameraIdList();
            int length = cameraIdList.length;
            int i = 0;
            while (i < length) {
                String camId = cameraIdList[i];
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(camId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                int mFacing = Integer.parseInt(preferences.getString(getString(R.string.lens_key), "1"));
                if (facing != null) {
                    if (facing == mFacing) {
                        mChar = characteristics;
                        mCamId = camId;
                        break;
                    }
                }
                i++;
            }
            recreationsOnReady = 0;
        } catch (CameraAccessException e) {
            e.printStackTrace();
            if (recreationsOnReady < 3) {
                toast("Problem communicating with camera, restarting application...");
                recreationsOnReady++;
                recreate();
            } else {
                toast("Tried restarting application 3 times now, there's a problem here");
            }
        }
        Size[] sizes = mChar.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                .getOutputSizes(ImageFormat.JPEG);
        ArrayList<String> list = new ArrayList<>();
        for (Size size : sizes) {
            list.add(size.getWidth() + "x" + size.getHeight());
        }
        String[] strArr = new String[list.size()];
        possibleSizes = strArr;
        list.toArray(strArr);
        frameSize = getOptimalSize(sizes);
    }

    private Size getOptimalSize(Size[] outputSizes) {
        Size target = Size.parseSize(preferences.getString(getString(R.string.size_key), defaultSize.toString()));
        Size closest = new Size(0, 0);
        int closestDistance = target.getHeight() + target.getWidth();
        for (Size size : outputSizes) {
            if (size == target) {
                return size;
            }
            int distance =
                    Math.abs(
                            Math.min(
                                    target.getHeight() - size.getHeight() + target.getWidth() - size.getWidth(),
                                    target.getWidth() - size.getHeight() + target.getHeight() - size.getWidth()
                            )
                    );
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = size;
            }
        }
        return closest;
    }

    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for (int result : grantResults) {
            if (result != 0) {
                toast("Cannot run without internet and camera permissions");
                finish();
                return;
            }
        }
        readyCamera();
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() != R.id.action_settings) {
            return super.onOptionsItemSelected(item);
        }
        MJpegHttpServer mJpegHttpServer = server;
        if (mJpegHttpServer != null && mJpegHttpServer.isRunning()) {
            toast("Stopping server");
            toggleServer(findViewById(R.id.startStreaming));
        }
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
        return true;
    }

    private void toast(Object... objects) {
        final StringBuilder res = new StringBuilder();
        for (int i = 0; i < objects.length; i++) {
            Object object = objects[i];
            if (object == null) {
                res.append("null");
            } else {
                res.append(object);
            }
            if (i < objects.length - 1) {
                res.append(" ");
            }
        }
        runOnUiThread(() ->
                Toast.makeText(MainActivity.this, res.toString(), Toast.LENGTH_LONG).show());
    }

    @SuppressLint("MissingPermission")
    public void toggleServer(final View v) {
        new Thread(() -> {
            if (server == null || !server.isRunning()) {
                String portStr = preferences.getString(getString(R.string.server_port_key), "0");
                int port = Integer.parseInt(portStr);
                try {
                    manager.openCamera(mCamId, cameraStateCallback, backgroundHandler);
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
                try {
                    server = new MJpegHttpServer(buffer, port);
                } catch (java.net.BindException be) {
                    be.printStackTrace();
                    try {
                        server = new MJpegHttpServer(buffer, 0);
                    } catch (IOException e) {
                        toast("Server failed to start on any port");
                        e.printStackTrace();
                        return;
                    }
                    toast("Cannot use the port " + portStr);
                } catch (IOException e) {
                    e.printStackTrace();
                    try {
                        curSession.stopRepeating();
                    } catch (CameraAccessException ex) {
                        ex.printStackTrace();
                    }
                }
                server.setConnectionListener(new MJpegHttpServer.OnConnectionListener() {
                    @Override
                    public boolean accept(final InetAddress address) {
                        runOnUiThread(() ->
                                logView.setText("Streaming to " + address.toString().substring(1)));
                        return true;
                    }

                    @Override
                    public void disconnect(final InetAddress address) {
                        runOnUiThread(() ->
                                logView.setText(address.toString().substring(1) + " disconnected"));
                    }
                });
                server.startServer();
                toast("server was started successfully");
                runOnUiThread(() -> {
                    addrText.setText(String.format("Server is running on %s:%s", getIPAddress(true), server.getPort()));
                    ((FloatingActionButton) v).setImageResource(android.R.drawable.ic_media_pause);
                });
                // wakeLock.acquire();

            } else {
                runOnUiThread(() ->
                        addrText.setText("Closing server... this might take a few seconds"));
                server.close();
                buffer = new MJpegBuffer();
                try {
                    curSession.abortCaptures();
                    curSession.close();
                } catch (CameraAccessException ex) {
                    ex.printStackTrace();
                }
                curSession = null;
                reader.close();
                mCamera.close();
                runOnUiThread(() -> {
                    addrText.setText("The server is not running");
                    logView.setText("");
                    ((FloatingActionButton) v).setImageResource(android.R.drawable.ic_media_play);
                });
                toast("Server is closed");
                server = null;
            }
        }).start();
    }

    public String getIPAddress(boolean useIPv4) {
        try {
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        boolean isIPv4 = sAddr.indexOf(58) < 0;
                        if (useIPv4) {
                            if (isIPv4) {
                                return sAddr;
                            }
                        } else if (!isIPv4) {
                            int delim = sAddr.indexOf(37);
                            return delim < 0 ? sAddr.toUpperCase() : sAddr.substring(0, delim).toUpperCase();
                        }
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return "";
    }

    protected void onDestroy() {
        CameraCaptureSession cameraCaptureSession = curSession;
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
        }
        reader.close();
        mCamera.close();
        MJpegHttpServer mJpegHttpServer = server;
        if (mJpegHttpServer != null && mJpegHttpServer.isRunning()) {
            server.close();
        }
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals(getString(R.string.size_key))) {
            StreamConfigurationMap map = mChar.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                frameSize = getOptimalSize(map.getOutputSizes(ImageFormat.JPEG));
            }
            if (server != null && server.isRunning()) {
                toggleServer(findViewById(R.id.startStreaming));
            }
        } else if (key.equals(getString(R.string.lens_key))) {
            readyCamera();
        }
    }

    public void toggleFlash(View view) {
        flashEnabled = !flashEnabled;
        ((ImageView) view).setImageResource(flashEnabled ? R.drawable.flash_on : R.drawable.flash_off);
        try {
            manager.setTorchMode(mCamId, flashEnabled);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        if (curSession == null) return;
        try {
            curSession.stopRepeating();
            buildRequest();
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
        Log.e("Surface", "Available");
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
        Log.e("Surface", "Changed");
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        Log.e("Surface", "Destroyed");
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        Log.e("Surface", "Updated");
    }
}
