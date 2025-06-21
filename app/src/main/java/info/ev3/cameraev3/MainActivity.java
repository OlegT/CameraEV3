package info.ev3.cameraev3;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Range;
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
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

//import org.tensorflow.lite.Interpreter;



public class MainActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {

    private static final String TAG = "EV3Controller";
    private static final UUID EV3_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 201;
    private static final int BINARIZATION_THRESHOLD = 128;

    private static final String PREFS_NAME = "AppSettings";
    private static final String KEY_THRESHOLD = "threshold";
    private static final String KEY_TRANSPARENCY = "transparency";
    private static final String KEY_SPEED = "speed";
    private static final String KEY_KP = "kp";
    private static final String KEY_KD = "kd";
    private static final String KEY_INVERSE = "inverse";
    private static final String KEY_FILTER = "filter";

    private static final int[][] DIRS8 = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1},
            {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };



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
    private Bitmap overlayBitmap;
    private SeekBar transparencySeekBar;
    private SeekBar thresholdSeekBar;
    private EditText transparencyValue;
    private EditText thresholdValue;
    private CheckBox flashCheckbox;
    private boolean isFlashSupported = false;
    private volatile int binarizationThreshold = 128;

    // Bluetooth
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket socket;
    private OutputStream outputStream;
    private InputStream inputStream;
    private ArrayAdapter<String> devicesAdapter;
    private ArrayList<BluetoothDevice> ev3Devices = new ArrayList<>();
    private BluetoothDevice selectedDevice;
    private Button connectButton;

    // PID
    private EditText speedValue;
    private int speed = 75;
    private EditText kpValue;
    private double kp = 1.0;
    private EditText kdValue;
    private double kd = 0.5;
    private double e_last = 0;
    private boolean turnRight = true;
    private boolean isConnected = false;
    private boolean isSTART = false;
    private boolean isFirst = true;
    private boolean isFirstRead = true;

    // FPS
    private long lastFpsUpdateTime = 0;
    private int frameCount = 0;
    private float currentFps = 0;

    // Checkboxes
    private CheckBox inverseCheckbox;
    private CheckBox filterCheckbox;

    // Zoom
    private SeekBar zoomSeekBar;
    private TextView zoomValue;
    private float minZoom = 1.0f;
    private float maxZoom = 1.0f;
    private float currentZoom = 1.0f;

    // Listeners
    private final CompoundButton.OnCheckedChangeListener inverseListener = (buttonView, isChecked) -> saveSettings();
    private final CompoundButton.OnCheckedChangeListener filterListener = (buttonView, isChecked) -> saveSettings();
    //endregion

    // Очередь команд для Bluetooth
    private final LinkedBlockingQueue<Runnable> commandQueue = new LinkedBlockingQueue<>(1);
    private ExecutorService bluetoothExecutor;


    private List<int[]> whiteLines = new ArrayList<>();

    private volatile float lastDistance = -1;
    private volatile int lastEncoderA = -1;
    private volatile int lastEncoderA0 = -1;
    private volatile int lastEncoderB = -1;
    private volatile int lastEncoderC = -1;

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
        inverseCheckbox = findViewById(R.id.inverseCheckbox);
        filterCheckbox = findViewById(R.id.filterCheckbox);

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

        // Инициализация Bluetooth исполнителя
        bluetoothExecutor = Executors.newSingleThreadExecutor();

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

    public static Bitmap removeSmallIslands(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int[] result = pixels.clone();

        // 1. Удаление одиночных пикселей
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                int pixel = pixels[idx];
                boolean isBlack = (pixel & 0x00FFFFFF) == 0x000000;
                boolean hasNeighborSame = false;
                for (int[] d : DIRS8) {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                    int nPixel = pixels[ny * width + nx];
                    boolean neighborIsBlack = (nPixel & 0x00FFFFFF) == 0x000000;
                    if (neighborIsBlack == isBlack) {
                        hasNeighborSame = true;
                        break;
                    }
                }
                if (!hasNeighborSame) {
                    result[idx] = isBlack ? 0xFFFFFFFF : 0xFF000000;
                }
            }
        }

        // 2. Удаление 2x2 островков
        for (int y = 0; y < height - 1; y++) {
            for (int x = 0; x < width - 1; x++) {
                int idx00 = y * width + x;
                int idx01 = (y + 1) * width + x;
                int idx10 = y * width + (x + 1);
                int idx11 = (y + 1) * width + (x + 1);

                int p00 = result[idx00], p01 = result[idx01], p10 = result[idx10], p11 = result[idx11];
                boolean blockIsBlack = ((p00 & 0x00FFFFFF) == 0x000000) &&
                        ((p01 & 0x00FFFFFF) == 0x000000) &&
                        ((p10 & 0x00FFFFFF) == 0x000000) &&
                        ((p11 & 0x00FFFFFF) == 0x000000);
                boolean blockIsWhite = ((p00 & 0x00FFFFFF) != 0x000000) &&
                        ((p01 & 0x00FFFFFF) != 0x000000) &&
                        ((p10 & 0x00FFFFFF) != 0x000000) &&
                        ((p11 & 0x00FFFFFF) != 0x000000);

                if (blockIsBlack || blockIsWhite) {
                    // Проверим окружение блока
                    boolean surrounded = true;
                    for (int dy = -1; dy <= 2 && surrounded; dy++) {
                        for (int dx = -1; dx <= 2 && surrounded; dx++) {
                            // Только по периметру
                            if (dx == -1 || dx == 2 || dy == -1 || dy == 2) {
                                int nx = x + dx, ny = y + dy;
                                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                                int nPixel = result[ny * width + nx];
                                boolean neighborIsBlack = (nPixel & 0x00FFFFFF) == 0x000000;
                                if (blockIsBlack && neighborIsBlack) surrounded = false;
                                if (blockIsWhite && !neighborIsBlack) surrounded = false;
                            }
                        }
                    }
                    if (surrounded) {
                        int fillColor = blockIsBlack ? 0xFFFFFFFF : 0xFF000000;
                        result[idx00] = fillColor;
                        result[idx01] = fillColor;
                        result[idx10] = fillColor;
                        result[idx11] = fillColor;
                    }
                }
            }
        }

        Bitmap out = Bitmap.createBitmap(width, height, bitmap.getConfig());
        out.setPixels(result, 0, width, 0, 0, width, height);
        return out;
    }

    public static Bitmap removeIslands3x3(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int[] result = pixels.clone();

        // Перебираем внутренние точки, чтобы не выходить за границы (x: 1 .. width-4, y: 1 .. height-4)
        for (int y = 1; y < height - 3; y++) {
            for (int x = 1; x < width - 3; x++) {
                // 1. Получить цвет периметра
                int borderColor = -1;
                boolean borderConsistent = true;
                // Проверяем всю рамку 5x5 кроме внутреннего 3x3
                for (int dy = -1; dy <= 3 && borderConsistent; dy++) {
                    for (int dx = -1; dx <= 3 && borderConsistent; dx++) {
                        if (dx >= 0 && dx <= 2 && dy >= 0 && dy <= 2) continue; // skip inner 3x3
                        int nx = x + dx, ny = y + dy;
                        int idx = ny * width + nx;
                        int color = pixels[idx] & 0x00FFFFFF;
                        if (borderColor == -1) {
                            borderColor = color;
                        } else if (borderColor != color) {
                            borderConsistent = false;
                        }
                    }
                }
                if (!borderConsistent) continue;

                // 2. Если весь периметр одного цвета — залить внутренний 3x3 этим цветом
                int fillColor = (borderColor == 0x000000) ? 0xFF000000 : 0xFFFFFFFF;
                for (int dy = 0; dy < 3; dy++) {
                    int yw = (y + dy) * width;
                    for (int dx = 0; dx < 3; dx++) {
                        result[yw + (x + dx)] = fillColor;
                    }
                }
            }
        }

        Bitmap out = Bitmap.createBitmap(width, height, bitmap.getConfig());
        out.setPixels(result, 0, width, 0, 0, width, height);
        return out;
    }

    private int[] findLargestDarkSpotCenter(boolean[][] black) {
        if (black == null || black.length == 0 || black[0].length == 0) {
            return new int[]{-1, -1};
        }
        int height = black.length;
        int width = black[0].length;
        int minArea = width * height / 1000;
        boolean[][] visited = new boolean[height][width];
        int maxArea = 0;
        int centerX = -1;
        int centerY = -1;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (black[y][x] && !visited[y][x]) {
                    Queue<int[]> queue = new LinkedList<>();
                    queue.offer(new int[]{x, y});
                    visited[y][x] = true;
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
                                    black[newY][newX] && !visited[newY][newX]) {
                                visited[newY][newX] = true;
                                queue.offer(new int[]{newX, newY});
                            }
                        }
                    }
                    if (currentArea > maxArea && currentArea > minArea) {
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
                Range<Float> zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
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
        if (bluetoothExecutor != null) {
            bluetoothExecutor.shutdownNow();
        }
        commandQueue.clear();
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
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
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

    // Асинхронное выполнение команд
    private void executeCommand(Runnable command) {
        if (!isConnected || bluetoothExecutor.isShutdown()) return;
        commandQueue.clear();

        try {
            if (commandQueue.offer(command, 50, TimeUnit.MILLISECONDS)) {
                bluetoothExecutor.execute(() -> {
                    try {
                        Runnable task = commandQueue.poll();
                        if (task != null) {
                            task.run();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Command execution error", e);
                        runOnUiThread(this::disconnect);
                    }
                });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    private void sendMotorSpeedAngle(char port, byte power, int angle) throws IOException{
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        writeUShort(byteArrayOutputStream, 3);
        writeUByte(byteArrayOutputStream, 0x80);
        writeVariablesAllocation(byteArrayOutputStream, 0, 0);
        writeCommand(byteArrayOutputStream, 0xAE/*opOutput_Step_Speed*/);
        writeParameterAsSmallByte(byteArrayOutputStream, 0);
        writeParameterAsSmallByte(byteArrayOutputStream, getPortByte(port));
        writeParameterAsByte(byteArrayOutputStream, power);
        //Записываем, сколько оборотов двигатель будет разгоняться (0 - разгоняться будет моментально).
        writeParameterAsInteger(byteArrayOutputStream, 0);
        //Записываем, сколько оборотов двигатель будет крутиться на полной скорости (2,5 оборота, т.е. 900 градусов).
        writeParameterAsInteger(byteArrayOutputStream, angle);
        //Записываем, сколько оборотов двигатель будет замедляться (0,5 оборота, т.е. 180 градусов).
        writeParameterAsInteger(byteArrayOutputStream, 0);
        //Записываем, нужно ли тормозить в конце (1 - тормозить, 0 - не тормозить).
        writeParameterAsUByte(byteArrayOutputStream, 1);
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

    public float readUltrasonicSensor(byte port) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        int messageCounter = 1;
        writeUShort(byteArrayOutputStream, messageCounter);
        writeUByte(byteArrayOutputStream, 0x00);
        writeVariablesAllocation(byteArrayOutputStream, 4, 0);
        writeCommand(byteArrayOutputStream, 0x99);
        writeUByte(byteArrayOutputStream, 0x1D);
        writeParameterAsSmallByte(byteArrayOutputStream, 0);
        writeParameterAsSmallByte(byteArrayOutputStream, port);
        writeParameterAsSmallByte(byteArrayOutputStream, 0);
        writeParameterAsSmallByte(byteArrayOutputStream, 0);
        writeParameterAsSmallByte(byteArrayOutputStream, 1);
        writeUByte(byteArrayOutputStream, 0x60); // 0x60 | 0 == 0x60

        OutputStream outputStream = this.outputStream;
        writeUShort(outputStream, byteArrayOutputStream.size());
        byteArrayOutputStream.writeTo(outputStream);

        InputStream inputStream = this.inputStream;
        int replySize = readUShort(inputStream);
        byte[] reply = new byte[replySize];
        int offset = 0;
        while (offset < replySize) {
            int read = inputStream.read(reply, offset, replySize - offset);
            if (read == -1) throw new IOException("Disconnected");
            offset += read;
        }

        if (reply[2] != 2) return reply[2];
        ByteBuffer buf = ByteBuffer.wrap(reply);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        return buf.getFloat(3);
    }

    private int readMotorEncoder(char port) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        int messageCounter = 1;
        writeUShort(byteArrayOutputStream, messageCounter);
        writeUByte(byteArrayOutputStream, 0x00);
        writeVariablesAllocation(byteArrayOutputStream, 4, 0);
        writeCommand(byteArrayOutputStream, 0xB3);
        writeParameterAsSmallByte(byteArrayOutputStream, 0); // Layer (0)

        byte portNum = 0;
        switch (port) {
            case 'A': portNum = 0; break;
            case 'B': portNum = 1; break;
            case 'C': portNum = 2; break;
            case 'D': portNum = 3; break;
        }
        writeParameterAsSmallByte(byteArrayOutputStream, portNum); // Порт (битовая маска)
        writeUByte(byteArrayOutputStream, 0x60);
        writeUShort(outputStream, byteArrayOutputStream.size());
        byteArrayOutputStream.writeTo(outputStream);

        int replySize = readUShort(inputStream);
        byte[] reply = new byte[replySize];
        int offset = 0;
        while (offset < replySize) {
            int read = inputStream.read(reply, offset, replySize - offset);
            if (read == -1) throw new IOException("Disconnected");
            offset += read;
        }

        if (reply[2] != 0x02) {
            return reply[2];
        }

        return ByteBuffer.wrap(reply, 3, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt();
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

    private void sendMotorSpeedSync(char port, byte speed) {
        try {
            sendMotorSpeed(port, speed);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void sendMotorStopSync(char port) {
        try {
            sendMotorStop(port);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void sendMotorSpeedAngleSync(char port, byte power, int angle) {
        try {
            sendMotorSpeedAngle(port, power,angle);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private void readSensorsSync() {
        try {
            lastDistance = readUltrasonicSensor((byte) 0);
            lastEncoderA = readMotorEncoder('A');
            if (isFirstRead){
                lastEncoderA0 = lastEncoderA;
                isFirstRead = false;
            }
            lastEncoderA -= lastEncoderA0;
            //lastEncoderB = readMotorEncoder('B');
            //lastEncoderC = readMotorEncoder('C');
        } catch (IOException e) {
            throw new RuntimeException(e);
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
    //Записывает параметр с типом int.
    private void writeParameterAsInteger(OutputStream _stream, int value) throws IOException {
        writeUByte(_stream, 0x83);
        writeUByte(_stream, value & 0xFF);
        writeUByte(_stream, (value >> 8) & 0xFF);
        writeUByte(_stream, (value >> 16) & 0xFF);
        writeUByte(_stream, (value >> 24) & 0xFF);
    }
    //Читает unsigned byte.
    private int readUByte(InputStream _stream) throws IOException {
        byte bytes[] = new byte[1];
        _stream.read(bytes);
        return bytes[0] < 0 ? (int)bytes[0] + 256 : (int)bytes[0];
    }

    //Читает unsigned short.
    private int readUShort(InputStream _stream) throws IOException {
        return readUByte(_stream) | (readUByte(_stream) << 8);
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
        editor.putBoolean(KEY_INVERSE, inverseCheckbox.isChecked());
        editor.putBoolean(KEY_FILTER, filterCheckbox.isChecked());
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

        boolean inverse = settings.getBoolean(KEY_INVERSE, false);
        boolean filter = settings.getBoolean(KEY_FILTER, false);
        inverseCheckbox.setOnCheckedChangeListener(null);
        filterCheckbox.setOnCheckedChangeListener(null);
        inverseCheckbox.setChecked(inverse);
        filterCheckbox.setChecked(filter);
        inverseCheckbox.setOnCheckedChangeListener(inverseListener);
        filterCheckbox.setOnCheckedChangeListener(filterListener);
    }

    double PID(double e, double kp, double kd){
        double res = e * kp + (e - e_last) * kd;
        e_last = e;
        return res;
    }

    private List<WhiteStripe> findMaxWhiteStripesInColumns(boolean[][] black, int nColumns, int columnStep) {
        List<WhiteStripe> stripes = new ArrayList<>();
        int height = black.length;
        int width = black[0].length;
        int x = width - columnStep/2;
        for (int col = 0; col < nColumns; col++) {
            x-= columnStep;
            if (x < 0) break; // чтобы не выйти за границу

            int maxLen = 0;
            int maxStart = -1, maxEnd = -1;
            int curStart = -1, curLen = 0;
            for (int y = 0; y < height; y++) {
                if (!black[y][x]) { // белый пиксель
                    if (curStart == -1) curStart = y;
                    curLen++;
                } else {
                    if (curLen > maxLen) {
                        maxLen = curLen;
                        maxStart = curStart;
                        maxEnd = y - 1;
                    }
                    curStart = -1;
                    curLen = 0;
                }
            }
            // Если закончился на белом
            if (curLen > maxLen) {
                maxLen = curLen;
                maxStart = curStart;
                maxEnd = height - 1;
            }
            if (maxLen > 0 && maxStart > 0 && maxEnd < height - 1) {
                stripes.add(new WhiteStripe(x, maxStart, maxEnd));
            }
        }
        return stripes;
    }


    public static List<Point> findProjectedPoints(List<Point> points) {
        if (points.isEmpty()) {
            return new ArrayList<>();
        }
        if (points.size() == 1) {
            Point p = points.get(0);
            return Arrays.asList(new Point(p), new Point(p));
        }

        // Вычисление средних значений координат
        double sumX = 0, sumY = 0;
        for (Point p : points) {
            sumX += p.x;
            sumY += p.y;
        }
        double meanX = sumX / points.size();
        double meanY = sumY / points.size();

        // Вычисление элементов ковариационной матрицы
        double cov_xx = 0, cov_xy = 0, cov_yy = 0;
        for (Point p : points) {
            double dx = p.x - meanX;
            double dy = p.y - meanY;
            cov_xx += dx * dx;
            cov_xy += dx * dy;
            cov_yy += dy * dy;
        }

        // Вычисление собственных значений
        double trace = cov_xx + cov_yy;
        double det = cov_xx * cov_yy - cov_xy * cov_xy;
        double discriminant = Math.max(0, trace * trace - 4 * det);
        double lambda2 = (trace - Math.sqrt(discriminant)) / 2;

        // Вычисление нормали (a, b) к прямой
        double a = cov_xy;
        double b = lambda2 - cov_xx;
        double norm = Math.sqrt(a * a + b * b);

        // Обработка вырожденного случая (коллинеарные точки)
        if (norm < 1e-10) {
            if (cov_xx <= cov_yy) {
                a = 1;
                b = 0;
            } else {
                a = 0;
                b = 1;
            }
            norm = 1;
        }
        a /= norm;
        b /= norm;

        // Коэффициент c уравнения прямой
        double c = -(a * meanX + b * meanY);

        // Проекция первой точки
        Point first = points.get(0);
        double dFirst = a * first.x + b * first.y + c;
        Point projFirst = new Point(
                (int) Math.round(first.x - a * dFirst),
                (int) Math.round(first.y - b * dFirst)
        );

        // Проекция последней точки
        Point last = points.get(points.size() - 1);
        double dLast = a * last.x + b * last.y + c;
        Point projLast = new Point(
                (int) Math.round(last.x - a * dLast),
                (int) Math.round(last.y - b * dLast)
        );

        return Arrays.asList(projFirst, projLast);
    }


    @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}
    @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { return false; }
    @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}





    // ---------------------------------------------------------------------------
    // ------------------------------   MAIN LOOP   ------------------------------
    // ---------------------------------------------------------------------------
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
        if (filterCheckbox.isChecked()) {
            //overlayBitmap = removeSmallIslands(overlayBitmap);
            overlayBitmap = removeIslands3x3(overlayBitmap);
        }

        boolean inv = inverseCheckbox.isChecked();
        boolean[][] black = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (inv) {
                    black[y][x] = !ContourExtractor.isBlack(overlayBitmap.getPixel(x, y));
                }else{
                    black[y][x] = ContourExtractor.isBlack(overlayBitmap.getPixel(x, y));
                }
            }
        }


        List<Contour> contours = ContourExtractor.extractContours(black);
        List<Contour> contours4 = new ArrayList<>();


        int nColumns = 50; // сколько колонок
        int columnStep = 3; // шаг между колонками (пикселей)
        List<WhiteStripe> whiteStripes = findMaxWhiteStripesInColumns(black, nColumns, columnStep);

        // Right
        List<Point> points= new ArrayList<>();
        for (WhiteStripe stripe : whiteStripes) {
            points.add(new Point(stripe.columnX, stripe.yStart));
        }
        List<Point> pLineR = findProjectedPoints(points);
        // Left
        points.clear();
        for (WhiteStripe stripe : whiteStripes) {
            points.add(new Point(stripe.columnX, stripe.yEnd));
        }
        List<Point> pLineL = findProjectedPoints(points);
        if (!pLineR.isEmpty() && !pLineL.isEmpty()) {
            List<Point> polyPoints = Arrays.asList(pLineR.get(0), pLineR.get(1), pLineL.get(1), pLineL.get(0));
            contours4.add(new Contour(polyPoints));
        }


        int[] center = findLargestDarkSpotCenter(black);
        double ang = 0;
        int viewCenterX, viewCenterY;
        int cX, cY;

        if (center[0]!=-1 && center[1]!=-1) {
            cX = height - center[1];
            cY = center[0];
            viewCenterX = (int) (cX * scale1);
            viewCenterY = (int) (cY * scale2);
            ang = PID(cX * 2.0 / height - 1.0, kp, kd);
            if (ang>0) turnRight = true; else turnRight = false;
        }else{
            cY = 0;
            cX = 0;
            viewCenterY = 0;
            viewCenterX = 0;
            if (turnRight) ang =  Math.PI/2;
            else ang = -Math.PI/2;
        }

        int delta = 0;

        if (isConnected) {
            int finalAng1 = (int) ang;
            delta = (finalAng1 - lastEncoderA)/5;
            if (delta > 127) delta = 127;
            else if (delta < -127) delta = -127;
            final int delta1 = delta;
            Runnable commandGroup = () -> {

                if (isSTART) {

                    // Читаем данные сенсоров
                    if (isFirstRead) {
                        readSensorsSync();
                        sendMotorSpeedSync('B', (byte) speed);
                        sendMotorSpeedSync('C', (byte) speed);
                    }
                    else {
                        readSensorsSync();

                        // Выполняем команды для моторов
                        if (Math.abs(delta1) > 1) {
                            sendMotorSpeedSync('A', (byte) delta1);
                        } else {
                            sendMotorStopSync('A');
                        }
                    /*
                    if (finalAng1 > 0) {
                        sendMotorSpeedSync('B', (byte) (int) (speed * Math.cos(2 * finalAng1)));
                        sendMotorSpeedSync('C', (byte) speed);
                    } else {
                        sendMotorSpeedSync('B', (byte) speed);
                        sendMotorSpeedSync('C', (byte) (int) (speed * Math.cos(2 * finalAng1)));
                    }
                     */
                        //sendMotorSpeedSync('B', (byte) speed);
                        //sendMotorSpeedSync('C', (byte) speed);
                    }
                } else if (isFirst) {
                    sendMotorStopSync('A');
                    sendMotorStopSync('B');
                    sendMotorStopSync('C');
                    readSensorsSync();
                    if (lastEncoderA<0)
                        sendMotorSpeedAngleSync('A',(byte) 20, -lastEncoderA);
                    else
                        sendMotorSpeedAngleSync('A',(byte) -20, lastEncoderA);
                    //sendMotorStopSync('A');
                    isFirst = false;
                    isFirstRead = true;
                }



            };

            // Отправляем группу команд на выполнение
            executeCommand(commandGroup);
        }


        double finalAng = ang;
        int finalDelta = delta;
        runOnUiThread(() -> {
            overlayView.setCenter(viewCenterX, viewCenterY);
            overlayView.setCenterRaw(cX * 176 / 144, cY * 144 / 176);
            overlayView.setOverlayBitmap(overlayBitmap);
            overlayView.setContours(contours, overlayBitmap.getWidth(), overlayBitmap.getHeight());
            overlayView.setContours4(contours4);
            //overlayView.setWhiteStripes(whiteStripes);
            overlayView.invalidate();
            DecimalFormat df = new DecimalFormat("#.##");
            logOutput.setText(" e=" + df.format(cX * 2.0 / height - 1.0) +
                    "Ang=" + df.format(finalAng) + " Ang_=" + df.format(finalAng * 180 / Math.PI) + "\n" +
                    "Speed = " + (int) (speed * Math.cos(2 * finalAng))+"\n"+
                    "Dist = " + df.format(lastDistance) + "\n"+
                    "Encoder A = " + lastEncoderA+ "\n"+
                    "Delta = " + finalDelta
            );
        });
    }


}
