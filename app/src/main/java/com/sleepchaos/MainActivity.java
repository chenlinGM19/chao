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
import android.widget.CheckBox;
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
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.slider.RangeSlider;
import com.google.android.material.slider.Slider;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class MainActivity extends AppCompatActivity {

    // Wrapper class to track selection state
    private static class AudioItem {
        Uri uri;
        boolean isSelected;
        
        AudioItem(Uri uri) {
            this.uri = uri;
            this.isSelected = true; // Default selected
        }
    }

    private ArrayList<AudioItem> playlist = new ArrayList<>();
    private FileAdapter fileAdapter;
    
    private TextView tvTimerValue;
    private TextView tvPlayDurValue;
    private TextView tvPauseDurValue;
    private TextView tvPlaylistHeader;
    private TextView tvVolRangeValue;
    
    private View layoutEmptyState;
    private View containerPlaylist;
    private TextView tvExternalHint;
    
    private ExtendedFloatingActionButton btnAction;
    private MaterialButton btnExport;
    
    private Slider sliderTimer;
    private RangeSlider sliderPlayDur;
    private RangeSlider sliderPauseDur;
    private RangeSlider sliderVolRange;
    private Slider sliderVolFreq;
    
    private MaterialButtonToggleGroup toggleMode;
    
    private boolean isPlaying = false;
    private boolean isExternalMode = false;

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
        tvPlayDurValue = findViewById(R.id.tvPlayDurValue);
        tvPauseDurValue = findViewById(R.id.tvPauseDurValue);
        tvVolRangeValue = findViewById(R.id.tvVolRangeValue);
        
        layoutEmptyState = findViewById(R.id.layoutEmptyState);
        containerPlaylist = findViewById(R.id.containerPlaylist);
        tvExternalHint = findViewById(R.id.tvExternalHint);
        
        btnAction = findViewById(R.id.btnAction);
        btnExport = findViewById(R.id.btnExport);
        
        sliderTimer = findViewById(R.id.sliderTimer);
        sliderPlayDur = findViewById(R.id.sliderPlayDur);
        sliderPauseDur = findViewById(R.id.sliderPauseDur);
        sliderVolRange = findViewById(R.id.sliderVolRange);
        sliderVolFreq = findViewById(R.id.sliderVolFreq);
        
        toggleMode = findViewById(R.id.toggleMode);
        
        View btnAddFiles = findViewById(R.id.btnAddFiles);
        View btnClear = findViewById(R.id.btnClear);
        RecyclerView recyclerView = findViewById(R.id.recyclerViewFiles);

        // Setup RecyclerView
        fileAdapter = new FileAdapter(playlist);
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
                playlist.remove(position);
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
            playlist.clear();
            updatePlaylistUI();
        });

        sliderTimer.addOnChangeListener((slider, value, fromUser) -> {
            int mins = (int) value;
            if (mins == 0) tvTimerValue.setText("Infinite");
            else tvTimerValue.setText(mins + " min");
        });
        
        sliderPlayDur.addOnChangeListener((slider, value, fromUser) -> {
            List<Float> values = slider.getValues();
            int min = Math.round(values.get(0));
            int max = Math.round(values.get(1));
            tvPlayDurValue.setText(String.format(getString(R.string.val_time_range), min, max));
        });
        
        sliderPauseDur.addOnChangeListener((slider, value, fromUser) -> {
            List<Float> values = slider.getValues();
            int min = Math.round(values.get(0));
            int max = Math.round(values.get(1));
            tvPauseDurValue.setText(String.format(getString(R.string.val_time_range), min, max));
        });
        
        sliderVolRange.addOnChangeListener((slider, value, fromUser) -> {
            List<Float> values = slider.getValues();
            int min = Math.round(values.get(0));
            int max = Math.round(values.get(1));
            tvVolRangeValue.setText(String.format(getString(R.string.val_vol_range), min, max));
        });
        
        // Mode Toggle Listener
        toggleMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                if (checkedId == R.id.btnModeLocal) {
                    isExternalMode = false;
                    containerPlaylist.setVisibility(View.VISIBLE);
                    tvExternalHint.setVisibility(View.GONE);
                    btnExport.setVisibility(View.VISIBLE);
                } else if (checkedId == R.id.btnModeExternal) {
                    isExternalMode = true;
                    containerPlaylist.setVisibility(View.GONE);
                    tvExternalHint.setVisibility(View.VISIBLE);
                    btnExport.setVisibility(View.GONE); // Exporting doesn't apply to external apps
                }
            }
        });

        // Initialize text
        List<Float> playVals = sliderPlayDur.getValues();
        tvPlayDurValue.setText(String.format(getString(R.string.val_time_range), Math.round(playVals.get(0)), Math.round(playVals.get(1))));
        List<Float> pauseVals = sliderPauseDur.getValues();
        tvPauseDurValue.setText(String.format(getString(R.string.val_time_range), Math.round(pauseVals.get(0)), Math.round(pauseVals.get(1))));

        btnAction.setOnClickListener(v -> togglePlayback());
        
        btnExport.setOnClickListener(v -> performExport());
    }

    private void addUriToPlaylist(Uri uri) {
        if (uri != null) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                // Ignore
            }
            playlist.add(new AudioItem(uri));
        }
    }

    private void updatePlaylistUI() {
        String label = playlist.isEmpty() ? "SOUNDSCAPES" : "SOUNDSCAPES (" + playlist.size() + ")";
        tvPlaylistHeader.setText(label);
        fileAdapter.notifyDataSetChanged();
        layoutEmptyState.setVisibility(playlist.isEmpty() ? View.VISIBLE : View.GONE);
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
            
            // Only validate playlist if in Local Mode
            if (!isExternalMode) {
                List<Uri> selectedUris = new ArrayList<>();
                for (AudioItem item : playlist) {
                    if (item.isSelected) selectedUris.add(item.uri);
                }

                if (selectedUris.isEmpty()) {
                    if (playlist.isEmpty()) {
                        Toast.makeText(this, "Please add audio files first", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Please select at least one track to play", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                
                ArrayList<String> uriStrings = new ArrayList<>();
                for (Uri u : selectedUris) {
                    uriStrings.add(u.toString());
                }
                serviceIntent.putStringArrayListExtra(ChaosService.EXTRA_URI_LIST, uriStrings);
            }

            // Get Ranges
            List<Float> playRange = sliderPlayDur.getValues();
            List<Float> pauseRange = sliderPauseDur.getValues();
            List<Float> volRange = sliderVolRange.getValues();

            serviceIntent.setAction(ChaosService.ACTION_START);
            serviceIntent.putExtra(ChaosService.EXTRA_IS_EXTERNAL_MODE, isExternalMode);
            serviceIntent.putExtra(ChaosService.EXTRA_DURATION_MINS, (int) sliderTimer.getValue());
            
            // Pass Play/Pause ranges in seconds
            serviceIntent.putExtra(ChaosService.EXTRA_PLAY_MIN_SEC, Math.round(playRange.get(0)));
            serviceIntent.putExtra(ChaosService.EXTRA_PLAY_MAX_SEC, Math.round(playRange.get(1)));
            serviceIntent.putExtra(ChaosService.EXTRA_PAUSE_MIN_SEC, Math.round(pauseRange.get(0)));
            serviceIntent.putExtra(ChaosService.EXTRA_PAUSE_MAX_SEC, Math.round(pauseRange.get(1)));
            
            // Pass Volume config
            serviceIntent.putExtra(ChaosService.EXTRA_MIN_VOL, volRange.get(0) / 100f);
            serviceIntent.putExtra(ChaosService.EXTRA_MAX_VOL, volRange.get(1) / 100f);
            serviceIntent.putExtra(ChaosService.EXTRA_VOL_FREQ, (int) sliderVolFreq.getValue());

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
            setControlsEnabled(false);
        } else {
            btnAction.setText(R.string.btn_start);
            btnAction.setIconResource(android.R.drawable.ic_media_play);
            btnAction.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.primary_color));
            setControlsEnabled(true);
        }
    }
    
    private void setControlsEnabled(boolean enabled) {
        sliderPlayDur.setEnabled(enabled);
        sliderPauseDur.setEnabled(enabled);
        sliderTimer.setEnabled(enabled);
        sliderVolRange.setEnabled(enabled);
        sliderVolFreq.setEnabled(enabled);
        
        // Only enable toggle if stopped
        for(int i = 0; i < toggleMode.getChildCount(); i++) {
            toggleMode.getChildAt(i).setEnabled(enabled);
        }
        
        // Export only enabled in Local mode
        if (!isExternalMode) {
             btnExport.setEnabled(enabled);
        }
    }

    private void performExport() {
        if (isExternalMode) return; 

        Uri source = null;
        for (AudioItem item : playlist) {
            if (item.isSelected) {
                source = item.uri;
                break;
            }
        }

        if (source == null) {
            if (playlist.isEmpty()) {
                Toast.makeText(this, "No audio to export", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Select a track to export", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        int duration = (int) sliderTimer.getValue();
        
        List<Float> playRange = sliderPlayDur.getValues();
        List<Float> pauseRange = sliderPauseDur.getValues();
        List<Float> volRange = sliderVolRange.getValues();
        
        int minPlay = Math.round(playRange.get(0));
        int maxPlay = Math.round(playRange.get(1));
        int minPause = Math.round(pauseRange.get(0));
        int maxPause = Math.round(pauseRange.get(1));
        
        float minVol = volRange.get(0) / 100f;
        float maxVol = volRange.get(1) / 100f;
        int volFreq = (int) sliderVolFreq.getValue();

        if (duration == 0) duration = 10; 

        Toast.makeText(this, R.string.export_start, Toast.LENGTH_LONG).show();
        btnExport.setEnabled(false);

        AudioExporter.exportChaosAudio(this, source, duration, minPlay, maxPlay, minPause, maxPause, minVol, maxVol, volFreq, new AudioExporter.ExportCallback() {
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
        private final ArrayList<AudioItem> items;

        FileAdapter(ArrayList<AudioItem> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file, parent, false);
            return new FileViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
            AudioItem item = items.get(position);
            holder.tvFileName.setText(getFileName(item.uri));
            holder.chkSelected.setOnCheckedChangeListener(null);
            holder.chkSelected.setChecked(item.isSelected);
            holder.chkSelected.setOnCheckedChangeListener((buttonView, isChecked) -> item.isSelected = isChecked);
            holder.itemView.setOnClickListener(v -> holder.chkSelected.toggle());
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class FileViewHolder extends RecyclerView.ViewHolder {
            TextView tvFileName;
            CheckBox chkSelected;
            FileViewHolder(View itemView) {
                super(itemView);
                tvFileName = itemView.findViewById(R.id.tvFileName);
                chkSelected = itemView.findViewById(R.id.chkSelected);
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