package info.ev3.cameraev3;
import android.Manifest;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import android.widget.SeekBar;
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
    private static final int BINARIZATION_THRESHOLD = 128; // Порог яркости (0-255)
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
    private boolean isConnected = false;
    private boolean isSTART = false;
    private boolean isFirst = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        backgroundThread = new HandlerThread("BackgroundThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());


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

        // Инициализация Bluetooth
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            connectButton.setEnabled(false);
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
        }

        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        List<String> allCameraIds = new ArrayList<>();
        List<String> cameraNames = new ArrayList<>();

        try {
            cameraIds = manager.getCameraIdList();
            selectedCameraId = cameraIds[0];

            try {
                for (String cameraId : cameraIds) {
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                    Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);

                    // Добавляем все камеры в список
                    allCameraIds.add(cameraId);

                    // Определяем тип камеры
                    String cameraType = "Unknown";
                    if (lensFacing != null) {
                        switch (lensFacing) {
                            case CameraCharacteristics.LENS_FACING_BACK:
                                cameraType = "Back";
                                break;
                            case CameraCharacteristics.LENS_FACING_FRONT:
                                cameraType = "Forward";
                                break;
                            case CameraCharacteristics.LENS_FACING_EXTERNAL:
                                cameraType = "External";
                                break;
                        }
                    }

                    // Получаем разрешение
                    StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    Size[] sizes = map != null ? map.getOutputSizes(SurfaceTexture.class) : new Size[0];
                    String resolution = sizes.length > 0 ? sizes[0].getWidth() + "x" + sizes[0].getHeight() : "N/A";

                    cameraNames.add(cameraType + " #" + cameraId + " (" + resolution + ")");
                }
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

            // Используем все камеры
            cameraIds = allCameraIds.toArray(new String[0]);


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


            // Spinner adapter
            cameraNames = getCameraNames(manager, cameraIds);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_spinner_item,
                    cameraNames // Список сформирован для всех камер
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

                    // Update preview size for new camera
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
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
            // По умолчанию первая камера
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
                isSTART =! isSTART;
                isFirst = true;
                if (isSTART) {
                    startButton.setText("STOP");
                } else {
                    startButton.setText("START");
                }
            }
        });

        // Инициализация
        transparencyValue = findViewById(R.id.transparencyValue);
        thresholdValue = findViewById(R.id.thresholdValue);

        // Инициализация ползунков
        transparencySeekBar = findViewById(R.id.transparencySeekBar);
        thresholdSeekBar = findViewById(R.id.thresholdSeekBar);

        // Начальные значения
        transparencySeekBar.setProgress(128);
        thresholdSeekBar.setProgress(binarizationThreshold);

        // Слушатели изменений
        transparencySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                transparencyValue.setText(String.valueOf(progress));
                overlayView.setBitmapAlpha(progress);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        thresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                thresholdValue.setText(String.valueOf(progress));
                binarizationThreshold = progress;
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Слушатели для EditText
        transparencyValue.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

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
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

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

    }

    private void openCamera() {
        if (!isCameraSupported(selectedCameraId)) {
            log("Камера " + selectedCameraId + " не поддерживает предпросмотр");
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
            log("Открытие камеры: " + selectedCameraId);
        } catch (CameraAccessException | SecurityException e) {
            log("Ошибка доступа: " + e.getMessage());
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

        // Рассчитываем соотношение сторон превью
        float ratio = (float) previewSize.getWidth() / previewSize.getHeight();
        int previewHeight = (int) (screenWidth / ratio);

        // Сохраняем коэффициенты масштабирования
        scaleX = (float) screenWidth / previewSize.getWidth();
        scaleY = (float) previewHeight / previewSize.getHeight();

        scale1 = (float) screenWidth / previewSize.getHeight();
        scale2 = (float) previewHeight /  previewSize.getWidth();

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

        // Создаем ImageReader
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

        // Update resolution text in overlay
        String resolution = previewSize.getWidth() + "x" + previewSize.getHeight();
        overlayView.setResolution(resolution);

        SurfaceTexture texture = textureView.getSurfaceTexture();
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        Surface surface = new Surface(texture);
        try {
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            previewRequestBuilder.addTarget(imageReader.getSurface()); // Добавляем ImageReader

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
        Image.Plane yPlane = image.getPlanes()[0];
        ByteBuffer yBuffer = yPlane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();

        byte[] yData = new byte[yBuffer.remaining()];
        yBuffer.get(yData);

        // Создаем бинарную маску
        overlayBitmap = createBinaryMask(yData, width, height, rowStride, pixelStride);

        int[] center = findLargestDarkSpotCenter(overlayBitmap);

        int cX = height-center[1];
        int cY = center[0];

        int viewCenterX = (int) (cX * scale1);
        int viewCenterY = (int) (cY * scale2);

        if (isConnected && isSTART) {
            sendMotorSpeed('C', (byte) 75);
        }
        else if (isConnected && !isSTART) {
            if (isFirst){
                sendMotorStop('C');
                isFirst = false;
            }
        }

        runOnUiThread(() -> {
            overlayView.setCenter(viewCenterX, viewCenterY);
            overlayView.setCenterRaw(cX*176/144, cY*144/176);
            overlayView.setOverlayBitmap(overlayBitmap);
            overlayView.invalidate();
            /*
            logOutput.setText(
                    "RAW coordinates: X=" + (height-center[1]) + " Y=" + center[0] + "\n" +
                    "View coordinates:X=" + viewCenterX + " Y=" + viewCenterY + "\n" +
                    "width = " + width + " height = " + height);

             */
        });
    }

    private Bitmap createBinaryMask(byte[] yData, int width, int height, int rowStride, int pixelStride) {

        /*
        int scaleFactor = 2; // Уменьшение в 2 раза
        width /= scaleFactor;
        height /= scaleFactor;
        */

        int[] pixels = new int[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * rowStride + x * pixelStride;
                if (index >= yData.length) continue;

                int luminance = yData[index] & 0xFF;
                int color = (luminance < binarizationThreshold)
                        ? 0xFF000000 // черный
                        : 0xFFFFFFFF; // белый
                pixels[y * width + x] = color;
                //pixels[x * height + y] = color;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private int[] findDarkestSpot(Bitmap bitmap) {
        if (bitmap == null) {
            return new int[]{-1, -1};
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        int sumX = 0;
        int sumY = 0;
        int count = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = pixels[y * width + x];
                int rgb = pixel & 0x00FFFFFF;
                if (rgb == 0x000000) {
                    sumX += x;
                    sumY += y;
                    count++;
                }
            }
        }
        if (count > 0) {
            int[] result = {sumX / count, sumY / count};
            return result;
        }
        return new int[]{-1, -1};
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
        try {
            for (String cameraId : cameraIds) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                int facing = (lensFacing != null) ? lensFacing : -1;
                String cameraType;

                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraType = "Задняя";
                } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    cameraType = "Передняя";
                } else if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                    cameraType = "Внешняя";
                } else {
                    cameraType = "Неизвестная";
                }

                names.add(cameraType + " #" + cameraId);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        return names;
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
            isFlashSupported = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
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

        }else{
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
        // Реализуйте подключение здесь
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "NO PERMISSION!", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Connecting to " + device.getName(), Toast.LENGTH_SHORT).show();
        selectedDevice = device;
        connectToDevice();
    }
