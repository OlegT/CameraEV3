package info.ev3.cameraev3;

import android.graphics.Point;

import java.util.ArrayList;
import java.util.List;

public class Contour {
    private static int nextId = 1;

    private int id;
    private final List<Point> points;
    private final boolean isClosed;
    private final double area;

    // Граничные точки
    private final Point leftmost;
    private final Point rightmost;
    private final Point topmost;
    private final Point bottommost;
    private final Point centroid;

    public Contour(List<Point> points) {
        this.id = -1;
        this.points = new ArrayList<>(points);

        // Инициализация граничных точек
        Point l = null, r = null, t = null, b = null;
        double cx = 0, cy = 0;

        if (!points.isEmpty()) {
            l = r = t = b = points.get(0);
            for (Point p : points) {
                // Обновляем граничные точки
                if (p.x < l.x) l = p;
                if (p.x > r.x) r = p;
                if (p.y < t.y) t = p;
                if (p.y > b.y) b = p;

                // Считаем центр масс
                cx += p.x;
                cy += p.y;
            }

            // Вычисляем центр масс
            cx /= points.size();
            cy /= points.size();
        }

        this.leftmost = l;
        this.rightmost = r;
        this.topmost = t;
        this.bottommost = b;
        this.centroid = (points.isEmpty()) ? null : new Point((int) cx, (int) cy);

        this.isClosed = isContourClosed(points);
        this.area = calculateArea();
    }

    private boolean isContourClosed(List<Point> points) {
        if (points.size() < 3) return false;
        Point first = points.get(0);
        Point last = points.get(points.size() - 1);
        return first.equals(last);
    }

    private double calculateArea() {
        if (points.size() < 3) return 0;
        double area = 0.0;
        int n = points.size();
        for (int i = 0; i < n; i++) {
            Point p1 = points.get(i);
            Point p2 = points.get((i + 1) % n);
            area += (p1.x * p2.y - p2.x * p1.y);
        }
        return Math.abs(area) / 2.0;
    }

    // Геттеры
    public int getId() { return id; }
    public void setId(int ID) { id = ID; }
    public List<Point> getPoints() { return new ArrayList<>(points); }
    public int size() {return points.size(); }
    public boolean isClosed() { return isClosed; }
    public double getArea() { return area; }
    public Point getLeftmostPoint() { return leftmost; }
    public Point getRightmostPoint() { return rightmost; }
    public Point getTopmostPoint() { return topmost; }
    public Point getBottommostPoint() { return bottommost; }
    public Point getCentroid() { return centroid; }
}