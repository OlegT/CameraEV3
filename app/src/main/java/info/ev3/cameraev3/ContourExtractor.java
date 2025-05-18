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

    static boolean isBlack(int pixel) {
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
    public static List<List<Point>> extractContours(boolean[][] black) {
        int height = black.length;
        int width = black[0].length;
        boolean[][] used = new boolean[height][width];
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



    public static List<List<Point>> findConvexQuadrilaterals(List<List<Point>> contours, double epsilon) {
        List<List<Point>> quads = new ArrayList<>();
        for (List<Point> contour : contours) {
            if (contour == null || contour.size() < 3) continue;
            List<Point> simplified = approxPolyDP(contour, epsilon);
            simplified = removeClosePoints(simplified, 4);

            // Гарантируем замкнутость
            if (simplified.size() > 1 && !simplified.get(0).equals(simplified.get(simplified.size() - 1))) {
                simplified.add(new Point(simplified.get(0).x, simplified.get(0).y));
            }

            // Проверяем, что это четырёхугольник (замкнутый, 5 точек)
            if (simplified.size() == 5) {
                // Проверяем выпуклость
                if (isConvex(simplified)) {
                    quads.add(simplified);
                }
            }
        }
        return quads;
    }

    public static List<Point> removeClosePoints(List<Point> poly, double minDist) {
        if (poly == null || poly.size() < 2) return poly;
        List<Point> result = new ArrayList<>();
        result.add(poly.get(0));
        for (int i = 1; i < poly.size(); i++) {
            Point prev = result.get(result.size() - 1);
            Point curr = poly.get(i);
            if (distance(prev, curr) >= minDist) {
                result.add(curr);
            }
        }
        // Проверяем первую и последнюю, чтобы не было "слипания" после замыкания
        if (result.size() > 2 && distance(result.get(0), result.get(result.size() - 1)) < minDist) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    private static double distance(Point a, Point b) {
        int dx = a.x - b.x, dy = a.y - b.y;
        return Math.hypot(dx, dy);
    }

    // Проверка выпуклости для замкнутого четырехугольника
    public static boolean isConvex(List<Point> poly) {
        int n = poly.size() - 1; // замкнутый: последняя = первая
        if (n != 4) return false;
        boolean gotNegative = false;
        boolean gotPositive = false;
        for (int i = 0; i < n; i++) {
            int dx1 = poly.get((i + 1) % n).x - poly.get(i).x;
            int dy1 = poly.get((i + 1) % n).y - poly.get(i).y;
            int dx2 = poly.get((i + 2) % n).x - poly.get((i + 1) % n).x;
            int dy2 = poly.get((i + 2) % n).y - poly.get((i + 1) % n).y;
            int cross = dx1 * dy2 - dy1 * dx2;
            if (cross < 0) gotNegative = true;
            if (cross > 0) gotPositive = true;
            if (gotNegative && gotPositive) return false; // невыпуклый
        }
        return true;
    }

    public static List<List<Point>> simplifyContours(List<List<Point>> contours, double epsilon) {
        List<List<Point>> simplifiedContours = new ArrayList<>();
        for (List<Point> contour : contours) {
            if (contour == null || contour.size() < 3) continue;
            List<Point> simplified = approxPolyDP(contour, epsilon);
            // Гарантируем замкнутость (первая == последней)
            if (simplified.size() > 1 && !simplified.get(0).equals(simplified.get(simplified.size() - 1))) {
                simplified.add(new Point(simplified.get(0).x, simplified.get(0).y));
            }
            simplifiedContours.add(simplified);
        }
        return simplifiedContours;
    }

    // Реализация approxPolyDP
    public static List<Point> approxPolyDP(List<Point> points, double epsilon) {
        if (points.size() < 3) return new ArrayList<>(points);
        boolean closed = points.get(0).equals(points.get(points.size() - 1));
        List<Point> input = closed ? points.subList(0, points.size() - 1) : points;
        List<Point> out = new ArrayList<>();
        rdpRecursive(input, 0, input.size() - 1, epsilon * epsilon, out);
        out.add(input.get(input.size() - 1));
        if (closed) out.add(input.get(0));
        return out;
    }

    private static void rdpRecursive(List<Point> pts, int first, int last, double sqEps, List<Point> out) {
        if (last <= first + 1) {
            out.add(pts.get(first));
            return;
        }
        double maxDist = 0;
        int index = -1;
        Point A = pts.get(first), B = pts.get(last);
        for (int i = first + 1; i < last; i++) {
            double d = perpendicularSqDistance(pts.get(i), A, B);
            if (d > maxDist) {
                maxDist = d;
                index = i;
            }
        }
        if (maxDist > sqEps) {
            rdpRecursive(pts, first, index, sqEps, out);
            rdpRecursive(pts, index, last, sqEps, out);
        } else {
            out.add(pts.get(first));
        }
    }

    private static double perpendicularSqDistance(Point p, Point a, Point b) {
        double dx = b.x - a.x, dy = b.y - a.y;
        double px = p.x - a.x, py = p.y - a.y;
        double dot = dx * px + dy * py;
        double lenSq = dx * dx + dy * dy;
        double param = lenSq != 0 ? dot / lenSq : -1;
        double xx, yy;
        if (param < 0) {
            xx = a.x; yy = a.y;
        } else if (param > 1) {
            xx = b.x; yy = b.y;
        } else {
            xx = a.x + param * dx;
            yy = a.y + param * dy;
        }
        double dx1 = p.x - xx, dy1 = p.y - yy;
        return dx1 * dx1 + dy1 * dy1;
    }
}
