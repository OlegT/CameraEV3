package info.ev3.cameraev3;

import android.graphics.Point;
import java.util.List;

public class TrackedContour {
    private static int nextId = 1;

    private final int id;
    private Contour contour;
    private int lifetime;
    private int missedFrames;

    // Граничные точки
    private Point leftPoint;
    private Point rightPoint;
    private Point topPoint;
    private Point bottomPoint;

    public TrackedContour(Contour contour) {
        this.id = nextId++;
        this.contour = contour;
        this.contour.setId(id);
        this.lifetime = 1;
        this.missedFrames = 0;
        calculateBoundaryPoints();
    }

    private void calculateBoundaryPoints() {
        List<Point> points = contour.getPoints();
        leftPoint = points.get(0);
        rightPoint = points.get(0);
        topPoint = points.get(0);
        bottomPoint = points.get(0);

        for (Point p : points) {
            if (p.x < leftPoint.x) leftPoint = p;
            if (p.x > rightPoint.x) rightPoint = p;
            if (p.y < topPoint.y) topPoint = p;
            if (p.y > bottomPoint.y) bottomPoint = p;
        }
    }

    public boolean isSameContour1(Contour other) {
        if (other == null) return false;

        // 1. Площадь
        double area1 = contour.getArea();
        double area2 = other.getArea();
        if (Math.abs(area1 - area2) > area1 * 0.2) return false;

        // 2. Центр масс
        Point center1 = contour.getCentroid();
        Point center2 = other.getCentroid();
        if (distance(center1, center2) > 15.0) return false;

        return true;
    }

    public boolean isSameContour2(Contour other) {
        if (other == null) return false;

        // Проверка площади
        double areaDiff = Math.abs(contour.getArea() - other.getArea());
        double areaThreshold = contour.getArea() * 0.2; // 20% разница

        if (areaDiff > areaThreshold) return false;

        // Проверка граничных точек
        Point otherLeft = other.getLeftmostPoint();
        Point otherRight = other.getRightmostPoint();
        Point otherTop = other.getTopmostPoint();
        Point otherBottom = other.getBottommostPoint();

        double distanceThreshold = 10.0; // Пикселей

        return distance(leftPoint, otherLeft) < distanceThreshold &&
                distance(rightPoint, otherRight) < distanceThreshold &&
                distance(topPoint, otherTop) < distanceThreshold &&
                distance(bottomPoint, otherBottom) < distanceThreshold;
    }

    private double distance(Point p1, Point p2) {
        return Math.hypot(p1.x - p2.x, p1.y - p2.y);
    }

    public void update(Contour newContour) {
        this.contour = newContour;
        newContour.setId(id);
        this.lifetime++;
        this.missedFrames = 0;
        calculateBoundaryPoints();
    }

    public void markMissed() {
        missedFrames++;
    }

    // Геттеры
    public int getId() { return id; }
    public Contour getContour() { return contour; }
    public int getLifetime() { return lifetime; }
    public int getMissedFrames() { return missedFrames; }
}
