package info.ev3.cameraev3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Point;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;


public class OverlayView extends View {
    private Paint paint;
    private Paint textPaint;
    private String resolutionText = "";
    private int centerX = -1;
    private int centerY = -1;
    private int centerXraw = -1;
    private int centerYraw = -1;
    private Paint centerPaint;
    private Bitmap overlayBitmap;
    private Paint bitmapPaint;
    private float fps = 0;
    private Paint fpsPaint;

    private List<List<Point>> contours = new ArrayList<>();
    private int bitmapWidth, bitmapHeight;
    private Paint contourPaint;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Настройка Paint для отрисовки битмапа
        bitmapPaint = new Paint();
        bitmapPaint.setAlpha(128); // 50% прозрачность

        // Paint for lines
        paint = new Paint();
        paint.setColor(0xFF0000FF);
        paint.setStrokeWidth(5);

        // Paint for text
        textPaint = new Paint();
        textPaint.setColor(0xFF00FF00); // Green color
        textPaint.setTextSize(50); // Text size in pixels
        textPaint.setAntiAlias(true);

        centerPaint = new Paint();
        centerPaint.setColor(0xFFFF0000); // Красный цвет
        centerPaint.setStrokeWidth(10);
        centerPaint.setStyle(Paint.Style.STROKE);

        fpsPaint = new Paint();
        fpsPaint.setColor(0xFFFF00FF); // Фиолетовый цвет
        fpsPaint.setTextSize(50);
        fpsPaint.setTextAlign(Paint.Align.RIGHT);
        fpsPaint.setAntiAlias(true);

        // Paint для контуров
        contourPaint = new Paint();
        contourPaint.setColor(0xFF00FF00); // Зеленый цвет
        contourPaint.setStyle(Paint.Style.STROKE);
        contourPaint.setStrokeWidth(3);
        contourPaint.setAntiAlias(true);
    }

    public void setContours(List<List<Point>> contours, int bitmapWidth, int bitmapHeight) {
        this.contours = contours;
        this.bitmapWidth = bitmapWidth;
        this.bitmapHeight = bitmapHeight;
        invalidate();
    }

    public void setBitmapAlpha(int alpha) {
        bitmapPaint.setAlpha(alpha);
        invalidate();
    }

    public void setFps(float fps) {
        this.fps = fps;
        invalidate();
    }
    public void setOverlayBitmap(Bitmap bitmap) {
        this.overlayBitmap = bitmap;
    }

    public void setCenter(int x, int y) {
        centerX = x;
        centerY = y;
    }

    public void setCenterRaw(int x, int y) {
        centerXraw = x;
        centerYraw = y;
    }

    public void setResolution(String resolution) {
        resolutionText = resolution;
        invalidate(); // Redraw view when resolution changes
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        // Отрисовка бинарной маски
        if (overlayBitmap != null) {
            Matrix matrix = new Matrix();
            matrix.postRotate(90);
            Bitmap rotatedBitmap = Bitmap.createBitmap(overlayBitmap, 0, 0, overlayBitmap.getWidth(), overlayBitmap.getHeight(), matrix, true);
            Rect src = new Rect(0, 0, rotatedBitmap.getWidth(), rotatedBitmap.getHeight());
            Rect dst = new Rect(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(rotatedBitmap, src, dst, bitmapPaint);
        }

        // Draw lines
        canvas.drawLine(w/2f, 0, w/2f, h, paint);
        //canvas.drawLine(2*w/3f, 0, 2*w/3f, h, paint);

        // Draw resolution text
        if (!resolutionText.isEmpty()) {
            canvas.drawText(resolutionText, 20, 120, textPaint); // Position: x=20, y=60
        }

        if (centerXraw != -1 && centerYraw != -1) {
            canvas.drawText("("+ centerXraw + ", " + centerYraw + ")", 20, 170, textPaint);
        }

        // Отрисовка центра
        if (centerX != -1 && centerY != -1) {
            // Корректируем координаты для повернутого изображения

            canvas.drawCircle(centerX, centerY, 20, centerPaint);
            canvas.drawLine(centerX - 30, centerY, centerX + 30, centerY, centerPaint);
            canvas.drawLine(centerX, centerY - 30, centerX, centerY + 30, centerPaint);
        }

        // Отрисовка контуров
        if (bitmapWidth > 0 && bitmapHeight > 0) {
            float scaleX = getWidth() / (float) bitmapHeight;
            float scaleY = getHeight() / (float) bitmapWidth;

            for (List<Point> contour : contours) {
                if (contour.size() < 2) continue;

                android.graphics.Path path = new android.graphics.Path();
                Point first = contour.get(0);

                /*
                cX = height - center[1];
                cY = center[0];
                viewCenterX = (int) (cX * scale1);
                viewCenterY = (int) (cY * scale2);
                 */

                float x = (bitmapHeight - first.y) * scaleX;
                float y = first.x * scaleY;

                //float x = first.y * scaleX;
                //float y = (bitmapWidth - first.x - 1) * scaleY;
                path.moveTo(x, y);

                for (int i = 1; i < contour.size(); i++) {
                    Point p = contour.get(i);
                    //x = p.y * scaleX;
                    //y = (bitmapWidth - p.x - 1) * scaleY;
                    x = (bitmapHeight - p.y) * scaleX;
                    y = p.x * scaleY;

                    path.lineTo(x, y);
                }
                canvas.drawPath(path, contourPaint);
            }
        }


        if (fps > 0) {
            String fpsText = String.format("%.1f FPS", fps);
            canvas.drawText(fpsText, getWidth() - 20, 120, fpsPaint);
        }
    }
}