package info.ev3.cameraev3;
import android.Manifest;
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
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
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

import android.widget.SeekBar;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {
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
                openCamera();
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

        processImage(image);
        image.close();
    };

    private void processImage(Image image) {
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

    @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}
    @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { return false; }
    @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
}