package com.septi.resq.utils;

import android.graphics.Color;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.List;

public class PolylineUtils {

    public static Polyline drawRoute(MapView map, List<GeoPoint> points, int color, float width) {
        Polyline routeLine = new Polyline();
        routeLine.setPoints(points);
        routeLine.setColor(color);
        routeLine.setWidth(width);

        map.getOverlays().add(routeLine);
        map.invalidate();
        return routeLine;
    }

    public static List<GeoPoint> decodePolyline(String encoded) {
        List<GeoPoint> points = new ArrayList<>();
        int index = 0, len = encoded.length();
        int lat = 0, lng = 0;

        while (index < len) {
            int result = 1;
            int shift = 0;
            int b;
            do {
                b = encoded.charAt(index++) - 63 - 1;
                result += b << shift;
                shift += 5;
            } while (b >= 0x1f);
            lat += (result & 1) != 0 ? ~(result >> 1) : (result >> 1);

            result = 1;
            shift = 0;
            do {
                b = encoded.charAt(index++) - 63 - 1;
                result += b << shift;
                shift += 5;
            } while (b >= 0x1f);
            lng += (result & 1) != 0 ? ~(result >> 1) : (result >> 1);

            points.add(new GeoPoint(lat * 1e-5, lng * 1e-5));
        }
        return points;
    }
}
