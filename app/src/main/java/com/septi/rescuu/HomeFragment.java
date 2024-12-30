package com.septi.rescuu;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.septi.rescuu.adapter.ActiveTeamsAdapter;
import com.septi.rescuu.adapter.QuickActionAdapter;
import com.septi.rescuu.adapter.RecentReportsAdapter;
import com.septi.rescuu.model.QuickAction;
import com.septi.rescuu.model.RescueTeam;
import com.septi.rescuu.model.Report;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class HomeFragment extends Fragment {

    private RecyclerView rvQuickActions;
    private RecyclerView rvActiveTeams;
    private RecyclerView rvRecentReports;
    private MaterialButton btnEmergency;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        initializeViews(view);
        setupRecyclerViews();
        setupClickListeners();

        return view;
    }
    private void initializeViews(View view) {
        rvQuickActions = view.findViewById(R.id.rv_quick_actions);
        rvActiveTeams = view.findViewById(R.id.rv_active_teams);
        rvRecentReports = view.findViewById(R.id.rv_recent_reports);
        btnEmergency = view.findViewById(R.id.btn_emergency);
    }

    private void setupRecyclerViews() {


        // Setup Active Teams
        List<RescueTeam> activeTeams = new ArrayList<>();
        activeTeams.add(new RescueTeam("Tim Alpha", "2 km", "Tersedia"));
        activeTeams.add(new RescueTeam("Tim Beta", "3.5 km", "Dalam Tugas"));
        activeTeams.add(new RescueTeam("Tim Delta", "5 km", "Tersedia"));

        // Setup Recent Reports
        List<Report> recentReports = new ArrayList<>();
        recentReports.add(new Report("Kecelakaan Motor", "Jl. Sudirman", "10 menit yang lalu"));
        recentReports.add(new Report("Kebakaran", "Jl. Thamrin", "30 menit yang lalu"));
        recentReports.add(new Report("Banjir", "Jl. Gatot Subroto", "1 jam yang lalu"));


        List<QuickAction> quickActions = new ArrayList<>();
        quickActions.add(new QuickAction("Lapor Kecelakaan", R.drawable.ic_accident));
        quickActions.add(new QuickAction("Panggil Ambulans", R.drawable.ic_ambulance));
        quickActions.add(new QuickAction("Hubungi Polisi", R.drawable.ic_police));
        quickActions.add(new QuickAction("Lihat Semua", R.drawable.ic_chevron_right)); // Add view all option

        GridLayoutManager gridLayoutManager = new GridLayoutManager(getContext(), 2);
        rvQuickActions.setLayoutManager(gridLayoutManager);

        QuickActionAdapter adapter = new QuickActionAdapter(quickActions);
        adapter.setOnItemClickListener((quickAction, position) -> {
            if (position == 3) { // "Lihat Semua" position
                showAllActionsDialog();
            }
        });

        rvQuickActions.setAdapter(adapter);
        rvActiveTeams.setAdapter(new ActiveTeamsAdapter(activeTeams));
     rvRecentReports.setAdapter(new RecentReportsAdapter(recentReports));
    }

    private void showAllActionsDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_all_actions, null);
        RecyclerView dialogRecyclerView = dialogView.findViewById(R.id.dialog_recycler_view);

        List<QuickAction> allActions = new ArrayList<>();
        allActions.add(new QuickAction("Lapor Kecelakaan", R.drawable.ic_accident));
        allActions.add(new QuickAction("Panggil Ambulans", R.drawable.ic_ambulance));
        allActions.add(new QuickAction("Hubungi Polisi", R.drawable.ic_police));
        allActions.add(new QuickAction("Pemadam Kebakaran", R.drawable.ic_fire));

        GridLayoutManager dialogGridManager = new GridLayoutManager(getContext(), 2);
        dialogRecyclerView.setLayoutManager(dialogGridManager);
        dialogRecyclerView.setAdapter(new QuickActionAdapter(allActions));

        builder.setView(dialogView)
                .setTitle("Semua Aksi Cepat")
                .setPositiveButton("Tutup", (dialog, which) -> dialog.dismiss());

        builder.create().show();
    }

    private void setupClickListeners() {
        btnEmergency.setOnClickListener(v -> {
            // Handle emergency button click
            // Implement emergency call logic here
        });
    }
}