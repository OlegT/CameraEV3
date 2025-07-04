package info.ev3.cameraev3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Point;
import android.util.AttributeSet;
import android.view.View;

import java.text.DecimalFormat;
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
    private List<Contour> contours = new ArrayList<>();
    private List<Contour> contours4 = new ArrayList<>();
    private int bitmapWidth, bitmapHeight;
    private float scaleX, scaleY;
    private Paint contourPaint;
    private Paint contourPaint4;
    private Paint whiteLinePaint;
    private Paint fillPaint;
    private Paint idPaint;
    private List<WhiteStripe> whiteStripes = new ArrayList<>();

    private Point viewPoint(Point p) {
        int x = (int)((bitmapHeight - p.y) * scaleX);
        int y = (int)(p.x * scaleY);
        return new Point(x, y);
    }

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Настройка Paint для отрисовки битмапа
        bitmapPaint = new Paint();
        bitmapPaint.setAlpha(128); // 50% прозрачность

        // Paint for lines
        paint = new Paint();
        paint.setColor(0xFF0000FF);
        paint.setStrokeWidth(5);
        paint.setPathEffect(new DashPathEffect(new float[]{15, 10}, 0));

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
        contourPaint.setColor(0xFFFFFF00); //  Желтый цвет
        contourPaint.setStyle(Paint.Style.STROKE);
        contourPaint.setStrokeWidth(3);
        contourPaint.setAntiAlias(true);

        // Paint для четырехугольноков
        contourPaint4 = new Paint();
        contourPaint4.setColor(0xFF651FFF); // Синий цвет
        contourPaint4.setStyle(Paint.Style.STROKE);
        contourPaint4.setStrokeWidth(10);
        contourPaint4.setAntiAlias(true);

        // Paint для заливки четырехугольников
        fillPaint = new Paint();
        fillPaint.setColor(0x80651FFF); // Полупрозрачный фиолетовый (0x40 = 25% прозрачность)
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setAntiAlias(true);

        // Paint для белых сегментов
        whiteLinePaint = new Paint();
        whiteLinePaint.setColor(0xFF00AA00); // Зеленый цвет
        whiteLinePaint.setStrokeWidth(10);
        whiteLinePaint.setAntiAlias(true);

        // Paint для ID контуров
        idPaint = new Paint();
        idPaint.setColor(0xFF8700FF); // Синий цвет
        idPaint.setTextSize(40);
        idPaint.setAntiAlias(true);
    }

    public void setWhiteStripes(List<WhiteStripe> stripes) {
        this.whiteStripes = stripes;
        invalidate();
    }

    public void setContours(List<Contour> contours, int bitmapWidth, int bitmapHeight) {
        this.contours = contours;
        this.bitmapWidth = bitmapWidth;
        this.bitmapHeight = bitmapHeight;
        invalidate();
    }

    public void setContours4(List<Contour> contours) {
        this.contours4 = contours;
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
        DecimalFormat df = new DecimalFormat("#.####");
        // Отрисовка бинарной маски
        if (overlayBitmap != null) {
            Matrix matrix = new Matrix();
            matrix.postRotate(90);
            Bitmap rotatedBitmap = Bitmap.createBitmap(overlayBitmap, 0, 0, overlayBitmap.getWidth(), overlayBitmap.getHeight(), matrix, true);
            Rect src = new Rect(0, 0, rotatedBitmap.getWidth(), rotatedBitmap.getHeight());
            Rect dst = new Rect(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(rotatedBitmap, src, dst, bitmapPaint);
        }

        scaleX = getWidth() / (float) bitmapHeight;
        scaleY = getHeight() / (float) bitmapWidth;

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

            for (Contour contour : contours) {
                List<Point> points = contour.getPoints();
                if (points.size() < 2) continue;

                android.graphics.Path path = new android.graphics.Path();
                Point first = points.get(0);

                Point pv = viewPoint(first);
                path.moveTo(pv.x, pv.y);

                for (int i = 1; i < points.size(); i++) {
                    pv = viewPoint(points.get(i));
                    path.lineTo(pv.x, pv.y);
                }
                canvas.drawPath(path, contourPaint);

                Point centroid = contour.getCentroid();
                if (centroid != null) {
                    pv = viewPoint(centroid);
                    //canvas.drawText("ID: " + contour.getId() + "("+df.format(contour.getDiffArea())+")", x, y, idPaint);
                    canvas.drawText(df.format(contour.getDiffArea()), pv.x, pv.y, idPaint);
                }
            }
        }

        // Отрисовка четырехугольников
        if (bitmapWidth > 0 && bitmapHeight > 0) {

            for (Contour contour : contours4) {
                List<Point> points = contour.getPoints();
                if (points.size() < 2) continue;

                android.graphics.Path path = new android.graphics.Path();
                Point pv = viewPoint(points.get(0));
                path.moveTo(pv.x, pv.y);

                for (int i = 1; i < points.size(); i++) {
                    pv = viewPoint(points.get(i));
                    path.lineTo(pv.x, pv.y);
                }
                path.close();
                canvas.drawPath(path, fillPaint);
                canvas.drawPath(path, contourPaint4);
            }
        }

        // Draw white stripes
        if (whiteStripes != null) {

            for (WhiteStripe ws : whiteStripes) {
                Point pv1 = viewPoint(new Point(ws.columnX, ws.yStart));
                Point pv2 = viewPoint(new Point(ws.columnX, ws.yEnd));
                canvas.drawLine(pv1.x, pv1.y, pv2.x, pv2.y, whiteLinePaint);
            }
        }

        if (fps > 0) {
            String fpsText = String.format("%.1f FPS", fps);
            canvas.drawText(fpsText, getWidth() - 20, 120, fpsPaint);
        }
    }
}