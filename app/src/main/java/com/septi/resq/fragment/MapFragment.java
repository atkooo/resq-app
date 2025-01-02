package com.septi.resq.fragment;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.septi.resq.utils.LocationUtils;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.septi.resq.R;
import com.septi.resq.model.Emergency;
import com.septi.resq.database.EmergencyDBHelper;
import com.septi.resq.utils.MarkerUtils;
import com.septi.resq.utils.PulsingLocationOverlay;
import com.septi.resq.viewmodel.EmergencyViewModel;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MapFragment extends Fragment {
    private MapView mapView;
    private EmergencyDBHelper dbHelper;
    private Marker selectedLocation;
    private Marker currentLocationMarker;
    private List<Marker> emergencyMarkers;
    private GeoPoint lastKnownLocation;
    private PulsingLocationOverlay pulsingOverlay;

    private Uri photoUri;
    private ImageView imagePreview;
    private ActivityResultLauncher<Uri> takePicture;
    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final int MARKER_SIZE_DP = 40;
    private static final double DEFAULT_LATITUDE = -6.200000;
    private static final double DEFAULT_LONGITUDE = 106.816666;
    private static final double DEFAULT_ZOOM = 15.0;

    private EmergencyViewModel viewModel;

    @Override
    public void onCreate( Bundle savedInstanceState ) {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EmergencyViewModel.class);
        viewModel.init(dbHelper);

        // Initialize the camera launcher
        takePicture = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                result -> {
                    if (result && imagePreview != null) {
                        imagePreview.setImageURI(photoUri);
                        imagePreview.setVisibility(View.VISIBLE);
                    }
                }
        );

        // Observe new emergencies
        viewModel.getNewEmergency().observe(this, this::addEmergencyMarker);
        viewModel.getEmergencies().observe(this, this::updateAllMarkers);
    }


    private void addEmergencyMarker(Emergency emergency) {
        Marker newMarker = new Marker(mapView);
        newMarker.setPosition(new GeoPoint(emergency.getLatitude(), emergency.getLongitude()));
        updateMarkerInfo(newMarker, emergency);
        mapView.getOverlays().add(newMarker);
        emergencyMarkers.add(newMarker);
        mapView.invalidate();
    }

    private void updateAllMarkers(List<Emergency> emergencies) {
        // Clear existing markers
        for (Marker marker : emergencyMarkers) {
            mapView.getOverlays().remove(marker);
        }
        emergencyMarkers.clear();

        // Add updated markers
        for (Emergency emergency : emergencies) {
            addEmergencyMarker(emergency);
        }
    }


    // Add this method to create temporary image file
    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = requireContext().getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
        return File.createTempFile(imageFileName, ".jpg", storageDir);
    }


    // Constants


    @Override
    public View onCreateView( LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState ) {
        // Initialize database helper first
        dbHelper = new EmergencyDBHelper(requireContext());
        emergencyMarkers = new ArrayList<>();
        // Initialize OpenStreetMap configuration
        Configuration.getInstance().setUserAgentValue(requireContext().getPackageName());

        // Inflate the layout
        View view = inflater.inflate(R.layout.fragment_map, container, false);

        // Initialize map AFTER inflating the view
        mapView = view.findViewById(R.id.map_view);

        // Configure map base settings
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.setTilesScaledToDpi(true);
        mapView.setHorizontalMapRepetitionEnabled(false);
        mapView.setVerticalMapRepetitionEnabled(false);
        mapView.setMinZoomLevel(4.0);
        mapView.setMaxZoomLevel(19.0);

        // Enable hardware acceleration
        mapView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // Set initial map position to default location (Jakarta)
        GeoPoint startPoint = new GeoPoint(DEFAULT_LATITUDE, DEFAULT_LONGITUDE);
        mapView.getController().setZoom(DEFAULT_ZOOM);
        mapView.getController().setCenter(startPoint);

        // Initialize location services

        // Setup UI controls
        setupUIControls(view);

        // Request permissions and fetch current location
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            getCurrentLocation();
        } else {
            requestLocationPermissions();
        }

        // Add map click listener
        setupMapClickListener();

        // Load existing markers
        loadExistingMarkers();

        return view;
    }


    private void setupUIControls( View view ) {
        FloatingActionButton myLocationButton = view.findViewById(R.id.my_location_button);
        myLocationButton.setOnClickListener(v -> getCurrentLocation());

        MaterialButton reportButton = view.findViewById(R.id.report_button);
        reportButton.setOnClickListener(v -> reportEmergency());
    }

    private void setupMapClickListener() {
        mapView.getOverlays().add(new org.osmdroid.views.overlay.Overlay() {
            @Override
            public boolean onSingleTapConfirmed( MotionEvent e, MapView mapView ) {
                org.osmdroid.api.IGeoPoint p = mapView.getProjection().fromPixels((int) e.getX(), (int) e.getY());

                boolean clickedOnMarker = false;

                // Check if clicked on any marker
                for (Marker marker : emergencyMarkers) {
                    if (marker.getBounds().contains((float) p.getLatitude(), (float) p.getLongitude())) {
                        marker.showInfoWindow();
                        clickedOnMarker = true;
                        break;
                    }
                }

                // If clicked outside any marker, close all info windows
                if (!clickedOnMarker) {
                    for (Marker marker : emergencyMarkers) {
                        if (marker.isInfoWindowShown()) {
                            marker.closeInfoWindow();
                        }
                    }
                }

                return false;
            }

            @Override
            public boolean onLongPress( MotionEvent e, MapView mapView ) {
                // Tekan lama untuk menambahkan laporan baru
                org.osmdroid.api.IGeoPoint p = mapView.getProjection().fromPixels((int) e.getX(), (int) e.getY());

                // Hapus marker sebelumnya jika ada
                if (selectedLocation != null) {
                    mapView.getOverlays().remove(selectedLocation);
                }

                // Tambahkan marker baru di lokasi yang dipilih
                selectedLocation = new Marker(mapView);
                selectedLocation.setPosition(new GeoPoint(p.getLatitude(), p.getLongitude()));
                selectedLocation.setAnchor(0.5f, 1.0f);

                mapView.getOverlays().add(selectedLocation);
                mapView.invalidate();

                showReportDialog(p.getLatitude(), p.getLongitude());
                return true;
            }
        });
    }


    private void loadExistingMarkers() {
        // Clear existing emergency markers from both list and map
        for (Marker marker : emergencyMarkers) {
            mapView.getOverlays().remove(marker);
        }
        emergencyMarkers.clear();

        // Load markers from database
        for (Emergency emergency : dbHelper.getAllEmergencies()) {
            Marker marker = new Marker(mapView);
            marker.setPosition(new GeoPoint(emergency.getLatitude(), emergency.getLongitude()));
            updateMarkerInfo(marker, emergency);

            // Add to both map and our list
            mapView.getOverlays().add(marker);
            emergencyMarkers.add(marker);
        }
        mapView.invalidate();
    }

    private void reportEmergency() {
        if (lastKnownLocation != null) {
            showReportDialog(lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude());
        } else if (selectedLocation != null) {
            GeoPoint location = selectedLocation.getPosition();
            showReportDialog(location.getLatitude(), location.getLongitude());
        } else {
            Toast.makeText(requireContext(), "Silakan pilih lokasi kejadian terlebih dahulu", Toast.LENGTH_SHORT).show();
        }
    }


    private void getCurrentLocation() {
        // Check if location permissions are granted
        if (LocationUtils.hasLocationPermission(requireContext())) {
            // Use LocationUtils to get the last known location
            Location location = LocationUtils.getLastKnownLocation(requireContext());

            if (location != null) {
                updateCurrentLocation(location);
            } else {
                Toast.makeText(requireContext(), "Unable to get location.", Toast.LENGTH_SHORT).show();
            }
        } else {
            // Request permissions if not granted
            LocationUtils.requestLocationPermissions(requireContext());
        }
    }


    private void updateCurrentLocation( Location location ) {
        GeoPoint currentLocation = new GeoPoint(location.getLatitude(), location.getLongitude());
        lastKnownLocation = currentLocation;

        mapView.getController().animateTo(currentLocation);
        mapView.getController().setZoom(18.0);

        if (currentLocationMarker == null) {
            currentLocationMarker = new Marker(mapView);
            mapView.getOverlays().add(currentLocationMarker);
        }

        // Set marker position and properties
        currentLocationMarker.setPosition(currentLocation);
        currentLocationMarker.setAnchor(0.5f, 0.5f);  // Changed to center anchor
        currentLocationMarker.setTitle("Lokasi Anda");

        Drawable icon = getResources().getDrawable(R.drawable.ic_location);
// Mendapatkan context
        Context context = getContext(); // Gunakan 'getContext()' jika berada di dalam Fragment

// Resize ikon
        Drawable resizedIcon = MarkerUtils.resizeMarkerIcon(context, icon, 20);

// Mengatur ikon yang sudah diresize ke marker
        currentLocationMarker.setIcon(resizedIcon);



        // Update or create pulsing overlay
        if (pulsingOverlay == null) {
            pulsingOverlay = new PulsingLocationOverlay(requireContext(), mapView, currentLocation);
            mapView.getOverlays().add(0, pulsingOverlay); // Add at index 0 to draw below marker
        } else {
            pulsingOverlay.updateLocation(currentLocation);
        }
        pulsingOverlay.startAnimation();

        mapView.invalidate();

        Toast.makeText(requireContext(), "Lokasi ditemukan!", Toast.LENGTH_SHORT).show();
    }


    // Update your showReportDialog method
    private void showReportDialog( final double latitude, final double longitude ) {
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_report_emergency, null);

        Spinner typeSpinner = dialogView.findViewById(R.id.spinner_emergency_type);
        EditText descriptionEdit = dialogView.findViewById(R.id.edit_description);
        MaterialButton takePhotoButton = dialogView.findViewById(R.id.btn_take_photo);
        imagePreview = dialogView.findViewById(R.id.img_preview);

        // Setup emergency type spinner
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item,
                new String[]{"Kecelakaan", "Kebakaran", "Bencana Alam", "Kriminal", "Medis", "Lainnya"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSpinner.setAdapter(adapter);

        // Setup camera button
        takePhotoButton.setOnClickListener(v -> {
            if (checkCameraPermission()) {
                launchCamera();
            } else {
                requestCameraPermission();
            }
        });

        // Create and show dialog
        new AlertDialog.Builder(requireContext())
                .setTitle("Laporkan Kejadian Darurat")
                .setView(dialogView)
                .setPositiveButton("Laporkan", ( dialog, which ) -> {
                    String type = typeSpinner.getSelectedItem().toString();
                    String description = descriptionEdit.getText().toString();
                    String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(new Date());
                    String photoPath = photoUri != null ? photoUri.toString() : null;

                    Emergency emergency = new Emergency(latitude, longitude, type, description, timestamp, photoPath);
                    viewModel.addEmergency(emergency);
                    long id = dbHelper.insertEmergency(emergency);

                    if (id > 0) {
                        // Remove temporary selected location marker
                        if (selectedLocation != null) {
                            mapView.getOverlays().remove(selectedLocation);
                            selectedLocation = null;
                        }

                        // Create and add permanent marker
                        Marker newMarker = new Marker(mapView);
                        newMarker.setPosition(new GeoPoint(latitude, longitude));
                        updateMarkerInfo(newMarker, emergency);

                        mapView.getOverlays().add(newMarker);
                        emergencyMarkers.add(newMarker);

                        mapView.invalidate();

                        Toast.makeText(requireContext(), "Kejadian berhasil dilaporkan", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private boolean checkCameraPermission() {
        return ActivityCompat.checkSelfPermission(requireContext(),
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(requireActivity(),
                new String[]{Manifest.permission.CAMERA},
                PERMISSION_REQUEST_CODE);
    }

    private void launchCamera() {
        try {
            File photoFile = createImageFile();
            photoUri = FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".fileprovider",
                    photoFile);
            takePicture.launch(photoUri);
        } catch (IOException ex) {
            Toast.makeText(requireContext(),
                    "Error creating image file",
                    Toast.LENGTH_SHORT).show();
        }
    }





    private void updateMarkerInfo( Marker marker, Emergency emergency ) {
        marker.setTitle(emergency.getType());
        marker.setSnippet("Waktu: " + emergency.getTimestamp() + "\n" + "Deskripsi: " + emergency.getDescription());

        // Set fixed anchor points
        marker.setAnchor(0.5f, 1.0f);
        marker.setDraggable(false);

        // Set and resize icon
        int iconDrawable;
        switch (emergency.getType()) {
            case "Kecelakaan":
                iconDrawable = R.drawable.ic_accident;
                break;
            case "Kebakaran":
                iconDrawable = R.drawable.ic_fire;
                break;
            case "Bencana Alam":
                iconDrawable = R.drawable.ic_disaster;
                break;
            case "Kriminal":
                iconDrawable = R.drawable.ic_police;
                break;
            case "Medis":
                iconDrawable = R.drawable.ic_emergency;
                break;
            default:
                iconDrawable = R.drawable.error_image;
                break;
        }

        Drawable icon = getResources().getDrawable(iconDrawable);
        Drawable resizedIcon = MarkerUtils.resizeMarkerIcon(getContext(), icon, MARKER_SIZE_DP);
        marker.setIcon(resizedIcon);

        // Set info window
        marker.setInfoWindow(new CustomInfoWindow(mapView));
    }

    // Custom InfoWindow implementation
    private class CustomInfoWindow extends org.osmdroid.views.overlay.infowindow.InfoWindow {
        private Handler autoCloseHandler;
        private static final long AUTO_CLOSE_DELAY = 10000; // 5 seconds

        public CustomInfoWindow( MapView mapView ) {
            super(R.layout.marker_info_window, mapView);
            autoCloseHandler = new Handler();
        }

        @Override
        public void onOpen( Object item ) {
            Marker marker = (Marker) item;
            View view = getView();
            if (view != null) {
                TextView titleText = view.findViewById(R.id.title);
                TextView snippetText = view.findViewById(R.id.snippet);
                ImageView imageView = view.findViewById(R.id.emergency_image);

                titleText.setText(marker.getTitle());
                snippetText.setText(marker.getSnippet());

                // Find the emergency data for this marker
                Emergency emergency = findEmergencyForMarker(marker);
                if (emergency != null && emergency.getPhotoPath() != null) {
                    try {
                        Uri photoUri = Uri.parse(emergency.getPhotoPath());
                        imageView.setImageURI(photoUri);
                        imageView.setVisibility(View.VISIBLE);
                    } catch (Exception e) {
                        imageView.setVisibility(View.GONE);
                    }
                } else {
                    imageView.setVisibility(View.GONE);
                }

                // Schedule auto-close after 5 seconds
                autoCloseHandler.removeCallbacksAndMessages(null);
                autoCloseHandler.postDelayed(this::close, AUTO_CLOSE_DELAY);
            }
        }

        @Override
        public void onClose() {
            autoCloseHandler.removeCallbacksAndMessages(null);
        }
    }

    // Add this helper method in your MapFragment class
    private Emergency findEmergencyForMarker( Marker marker ) {
        GeoPoint position = marker.getPosition();
        // Query the database for the emergency at this location
        List<Emergency> emergencies = dbHelper.getAllEmergencies();
        for (Emergency emergency : emergencies) {
            if (Math.abs(emergency.getLatitude() - position.getLatitude()) < 0.0001 &&
                    Math.abs(emergency.getLongitude() - position.getLongitude()) < 0.0001) {
                return emergency;
            }
        }
        return null;
    }

    private void requestLocationPermissions() {
        LocationUtils.requestLocationPermissions(requireContext());
    }


    @Override
    public void onPause() {
        super.onPause();
        if (pulsingOverlay != null) {
            pulsingOverlay.stopAnimation();
        }
        mapView.onPause();
    }

    @Override
    public void onRequestPermissionsResult( int requestCode, String[] permissions, int[] grantResults ) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchCamera();
            } else {
                Toast.makeText(requireContext(),
                        "Camera permission is required to take photos",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }


    @Override
    public void onResume() {
        super.onResume();
        if (pulsingOverlay != null) {
            pulsingOverlay.startAnimation();
        }
        mapView.onResume();
        loadExistingMarkers();
    }
}