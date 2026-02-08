package com.sleepchaos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.media.session.MediaButtonReceiver;
import java.util.ArrayList;
import java.util.Random;

public class ChaosService extends Service implements AudioManager.OnAudioFocusChangeListener {

    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String ACTION_PAUSE = "ACTION_PAUSE";
    public static final String ACTION_RESUME = "ACTION_RESUME";
    
    public static final String EXTRA_URI_LIST = "EXTRA_URI_LIST";
    public static final String EXTRA_DURATION_MINS = "EXTRA_DURATION_MINS";
    public static final String EXTRA_IS_EXTERNAL_MODE = "EXTRA_IS_EXTERNAL_MODE";
    
    // New Range Extras
    public static final String EXTRA_PLAY_MIN_SEC = "EXTRA_PLAY_MIN_SEC";
    public static final String EXTRA_PLAY_MAX_SEC = "EXTRA_PLAY_MAX_SEC";
    public static final String EXTRA_PAUSE_MIN_SEC = "EXTRA_PAUSE_MIN_SEC";
    public static final String EXTRA_PAUSE_MAX_SEC = "EXTRA_PAUSE_MAX_SEC";
    
    public static final String EXTRA_MIN_VOL = "EXTRA_MIN_VOL";
    public static final String EXTRA_MAX_VOL = "EXTRA_MAX_VOL";
    public static final String EXTRA_VOL_FREQ = "EXTRA_VOL_FREQ";
    
    public static final String CHANNEL_ID = "ChaosServiceChannel";

    private MediaPlayer mediaPlayer;
    private Handler chaosHandler;
    private Random random;
    
    // Timers
    private CountDownTimer sleepTimer; // Overall session timer
    private CountDownTimer phaseTimer; // Current Play/Silence phase timer
    
    // State Tracking
    private boolean isServiceRunning = false;
    private boolean isManuallyPaused = false;
    private boolean isPausedByFocus = false;
    private boolean isInIntermittentPause = false; // "Silence Phase"
    private boolean isExternalMode = false;
    
    // Resume State Tracking
    private long timeRemainingInPhase = 0;
    private long timeRemainingInSession = 0;
    
    private float currentVolume = 0.5f;
    private float minVolume = 0.2f;
    private float maxVolume = 0.8f;
    private int volumeFreq = 5;
    
    private int minPlaySec = 10;
    private int maxPlaySec = 60;
    private int minPauseSec = 5;
    private int maxPauseSec = 20;
    
    private int originalStreamVolume = -1;
    
    private ArrayList<Uri> playlist = new ArrayList<>();
    private int currentTrackIndex = 0;
    
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private PowerManager.WakeLock wakeLock;
    private MediaSessionCompat mediaSession;
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;
    
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pauseChaos();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        chaosHandler = new Handler(Looper.getMainLooper());
        random = new Random();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        notificationManager = getSystemService(NotificationManager.class);
        
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepChaos:WakeLock");
        
        setupMediaSession();
        createNotificationChannel();
        
