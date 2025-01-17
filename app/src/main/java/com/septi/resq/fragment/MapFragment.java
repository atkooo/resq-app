package com.septi.resq.fragment;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.septi.resq.R;
import com.septi.resq.database.EmergencyDBHelper;
import com.septi.resq.database.TrackingDBHelper;
import com.septi.resq.model.Emergency;
import com.septi.resq.utils.LocationSearchManager;
import com.septi.resq.utils.LocationUtils;
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
import java.util.Objects;
import java.util.stream.Collectors;

public class MapFragment extends Fragment {
    private MapView mapView;
    private LocationSearchManager searchManager;
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
    private long highlightedEmergencyId = -1;
    private EmergencyViewModel viewModel;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = requireContext();

        viewModel = new ViewModelProvider(requireActivity()).get(EmergencyViewModel.class);
        viewModel.init(
                new EmergencyDBHelper(context),
                new TrackingDBHelper(context)
        );

        takePicture = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                result -> {
                    if (result && imagePreview != null) {
                        imagePreview.setImageURI(photoUri);
                        imagePreview.setVisibility(View.VISIBLE);
                    }
                }
        );

        if (getArguments() != null) {
            highlightedEmergencyId = getArguments().getLong("emergencyId", -1);
        }

        Activity activity = getActivity();
        if (activity != null) {
            Intent intent = activity.getIntent();
            if (intent != null && intent.getBooleanExtra("navigateToMap", false)) {
                highlightedEmergencyId = intent.getLongExtra("emergencyId", -1);
            }
        }

        viewModel.getNewEmergency().observe(this, this::addEmergencyMarker);
        viewModel.getUpdatedEmergency().observe(this, this::updateEmergencyMarker);
        viewModel.getDeletedEmergencyId().observe(this, this::removeEmergencyMarker);
        viewModel.getEmergencies().observe(this, this::updateAllMarkers);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(requireActivity()).get(EmergencyViewModel.class);
        dbHelper = new EmergencyDBHelper(requireContext());
        emergencyMarkers = new ArrayList<>();
        Configuration.getInstance().setUserAgentValue(requireContext().getPackageName());
        searchManager = new LocationSearchManager(requireContext(), mapView);
        View view = inflater.inflate(R.layout.fragment_map, container, false);
        mapView = view.findViewById(R.id.map_view);
        searchManager = new LocationSearchManager(requireContext(), mapView);
        setupSearchBar(view);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.setTilesScaledToDpi(true);
        mapView.setHorizontalMapRepetitionEnabled(false);
        mapView.setVerticalMapRepetitionEnabled(false);
        mapView.setMinZoomLevel(4.0);
        mapView.setMaxZoomLevel(19.0);
        mapView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        GeoPoint startPoint = new GeoPoint(DEFAULT_LATITUDE, DEFAULT_LONGITUDE);
        mapView.getController().setZoom(DEFAULT_ZOOM);
        mapView.getController().setCenter(startPoint);

        setupUIControls(view);

        if (LocationUtils.hasLocationPermission(requireContext())) {
            getCurrentLocation();
        } else {
            LocationUtils.requestLocationPermissions(requireActivity());
        }

        setupMapClickListener();
        loadExistingMarkers();
        if (highlightedEmergencyId != -1) {
            new Handler().postDelayed(() -> showHighlightedEmergency(highlightedEmergencyId), 500);
        }

        return view;
    }

    private void showHighlightedEmergency(long emergencyId) {
        Emergency emergency = dbHelper.getEmergencyById((int) emergencyId);
        if (emergency != null) {
            GeoPoint point = new GeoPoint(emergency.getLatitude(), emergency.getLongitude());

            mapView.getController().animateTo(point);
            mapView.getController().setZoom(18.0);

            for (Marker marker : emergencyMarkers) {
                if (findEmergencyForMarker(marker) != null &&
                        Objects.requireNonNull(findEmergencyForMarker(marker)).getId() == emergencyId) {
                    marker.showInfoWindow();
                    break;
                }
            }
        }
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
        for (Marker marker : emergencyMarkers) {
            mapView.getOverlays().remove(marker);
        }
        emergencyMarkers.clear();

        for (Emergency emergency : emergencies) {
            addEmergencyMarker(emergency);
        }
    }

    private void updateEmergencyMarker(Emergency emergency) {
        if (emergency == null) return;

        for (Marker marker : emergencyMarkers) {
            String markerId = marker.getId();
            if (markerId != null && markerId.equals(String.valueOf(emergency.getId()))) {
                marker.setPosition(new GeoPoint(emergency.getLatitude(), emergency.getLongitude()));
                mapView.invalidate();
                break;
            }
        }
    }

    private void removeEmergencyMarker(long emergencyId) {
        Marker markerToRemove = null;
        for (Marker marker : emergencyMarkers) {
            if (marker.getId().equals(String.valueOf(emergencyId))) {
                markerToRemove = marker;
                break;
            }
        }
        if (markerToRemove != null) {
            mapView.getOverlays().remove(markerToRemove);
            emergencyMarkers.remove(markerToRemove);
            mapView.invalidate();
        }
    }


    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = requireContext().getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
        return File.createTempFile(imageFileName, ".jpg", storageDir);
    }


    private void setupUIControls(View view) {
        FloatingActionButton myLocationButton = view.findViewById(R.id.my_location_button);
        myLocationButton.setOnClickListener(v -> getCurrentLocation());

        MaterialButton reportButton = view.findViewById(R.id.report_button);
        reportButton.setOnClickListener(v -> reportEmergency());
        EditText searchBar = view.findViewById(R.id.search_bar);
        searchBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String query = searchBar.getText().toString();
                if (!query.isEmpty()) {
                    searchManager.searchLocation(query);
                    InputMethodManager imm = (InputMethodManager) requireContext()
                            .getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(searchBar.getWindowToken(), 0);
                }
                return true;
            }
            return false;
        });
    }


    private void setupSearchBar(View rootView) {
        EditText searchBar = rootView.findViewById(R.id.search_bar);
        AutoCompleteTextView autoCompleteTextView = (AutoCompleteTextView) searchBar;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, new ArrayList<>());
        autoCompleteTextView.setAdapter(adapter);

        searchBar.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() >= 3) {
                    searchManager.searchSuggestions(s.toString());
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });


        searchManager.setOnSearchResultListener(suggestions -> {
            adapter.clear();
            adapter.addAll(suggestions);
            adapter.notifyDataSetChanged();
        });

        autoCompleteTextView.setOnItemClickListener((parent, viewItem, position, id) -> {
            String selectedLocation = (String) parent.getItemAtPosition(position);
            searchManager.searchLocation(selectedLocation);

            InputMethodManager imm = (InputMethodManager) requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(searchBar.getWindowToken(), 0);
        });

        searchBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {

                String query = searchBar.getText().toString().trim();
                if (!query.isEmpty()) {
                    searchManager.searchLocation(query);

                    InputMethodManager imm = (InputMethodManager) requireContext()
                            .getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(searchBar.getWindowToken(), 0);
                }
                return true;
            }
            return false;
        });
    }

    private void setupMapClickListener() {
        mapView.getOverlays().add(new org.osmdroid.views.overlay.Overlay() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e, MapView mapView) {
                org.osmdroid.api.IGeoPoint p = mapView.getProjection().fromPixels((int) e.getX(), (int) e.getY());

                boolean clickedOnMarker = false;

                for (Marker marker : emergencyMarkers) {
                    if (marker.getBounds().contains((float) p.getLatitude(), (float) p.getLongitude())) {
                        marker.showInfoWindow();
                        clickedOnMarker = true;
                        break;
                    }
                }

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
            public boolean onLongPress(MotionEvent e, MapView mapView) {
                org.osmdroid.api.IGeoPoint p = mapView.getProjection().fromPixels((int) e.getX(), (int) e.getY());
                if (selectedLocation != null) {
                    mapView.getOverlays().remove(selectedLocation);
                }

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
        for (Marker marker : emergencyMarkers) {
            mapView.getOverlays().remove(marker);
        }
        emergencyMarkers.clear();

        for (Emergency emergency : dbHelper.getAllEmergencies()) {
            Marker marker = new Marker(mapView);
            marker.setPosition(new GeoPoint(emergency.getLatitude(), emergency.getLongitude()));
            updateMarkerInfo(marker, emergency);

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
        if (LocationUtils.hasLocationPermission(requireContext())) {
            Location location = LocationUtils.getLastKnownLocation(requireContext());

            if (location != null) {
                updateCurrentLocation(location);
            } else {
                Toast.makeText(requireContext(), "Unable to get location.", Toast.LENGTH_SHORT).show();
            }
        } else {
            LocationUtils.checkLocationPermission(requireActivity());
        }
    }

    private void updateCurrentLocation(Location location) {
        GeoPoint currentLocation = new GeoPoint(location.getLatitude(), location.getLongitude());
        lastKnownLocation = currentLocation;

        mapView.getController().animateTo(currentLocation);
        mapView.getController().setZoom(18.0);

        if (currentLocationMarker == null) {
            currentLocationMarker = new Marker(mapView);
            mapView.getOverlays().add(currentLocationMarker);
        }

        currentLocationMarker.setPosition(currentLocation);
        currentLocationMarker.setAnchor(0.5f, 0.5f);
        currentLocationMarker.setTitle("Lokasi Anda");

        Drawable icon = getResources().getDrawable(R.drawable.ic_location);
        Context context = getContext();

        Drawable resizedIcon = MarkerUtils.resizeMarkerIcon(context, icon, 20);
        currentLocationMarker.setIcon(resizedIcon);


        if (pulsingOverlay == null) {
            pulsingOverlay = new PulsingLocationOverlay(requireContext(), mapView, currentLocation);
            mapView.getOverlays().add(0, pulsingOverlay);
        } else {
            pulsingOverlay.updateLocation(currentLocation);
        }
        pulsingOverlay.startAnimation();

        mapView.invalidate();

        Toast.makeText(requireContext(), "Lokasi ditemukan!", Toast.LENGTH_SHORT).show();
    }


    private void showReportDialog(final double latitude, final double longitude) {
        List<Emergency> unfinishedEmergencies = dbHelper.getAllEmergencies().stream()
                .filter(e -> e.getStatus() == Emergency.EmergencyStatus.MENUNGGU ||
                        e.getStatus() == Emergency.EmergencyStatus.PROSES)
                .collect(Collectors.toList());

        if (!unfinishedEmergencies.isEmpty()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Peringatan")
                    .setMessage("Anda memiliki laporan darurat yang belum selesai. " +
                            "Harap tunggu hingga laporan sebelumnya ditangani.")
                    .setPositiveButton("OK", (dialog, which) -> {
                        Emergency firstUnfinished = unfinishedEmergencies.get(0);
                        GeoPoint point = new GeoPoint(firstUnfinished.getLatitude(),
                                firstUnfinished.getLongitude());
                        mapView.getController().animateTo(point);

                        for (Marker marker : emergencyMarkers) {
                            Emergency markerEmergency = findEmergencyForMarker(marker);
                            if (markerEmergency != null &&
                                    markerEmergency.getId() == firstUnfinished.getId()) {
                                marker.showInfoWindow();
                                break;
                            }
                        }
                    })
                    .show();
            return;
        }

        View dialogView = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_report_emergency, null);

        Spinner typeSpinner = dialogView.findViewById(R.id.spinner_emergency_type);
        EditText descriptionEdit = dialogView.findViewById(R.id.edit_description);
        MaterialButton takePhotoButton = dialogView.findViewById(R.id.btn_take_photo);
        imagePreview = dialogView.findViewById(R.id.img_preview);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item,
                new String[]{"Kecelakaan", "Kebakaran", "Bencana Alam", "Kriminal", "Medis", "Lainnya"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSpinner.setAdapter(adapter);

        takePhotoButton.setOnClickListener(v -> {
            if (checkCameraPermission()) {
                launchCamera();
            } else {
                requestCameraPermission();
            }
        });

        new AlertDialog.Builder(requireContext())
                .setTitle("Laporkan Kejadian Darurat")
                .setView(dialogView)
                .setPositiveButton("Laporkan", (dialog, which) -> {
                    String type = typeSpinner.getSelectedItem().toString();
                    String description = descriptionEdit.getText().toString();
                    String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                            Locale.getDefault())
                            .format(new Date());
                    String photoPath = photoUri != null ? photoUri.toString() : null;

                    Emergency emergency = new Emergency(latitude, longitude, type,
                            description, timestamp, photoPath);
                    viewModel.addEmergency(emergency);
                    updateMapMarker(emergency);

                    Toast.makeText(requireContext(),
                            "Kejadian berhasil dilaporkan",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void updateMapMarker(Emergency emergency) {
        if (selectedLocation != null) {
            mapView.getOverlays().remove(selectedLocation);
            selectedLocation = null;
        }

        Marker newMarker = new Marker(mapView);
        newMarker.setPosition(new GeoPoint(emergency.getLatitude(), emergency.getLongitude()));
        updateMarkerInfo(newMarker, emergency);

        mapView.getOverlays().add(newMarker);
        emergencyMarkers.add(newMarker);

        mapView.invalidate();
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


    private void updateMarkerInfo(Marker marker, Emergency emergency) {
        marker.setTitle(emergency.getType());
        marker.setSnippet("Waktu: " + emergency.getTimestamp() + "\n" + "Deskripsi: " + emergency.getDescription());
        marker.setAnchor(0.5f, 1.0f);
        marker.setDraggable(false);
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

        marker.setInfoWindow(new CustomInfoWindow(mapView));
    }

    private class CustomInfoWindow extends org.osmdroid.views.overlay.infowindow.InfoWindow {
        private final Handler autoCloseHandler;
        private static final long AUTO_CLOSE_DELAY = 10000;

        public CustomInfoWindow(MapView mapView) {
            super(R.layout.marker_info_window, mapView);
            autoCloseHandler = new Handler();
        }

        @Override
        public void onOpen(Object item) {
            Marker marker = (Marker) item;
            View view = getView();
            if (view != null) {
                TextView titleText = view.findViewById(R.id.title);
                TextView snippetText = view.findViewById(R.id.snippet);
                ImageView imageView = view.findViewById(R.id.emergency_image);

                titleText.setText(marker.getTitle());
                snippetText.setText(marker.getSnippet());

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

                autoCloseHandler.removeCallbacksAndMessages(null);
                autoCloseHandler.postDelayed(this::close, AUTO_CLOSE_DELAY);
            }
        }

        @Override
        public void onClose() {
            autoCloseHandler.removeCallbacksAndMessages(null);
        }
    }

    private Emergency findEmergencyForMarker(Marker marker) {
        GeoPoint position = marker.getPosition();
        List<Emergency> emergencies = dbHelper.getAllEmergencies();
        for (Emergency emergency : emergencies) {
            if (Math.abs(emergency.getLatitude() - position.getLatitude()) < 0.0001 &&
                    Math.abs(emergency.getLongitude() - position.getLongitude()) < 0.0001) {
                return emergency;
            }
        }
        return null;
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchCamera();
            } else {
                Toast.makeText(requireContext(),
                        "Izin Camera diperlukan",
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