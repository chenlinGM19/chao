package com.camulator.pro;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.location.Address;
import android.location.Geocoder;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.ResolutionSelector;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private ExecutorService cameraExecutor;
    
    private PreviewView viewFinder;
    private ImageView ivPreviewOverlay;
    private View vShutterFlash; // Flash animation view
    private CurveView curveView;
    private View presetEditorContainer, controlsContainer;
    private View maskTop, maskBottom;
    private LinearLayout focalLengthContainer, filterContainer, llPresetList;
    private Button btnRatio;
    private SeekBar sbSaturation;
    private ImageView ivLastImage;

    private FusedLocationProviderClient fusedLocationClient;
    private Camera camera;
    private Vibrator vibrator;

    private ImageUtils.FilterType currentFilter = ImageUtils.FilterType.NONE;
    private float currentSaturation = 0f;
    private ImageUtils.WatermarkConfig wmConfig = new ImageUtils.WatermarkConfig();
    
    private List<ImageUtils.CurvePreset> loadedPresets = new ArrayList<>();
    private ImageUtils.CurvePreset currentPreset = new ImageUtils.CurvePreset();
    
    private int aspectRatioMode = 0; // 0=4:3, 1=16:9, 2=1:1
    
    private static final float FULL_FRAME_DIAGONAL = 43.2666f;
    private float baseEquivalentFocalLength = 24.0f; 
    private static final int[] FOCAL_LENGTHS = {16, 24, 28, 35, 50, 75, 85, 105, 135};
    private int selectedFocalLength = 24;
    
    private boolean isFrozen = false;
    private Bitmap frozenBitmap = null;
    private Bitmap reuseBitmap;
    private Matrix previewTransformMatrix = new Matrix();

    private ActivityResultLauncher<Intent> exportLauncher;
    private ActivityResultLauncher<Intent> importLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Immersive Full Screen
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().getInsetsController().hide(WindowInsets.Type.statusBars());
        }
        
        setContentView(R.layout.activity_main);

        // Bind Views
        viewFinder = findViewById(R.id.viewFinder);
        ivPreviewOverlay = findViewById(R.id.ivPreviewOverlay);
        vShutterFlash = findViewById(R.id.vShutterFlash);
        curveView = findViewById(R.id.curveView);
        presetEditorContainer = findViewById(R.id.presetEditorContainer);
        controlsContainer = findViewById(R.id.controlsContainer);
        maskTop = findViewById(R.id.maskTop);
        maskBottom = findViewById(R.id.maskBottom);
        focalLengthContainer = findViewById(R.id.focalLengthContainer);
        filterContainer = findViewById(R.id.filterContainer);
        llPresetList = findViewById(R.id.llPresetList);
        btnRatio = findViewById(R.id.btnRatio);
        sbSaturation = findViewById(R.id.sbSaturation);
        ivLastImage = findViewById(R.id.ivLastImage);
        
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        curveView.setOnCurveChangeListener(this::triggerPreviewUpdate);

        // Hide raw preview, show overlay
        viewFinder.setVisibility(View.INVISIBLE); 
        // Hack: Keep it attached for CameraX lifecycle but invisible
        viewFinder.setAlpha(0f);
        viewFinder.setVisibility(View.VISIBLE);

        loadDefaultPresets();
        setupControls();
        setupFocalLengthButtons();
        setupFilterButtons();
        refreshPresetListUI();
        setupImportExport();
        cameraExecutor = Executors.newSingleThreadExecutor();

        if (isCameraPermissionGranted()) {
            startCamera();
            checkAndRequestOptionalPermissions();
            updateLocation();
        } else {
            requestPermissions();
        }
    }
    
    private void performHaptic() {
        if (vibrator != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
            } else {
                vibrator.vibrate(20);
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                
                int targetRatio = (aspectRatioMode == 1) ? AspectRatio.RATIO_16_9 : AspectRatio.RATIO_4_3;

                // 1. Preview
                Preview preview = new Preview.Builder()
                        .setTargetAspectRatio(targetRatio)
                        .build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                // 2. ImageAnalysis (OPTIMIZED)
                // Limit resolution to 720p (approx) for stable 30FPS filtering on mid-range devices
                ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(targetRatio == AspectRatio.RATIO_16_9 ? AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY : AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(new Size(720, 1280), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER))
                        .build();

                imageAnalysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                // 3. ImageCapture (Full Quality)
                imageCapture = new ImageCapture.Builder()
                        .setTargetAspectRatio(targetRatio)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis, imageCapture);
                
                calculateBaseFocalLength();
                applyFocalLengthZoom(selectedFocalLength);

            } catch (ExecutionException | InterruptedException e) {
                Log.e("Camera", "Binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // High-performance loop
    private void analyzeImage(@NonNull ImageProxy image) {
        if (isFrozen) {
            image.close();
            return;
        }

        int rotationDegrees = image.getImageInfo().getRotationDegrees();
        int width = image.getWidth();
        int height = image.getHeight();
        
        // Re-allocate only if size changes
        if (reuseBitmap == null || reuseBitmap.getWidth() != width || reuseBitmap.getHeight() != height) {
            reuseBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        
        reuseBitmap.copyPixelsFromBuffer(image.getPlanes()[0].getBuffer());
        image.close();

        // Apply Native Java Filters (Zero allocation inside logic)
        ImageUtils.applyPreviewEffects(reuseBitmap, currentFilter, currentSaturation, 
            curveView.getLutRGB(), curveView.getLutR(), curveView.getLutG(), curveView.getLutB());

        runOnUiThread(() -> {
            if (!isFrozen) {
                ivPreviewOverlay.setImageBitmap(reuseBitmap);
                
                // Handle Rotation via View Transform (Much faster than rotating bitmap bits)
                if (ivPreviewOverlay.getRotation() != rotationDegrees) {
                     ivPreviewOverlay.setRotation(rotationDegrees);
                }
                
                // Scale Adjustment to fill screen properly based on rotation
                if (rotationDegrees == 90 || rotationDegrees == 270) {
                     float scale = (float) ivPreviewOverlay.getWidth() / ivPreviewOverlay.getHeight();
                     // Simple check: if view is portrait but image is landscape rotated, we might need to scale up
                     // CameraX previewView handles this complex logic, but for overlay we keep it simple:
                     // centerCrop typically handles filling.
                }
                
                if (aspectRatioMode == 2) ivPreviewOverlay.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                else ivPreviewOverlay.setScaleType(ImageView.ScaleType.CENTER_CROP);
            }
        });
    }

    private void takePhoto() {
        if (imageCapture == null) return;
        performHaptic();
        
        // Shutter Animation
        vShutterFlash.setVisibility(View.VISIBLE);
        vShutterFlash.setAlpha(1f);
        vShutterFlash.animate().alpha(0f).setDuration(150).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                vShutterFlash.setVisibility(View.GONE);
            }
        }).start();
        
        // Capture Config
        int[] lutRGB = curveView.getLutRGB();
        int[] lutR = curveView.getLutR();
        int[] lutG = curveView.getLutG();
        int[] lutB = curveView.getLutB();
        boolean crop = (aspectRatioMode == 2);
        float sat = currentSaturation;
        ImageUtils.FilterType filter = currentFilter;
        // Clone config to avoid race conditions if user changes settings while saving
        ImageUtils.WatermarkConfig wm = wmConfig.clone(); 

        imageCapture.takePicture(ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                // Offload processing
                cameraExecutor.execute(() -> {
                    try {
                        // 1. Convert
                        Bitmap bitmap = ImageUtils.imageProxyToBitmap(image);
                        image.close(); // Close ASAP
                        
                        if (bitmap != null) {
                            // 2. Process (Filter + Curve + Watermark)
                            Bitmap processed = ImageUtils.processImage(bitmap, filter, sat,
                                    lutRGB, lutR, lutG, lutB, wm, crop);
                                    
                            // 3. Save
                            Uri savedUri = saveImage(processed);
                            
                            // 4. Update UI
                            if (savedUri != null) {
                                runOnUiThread(() -> {
                                    ivLastImage.setImageBitmap(processed);
                                    Toast.makeText(MainActivity.this, "Saved to Gallery", Toast.LENGTH_SHORT).show();
                                });
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Toast.makeText(MainActivity.this, "Capture Failed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ... (Standard boilerplates for Permissions, Location, Setup below) ...
    // Keeping existing helper methods but ensuring they are robust

    private void triggerPreviewUpdate() {
        if (isFrozen && frozenBitmap != null) {
            updateFreezeFrame();
        }
    }
    
    private void loadDefaultPresets() {
        ImageUtils.CurvePreset pDefault = new ImageUtils.CurvePreset();
        pDefault.name = "Reset";
        loadedPresets.add(pDefault);
    }

    private void setupImportExport() {
        exportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) saveXmpToFile(uri);
                }
            }
        );

        importLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) importXmpFromFile(uri);
                }
            }
        );
    }
    
    private void saveXmpToFile(Uri uri) {
        captureCurrentStateToPreset(currentPreset);
        String xmp = currentPreset.toXmp();
        try {
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os != null) {
                os.write(xmp.getBytes());
                os.close();
                Toast.makeText(this, "Exported: " + currentPreset.name, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Export Failed", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void importXmpFromFile(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            is.close();
            ImageUtils.CurvePreset newPreset = ImageUtils.CurvePreset.fromXmp(sb.toString());
            loadedPresets.add(newPreset);
            refreshPresetListUI();
            applyPreset(newPreset);
            Toast.makeText(this, "Imported: " + newPreset.name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Invalid XMP File", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupControls() {
        findViewById(R.id.btnCapture).setOnClickListener(v -> takePhoto());
        
        findViewById(R.id.btnGallery).setOnClickListener(v -> {
            performHaptic();
            startActivity(new Intent(this, GalleryActivity.class));
        });
        
        btnRatio.setOnClickListener(v -> {
            performHaptic();
            aspectRatioMode = (aspectRatioMode + 1) % 3;
            updateAspectRatioUI();
            startCamera(); 
        });
        
        findViewById(R.id.btnEditPreset).setOnClickListener(v -> { performHaptic(); enterEditorMode(); });
        findViewById(R.id.btnCloseEditor).setOnClickListener(v -> { performHaptic(); exitEditorMode(); });

        sbSaturation.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentSaturation = progress - 100;
                triggerPreviewUpdate();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        findViewById(R.id.btnResetCurve).setOnClickListener(v -> {
            performHaptic();
            currentSaturation = 0;
            sbSaturation.setProgress(100);
            curveView.resetCurves();
            triggerPreviewUpdate();
        });
        
        findViewById(R.id.btnSavePreset).setOnClickListener(v -> showSavePresetDialog());
        
        findViewById(R.id.btnCurveRGB).setOnClickListener(v -> curveView.setChannel(CurveView.Channel.RGB));
        findViewById(R.id.btnCurveR).setOnClickListener(v -> curveView.setChannel(CurveView.Channel.RED));
        findViewById(R.id.btnCurveG).setOnClickListener(v -> curveView.setChannel(CurveView.Channel.GREEN));
        findViewById(R.id.btnCurveB).setOnClickListener(v -> curveView.setChannel(CurveView.Channel.BLUE));
        
        findViewById(R.id.btnWatermark).setOnClickListener(v -> { performHaptic(); showWatermarkSettingsDialog(); });
        
        findViewById(R.id.btnExportXmp).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/xml");
            intent.putExtra(Intent.EXTRA_TITLE, currentPreset.name + ".xmp");
            exportLauncher.launch(intent);
        });
        
        findViewById(R.id.btnImportXmp).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*"); 
            importLauncher.launch(intent);
        });
    }
    
    private void showSavePresetDialog() {
        EditText input = new EditText(this);
        input.setHint("Preset Name");
        input.setTextColor(Color.BLACK);
        
        new AlertDialog.Builder(this)
            .setTitle("Save Preset")
            .setView(input)
            .setPositiveButton("Save", (dialog, which) -> {
                String name = input.getText().toString();
                if (!name.isEmpty()) {
                    ImageUtils.CurvePreset newPreset = new ImageUtils.CurvePreset();
                    captureCurrentStateToPreset(newPreset);
                    newPreset.name = name;
                    loadedPresets.add(newPreset);
                    refreshPresetListUI();
                    Toast.makeText(this, "Saved " + name, Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    private void captureCurrentStateToPreset(ImageUtils.CurvePreset preset) {
        preset.rgb = curveView.getPoints(CurveView.Channel.RGB);
        preset.r = curveView.getPoints(CurveView.Channel.RED);
        preset.g = curveView.getPoints(CurveView.Channel.GREEN);
        preset.b = curveView.getPoints(CurveView.Channel.BLUE);
        preset.saturation = currentSaturation;
    }
    
    private void refreshPresetListUI() {
        llPresetList.removeAllViews();
        for (ImageUtils.CurvePreset preset : loadedPresets) {
            Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
            btn.setText(preset.name);
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(12);
            btn.setBackgroundResource(android.R.drawable.btn_default_small);
            btn.getBackground().setTint(Color.DKGRAY);
            
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 16, 0);
            btn.setLayoutParams(params);
            
            btn.setOnClickListener(v -> applyPreset(preset));
            llPresetList.addView(btn);
        }
    }
    
    private void applyPreset(ImageUtils.CurvePreset preset) {
        performHaptic();
        currentPreset = preset;
        curveView.setPoints(CurveView.Channel.RGB, preset.rgb);
        curveView.setPoints(CurveView.Channel.RED, preset.r);
        curveView.setPoints(CurveView.Channel.GREEN, preset.g);
        curveView.setPoints(CurveView.Channel.BLUE, preset.b);
        currentSaturation = preset.saturation;
        sbSaturation.setProgress((int)(currentSaturation + 100));
        triggerPreviewUpdate();
    }
    
    private void enterEditorMode() {
        // Create a copy of the current preview
        if (reuseBitmap != null) {
            isFrozen = true;
            // Need a clean copy to apply filters onto repeatedly
            frozenBitmap = reuseBitmap.copy(Bitmap.Config.ARGB_8888, true);
            presetEditorContainer.setVisibility(View.VISIBLE);
            controlsContainer.setVisibility(View.GONE);
            updateFreezeFrame();
        } else {
            Toast.makeText(this, "Wait for stream...", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void exitEditorMode() {
        presetEditorContainer.setVisibility(View.GONE);
        controlsContainer.setVisibility(View.VISIBLE);
        frozenBitmap = null;
        isFrozen = false; 
    }
    
    private void updateFreezeFrame() {
        if (frozenBitmap == null) return;
        cameraExecutor.execute(() -> {
             // Reset from raw frozen state or keep applying? 
             // Logic: Filter loop applies to "source", writes to "dest".
             // Here we use frozenBitmap as source, copy it to a temp, apply, display.
             Bitmap temp = frozenBitmap.copy(Bitmap.Config.ARGB_8888, true);
             ImageUtils.applyPreviewEffects(temp, currentFilter, currentSaturation, 
                curveView.getLutRGB(), curveView.getLutR(), curveView.getLutG(), curveView.getLutB());
             runOnUiThread(() -> ivPreviewOverlay.setImageBitmap(temp));
        });
    }
    
    private void updateAspectRatioUI() {
        if (aspectRatioMode == 0) { 
            btnRatio.setText("4:3");
            maskTop.setVisibility(View.GONE);
            maskBottom.setVisibility(View.GONE);
        } else if (aspectRatioMode == 1) { 
            btnRatio.setText("16:9");
            maskTop.setVisibility(View.GONE);
            maskBottom.setVisibility(View.GONE);
        } else { 
            btnRatio.setText("1:1");
            maskTop.setVisibility(View.VISIBLE);
            maskBottom.setVisibility(View.VISIBLE);
        }
    }

    private void setupFilterButtons() {
        filterContainer.removeAllViews();
        for (ImageUtils.FilterType type : ImageUtils.FilterType.values()) {
            Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
            btn.setText(type.name().replace("_", " "));
            btn.setTextColor(type == currentFilter ? Color.YELLOW : Color.WHITE);
            btn.setTextSize(12);
            btn.setBackgroundColor(Color.TRANSPARENT);
            btn.setOnClickListener(v -> {
                performHaptic();
                currentFilter = type;
                setupFilterButtons(); 
                triggerPreviewUpdate();
            });
            filterContainer.addView(btn);
        }
    }

    private void showWatermarkSettingsDialog() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(R.layout.dialog_watermark_settings);

        Switch swEnabled = dialog.findViewById(R.id.swWatermarkEnabled);
        Switch swLogo = dialog.findViewById(R.id.swShowLogo);
        EditText etText = dialog.findViewById(R.id.etCustomText);
        Switch swTime = dialog.findViewById(R.id.swShowTime);
        Switch swCoords = dialog.findViewById(R.id.swShowCoords);
        Switch swPlace = dialog.findViewById(R.id.swShowPlace);
        RadioGroup rgSize = dialog.findViewById(R.id.rgTextSize);
        RadioGroup rgPos = dialog.findViewById(R.id.rgPosition);
        RadioGroup rgStyle = dialog.findViewById(R.id.rgStyle);
        RadioGroup rgBg = dialog.findViewById(R.id.rgBgColor);

        if (swEnabled != null) {
            swEnabled.setChecked(wmConfig.enabled);
            swLogo.setChecked(wmConfig.showLogo);
            etText.setText(wmConfig.customText);
            swTime.setChecked(wmConfig.showTime);
            swCoords.setChecked(wmConfig.showCoords);
            swPlace.setChecked(wmConfig.showPlace);

            switch (wmConfig.textSize) {
                case 0: rgSize.check(R.id.rbSmall); break;
                case 1: rgSize.check(R.id.rbMedium); break;
                case 2: rgSize.check(R.id.rbLarge); break;
            }
            switch (wmConfig.position) {
                case 0: rgPos.check(R.id.rbPosLeft); break;
                case 1: rgPos.check(R.id.rbPosCenter); break;
                case 2: rgPos.check(R.id.rbPosRight); break;
            }
            
            if (wmConfig.styleFooter) rgStyle.check(R.id.rbStyleFooter);
            else rgStyle.check(R.id.rbStyleOverlay);
            
            if (wmConfig.backgroundColor == Color.BLACK) rgBg.check(R.id.rbBgBlack);
            else rgBg.check(R.id.rbBgWhite);

            dialog.setOnDismissListener(d -> {
                wmConfig.enabled = swEnabled.isChecked();
                wmConfig.showLogo = swLogo.isChecked();
                wmConfig.customText = etText.getText().toString();
                wmConfig.showTime = swTime.isChecked();
                wmConfig.showCoords = swCoords.isChecked();
                wmConfig.showPlace = swPlace.isChecked();

                int selectedId = rgSize.getCheckedRadioButtonId();
                if (selectedId == R.id.rbSmall) wmConfig.textSize = 0;
                else if (selectedId == R.id.rbMedium) wmConfig.textSize = 1;
                else if (selectedId == R.id.rbLarge) wmConfig.textSize = 2;
                
                int posId = rgPos.getCheckedRadioButtonId();
                if (posId == R.id.rbPosLeft) wmConfig.position = 0;
                else if (posId == R.id.rbPosCenter) wmConfig.position = 1;
                else if (posId == R.id.rbPosRight) wmConfig.position = 2;
                
                wmConfig.styleFooter = (rgStyle.getCheckedRadioButtonId() == R.id.rbStyleFooter);
                
                if (rgBg.getCheckedRadioButtonId() == R.id.rbBgBlack) {
                    wmConfig.backgroundColor = Color.BLACK;
                    wmConfig.textColor = Color.WHITE;
                } else {
                    wmConfig.backgroundColor = Color.WHITE;
                    wmConfig.textColor = Color.BLACK;
                }
                
                if (wmConfig.showPlace && (wmConfig.placeName == null || wmConfig.placeName.isEmpty())) {
                    updateLocation();
                }
                Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show();
            });
        }
        dialog.show();
    }

    private void setupFocalLengthButtons() {
        focalLengthContainer.removeAllViews();
        for (int focalLength : FOCAL_LENGTHS) {
            Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
            btn.setText(focalLength + "mm");
            btn.setTextColor(focalLength == selectedFocalLength ? Color.YELLOW : Color.WHITE);
            btn.setTextSize(13);
            btn.setBackgroundColor(Color.TRANSPARENT);
            btn.setOnClickListener(v -> {
                performHaptic();
                selectedFocalLength = focalLength;
                applyFocalLengthZoom(focalLength);
                updateFocalLengthUI();
            });
            focalLengthContainer.addView(btn);
        }
    }

    private void updateFocalLengthUI() {
        for (int i = 0; i < focalLengthContainer.getChildCount(); i++) {
            Button btn = (Button) focalLengthContainer.getChildAt(i);
            int fl = FOCAL_LENGTHS[i];
            if (fl == selectedFocalLength) {
                btn.setTextColor(Color.YELLOW);
                btn.setTypeface(null, android.graphics.Typeface.BOLD);
            } else {
                btn.setTextColor(Color.WHITE);
                btn.setTypeface(null, android.graphics.Typeface.NORMAL);
            }
        }
    }

    private void applyFocalLengthZoom(int targetEquivalentMm) {
        if (camera == null) return;
        ZoomState zoomState = camera.getCameraInfo().getZoomState().getValue();
        if (zoomState == null) return;
        float targetRatio = targetEquivalentMm / baseEquivalentFocalLength;
        float min = zoomState.getMinZoomRatio();
        float max = zoomState.getMaxZoomRatio();
        float finalRatio = Math.max(min, Math.min(max, targetRatio));
        camera.getCameraControl().setZoomRatio(finalRatio);
    }

    private void calculateBaseFocalLength() {
        try {
            Camera2CameraInfo camera2Info = Camera2CameraInfo.from(camera.getCameraInfo());
            android.util.SizeF sensorSize = camera2Info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float[] focalLengths = camera2Info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (sensorSize != null && focalLengths != null && focalLengths.length > 0) {
                float w = sensorSize.getWidth();
                float h = sensorSize.getHeight();
                float sensorDiagonal = (float) Math.sqrt(w * w + h * h);
                float cropFactor = FULL_FRAME_DIAGONAL / sensorDiagonal;
                baseEquivalentFocalLength = focalLengths[0] * cropFactor;
            }
        } catch (Exception e) { baseEquivalentFocalLength = 24.0f; }
    }
    
    private Uri saveImage(Bitmap bitmap) {
        String filename = "CAM_" + System.currentTimeMillis() + ".jpg";
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camulator");
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
        try {
            if (uri != null) {
                OutputStream stream = getContentResolver().openOutputStream(uri);
                if (stream != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 97, stream);
                    stream.close();
                }
                if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    contentValues.clear();
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, contentValues, null, null);
                }
                return uri;
            }
        } catch (Exception e) {}
        return null;
    }
    
    private void updateLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
                if (location != null) {
                    wmConfig.latLng = String.format(Locale.US, "%.4f, %.4f", location.getLatitude(), location.getLongitude());
                    cameraExecutor.execute(() -> {
                        Geocoder geocoder = new Geocoder(MainActivity.this, Locale.getDefault());
                        try {
                            List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                            if (addresses != null && !addresses.isEmpty()) {
                                Address addr = addresses.get(0);
                                wmConfig.placeName = getFormattedPlace(addr);
                            }
                        } catch (IOException e) {}
                    });
                }
            });
        }
    }
    
    private String getFormattedPlace(Address addr) {
        String locality = addr.getLocality();
        String subAdmin = addr.getSubAdminArea();
        String country = addr.getCountryName();
        if (locality != null) return locality + (country != null ? ", " + country : "");
        if (subAdmin != null) return subAdmin + (country != null ? ", " + country : "");
        return country != null ? country : "";
    }

    private boolean isCameraPermissionGranted() { return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; }

    private void requestPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
        } else {
             permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), 10);
    }
    
    private void checkAndRequestOptionalPermissions() {
        // Silent check for optional features
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
             ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 11);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 10) {
            if (isCameraPermissionGranted()) {
                startCamera();
                updateLocation();
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show();
            }
        }
    }
}