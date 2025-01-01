package com.septi.resq.fragment;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;

import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import com.septi.resq.R;
import com.septi.resq.database.EmergencyDBHelper;
import com.septi.resq.model.Emergency;

public class TrackingFragment extends Fragment {
    private MapView map;
    private static final GeoPoint AMBULANCE_LOCATION = new GeoPoint(0.0530266, 111.4755201);
    private Marker ambulanceMarker;
    private Polyline routeLine;
    private static final String OSRM_API_URL = "https://router.project-osrm.org/route/v1/driving/";
    private static final float SPEED = 80.0f;
    private Handler animationHandler = new Handler();
    private List<GeoPoint> currentRoute;
    private int currentRouteIndex = 0;
    private boolean isMoving = false;
    private EmergencyDBHelper dbHelper;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context ctx = requireActivity().getApplicationContext();
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        dbHelper = new EmergencyDBHelper(ctx);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_tracking, container, false);
        initializeMap(view);

        // Initialize the Toolbar from the included layout
        Toolbar toolbar = view.findViewById(R.id.toolbar);  // Add this line
        // Set the Toolbar as ActionBar
        ((AppCompatActivity) requireActivity()).setSupportActionBar(toolbar);

        if (((AppCompatActivity) requireActivity()).getSupportActionBar() != null) {
            ((AppCompatActivity) requireActivity()).getSupportActionBar().setDisplayHomeAsUpEnabled(false); // No back button
            ((AppCompatActivity) requireActivity()).getSupportActionBar().setTitle("Tracking");
        }
        return view;
    }

    private void initializeMap(View view) {
        map = view.findViewById(R.id.map);
        map.setMultiTouchControls(true);
        map.getController().setZoom(15.0);
        map.getController().setCenter(AMBULANCE_LOCATION);

        ambulanceMarker = new Marker(map);
        ambulanceMarker.setPosition(AMBULANCE_LOCATION);
        ambulanceMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        ambulanceMarker.setTitle("Ambulance");
        map.getOverlays().add(ambulanceMarker);

        loadEmergencyMarkersFromDatabase();
    }

    private void loadEmergencyMarkersFromDatabase() {
        List<Emergency> emergencies = dbHelper.getAllEmergencies();
        for (Emergency emergency : emergencies) {
            GeoPoint position = new GeoPoint(emergency.getLatitude(), emergency.getLongitude());
            Marker marker = new Marker(map);
            marker.setPosition(position);
            marker.setTitle(emergency.getType());
            marker.setSnippet(emergency.getDescription());
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);

            marker.setOnMarkerClickListener((marker1, mapView) -> {
                stopAmbulanceMovement();
                calculateRoute(ambulanceMarker.getPosition(), marker1.getPosition());
                return true;
            });

            map.getOverlays().add(marker);
        }
        map.invalidate();
    }
    private void addIncidentMarker(GeoPoint position, String title) {
        Marker marker = new Marker(map);
        marker.setPosition(position);
        marker.setTitle(title);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);

        marker.setOnMarkerClickListener((marker1, mapView) -> {
            stopAmbulanceMovement();
            calculateRoute(ambulanceMarker.getPosition(), marker1.getPosition());
            return true;
        });

        map.getOverlays().add(marker);
    }

    private void calculateRoute(GeoPoint start, GeoPoint end) {
        String url = OSRM_API_URL +
                start.getLongitude() + "," + start.getLatitude() + ";" +
                end.getLongitude() + "," + end.getLatitude() +
                "?overview=full&geometries=polyline";

        new Thread(() -> {
            try {
                URL routeUrl = new URL(url);
                HttpURLConnection conn = (HttpURLConnection) routeUrl.openConnection();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));

                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONObject jsonResponse = new JSONObject(response.toString());
                String geometry = jsonResponse.getJSONArray("routes")
                        .getJSONObject(0)
                        .getString("geometry");

                currentRoute = decodePolyline(geometry);

                requireActivity().runOnUiThread(() -> {
                    drawRoute(currentRoute);
                    startAmbulanceMovement();
                });

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void drawRoute(List<GeoPoint> points) {
        if (routeLine != null) {
            map.getOverlays().remove(routeLine);
        }

        routeLine = new Polyline();
        routeLine.setPoints(points);
        routeLine.setColor(Color.BLUE);
        routeLine.setWidth(10f);
        map.getOverlays().add(routeLine);
        map.invalidate();
    }

    private void startAmbulanceMovement() {
        if (!isMoving && currentRoute != null && !currentRoute.isEmpty()) {
            isMoving = true;
            currentRouteIndex = 0;
            moveAmbulance();
        }
    }

    private void stopAmbulanceMovement() {
        isMoving = false;
        animationHandler.removeCallbacksAndMessages(null);
    }

    private void moveAmbulance() {
        if (!isMoving || currentRouteIndex >= currentRoute.size() - 1) {
            isMoving = false;
            return;
        }

        GeoPoint current = currentRoute.get(currentRouteIndex);
        GeoPoint next = currentRoute.get(currentRouteIndex + 1);

        // Calculate distance in kilometers
        double distance = calculateDistance(current, next);

        // Calculate time needed for this segment (distance/speed in hours * 3600000 to get milliseconds)
        long timeForSegment = (long)((distance / SPEED) * 3600000);

        // Move marker
        ambulanceMarker.setPosition(current);
        map.invalidate();

        // Schedule next movement
        animationHandler.postDelayed(() -> {
            currentRouteIndex++;
            moveAmbulance();
        }, Math.max(timeForSegment, 16)); // Minimum 16ms for smooth animation
    }

    private double calculateDistance(GeoPoint p1, GeoPoint p2) {
        double R = 6371; // Earth's radius in kilometers
        double dLat = Math.toRadians(p2.getLatitude() - p1.getLatitude());
        double dLon = Math.toRadians(p2.getLongitude() - p1.getLongitude());
        double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                Math.cos(Math.toRadians(p1.getLatitude())) * Math.cos(Math.toRadians(p2.getLatitude())) *
                        Math.sin(dLon/2) * Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R * c;
    }

    private List<GeoPoint> decodePolyline(String encoded) {
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

    @Override
    public void onResume() {
        super.onResume();
        map.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        map.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAmbulanceMovement();
    }
}