package com.sleepchaos;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.slider.Slider;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private ArrayList<Uri> playlistUris = new ArrayList<>();
    private FileAdapter fileAdapter;
    private TextView tvTimerValue;
    private TextView tvModeValue;
    private TextView tvPlaylistHeader;
    private View layoutEmptyState;
    private ExtendedFloatingActionButton btnAction;
    private MaterialButton btnExport;
    private Slider sliderTimer;
    private Slider sliderMode;
    private boolean isPlaying = false;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (!isGranted) {
                    Toast.makeText(this, "Permissions required for playback", Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    boolean added = false;
                    if (data.getClipData() != null) {
                        ClipData clipData = data.getClipData();
                        for (int i = 0; i < clipData.getItemCount(); i++) {
                            addUriToPlaylist(clipData.getItemAt(i).getUri());
                            added = true;
                        }
                    } else if (data.getData() != null) {
                        addUriToPlaylist(data.getData());
                        added = true;
                    }
                    if(added) updatePlaylistUI();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind Views
        tvPlaylistHeader = findViewById(R.id.tvPlaylistHeader);
        tvTimerValue = findViewById(R.id.tvTimerValue);
        tvModeValue = findViewById(R.id.tvModeValue);
        layoutEmptyState = findViewById(R.id.layoutEmptyState);
        btnAction = findViewById(R.id.btnAction);
        btnExport = findViewById(R.id.btnExport);
        sliderTimer = findViewById(R.id.sliderTimer);
        sliderMode = findViewById(R.id.sliderMode);
        View btnAddFiles = findViewById(R.id.btnAddFiles);
        View btnClear = findViewById(R.id.btnClear);
        RecyclerView recyclerView = findViewById(R.id.recyclerViewFiles);

        // Setup RecyclerView
        fileAdapter = new FileAdapter(playlistUris);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(fileAdapter);
        
        ItemTouchHelper.SimpleCallback simpleItemTouchCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder h, @NonNull RecyclerView.ViewHolder t) {
                return false;
            }
            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int swipeDir) {
                int position = viewHolder.getAdapterPosition();
                playlistUris.remove(position);
                fileAdapter.notifyItemRemoved(position);
                updatePlaylistUI();
            }
        };
        new ItemTouchHelper(simpleItemTouchCallback).attachToRecyclerView(recyclerView);

        // Initial Setup
        checkPermissions();
        updatePlaylistUI();

        // Listeners
        btnAddFiles.setOnClickListener(v -> openFilePicker());
        
        btnClear.setOnClickListener(v -> {
            if (isPlaying) {
                Toast.makeText(this, "Stop playback before clearing list", Toast.LENGTH_SHORT).show();
                return;
            }
            playlistUris.clear();
            updatePlaylistUI();
        });

        sliderTimer.addOnChangeListener((slider, value, fromUser) -> {
            int mins = (int) value;
            if (mins == 0) tvTimerValue.setText("Infinite");
            else tvTimerValue.setText(mins + " min");
        });
        
        sliderMode.addOnChangeListener((slider, value, fromUser) -> {
            updateModeLabel((int) value);
        });
        updateModeLabel(5); // Default

        btnAction.setOnClickListener(v -> togglePlayback());
        
        btnExport.setOnClickListener(v -> performExport());
    }
    
    private void updateModeLabel(int mode) {
        String desc;
        if (mode <= 2) desc = getString(R.string.mode_desc_rapid);
        else if (mode <= 4) desc = getString(R.string.mode_desc_moderate);
        else if (mode <= 6) desc = getString(R.string.mode_desc_balanced);
        else if (mode <= 8) desc = getString(R.string.mode_desc_slow);
        else desc = getString(R.string.mode_desc_sparse);
        
        tvModeValue.setText(getString(R.string.mode_pattern, mode, desc));
    }

    private void addUriToPlaylist(Uri uri) {
        if (uri != null) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                // Ignore
            }
            playlistUris.add(uri);
        }
    }

    private void updatePlaylistUI() {
        String label = playlistUris.isEmpty() ? "SOUNDSCAPES" : "SOUNDSCAPES (" + playlistUris.size() + ")";
        tvPlaylistHeader.setText(label);
        fileAdapter.notifyDataSetChanged();
        layoutEmptyState.setVisibility(playlistUris.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else {
             if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*"); 
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*", "application/ogg"}); 
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        filePickerLauncher.launch(intent);
    }

    private void togglePlayback() {
        Intent serviceIntent = new Intent(this, ChaosService.class);

        if (!isPlaying) {
            if (playlistUris.isEmpty()) {
                Toast.makeText(this, "Please add audio files first", Toast.LENGTH_SHORT).show();
                return;
            }

            ArrayList<String> uriStrings = new ArrayList<>();
            for (Uri u : playlistUris) {
                uriStrings.add(u.toString());
            }

            serviceIntent.setAction(ChaosService.ACTION_START);
            serviceIntent.putStringArrayListExtra(ChaosService.EXTRA_URI_LIST, uriStrings);
            serviceIntent.putExtra(ChaosService.EXTRA_DURATION_MINS, (int) sliderTimer.getValue());
            serviceIntent.putExtra(ChaosService.EXTRA_INTENSITY_MODE, (int) sliderMode.getValue());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            isPlaying = true;
            toggleButtonState(true);
        } else {
            serviceIntent.setAction(ChaosService.ACTION_STOP);
            startService(serviceIntent);
            isPlaying = false;
            toggleButtonState(false);
        }
    }
    
    private void toggleButtonState(boolean playing) {
        if (playing) {
            btnAction.setText(R.string.btn_stop);
            btnAction.setIconResource(android.R.drawable.ic_media_pause);
            btnAction.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.accent_error));
            sliderMode.setEnabled(false);
            sliderTimer.setEnabled(false);
            btnExport.setEnabled(false);
        } else {
            btnAction.setText(R.string.btn_start);
            btnAction.setIconResource(android.R.drawable.ic_media_play);
            btnAction.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.primary_color));
            sliderMode.setEnabled(true);
            sliderTimer.setEnabled(true);
            btnExport.setEnabled(true);
        }
    }

    private void performExport() {
        if (playlistUris.isEmpty()) {
            Toast.makeText(this, "No audio to export", Toast.LENGTH_SHORT).show();
            return;
        }

        // Only export the first file for simplicity in this native implementation
        Uri source = playlistUris.get(0);
        int duration = (int) sliderTimer.getValue();
        int mode = (int) sliderMode.getValue();

        if (duration == 0) duration = 10; // Default 10 min if infinite selected for export

        Toast.makeText(this, R.string.export_start, Toast.LENGTH_LONG).show();
        btnExport.setEnabled(false);

        AudioExporter.exportChaosAudio(this, source, duration, mode, new AudioExporter.ExportCallback() {
            @Override
            public void onSuccess(String path) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, R.string.export_success, Toast.LENGTH_LONG).show();
                    btnExport.setEnabled(true);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, String.format(getString(R.string.export_error), error), Toast.LENGTH_LONG).show();
                    btnExport.setEnabled(true);
                });
            }
        });
    }

    // RecyclerView Adapter
    private class FileAdapter extends RecyclerView.Adapter<FileAdapter.FileViewHolder> {
        private final ArrayList<Uri> uris;

        FileAdapter(ArrayList<Uri> uris) {
            this.uris = uris;
        }

        @NonNull
        @Override
        public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file, parent, false);
            return new FileViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
            Uri uri = uris.get(position);
            holder.tvFileName.setText(getFileName(uri));
            holder.itemView.setAlpha(0f);
            holder.itemView.animate().alpha(1f).setDuration(300).start();
        }

        @Override
        public int getItemCount() {
            return uris.size();
        }

        class FileViewHolder extends RecyclerView.ViewHolder {
            TextView tvFileName;

            FileViewHolder(View itemView) {
                super(itemView);
                tvFileName = itemView.findViewById(R.id.tvFileName);
            }
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) result = cursor.getString(index);
                }
            } catch (Exception e) {}
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result;
    }
}