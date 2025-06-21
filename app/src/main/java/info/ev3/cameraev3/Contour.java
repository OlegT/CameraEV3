package info.ev3.cameraev3;

import android.graphics.Point;

import java.util.ArrayList;
import java.util.List;

public class Contour {
    private List<Point> points;
    private boolean isClosed;
    private double area;

    public Contour(List<Point> points) {
        this.points = new ArrayList<>(points);
        this.isClosed = isContourClosed(points);
        this.area = calculateArea();
    }

    public List<Point> getPoints() {
        return new ArrayList<>(points);
    }

    public int size() {
        return points.size();
    }

    public boolean isClosed() {
        return isClosed;
    }

    public double getArea() {
        return area;
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

    public Point getCentroid() {
        if (points.isEmpty()) return null;
        double cx = 0, cy = 0;
        for (Point p : points) {
            cx += p.x;
            cy += p.y;
        }
        return new Point((int)(cx / points.size()), (int)(cy / points.size()));
    }

    public void simplify(double epsilon) {
        if (points.size() > 2) {
            this.points = ContourExtractor.approxPolyDP(points, epsilon);
            //this.isClosed = isContourClosed(points);
            //this.area = calculateArea();
        }
    }
}