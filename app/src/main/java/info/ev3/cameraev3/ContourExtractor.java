package info.ev3.cameraev3;

import android.graphics.Bitmap;
import android.graphics.Point;

import java.util.*;

public class ContourExtractor {

    private static final int[][] DIRS8 = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1},
            {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    // Направления через один (16)
    private static final int[][] DIRS16 = {
            {2, 0}, {2, 1}, {2, -1}, {1, 2}, {1, -2},
            {0, 2}, {0, -2}, {-1, 2}, {-1, -2}, {-2, 0},
            {-2, 1}, {-2, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private static boolean isBlack(int pixel) {
        return (pixel & 0x00FFFFFF) == 0x000000;
    }

    private static boolean isBoundary(int x, int y, int width, int height, boolean[][] black) {
        for (int[] d : DIRS8) {
            int nx = x + d[0], ny = y + d[1];
            if (nx < 0 || ny < 0 || nx >= width || ny >= height)
                return true;
            if (!black[ny][nx])
                return true;
        }
        return false;
    }

    private static boolean pointsAreClose(Point p1, Point p2) {
        return Math.abs(p1.x - p2.x) <= 5 && Math.abs(p1.y - p2.y) <= 5;
    }

    public static List<List<Point>> extractContours(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        boolean[][] used = new boolean[height][width];
        boolean[][] black = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                black[y][x] = isBlack(bitmap.getPixel(x, y));
            }
        }
        double minArea = width * height * 0.001; // 1% от общей площади

        // Список открытых (пока не замкнутых) ломаных
        List<List<Point>> openContours = new ArrayList<>();
        // Итоговые замкнутые контуры
        List<List<Point>> contours = new ArrayList<>();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (used[y][x]) continue;
                if (black[y][x] && isBoundary(x, y, width, height, black)) {
                    List<Point> contour = traceBoundary(x, y, width, height, black, used);
                    if (contour.size() < 2) continue;

                    // Попробуем объединить с уже существующими ломанными (openContours)
                    boolean merged = false;
                    for (List<Point> open : openContours) {
                        Point openStart = open.get(0);
                        Point openEnd = open.get(open.size() - 1);
                        Point contourStart = contour.get(0);
                        Point contourEnd = contour.get(contour.size() - 1);

                        if (pointsAreClose(openEnd, contourStart)) {
                            // open + contour
                            open.addAll(contour.subList(1, contour.size()));
                            merged = true;
                            break;
                        } else if (pointsAreClose(openEnd, contourEnd)) {
                            // open + reversed contour
                            Collections.reverse(contour);
                            open.addAll(contour.subList(1, contour.size()));
                            merged = true;
                            break;
                        } else if (pointsAreClose(openStart, contourEnd)) {
                            // contour + open
                            List<Point> temp = new ArrayList<>(contour);
                            temp.addAll(open.subList(1, open.size()));
                            open.clear();
                            open.addAll(temp);
                            merged = true;
                            break;
                        } else if (pointsAreClose(openStart, contourStart)) {
                            // reversed contour + open
                            Collections.reverse(contour);
                            List<Point> temp = new ArrayList<>(contour);
                            temp.addAll(open.subList(1, open.size()));
                            open.clear();
                            open.addAll(temp);
                            merged = true;
                            break;
                        }
                    }
                    if (!merged) {
                        openContours.add(new ArrayList<>(contour));
                    }
                }
            }
        }

        // После основного обхода пробуем замкнуть ломаные и добавить в итог
        for (List<Point> open : openContours) {
            if (open.size() < 3) continue; // мусор
            // Замкнуть если начало и конец достаточно близки
            if (pointsAreClose(open.get(0), open.get(open.size() - 1))) {
                open.add(new Point(open.get(0).x, open.get(0).y));

            }
            if (polygonArea(open) > minArea) {
                // Добавить выпуклую оболочку:
                //List<Point> convex = convexHull(open);
                //if (!convex.isEmpty() && !convex.get(0).equals(convex.get(convex.size() - 1))) {
                //    convex.add(new Point(convex.get(0).x, convex.get(0).y));
                //}
                //contours.add(convex);
                contours.add(open);
            }

        }
        return contours;
    }

    // Moore-Neighbor tracing + обход через 2 пикселя
    private static List<Point> traceBoundary(int startX, int startY, int width, int height, boolean[][] black, boolean[][] used) {
        List<Point> contour = new ArrayList<>();
        int x = startX, y = startY;
        int dir = 0;
        Point start = new Point(x, y);
        boolean first = true;

        do {
            contour.add(new Point(x, y));
            used[y][x] = true;
            boolean found = false;

            // 1. Сначала обычные 8 соседей
            for (int i = 0; i < 8; i++) {
                int ndir = (dir + i) % 8;
                int nx = x + DIRS8[ndir][0], ny = y + DIRS8[ndir][1];
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                if (used[ny][nx]) continue;
                if (black[ny][nx] && isBoundary(nx, ny, width, height, black)) {
                    for (int k = 0; k < 8; k++)
                    {
                        int nx1 = x + DIRS8[k][0], ny1 = y + DIRS8[k][1];
                        if (nx1 < 0 || ny1 < 0 || nx1 >= width || ny1 >= height) continue;
                        used[ny1][nx1] = true;
                    }
                    x = nx; y = ny; dir = (ndir + 6) % 8;
                    found = true;
                    break;
                }
            }
            // 2. Если не нашли среди 8, ищем среди "через один"
            if (!found) {
                for (int i = 0; i < DIRS16.length; i++) {
                    int nx = x + DIRS16[i][0], ny = y + DIRS16[i][1];
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                    if (used[ny][nx]) continue;
                    if (black[ny][nx] && isBoundary(nx, ny, width, height, black)) {
                        for (int k = 0; k < 8; k++)
                        {
                            int nx1 = x + DIRS8[k][0], ny1 = y + DIRS8[k][1];
                            if (nx1 < 0 || ny1 < 0 || nx1 >= width || ny1 >= height) continue;
                            used[ny1][nx1] = true;
                        }
                        x = nx; y = ny; dir = 0;
                        found = true;
                        break;
                    }
                }
            }
            if (!found) break;
            if (!first && x == start.x && y == start.y) break;
            first = false;
        } while (true);

        return contour;
    }

    // Выпуклая оболочка (Andrew's monotone chain)
    private static List<Point> convexHull(List<Point> points) {
        List<Point> pts = new ArrayList<>(new HashSet<>(points));
        if (pts.size() < 3) return pts;
        pts.sort((a, b) -> a.x != b.x ? Integer.compare(a.x, b.x) : Integer.compare(a.y, b.y));
        List<Point> lower = new ArrayList<>();
        for (Point p : pts) {
            while (lower.size() >= 2 && cross(lower.get(lower.size()-2), lower.get(lower.size()-1), p) <= 0)
                lower.remove(lower.size()-1);
            lower.add(p);
        }
        List<Point> upper = new ArrayList<>();
        for (int i = pts.size() - 1; i >= 0; i--) {
            Point p = pts.get(i);
            while (upper.size() >= 2 && cross(upper.get(upper.size()-2), upper.get(upper.size()-1), p) <= 0)
                upper.remove(upper.size()-1);
            upper.add(p);
        }
        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        lower.addAll(upper);
        return lower;
    }

    private static int cross(Point o, Point a, Point b) {
        return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
    }

    // Формула Гаусса для площади многоугольника
    private static double polygonArea(List<Point> contour) {
        if (contour.size() < 3) return 0;
        double area = 0.0;
        int n = contour.size();
        for (int i = 0; i < n - 1; i++) {
            Point p1 = contour.get(i);
            Point p2 = contour.get(i + 1);
            area += (p1.x * p2.y) - (p2.x * p1.y);
        }
        return Math.abs(area) / 2.0;
    }
}
