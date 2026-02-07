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
    public static final String ACTION_PLAY_PAUSE = "ACTION_PLAY_PAUSE";
    public static final String EXTRA_URI_LIST = "EXTRA_URI_LIST";
    public static final String EXTRA_DURATION_MINS = "EXTRA_DURATION_MINS";
    public static final String CHANNEL_ID = "ChaosServiceChannel";
    private static final float MAX_VOLUME = 1.0f;

    private MediaPlayer mediaPlayer;
    private Handler chaosHandler;
    private Random random;
    private CountDownTimer sleepTimer;
    private boolean isRunning = false;
    private boolean isPausedByFocus = false;
    private float currentVolume = 0.5f;
    
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
        
        // Prevent CPU from sleeping during silent intervals of the chaos logic
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepChaos:WakeLock");
        
        setupMediaSession();
        createNotificationChannel();
    }
    
    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, "ChaosMediaSession");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                // Determine logic to resume or start
            }
            @Override
            public void onPause() {
                stopChaos();
            }
            @Override
            public void onStop() {
                stopChaos();
            }
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
        
        if (!wakeLock.isHeld()) wakeLock.acquire(120 * 60 * 1000L /* 2 hours max safety */);

        updateNotification(true);
        updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING);
        
        playTrack(currentTrackIndex);
        scheduleNextEvent();

        if (durationMins > 0) {
            long millis = durationMins * 60 * 1000L;
            sleepTimer = new CountDownTimer(millis, 1000) {
                @Override
                public void onTick(long l) {
                    // Update metadata if needed, or simple logging
                }
                @Override
                public void onFinish() {
                    stopChaos();
                }
            }.start();
        }
    }

    private void updateNotification(boolean isPlaying) {
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

    private void playTrack(int index) {
        if (!isRunning || playlist.isEmpty()) return;
        
        if (index >= playlist.size()) index = 0;
        currentTrackIndex = index;
        
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
            
            mediaPlayer.setDataSource(this, playlist.get(currentTrackIndex));
            mediaPlayer.setOnCompletionListener(mp -> playTrack(currentTrackIndex + 1));
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mediaPlayer.prepare();
            setLogarithmicVolume(currentVolume);
            mediaPlayer.start();
            
        } catch (Exception e) {
            Log.e("ChaosService", "Error playing track", e);
            chaosHandler.postDelayed(() -> playTrack(currentTrackIndex + 1), 2000);
        }
    }

    private void scheduleNextEvent() {
        if (!isRunning) return;

        // Algorithm:
        // 0-1: Drift to silence (Deep pause)
        // 2-3: Micro pause (Brief silence)
        // 4-9: Volume drift (Change intensity)
        
        int decision = random.nextInt(10);
        long delayToNextEvent;

        if (decision < 2) { 
            // Deep Pause: Fade out completely, wait, then fade in next track/resume
            long pauseDuration = 10000 + random.nextInt(40000); // 10s - 50s
            fadeVolumeTo(0.0f, 3000, () -> {
                if (mediaPlayer != null) {
                     try { mediaPlayer.pause(); } catch (Exception e) {}
                }
            });
            delayToNextEvent = pauseDuration + 3000;
            
        } else if (decision < 3) {
            // Micro Pause: Just a dip
            long pauseDuration = 2000 + random.nextInt(5000);
            fadeVolumeTo(0.05f, 1000, null); // Don't stop, just get very quiet
            delayToNextEvent = pauseDuration + 1000;

        } else {
            // Volume Drift: Ensure playback and change volume
            if (mediaPlayer != null) {
                try {
                    if (!mediaPlayer.isPlaying()) {
                        mediaPlayer.start();
                        mediaPlayer.setVolume(0, 0); // Start silent then ramp up
                    }
                } catch (Exception e) {}
            }

            // Generate a random volume between 0.2 and 0.8
            // We avoid 1.0 to keep it non-jarring
            float targetVol = 0.2f + (random.nextFloat() * 0.6f);
            
            // Random fade duration: 2s to 8s (Slow drifts are more sleep inducing)
            int fadeDuration = 2000 + random.nextInt(6000);
            
            fadeVolumeTo(targetVol, fadeDuration, null);
            delayToNextEvent = fadeDuration + 5000 + random.nextInt(20000);
        }

        chaosHandler.postDelayed(this::scheduleNextEvent, delayToNextEvent);
    }
    
    // Smooth logarithmic fade
    private void fadeVolumeTo(float target, int durationMs, Runnable onComplete) {
        final int fps = 20; 
        final int steps = (durationMs / 1000) * fps;
        final long stepDelay = durationMs / steps;
        final float startVol = currentVolume;
        final float diff = target - startVol;

        new Thread(() -> {
            for (int i = 1; i <= steps; i++) {
                if (!isRunning) return;
                float progress = (float) i / steps;
                float newVol = startVol + (diff * progress);
                
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
    
    // Convert linear 0.0-1.0 to logarithmic decibel-like perception
    private void setLogarithmicVolume(float rawVolume) {
        if (mediaPlayer == null) return;
        // Simple approximation: Volume = raw^3
        float logVol = (float) (1 - (Math.log(MAX_VOLUME - rawVolume) / Math.log(MAX_VOLUME)));
        // Better approximation for Android MediaPlayer:
        float volume = (float) (1 - (Math.log(100 - (rawVolume * 100)) / Math.log(100)));
        if (volume < 0) volume = 0;
        if (volume > 1) volume = 1;
        
        // Actually, simple power curve is often safer/smoother for fades
        float powerVol = (float) Math.pow(rawVolume, 2.5);
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
                // Permanent loss (e.g., other music app started)
                stopChaos();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Transient loss (e.g., notification or phone call)
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    isPausedByFocus = true;
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                // Regained focus
                if (isPausedByFocus && isRunning) {
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