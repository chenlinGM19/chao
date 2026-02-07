package com.sleepchaos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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
import android.support.v4.media.MediaMetadataCompat;
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
    public static final String EXTRA_URI_LIST = "EXTRA_URI_LIST";
    public static final String EXTRA_DURATION_MINS = "EXTRA_DURATION_MINS";
    public static final String EXTRA_INTENSITY_MODE = "EXTRA_INTENSITY_MODE";
    
    public static final String CHANNEL_ID = "ChaosServiceChannel";
    private static final float MAX_VOLUME = 1.0f;

    private MediaPlayer mediaPlayer;
    private Handler chaosHandler;
    private Random random;
    private CountDownTimer sleepTimer;
    private boolean isRunning = false;
    private boolean isPausedByFocus = false;
    private boolean isInIntermittentPause = false; // True if we are in the "Pause" phase of chaos
    private float currentVolume = 0.5f;
    private int selectedMode = 3; // Default 3
    
    private ArrayList<Uri> playlist = new ArrayList<>();
    private int currentTrackIndex = 0;
    
    // System Services
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private PowerManager.WakeLock wakeLock;
    private MediaSessionCompat mediaSession;

    @Override
    public void onCreate() {
        super.onCreate();
        chaosHandler = new Handler(Looper.getMainLooper());
        random = new Random();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        
        // Prevent CPU from sleeping during silent intervals
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepChaos:WakeLock");
        
        setupMediaSession();
        createNotificationChannel();
    }
    
    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, "ChaosMediaSession");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() { }
            @Override
            public void onPause() { stopChaos(); }
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
                selectedMode = intent.getIntExtra(EXTRA_INTENSITY_MODE, 3);
                
                if (uriStrings != null && !uriStrings.isEmpty()) {
                    playlist.clear();
                    for (String s : uriStrings) {
                        playlist.add(Uri.parse(s));
                    }
                    if (requestAudioFocus()) {
                        startChaos(durationMins);
                    }
                }
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

    private void startChaos(int durationMins) {
        if (isRunning) stopChaos();
        isRunning = true;
        currentTrackIndex = 0;
        
        if (!wakeLock.isHeld()) wakeLock.acquire(4 * 60 * 60 * 1000L); // 4 hours safety

        updateNotification();
        updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING);
        
        // Initial Play
        startPlaybackPhase();

        if (durationMins > 0) {
            long millis = durationMins * 60 * 1000L;
            sleepTimer = new CountDownTimer(millis, 1000) {
                @Override
                public void onTick(long l) {}
                @Override
                public void onFinish() {
                    stopChaos();
                }
            }.start();
        }
    }

    // Phase 1: Play Audio (Variable Volume)
    private void startPlaybackPhase() {
        if (!isRunning) return;
        isInIntermittentPause = false;
        
        // Ensure player is ready
        if (mediaPlayer == null || !mediaPlayer.isPlaying()) {
            initAndPlayCurrentTrack();
        }
        
        // Determine how long we play before pausing
        // Modes: 1(Continuous/Rapid) -> 5(Deep/Sparse)
        long playDuration = getPlayDurationForMode(selectedMode);
        
        // During playback, we also drift volume up and down
        scheduleVolumeDrift(playDuration);
        
        Log.d("ChaosService", "Playing for: " + playDuration + "ms");
        
        chaosHandler.removeCallbacksAndMessages(null); // Clear old volume tasks
        chaosHandler.postDelayed(this::startPausePhase, playDuration);
    }
    
    // Phase 2: Pause Audio (Silence)
    private void startPausePhase() {
        if (!isRunning) return;
        isInIntermittentPause = true;

        // Fade out then pause
        fadeVolume(currentVolume, 0.0f, 2000, () -> {
             if (mediaPlayer != null && isRunning) {
                 try { mediaPlayer.pause(); } catch (Exception e) {}
             }
        });
        
        // Determine how long we stay silent
        long pauseDuration = getPauseDurationForMode(selectedMode);
        
        Log.d("ChaosService", "Pausing for: " + pauseDuration + "ms");
        
        chaosHandler.postDelayed(this::startPlaybackPhase, pauseDuration + 2000); // Add fade time
    }

    private long getPlayDurationForMode(int mode) {
        // Mode 1: Long Play, Short Pause
        // Mode 5: Short Play, Long Pause
        int minSec, maxSec;
        switch (mode) {
            case 1: minSec = 45; maxSec = 120; break;
            case 2: minSec = 30; maxSec = 90; break;
            case 3: minSec = 20; maxSec = 60; break; 
            case 4: minSec = 15; maxSec = 45; break;
            case 5: minSec = 10; maxSec = 30; break;
            default: minSec = 20; maxSec = 60; break;
        }
        // Add random variation
        return (minSec + random.nextInt(maxSec - minSec + 1)) * 1000L;
    }

    private long getPauseDurationForMode(int mode) {
        // Mode 1: Short Pause
        // Mode 5: Long Pause (up to 5 mins)
        int minSec, maxSec;
        switch (mode) {
            case 1: minSec = 5; maxSec = 15; break;
            case 2: minSec = 10; maxSec = 30; break;
            case 3: minSec = 20; maxSec = 60; break;
            case 4: minSec = 45; maxSec = 180; break; // 45s to 3m
            case 5: minSec = 60; maxSec = 300; break; // 1m to 5m
            default: minSec = 20; maxSec = 60; break;
        }
        return (minSec + random.nextInt(maxSec - minSec + 1)) * 1000L;
    }

    private void scheduleVolumeDrift(long maxDurationAvailable) {
        if (!isRunning || isInIntermittentPause) return;
        
        // Randomly change volume every few seconds while playing
        float targetVol = 0.2f + (random.nextFloat() * 0.8f); // 0.2 to 1.0
        int fadeTime = 2000 + random.nextInt(3000);
        
        fadeVolume(currentVolume, targetVol, fadeTime, null);
        
        int nextDriftDelay = fadeTime + 5000 + random.nextInt(10000);
        if (nextDriftDelay < maxDurationAvailable) {
            chaosHandler.postDelayed(() -> scheduleVolumeDrift(maxDurationAvailable - nextDriftDelay), nextDriftDelay);
        }
    }

    private void updateNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        
        Intent stopIntent = new Intent(this, ChaosService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_desc))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0))
                .setOngoing(true)
                .build();

        startForeground(1, notification);
    }
    
    private void updateMediaSessionState(int state) {
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_STOP)
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
                // Next track logic
                currentTrackIndex = (currentTrackIndex + 1) % playlist.size();
                initAndPlayCurrentTrack(); 
            });
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mediaPlayer.prepare();
            setLogarithmicVolume(0); // Start silent
            mediaPlayer.start();
            
            // Fade in
            float initialVol = 0.3f + (random.nextFloat() * 0.4f);
            fadeVolume(0, initialVol, 2000, null);
            
        } catch (Exception e) {
            Log.e("ChaosService", "Error playing track", e);
        }
    }
    
    private void fadeVolume(float from, float to, int durationMs, Runnable onComplete) {
        final int steps = 20;
        final long stepDelay = durationMs / steps;
        final float diff = to - from;

        new Thread(() -> {
            for (int i = 1; i <= steps; i++) {
                if (!isRunning) return;
                float progress = (float) i / steps;
                float newVol = from + (diff * progress);
                
                chaosHandler.post(() -> {
                    if (mediaPlayer != null) {
                        try {
                            setLogarithmicVolume(newVol);
                            currentVolume = newVol;
                        } catch (IllegalStateException e) {}
                    }
                });

                try { Thread.sleep(stepDelay); } catch (InterruptedException e) { return; }
            }
            if (onComplete != null) chaosHandler.post(onComplete);
        }).start();
    }
    
    private void setLogarithmicVolume(float rawVolume) {
        if (mediaPlayer == null) return;
        float powerVol = (float) Math.pow(rawVolume, 2.5);
        if (powerVol > 1.0f) powerVol = 1.0f;
        if (powerVol < 0.0f) powerVol = 0.0f;
        mediaPlayer.setVolume(powerVol, powerVol);
    }

    private void stopChaos() {
        isRunning = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        
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
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(this);
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
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                stopChaos();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    isPausedByFocus = true;
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (isPausedByFocus && isRunning && !isInIntermittentPause) {
                    if (mediaPlayer != null) mediaPlayer.start();
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