/*
    // Обновите onRequestPermissionsResult
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showBluetoothDevicesDialog();
            } else {
                Toast.makeText(this, "Permission required", Toast.LENGTH_SHORT).show();
            }
        }
    }
*/
    // Обработка включения Bluetooth
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

                // Send initialization sequence
                //sendCommand(new byte[]{0x02, 0x00, 0x01, 0x0F});

                runOnUiThread(() -> {
                    setConnectionState(true);
                    showToast("Подключено к " + selectedDevice.getName());
                });

            } catch (IOException e) {
                Log.e(TAG, "Connection error: " + e.getMessage());
                runOnUiThread(() -> showToast("Ошибка подключения"));
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
        /*
        btnConnect.setText(connected ? "Отключиться" : "Подключиться");
        btnRefresh.setEnabled(!connected);
        devicesList.setEnabled(!connected);

        motorCSlider.setEnabled(connected);
        motorDSlider.setEnabled(connected);
        btnMotorCForward.setEnabled(connected);
        btnMotorCBackward.setEnabled(connected);
        btnMotorCStop.setEnabled(connected);
        btnMotorDForward.setEnabled(connected);
        btnMotorDBackward.setEnabled(connected);
        btnMotorDStop.setEnabled(connected);
         */
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
                showToast("Отключено");
            });
        } catch (IOException e) {
            Log.e(TAG, "Disconnect error: " + e.getMessage());
        }
    }

    private void sendMotorSpeed(char port, byte speed) throws IOException {

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        //Записываем номер сообщения.
        writeUShort(byteArrayOutputStream, 2);
        //Записываем тип команды (прямая команда, ответ не требуется).
        writeUByte(byteArrayOutputStream, 0x80);
        //Записываем количество зарезервированных байт для переменных (здесь ничего не резервируется).
        writeVariablesAllocation(byteArrayOutputStream, 0, 0);
        //Записываем код команды.
        writeCommand(byteArrayOutputStream, 0xA5); // OUTPUT_POWER (0xA5)  OUTPUT_SPEED (0xA4).
        //Записываем номер модуля EV3.
        writeParameterAsSmallByte(byteArrayOutputStream, 0);
        //Записываем номер порта
        writeParameterAsSmallByte(byteArrayOutputStream, getPortByte(port));
        //Записываем мощность (от -100 до 100).
        writeParameterAsByte(byteArrayOutputStream, speed);

        //Отправляем сообщение на EV3.
        //Сначала отправляем размер сообщения.
        writeUShort(outputStream, byteArrayOutputStream.size());
        //Затем отправляем само сообщение.
        byteArrayOutputStream.writeTo(outputStream);


        byteArrayOutputStream.reset();


        //START
        //Записываем номер сообщения.
        writeUShort(byteArrayOutputStream, 2);
        //Записываем тип команды (прямая команда, ответ не требуется).
        writeUByte(byteArrayOutputStream, 0x80);
        //Записываем количество зарезервированных байт для переменных (здесь ничего не резервируется).
        writeVariablesAllocation(byteArrayOutputStream, 0, 0);
        //Записываем код команды.
        writeCommand(byteArrayOutputStream, 0xA6);
        //Записываем номер модуля EV3.
        writeParameterAsSmallByte(byteArrayOutputStream, 0);
        //Записываем номер порта
        writeParameterAsSmallByte(byteArrayOutputStream, getPortByte(port));
        //Отправляем сообщение на EV3.
        //Сначала отправляем размер сообщения.
        writeUShort(outputStream, byteArrayOutputStream.size());
        //Затем отправляем само сообщение.
        byteArrayOutputStream.writeTo(outputStream);

    }

    private void sendMotorSpeed_tik(char port, byte speed) throws IOException {

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        //Записываем номер сообщения.
        writeUShort(byteArrayOutputStream, 2);
        //Записываем тип команды (прямая команда, ответ не требуется).
        writeUByte(byteArrayOutputStream, 0x80);
        //Записываем количество зарезервированных байт для переменных (здесь ничего не резервируется).
        writeVariablesAllocation(byteArrayOutputStream, 0, 0);
        //Записываем код команды.
        writeCommand(byteArrayOutputStream, 0xAE);
        //Записываем номер модуля EV3.
        writeParameterAsSmallByte(byteArrayOutputStream, 0);
        //Записываем номер порта
        writeParameterAsSmallByte(byteArrayOutputStream, getPortByte(port));
        //Записываем мощность (от -100 до 100).
        writeParameterAsByte(byteArrayOutputStream, speed);
        //Записываем, сколько оборотов двигатель будет разгоняться (0 - разгоняться будет моментально).
        writeParameterAsInteger(byteArrayOutputStream, 0);
        //Записываем, сколько оборотов двигатель будет крутиться на полной скорости (2,5 оборота, т.е. 900 градусов).
        writeParameterAsInteger(byteArrayOutputStream, 900);
        //Записываем, сколько оборотов двигатель будет замедляться (0,5 оборота, т.е. 180 градусов).
        writeParameterAsInteger(byteArrayOutputStream, 180);
        //Записываем, нужно ли тормозить в конце (1 - тормозить, 0 - не тормозить).
        writeParameterAsUByte(byteArrayOutputStream, 1);

        //Отправляем сообщение на EV3.
        //Сначала отправляем размер сообщения.
        writeUShort(outputStream, byteArrayOutputStream.size());
        //Затем отправляем само сообщение.
        byteArrayOutputStream.writeTo(outputStream);
    }


    private void sendMotorStop(char port) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        //STOP
        //Записываем номер сообщения.
        writeUShort(byteArrayOutputStream, 2);
        //Записываем тип команды (прямая команда, ответ не требуется).
        writeUByte(byteArrayOutputStream, 0x80);
        //Записываем количество зарезервированных байт для переменных (здесь ничего не резервируется).
        writeVariablesAllocation(byteArrayOutputStream, 0, 0);
        //Записываем код команды.
        writeCommand(byteArrayOutputStream, 0xA3); // OUTPUT_STOP
        //Записываем номер модуля EV3.
        writeParameterAsSmallByte(byteArrayOutputStream, 0);
        //Записываем номер порта
        writeParameterAsSmallByte(byteArrayOutputStream, getPortByte(port));
        //Записываем, нужно ли тормозить в конце (1 - тормозить, 0 - не тормозить).
        writeParameterAsUByte(byteArrayOutputStream, 1);
        //Отправляем сообщение на EV3.
        //Сначала отправляем размер сообщения.
        writeUShort(outputStream, byteArrayOutputStream.size());
        //Затем отправляем само сообщение.
        byteArrayOutputStream.writeTo(outputStream);

        // ПИСК!!!
        //outputStream.write(new byte[]{14, 0, 0, 0, -128, 0, 0, -108, 1, 2, -126, -24, 3, -126, -24, 3});
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

    //Записывает unsigned byte.
    private void writeUByte(OutputStream _stream, int _ubyte) throws IOException {
        _stream.write(_ubyte > Byte.MAX_VALUE ? _ubyte - 256 : _ubyte);
    }

    //Записывает byte.
    private void writeByte(OutputStream _stream, byte _byte) throws IOException {
        _stream.write(_byte);
    }

    //Записывает unsigned short.
    private void writeUShort(OutputStream _stream, int _ushort) throws IOException {
        writeUByte(_stream, _ushort & 0xFF);
        writeUByte(_stream, (_ushort >> 8) & 0xFF);
    }


    //Записывает команду.
    private void writeCommand(OutputStream _stream, int opCode) throws IOException {
        writeUByte(_stream, opCode);
    }

    //Записывает количество зарезервированных байт для глобальных и локальных переменных.
    private void writeVariablesAllocation(OutputStream _stream, int globalSize, int localSize) throws IOException {
        writeUByte(_stream, globalSize & 0xFF);
        writeUByte(_stream, ((globalSize >> 8) & 0x3) | ((localSize << 2) & 0xFC));
    }

    //Записывает параметр с типом unsigned byte со значением в интервале 0-31 с помощью короткого формата.
    private void writeParameterAsSmallByte(OutputStream _stream, int value) throws IOException {
        if (value < 0 && value > 31)
            throw new IllegalArgumentException("Значение должно быть в интервале от 0 до 31.");
        writeUByte(_stream, value);
    }

    //Записывает параметр с типом unsigned byte.
    private void writeParameterAsUByte(OutputStream _stream, int value) throws IOException {
        if (value < 0 && value > 255)
            throw new IllegalArgumentException("Значение должно быть в интервале от 0 до 255.");
        writeUByte(_stream, 0x81);
        writeUByte(_stream, value);
    }

    //Записывает параметр с типом byte.
    private void writeParameterAsByte(OutputStream _stream, int value) throws IOException {
        if (value < Byte.MIN_VALUE && value > Byte.MAX_VALUE)
            throw new IllegalArgumentException("Значение должно быть в интервале от "
                    + Byte.MIN_VALUE + " до " + Byte.MAX_VALUE + ".");
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

    @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}
    @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { return false; }
    @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
}