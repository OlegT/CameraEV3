package info.ev3.cameraev3;

import android.Manifest;
import android.bluetooth.BluetoothSocket;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import java.nio.ByteBuffer;

import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import androidx.appcompat.app.AlertDialog;

public class MainActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {
    private static final String TAG = "EV3Controller";
    private static final UUID EV3_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private TextureView textureView;
    private OverlayView overlayView;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder previewRequestBuilder;
    private CameraCaptureSession captureSession;
    private Size previewSize;
    private Button startButton;
    private Spinner cameraSpinner;
    private EditText logOutput;
    private FrameLayout previewContainer;
    private String[] cameraIds;
    private String selectedCameraId;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private float scaleX = 1.0f;
    private float scaleY = 1.0f;
    private float scale1 = 1.0f;
    private float scale2 = 1.0f;
    private static final int BINARIZATION_THRESHOLD = 128;
    private Bitmap overlayBitmap;
    private SeekBar transparencySeekBar;
    private SeekBar thresholdSeekBar;
    private EditText transparencyValue;
    private EditText thresholdValue;
    private CheckBox flashCheckbox;
    private boolean isFlashSupported = false;
    private volatile int binarizationThreshold = 128;
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 201;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket socket;
    private OutputStream outputStream;
    private InputStream inputStream;
    private ArrayAdapter<String> devicesAdapter;
    private ArrayList<BluetoothDevice> ev3Devices = new ArrayList<>();
    private BluetoothDevice selectedDevice;
    private Button connectButton;
    private EditText speedValue;
    int speed = 75;
    private EditText kpValue;
    double kp = 1.0;
    private EditText kdValue;
    double kd = 0.5;
    double e_last = 0;
    private boolean isConnected = false;
    private boolean isSTART = false;
    private boolean isFirst = true;
    private static final String PREFS_NAME = "AppSettings";
    private static final String KEY_THRESHOLD = "threshold";
    private static final String KEY_TRANSPARENCY = "transparency";
    private static final String KEY_SPEED = "speed";
    private static final String KEY_KP = "kp";
    private static final String KEY_KD = "kd";
    private long lastFpsUpdateTime = 0;
    private int frameCount = 0;
    private float currentFps = 0;

    // Zoom control variables
    private SeekBar zoomSeekBar;
    private TextView zoomValue;
    private float minZoom = 1.0f;
    private float maxZoom = 1.0f;
    private float currentZoom = 1.0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        backgroundThread = new HandlerThread("BackgroundThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            );
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }
        setContentView(R.layout.activity_main);

        previewContainer = findViewById(R.id.previewContainer);
        textureView = findViewById(R.id.textureView);
        overlayView = findViewById(R.id.overlayView);
        startButton = findViewById(R.id.startButton);
        cameraSpinner = findViewById(R.id.cameraSpinner);
        logOutput = findViewById(R.id.logOutput);
        flashCheckbox = findViewById(R.id.flashCheckbox);
        connectButton = findViewById(R.id.connectButton);
        connectButton.setOnClickListener(v -> showBluetoothDevicesDialog());
        speedValue = findViewById(R.id.speedValue);
        kpValue = findViewById(R.id.kpValue);
        kdValue = findViewById(R.id.kdValue);

        // Zoom control
        zoomSeekBar = findViewById(R.id.zoomSeekBar);
        zoomValue = findViewById(R.id.zoomValue);
        zoomSeekBar.setEnabled(false);
        zoomValue.setText("Zoom: 1.0x");

        zoomSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!seekBar.isEnabled()) return;
                currentZoom = minZoom + (maxZoom - minZoom) * (progress / 100f);
                zoomValue.setText(String.format(Locale.US, "Zoom: %.2fx", currentZoom));
                applyZoom();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Bluetooth initialization
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            connectButton.setEnabled(false);
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
        }

        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            cameraIds = manager.getCameraIdList();
            selectedCameraId = cameraIds[0];

            // Use all cameras
            cameraIds = manager.getCameraIdList();

            // Set preview size for first camera
            try {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(selectedCameraId);
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    Size[] outputSizes = map.getOutputSizes(SurfaceTexture.class);
                    previewSize = getMinimalSize(outputSizes);
                }
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

            // Camera names with details
            List<String> cameraNames = getCameraNames(manager, cameraIds);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_spinner_item,
                    cameraNames
            );
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            cameraSpinner.setAdapter(adapter);
            cameraSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (cameraDevice != null) {
                        cameraDevice.close();
                        cameraDevice = null;
                    }
                    selectedCameraId = cameraIds[position];
                    CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
                    try {
                        CameraCharacteristics characteristics = manager.getCameraCharacteristics(selectedCameraId);
                        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                        if (map != null) {
                            Size[] outputSizes = map.getOutputSizes(SurfaceTexture.class);
                            previewSize = getMinimalSize(outputSizes);
                        }
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                    openCamera();
                    checkFlashSupport();
                    updateZoomControl();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
            if (cameraIds.length > 0) selectedCameraId = cameraIds[0];
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        checkFlashSupport();
        flashCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !isFlashSupported) {
                flashCheckbox.setChecked(false);
                Toast.makeText(this, "Flash not supported", Toast.LENGTH_SHORT).show();
                return;
            }
            updateFlashMode();
        });

        textureView.setSurfaceTextureListener(this);

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isSTART = !isSTART;
                isFirst = true;
                if (isSTART) {
                    startButton.setText("STOP");
                } else {
                    startButton.setText("START");
                }
            }
        });

        transparencyValue = findViewById(R.id.transparencyValue);
        thresholdValue = findViewById(R.id.thresholdValue);

        transparencySeekBar = findViewById(R.id.transparencySeekBar);
        thresholdSeekBar = findViewById(R.id.thresholdSeekBar);

        transparencySeekBar.setProgress(128);
        thresholdSeekBar.setProgress(binarizationThreshold);

        loadSettings();

        speedValue.addTextChangedListener(new GenericTextWatcher());
        kpValue.addTextChangedListener(new GenericTextWatcher());
        kdValue.addTextChangedListener(new GenericTextWatcher());

        transparencySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                transparencyValue.setText(String.valueOf(progress));
                overlayView.setBitmapAlpha(progress);
                saveSettings();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        thresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                thresholdValue.setText(String.valueOf(progress));
                binarizationThreshold = progress;
                saveSettings();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        transparencyValue.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                try {
                    int value = Integer.parseInt(s.toString());
                    value = Math.max(0, Math.min(255, value));
                    transparencySeekBar.setProgress(value);
                } catch (NumberFormatException e) {
                    transparencySeekBar.setProgress(128);
                }
            }
        });

        thresholdValue.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                try {
                    int value = Integer.parseInt(s.toString());
                    value = Math.max(0, Math.min(255, value));
                    thresholdSeekBar.setProgress(value);
                } catch (NumberFormatException e) {
                    thresholdSeekBar.setProgress(128);
                }
            }
        });

        updateZoomControl();
    }

    private class GenericTextWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) { saveSettings(); }
    }

    private void openCamera() {
        if (!isCameraSupported(selectedCameraId)) {
            log("Camera " + selectedCameraId + " does not support preview");
            return;
        }
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                Toast.makeText(this, "NO PERMISSION!", Toast.LENGTH_SHORT).show();
                return;
            }
            manager.openCamera(selectedCameraId, stateCallback, null);
            log("Opening camera: " + selectedCameraId);
        } catch (CameraAccessException | SecurityException e) {
            log("Access error: " + e.getMessage());
        }
    }

    private boolean isCameraSupported(String cameraId) {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            return map != null && map.getOutputSizes(SurfaceTexture.class).length > 0;
        } catch (CameraAccessException e) {
            return false;
        }
    }

    private void adjustAspectRatio() {
        if (previewSize == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        float ratio = (float) previewSize.getWidth() / previewSize.getHeight();
        int previewHeight = (int) (screenWidth / ratio);
        scaleX = (float) screenWidth / previewSize.getWidth();
        scaleY = (float) previewHeight / previewSize.getHeight();
        scale1 = (float) screenWidth / previewSize.getHeight();
        scale2 = (float) previewHeight / previewSize.getWidth();
        ViewGroup.LayoutParams containerParams = previewContainer.getLayoutParams();
        containerParams.width = screenWidth;
        containerParams.height = previewHeight;
        previewContainer.setLayoutParams(containerParams);
        ViewGroup.LayoutParams tvParams = textureView.getLayoutParams();
        tvParams.width = screenWidth;
        tvParams.height = previewHeight;
        textureView.setLayoutParams(tvParams);
        overlayView.setLayoutParams(tvParams);
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            startPreview();
            log("Camera opened");
            updateZoomControl();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
            log("Camera disconnected");
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            log("Camera error: " + error);
        }
    };

    private void startPreview() {
        if (cameraDevice == null || !textureView.isAvailable() || previewSize == null) return;
        adjustAspectRatio();

        if (imageReader != null) {
            imageReader.close();
        }
        imageReader = ImageReader.newInstance(
                previewSize.getWidth(),
                previewSize.getHeight(),
                ImageFormat.YUV_420_888,
                2
        );
        imageReader.setOnImageAvailableListener(imageAvailableListener, backgroundHandler);

        String resolution = previewSize.getWidth() + "x" + previewSize.getHeight();
        overlayView.setResolution(resolution);

        SurfaceTexture texture = textureView.getSurfaceTexture();
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        Surface surface = new Surface(texture);
        try {
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            previewRequestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL);
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            previewRequestBuilder.addTarget(surface);
            previewRequestBuilder.addTarget(imageReader.getSurface());

            // Apply initial zoom value
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom);
            }

            List<Surface> surfaces = Arrays.asList(surface, imageReader.getSurface());
            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, null);
                        log("Preview started");
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                        log("CaptureException: " + e.getMessage());
                    }
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    log("Configuration failed");
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            log("CameraAccessException: " + e.getMessage());
        }
    }

    private ImageReader.OnImageAvailableListener imageAvailableListener = reader -> {
        Image image = reader.acquireLatestImage();
        if (image == null) return;

        try {
            processImage(image);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        image.close();
    };

    private void processImage(Image image) throws IOException {
        frameCount++;
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFpsUpdateTime >= 1000) {
            currentFps = frameCount * 1000f / (currentTime - lastFpsUpdateTime);
            frameCount = 0;
            lastFpsUpdateTime = currentTime;
            runOnUiThread(() -> overlayView.setFps(currentFps));
        }

        Image.Plane yPlane = image.getPlanes()[0];
        ByteBuffer yBuffer = yPlane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();

        byte[] yData = new byte[yBuffer.remaining()];
        yBuffer.get(yData);
        image.close();

        overlayBitmap = createBinaryMask(yData, width, height, rowStride, pixelStride);
        int[] center = findLargestDarkSpotCenter(overlayBitmap);
        int cX = height - center[1];
        int cY = center[0];
        int viewCenterX = (int) (cX * scale1);
        int viewCenterY = (int) (cY * scale2);
        double ang = PID(cX * 2.0 / height - 1.0, kp, kd);

        if (isConnected && isSTART) {
            if (ang > 0) {
                sendMotorSpeed('B', (byte) (int) (speed * Math.cos(2 * ang)));
                sendMotorSpeed('C', (byte) speed);
            } else {
                sendMotorSpeed('B', (byte) speed);
                sendMotorSpeed('C', (byte) (int) (speed * Math.cos(2 * ang)));
            }
        } else if (isConnected && !isSTART) {
            if (isFirst) {
                sendMotorStop('B');
                sendMotorStop('C');
                isFirst = false;
            }
        }

        runOnUiThread(() -> {
            overlayView.setCenter(viewCenterX, viewCenterY);
            overlayView.setCenterRaw(cX * 176 / 144, cY * 144 / 176);
            overlayView.setOverlayBitmap(overlayBitmap);
            overlayView.invalidate();
            DecimalFormat df = new DecimalFormat("#.##");
            logOutput.setText(" e=" + df.format(cX * 2.0 / height - 1.0) + "\n" +
                    "Ang=" + df.format(ang) + " Ang_=" + df.format(ang * 180 / Math.PI) + "\n" +
                    "Speed = " + (int) (speed * Math.cos(2 * ang))
            );
        });
    }

    private Bitmap createBinaryMask(byte[] yData, int width, int height, int rowStride, int pixelStride) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * rowStride + x * pixelStride;
                if (index >= yData.length) continue;
                int luminance = yData[index] & 0xFF;
                int color = (luminance < binarizationThreshold)
                        ? 0xFF000000
                        : 0xFFFFFFFF;
                pixels[y * width + x] = color;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private int[] findLargestDarkSpotCenter(Bitmap bitmap) {
        if (bitmap == null) {
            return new int[]{-1, -1};
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        boolean[][] visited = new boolean[height][width];
        int maxArea = 0;
        int centerX = -1;
        int centerY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((pixels[y * width + x] & 0x00FFFFFF) == 0x000000 && !visited[y][x]) {
                    List<int[]> currentSpotPixels = new ArrayList<>();
                    Queue<int[]> queue = new LinkedList<>();
                    queue.offer(new int[]{x, y});
                    visited[y][x] = true;
                    currentSpotPixels.add(new int[]{x, y});
                    int currentArea = 0;
                    int currentSumX = 0;
                    int currentSumY = 0;
                    while (!queue.isEmpty()) {
                        int[] currentPixel = queue.poll();
                        int currentX = currentPixel[0];
                        int currentY = currentPixel[1];
                        currentArea++;
                        currentSumX += currentX;
                        currentSumY += currentY;
                        int[][] neighbors = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
                        for (int[] neighbor : neighbors) {
                            int newX = currentX + neighbor[0];
                            int newY = currentY + neighbor[1];
                            if (newX >= 0 && newX < width && newY >= 0 && newY < height &&
                                    (pixels[newY * width + newX] & 0x00FFFFFF) == 0x000000 && !visited[newY][newX]) {
                                visited[newY][newX] = true;
                                queue.offer(new int[]{newX, newY});
                                currentSpotPixels.add(new int[]{newX, newY});
                            }
                        }
                    }
                    if (currentArea > maxArea && currentArea > 0) {
                        maxArea = currentArea;
                        centerX = currentSumX / currentArea;
                        centerY = currentSumY / currentArea;
                    }
                }
            }
        }
        if (maxArea > 0) {
            return new int[]{centerX, centerY};
        } else {
            return new int[]{-1, -1};
        }
    }

    private void log(String message) {
        logOutput.append(message + "\n");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                log("Camera permission denied");
            }
        }
    }

    private Size getMinimalSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size minSize = sizes[0];
        long minArea = minSize.getWidth() * minSize.getHeight();
        for (Size size : sizes) {
            long area = size.getWidth() * size.getHeight();
            if (area < minArea) {
                minArea = area;
                minSize = size;
            }
        }
        return minSize;
    }

    private List<String> getCameraNames(CameraManager manager, String[] cameraIds) {
        List<String> names = new ArrayList<>();
        for (String cameraId : cameraIds) {
            try {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                String lensFacingStr;
                switch (lensFacing != null ? lensFacing : -1) {
                    case CameraCharacteristics.LENS_FACING_BACK:
                        lensFacingStr = "Back";
                        break;
                    case CameraCharacteristics.LENS_FACING_FRONT:
                        lensFacingStr = "Front";
                        break;
                    case CameraCharacteristics.LENS_FACING_EXTERNAL:
                        lensFacingStr = "External";
                        break;
                    default:
                        lensFacingStr = "Unknown";
                }
                float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                String focalStr = (focalLengths != null && focalLengths.length > 0)
                        ? String.format(Locale.US, " %.1fmm", focalLengths[0])
                        : "";
                String physicalIds = "";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    physicalIds = characteristics.getPhysicalCameraIds().toString();
                    if (!physicalIds.isEmpty()) physicalIds = " physId=" + physicalIds;
                }
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                String resolution = "N/A";
                if (map != null) {
                    Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
                    if (sizes != null && sizes.length > 0) {
                        Size max = sizes[0];
                        for (Size s : sizes) {
                            if (s.getWidth() * s.getHeight() > max.getWidth() * max.getHeight()) {
                                max = s;
                            }
                        }
                        resolution = max.getWidth() + "x" + max.getHeight();
                    }
                }
                names.add(lensFacingStr + " #" + cameraId + " (" + resolution + focalStr + physicalIds + ")");
            } catch (CameraAccessException e) {
                names.add("Access error for #" + cameraId);
            }
        }
        return names;
    }

    // ZOOM control methods
    private void updateZoomControl() {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(selectedCameraId);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.util.Range<Float> zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (zoomRange != null) {
                    minZoom = zoomRange.getLower();
                    maxZoom = zoomRange.getUpper();
                } else {
                    minZoom = 1.0f;
                    maxZoom = 1.0f;
                }
            } else {
                Float maxZoomObj = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                minZoom = 1.0f;
                maxZoom = (maxZoomObj != null && maxZoomObj > 1.0f) ? maxZoomObj : 1.0f;
            }
            if (maxZoom > minZoom) {
                zoomSeekBar.setEnabled(true);
                zoomSeekBar.setVisibility(View.VISIBLE);
                zoomValue.setVisibility(View.VISIBLE);
                zoomSeekBar.setMax(100);
                zoomSeekBar.setProgress(0);
                currentZoom = minZoom;
            } else {
                zoomSeekBar.setEnabled(false);
                zoomSeekBar.setVisibility(View.GONE);
                zoomValue.setVisibility(View.GONE);
                minZoom = maxZoom = currentZoom = 1.0f;
            }
            zoomValue.setText(String.format(Locale.US, "Zoom: %.2fx", currentZoom));
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void applyZoom() {
        if (previewRequestBuilder == null || captureSession == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom);
            } else {
                CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(selectedCameraId);
                Rect sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                if (sensorRect != null && maxZoom > 1.0f) {
                    int cropW = (int)(sensorRect.width() / currentZoom);
                    int cropH = (int)(sensorRect.height() / currentZoom);
                    int cropX = (sensorRect.width() - cropW) / 2;
                    int cropY = (sensorRect.height() - cropH) / 2;
                    Rect zoomRect = new Rect(cropX, cropY, cropX + cropW, cropY + cropH);
                    previewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomRect);
                }
            }
            captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
        }
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
        adjustAspectRatio();
    }

    private void checkFlashSupport() {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(selectedCameraId);
            Boolean flash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            isFlashSupported = (flash != null && flash);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            isFlashSupported = false;
        }
        flashCheckbox.setEnabled(isFlashSupported);
    }

    private void updateFlashMode() {
        if (previewRequestBuilder == null) return;
        try {
            if (flashCheckbox.isChecked() && isFlashSupported) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            } else {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            }
            captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void showBluetoothDevicesDialog() {
        if (!isConnected) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                            new String[]{Manifest.permission.BLUETOOTH_SCAN},
                            REQUEST_BLUETOOTH_PERMISSIONS
                    );
                    return;
                }
            }
            if (!checkBluetoothPermissions()) {
                requestBluetoothPermissions();
                return;
            }
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "NO PERMISSION!", Toast.LENGTH_SHORT).show();
                    return;
                }
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                return;
            }
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            List<BluetoothDevice> deviceList = new ArrayList<>(pairedDevices);
            String[] deviceNames = deviceList.stream()
                    .map(device -> device.getName() + "\n" + device.getAddress() + "\n------------------------------")
                    .toArray(String[]::new);
            new AlertDialog.Builder(this)
                    .setTitle("Select Device")
                    .setItems(deviceNames, (dialog, which) -> connectToDevice(deviceList.get(which)))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            setConnectionState(false);
            disconnect();
        }
    }

    private boolean checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH_PERMISSIONS);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH}, REQUEST_BLUETOOTH_PERMISSIONS);
        }
    }

    private void connectToDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "NO PERMISSION!", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Connecting to " + device.getName(), Toast.LENGTH_SHORT).show();
        selectedDevice = device;
        connectToDevice();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT && resultCode == RESULT_OK) {
            showBluetoothDevicesDialog();
        }
    }

    private void connectToDevice() {
        if (selectedDevice == null) {
            return;
        }
        new Thread(() -> {
            try {
                if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    showToast("NO PERMISSION!");
                    return;
                }
                socket = selectedDevice.createRfcommSocketToServiceRecord(EV3_UUID);
                bluetoothAdapter.cancelDiscovery();
                socket.connect();
                outputStream = socket.getOutputStream();
                inputStream = socket.getInputStream();
                runOnUiThread(() -> {
                    setConnectionState(true);
                    showToast("Connected to " + selectedDevice.getName());
                });
            } catch (IOException e) {
                Log.e(TAG, "Connection error: " + e.getMessage());
                runOnUiThread(() -> showToast("Connection error"));
                disconnect();
            }
        }).start();
    }

    private void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
    }

    private void setConnectionState(boolean connected) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        isConnected = connected;
        connectButton.setText(connected ? selectedDevice.getName() : "Connect Bluetooth");
    }

    private void disconnect() {
        try {
            if (socket != null) {
                socket.close();
                outputStream = null;
                inputStream = null;
            }
            runOnUiThread(() -> {
                setConnectionState(false);
                showToast("Disconnected");
            });
        } catch (IOException e) {
            Log.e(TAG, "Disconnect error: " + e.getMessage());
        }
    }

    private void sendMotorSpeed(char port, byte speed) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        writeUShort(byteArrayOutputStream, 2);
        writeUByte(byteArrayOutputStream, 0x80);
        writeVariablesAllocation(byteArrayOutputStream, 0, 0);
        writeCommand(byteArrayOutputStream, 0xA5);
        writeParameterAsSmallByte(byteArrayOutputStream, 0);
        writeParameterAsSmallByte(byteArrayOutputStream, getPortByte(port));
        writeParameterAsByte(byteArrayOutputStream, speed);
        writeUShort(outputStream, byteArrayOutputStream.size());
        byteArrayOutputStream.writeTo(outputStream);
        byteArrayOutputStream.reset();
        writeUShort(byteArrayOutputStream, 2);
        writeUByte(byteArrayOutputStream, 0x80);
        writeVariablesAllocation(byteArrayOutputStream, 0, 0);
        writeCommand(byteArrayOutputStream, 0xA6);
        writeParameterAsSmallByte(byteArrayOutputStream, 0);
        writeParameterAsSmallByte(byteArrayOutputStream, getPortByte(port));
        writeUShort(outputStream, byteArrayOutputStream.size());
        byteArrayOutputStream.writeTo(outputStream);
    }

    private void sendMotorStop(char port) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        writeUShort(byteArrayOutputStream, 2);
        writeUByte(byteArrayOutputStream, 0x80);
        writeVariablesAllocation(byteArrayOutputStream, 0, 0);
        writeCommand(byteArrayOutputStream, 0xA3);
        writeParameterAsSmallByte(byteArrayOutputStream, 0);
        writeParameterAsSmallByte(byteArrayOutputStream, getPortByte(port));
        writeParameterAsUByte(byteArrayOutputStream, 1);
        writeUShort(outputStream, byteArrayOutputStream.size());
        byteArrayOutputStream.writeTo(outputStream);
    }

    private byte getPortByte(char port) {
        switch (port) {
            case 'A': return 0x01;
            case 'B': return 0x02;
            case 'C': return 0x04;
            case 'D': return 0x08;
            default:  return 0x00;
        }
    }

    private void writeUByte(OutputStream _stream, int _ubyte) throws IOException {
        _stream.write(_ubyte > Byte.MAX_VALUE ? _ubyte - 256 : _ubyte);
    }
    private void writeByte(OutputStream _stream, byte _byte) throws IOException {
        _stream.write(_byte);
    }
    private void writeUShort(OutputStream _stream, int _ushort) throws IOException {
        writeUByte(_stream, _ushort & 0xFF);
        writeUByte(_stream, (_ushort >> 8) & 0xFF);
    }
    private void writeCommand(OutputStream _stream, int opCode) throws IOException {
        writeUByte(_stream, opCode);
    }
    private void writeVariablesAllocation(OutputStream _stream, int globalSize, int localSize) throws IOException {
        writeUByte(_stream, globalSize & 0xFF);
        writeUByte(_stream, ((globalSize >> 8) & 0x3) | ((localSize << 2) & 0xFC));
    }
    private void writeParameterAsSmallByte(OutputStream _stream, int value) throws IOException {
        if (value < 0 && value > 31)
            throw new IllegalArgumentException("Value must be in range 0..31.");
        writeUByte(_stream, value);
    }
    private void writeParameterAsUByte(OutputStream _stream, int value) throws IOException {
        if (value < 0 && value > 255)
            throw new IllegalArgumentException("Value must be in range 0..255.");
        writeUByte(_stream, 0x81);
        writeUByte(_stream, value);
    }
    private void writeParameterAsByte(OutputStream _stream, int value) throws IOException {
        if (value < Byte.MIN_VALUE && value > Byte.MAX_VALUE)
            throw new IllegalArgumentException("Value must be in range " + Byte.MIN_VALUE + " to " + Byte.MAX_VALUE + ".");
        writeUByte(_stream, 0x81);
        writeByte(_stream, (byte)value);
    }

    private void saveSettings() {
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = settings.edit();
        try{
            speed = Integer.parseInt(speedValue.getText().toString());
        } catch (NumberFormatException e) {
            speed = 0;
        }
        try {
            kp = Float.parseFloat(kpValue.getText().toString());
        } catch (NumberFormatException e) {
            kp = 0;
        }
        try {
            kd = Float.parseFloat(kdValue.getText().toString());
        } catch (NumberFormatException e) {
            kd = 0;
        }
        editor.putInt(KEY_THRESHOLD, binarizationThreshold);
        editor.putInt(KEY_TRANSPARENCY, transparencySeekBar.getProgress());
        editor.putInt(KEY_SPEED, speed);
        editor.putFloat(KEY_KP, (float)kp);
        editor.putFloat(KEY_KD, (float)kd);
        editor.apply();
    }

    private void loadSettings() {
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        binarizationThreshold = settings.getInt(KEY_THRESHOLD, 128);
        thresholdSeekBar.setProgress(binarizationThreshold);
        thresholdValue.setText(String.valueOf(binarizationThreshold));
        int transparency = settings.getInt(KEY_TRANSPARENCY, 128);
        transparencySeekBar.setProgress(transparency);
        transparencyValue.setText(String.valueOf(transparency));
        overlayView.setBitmapAlpha(transparency);
        speed = settings.getInt(KEY_SPEED, 75);
        speedValue.setText(String.valueOf(speed));
        kp = settings.getFloat(KEY_KP, 1.0f);
        kpValue.setText(String.valueOf(kp));
        kd = settings.getFloat(KEY_KD, 0.5f);
        kdValue.setText(String.valueOf(kd));
    }

    double PID(double e, double kp, double kd){
        double res = e * kp + (e - e_last) * kd;
        e_last = e;
        return res;
    }

    @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}
    @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { return false; }
    @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
}