        // Register Noisy Receiver
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }
    
    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, "ChaosMediaSession");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() { resumeChaos(); }
            @Override
            public void onPause() { pauseChaos(); }
            @Override
            public void onStop() { stopChaos(); }
        });
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MediaButtonReceiver.handleIntent(mediaSession, intent);
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                ArrayList<String> uriStrings = intent.getStringArrayListExtra(EXTRA_URI_LIST);
                int durationMins = intent.getIntExtra(EXTRA_DURATION_MINS, 30);
                isExternalMode = intent.getBooleanExtra(EXTRA_IS_EXTERNAL_MODE, false);
                
                minPlaySec = intent.getIntExtra(EXTRA_PLAY_MIN_SEC, 10);
                maxPlaySec = intent.getIntExtra(EXTRA_PLAY_MAX_SEC, 60);
                minPauseSec = intent.getIntExtra(EXTRA_PAUSE_MIN_SEC, 5);
                maxPauseSec = intent.getIntExtra(EXTRA_PAUSE_MAX_SEC, 20);

                minVolume = intent.getFloatExtra(EXTRA_MIN_VOL, 0.2f);
                maxVolume = intent.getFloatExtra(EXTRA_MAX_VOL, 0.8f);
                volumeFreq = intent.getIntExtra(EXTRA_VOL_FREQ, 5);
                
                // Safety checks
                if (minPlaySec > maxPlaySec) { int t = minPlaySec; minPlaySec = maxPlaySec; maxPlaySec = t; }
                if (minPauseSec > maxPauseSec) { int t = minPauseSec; minPauseSec = maxPauseSec; maxPauseSec = t; }
                if (minVolume > maxVolume) { float t = minVolume; minVolume = maxVolume; maxVolume = t; }
                
                currentVolume = minVolume + (maxVolume - minVolume) / 2;
                
                // Handle Mode Specific Setup
                if (!isExternalMode && uriStrings != null && !uriStrings.isEmpty()) {
                    playlist.clear();
                    for (String s : uriStrings) {
                        playlist.add(Uri.parse(s));
                    }
                    if (requestAudioFocus()) {
                        startChaos(durationMins);
                    }
                } else if (isExternalMode) {
                    // For external mode, we start immediately. Focus will be handled in phases.
                    // Save initial volume
                    originalStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    startChaos(durationMins);
                }
            } else if (ACTION_PAUSE.equals(action)) {
                pauseChaos();
            } else if (ACTION_RESUME.equals(action)) {
                resumeChaos();
            } else if (ACTION_STOP.equals(action)) {
                stopChaos();
            }
        }
        return START_NOT_STICKY;
    }

    private boolean requestAudioFocus() {
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener(this)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .build();
            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }
    
    private void abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(this);
        }
    }

    private void startChaos(int durationMins) {
        if (isServiceRunning) stopChaos();
        isServiceRunning = true;
        isManuallyPaused = false;
        currentTrackIndex = 0;
        
        if (!wakeLock.isHeld()) wakeLock.acquire(4 * 60 * 60 * 1000L); 

        initNotificationBuilder(true);
        startForeground(1, notificationBuilder.build());
        
        updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING);
        
        startPlaybackPhase();

        if (durationMins > 0) {
            long millis = durationMins * 60 * 1000L;
            timeRemainingInSession = millis;
            startSessionTimer(millis);
        }
    }
    
    private void startSessionTimer(long millis) {
        if (sleepTimer != null) sleepTimer.cancel();
        sleepTimer = new CountDownTimer(millis, 1000) {
            @Override
            public void onTick(long l) {
                timeRemainingInSession = l;
            }
            @Override
            public void onFinish() {
                stopChaos();
            }
        }.start();
    }

    private void pauseChaos() {
        if (!isServiceRunning || isManuallyPaused) return;
        isManuallyPaused = true;
        
        // Local Mode Pause
        if (!isExternalMode && mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
        
        // External Mode Pause - We must Request Focus to ensure silence if user manually pauses
        if (isExternalMode) {
             requestAudioFocus(); 
             // Restore volume on manual pause so user has control back
             if (originalStreamVolume != -1) {
                 audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalStreamVolume, 0);
             }
        }
        
        if (phaseTimer != null) {
            phaseTimer.cancel(); 
        }
        if (sleepTimer != null) {
            sleepTimer.cancel(); 
        }
        
        chaosHandler.removeCallbacksAndMessages(null);
        
        if (wakeLock.isHeld()) wakeLock.release();
        
        updateNotificationManuallyPaused();
        updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED);
    }
    
    private void resumeChaos() {
        if (!isServiceRunning || !isManuallyPaused) return;
        
        isManuallyPaused = false;
        if (!wakeLock.isHeld()) wakeLock.acquire(4 * 60 * 60 * 1000L);
        
        if (timeRemainingInSession > 0) {
            startSessionTimer(timeRemainingInSession);
        }
        
        // Determine Resume Action based on current phase
        if (isInIntermittentPause) {
             // We were in silence
             startPhaseTimer(timeRemainingInPhase, false);
             updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING);
             // External: Ensure focus is held to keep silence
             if (isExternalMode) requestAudioFocus();
        } else {
             // We were playing
             if (isExternalMode) {
                 // Trigger External Play
                 abandonAudioFocus();
                 sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
             } else {
                 if (requestAudioFocus()) {
                     if (mediaPlayer != null) mediaPlayer.start();
                     else initAndPlayCurrentTrack();
                 }
             }
             
             startPhaseTimer(timeRemainingInPhase, true);
             scheduleVolumeDrift(timeRemainingInPhase);
             updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING);
        }
    }

    // Phase 1: Play Audio
    private void startPlaybackPhase() {
        if (!isServiceRunning || isManuallyPaused) return;
        isInIntermittentPause = false;
        
        chaosHandler.removeCallbacksAndMessages(null);

        if (isExternalMode) {
            // EXTERNAL: Release focus to let other app play, and send PLAY command
            abandonAudioFocus();
            // Send PLAY command to wake up the potential player
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
            // Start Volume Fade In
            fadeVolume(0, currentVolume, 1000, null);
        } else {
            // LOCAL
            if (mediaPlayer == null || !mediaPlayer.isPlaying()) {
                if (mediaPlayer == null) {
                    initAndPlayCurrentTrack();
                } else {
                    mediaPlayer.start();
                    fadeVolume(0, currentVolume, 1000, null);
                }
            }
        }
        
        long playDuration = getNextDuration(minPlaySec, maxPlaySec);
        scheduleVolumeDrift(playDuration);
        
        startPhaseTimer(playDuration, true);
    }
    
    // Phase 2: Pause Audio (Silence Phase)
    private void startPausePhase() {
        if (!isServiceRunning || isManuallyPaused) return;
        isInIntermittentPause = true;

        chaosHandler.removeCallbacksAndMessages(null);

        fadeVolume(currentVolume, 0.0f, 2000, () -> {
             if (isExternalMode) {
                 // EXTERNAL: Request Focus to force pause other apps
                 requestAudioFocus();
             } else {
                 // LOCAL
                 if (mediaPlayer != null && isServiceRunning && !isManuallyPaused) {
                     try { mediaPlayer.pause(); } catch (Exception e) {}
                 }
             }
        });
        
        long pauseDuration = getNextDuration(minPauseSec, maxPauseSec);
        startPhaseTimer(pauseDuration, false);
    }

    private void startPhaseTimer(long durationMs, boolean isPlayingPhase) {
        if (phaseTimer != null) phaseTimer.cancel();
        timeRemainingInPhase = durationMs;
        
        updateNotificationProgress(durationMs, durationMs, isPlayingPhase);

        phaseTimer = new CountDownTimer(durationMs, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                timeRemainingInPhase = millisUntilFinished;
                updateNotificationProgress(durationMs, millisUntilFinished, isPlayingPhase);
            }

            @Override
            public void onFinish() {
                if (!isServiceRunning || isManuallyPaused) return;
                if (isPlayingPhase) {
                    startPausePhase();
                } else {
                    startPlaybackPhase();
                }
            }
        }.start();
    }
    
    // ... Notification Update methods remain the same ... 
    
    private void updateNotificationProgress(long maxMs, long remainingMs, boolean isPlayingPhase) {
        if (notificationBuilder == null || isManuallyPaused) return;
        
        String stateTitle = isPlayingPhase ? getString(R.string.state_active) : getString(R.string.state_silence);
        long sec = remainingMs / 1000;
        String timeStr = String.format(getString(R.string.time_remaining), sec / 60, sec % 60);

        Intent pauseIntent = new Intent(this, ChaosService.class);
        pauseIntent.setAction(ACTION_PAUSE);
        PendingIntent pausePendingIntent = PendingIntent.getService(this, 2, pauseIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stopIntent = new Intent(this, ChaosService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        notificationBuilder.setContentTitle(stateTitle)
                           .setContentText(timeStr)
                           .clearActions()
                           .addAction(android.R.drawable.ic_media_pause, "Pause", pausePendingIntent)
                           .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                           .setProgress((int)(maxMs/1000), (int)(remainingMs/1000), false);
        
        notificationManager.notify(1, notificationBuilder.build());
    }
    
    private void updateNotificationManuallyPaused() {
        if (notificationBuilder == null) return;
        
        Intent resumeIntent = new Intent(this, ChaosService.class);
        resumeIntent.setAction(ACTION_RESUME);
        PendingIntent resumePendingIntent = PendingIntent.getService(this, 3, resumeIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stopIntent = new Intent(this, ChaosService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        notificationBuilder.setContentTitle("Sleep Chaos (Paused)")
                           .setContentText("Tap play to resume")
                           .setProgress(0, 0, false)
                           .clearActions()
                           .addAction(android.R.drawable.ic_media_play, "Resume", resumePendingIntent)
                           .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent);
                           
        notificationManager.notify(1, notificationBuilder.build());
    }

    private long getNextDuration(int minSec, int maxSec) {
        if (minSec >= maxSec) return minSec * 1000L;
        return (minSec + random.nextInt(maxSec - minSec + 1)) * 1000L;
    }

    private void scheduleVolumeDrift(long maxDurationAvailable) {
        if (!isServiceRunning || isInIntermittentPause || isManuallyPaused) return;
        
        float range = maxVolume - minVolume;
        float targetVol = minVolume + (random.nextFloat() * range);
        
        int freqLevel = volumeFreq;
        int baseDelay = 30000 - ((freqLevel - 1) * 3000); 
        if (baseDelay < 2000) baseDelay = 2000;
        
        int variance = baseDelay / 2;
        int fadeTime = baseDelay / 2 + random.nextInt(variance); 
        int nextDelay = fadeTime + (baseDelay / 2) + random.nextInt(variance);

        fadeVolume(currentVolume, targetVol, fadeTime, null);
        
        if (nextDelay < maxDurationAvailable) {
            chaosHandler.postDelayed(() -> scheduleVolumeDrift(maxDurationAvailable - nextDelay), nextDelay);
        }
    }

    private void initNotificationBuilder(boolean isPlaying) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        
        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_desc))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1))
                .setOngoing(true)
                .setOnlyAlertOnce(true); 
    }
    
    private void updateMediaSessionState(int state) {
        long actions = PlaybackStateCompat.ACTION_STOP | PlaybackStateCompat.ACTION_PLAY_PAUSE;
        if (state == PlaybackStateCompat.STATE_PLAYING) {
            actions |= PlaybackStateCompat.ACTION_PAUSE;
        } else {
            actions |= PlaybackStateCompat.ACTION_PLAY;
        }
        
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .setActions(actions)
                .build());
    }

    private void initAndPlayCurrentTrack() {
        if (playlist.isEmpty()) return;
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
            );
            
            Uri uri = playlist.get(currentTrackIndex);
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.setOnCompletionListener(mp -> {
                currentTrackIndex = (currentTrackIndex + 1) % playlist.size();
                initAndPlayCurrentTrack(); 
            });
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mediaPlayer.prepare();
            setLogarithmicVolume(0); 
            mediaPlayer.start();
            
            float initialVol = minVolume + (maxVolume - minVolume) / 2;
            fadeVolume(0, initialVol, 2000, null);
            
        } catch (Exception e) {
            Log.e("ChaosService", "Error playing track", e);
        }
    }
    
    private void fadeVolume(float from, float to, int durationMs, Runnable onComplete) {
        final int steps = 20;
        final long stepDelay = Math.max(20, durationMs / steps);
        final float diff = to - from;

        new Thread(() -> {
            for (int i = 1; i <= steps; i++) {
                if (!isServiceRunning) return;
                if (isManuallyPaused) return; 
                
                float progress = (float) i / steps;
                float newVol = from + (diff * progress);
                
                chaosHandler.post(() -> {
                    if (isManuallyPaused) return;

                    if (isExternalMode) {
                        setStreamVolume(newVol);
                    } else {
                        if (mediaPlayer != null) {
                            try {
                                setLogarithmicVolume(newVol);
                            } catch (IllegalStateException e) {}
                        }
                    }
                    currentVolume = newVol;
                });

                try { Thread.sleep(stepDelay); } catch (InterruptedException e) { return; }
            }
            if (onComplete != null && !isManuallyPaused) chaosHandler.post(onComplete);
        }).start();
    }
    
    private void setLogarithmicVolume(float rawVolume) {
        if (mediaPlayer == null) return;
        float powerVol = (float) Math.pow(rawVolume, 2.5);
        if (powerVol > 1.0f) powerVol = 1.0f;
        if (powerVol < 0.0f) powerVol = 0.0f;
        mediaPlayer.setVolume(powerVol, powerVol);
    }
    
    private void setStreamVolume(float percent) {
        if (audioManager == null) return;
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int index = Math.round(percent * max);
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0); // 0 = no UI flags
        } catch (SecurityException e) {
            // Might happen in DND mode
        }
    }
    
    private void sendMediaKey(int keyCode) {
         if (audioManager == null) return;
         try {
             long eventTime = SystemClock.uptimeMillis();
             KeyEvent down = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0);
             KeyEvent up = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0);
             
             audioManager.dispatchMediaKeyEvent(down);
             audioManager.dispatchMediaKeyEvent(up);
         } catch (Exception e) {
             Log.e("ChaosService", "Failed to send media key", e);
         }
    }

    private void stopChaos() {
        isServiceRunning = false;
        isManuallyPaused = false;
        
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        
        // Restore volume if we were in external mode
        if (isExternalMode && originalStreamVolume != -1) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalStreamVolume, 0);
            } catch (Exception e) {}
        }
        
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception e) { e.printStackTrace(); }
            mediaPlayer = null;
        }
        if (sleepTimer != null) {
            sleepTimer.cancel();
            sleepTimer = null;
        }
        if (phaseTimer != null) {
            phaseTimer.cancel();
            phaseTimer = null;
        }
        
        abandonAudioFocus();
        
        try {
            unregisterReceiver(noisyReceiver);
        } catch (IllegalArgumentException e) {
            // ignore
        }
        
        updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED);
        mediaSession.setActive(false);
        mediaSession.release();
        
        chaosHandler.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Controls for the Sleep Chaos audio session");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        if (isExternalMode) {
            // In External Mode, we generally ignore focus loss because we deliberately abandon it to let others play.
            // But if we have gained focus (to silence others) and then lose it, it means someone else pressed play manually.
            if (isInIntermittentPause && (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
                // Someone else started playing during our silence phase.
                // We could fight it, or just accept it. Let's respect user intent and pause our service logic manually.
                pauseChaos();
            }
            return;
        }

        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                if (isServiceRunning) pauseChaos();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    isPausedByFocus = true;
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (isPausedByFocus && isServiceRunning && !isManuallyPaused && !isInIntermittentPause) {
                    if (mediaPlayer != null) mediaPlayer.start();
                    isPausedByFocus = false;
                } else if (isPausedByFocus && isServiceRunning && isInIntermittentPause) {
                    isPausedByFocus = false;
                }
                break;
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stopChaos();
        super.onDestroy();
    }
}