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
    public static final String EXTRA_PLAY_LEVEL = "EXTRA_PLAY_LEVEL";
    public static final String EXTRA_PAUSE_LEVEL = "EXTRA_PAUSE_LEVEL";
    public static final String EXTRA_MIN_VOL = "EXTRA_MIN_VOL";
    public static final String EXTRA_MAX_VOL = "EXTRA_MAX_VOL";
    public static final String EXTRA_VOL_FREQ = "EXTRA_VOL_FREQ";
    
    public static final String CHANNEL_ID = "ChaosServiceChannel";

    private MediaPlayer mediaPlayer;
    private Handler chaosHandler;
    private Random random;
    private CountDownTimer sleepTimer;
    private CountDownTimer phaseTimer; 
    
    private boolean isRunning = false;
    private boolean isPausedByFocus = false;
    private boolean isInIntermittentPause = false; 
    
    private float currentVolume = 0.5f;
    private float minVolume = 0.2f;
    private float maxVolume = 0.8f;
    private int volumeFreq = 5;
    
    private int playLevel = 5;
    private int pauseLevel = 5;
    
    private ArrayList<Uri> playlist = new ArrayList<>();
    private int currentTrackIndex = 0;
    
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private PowerManager.WakeLock wakeLock;
    private MediaSessionCompat mediaSession;
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;

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
                playLevel = intent.getIntExtra(EXTRA_PLAY_LEVEL, 5);
                pauseLevel = intent.getIntExtra(EXTRA_PAUSE_LEVEL, 5);
                minVolume = intent.getFloatExtra(EXTRA_MIN_VOL, 0.2f);
                maxVolume = intent.getFloatExtra(EXTRA_MAX_VOL, 0.8f);
                volumeFreq = intent.getIntExtra(EXTRA_VOL_FREQ, 5);
                
                // Safety check
                if (minVolume > maxVolume) {
                    float temp = minVolume; minVolume = maxVolume; maxVolume = temp;
                }
                currentVolume = minVolume + (maxVolume - minVolume) / 2;
                
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
        
        if (!wakeLock.isHeld()) wakeLock.acquire(4 * 60 * 60 * 1000L); 

        initNotificationBuilder();
        startForeground(1, notificationBuilder.build());
        
        updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING);
        
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

    // Phase 1: Play Audio
    private void startPlaybackPhase() {
        if (!isRunning) return;
        isInIntermittentPause = false;
        
        chaosHandler.removeCallbacksAndMessages(null);

        if (mediaPlayer == null || !mediaPlayer.isPlaying()) {
            if (mediaPlayer == null) {
                initAndPlayCurrentTrack();
            } else {
                mediaPlayer.start();
                // Fade in from 0 to minVolume (or current)
                fadeVolume(0, currentVolume, 1000, null);
            }
        }
        
        long playDuration = getPlayDuration(playLevel);
        scheduleVolumeDrift(playDuration);
        
        startPhaseTimer(playDuration, true);
    }
    
    // Phase 2: Pause Audio
    private void startPausePhase() {
        if (!isRunning) return;
        isInIntermittentPause = true;

        chaosHandler.removeCallbacksAndMessages(null);

        // Fade out then pause
        fadeVolume(currentVolume, 0.0f, 2000, () -> {
             if (mediaPlayer != null && isRunning) {
                 try { mediaPlayer.pause(); } catch (Exception e) {}
             }
        });
        
        long pauseDuration = getPauseDuration(pauseLevel);
        startPhaseTimer(pauseDuration, false);
    }

    private void startPhaseTimer(long durationMs, boolean isPlayingPhase) {
        if (phaseTimer != null) phaseTimer.cancel();
        
        updateNotificationProgress(durationMs, durationMs, isPlayingPhase);

        phaseTimer = new CountDownTimer(durationMs, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                updateNotificationProgress(durationMs, millisUntilFinished, isPlayingPhase);
            }

            @Override
            public void onFinish() {
                if (!isRunning) return;
                if (isPlayingPhase) {
                    startPausePhase();
                } else {
                    startPlaybackPhase();
                }
            }
        }.start();
    }
    
    private void updateNotificationProgress(long maxMs, long remainingMs, boolean isPlayingPhase) {
        if (notificationBuilder == null) return;
        
        String stateTitle = isPlayingPhase ? getString(R.string.state_active) : getString(R.string.state_silence);
        long sec = remainingMs / 1000;
        String timeStr = String.format(getString(R.string.time_remaining), sec / 60, sec % 60);

        notificationBuilder.setContentTitle(stateTitle)
                           .setContentText(timeStr)
                           .setProgress((int)(maxMs/1000), (int)(remainingMs/1000), false);
        
        notificationManager.notify(1, notificationBuilder.build());
    }

    public static long getPlayDuration(int level) {
        Random r = new Random();
        int baseSec = 10 + (level * 10); 
        int variance = 10;
        return (baseSec + r.nextInt(variance)) * 1000L;
    }

    public static long getPauseDuration(int level) {
        Random r = new Random();
        int minSec, maxSec;
        if (level <= 3) {
            minSec = 5 + (level * 5); 
            maxSec = minSec + 10;
        } else if (level <= 7) {
            minSec = 30 + ((level - 3) * 15); 
            maxSec = minSec + 30;
        } else {
            minSec = 100 + ((level - 7) * 40); 
            maxSec = minSec + 60;
        }
        return (minSec + r.nextInt(maxSec - minSec + 1)) * 1000L;
    }

    private void scheduleVolumeDrift(long maxDurationAvailable) {
        if (!isRunning || isInIntermittentPause) return;
        
        // Calculate Target Volume within Bounds
        float range = maxVolume - minVolume;
        float targetVol = minVolume + (random.nextFloat() * range);
        
        // Calculate Duration based on Frequency (Level 1-10)
        // High Freq (10) -> Short duration/interval (Fast changes)
        // Low Freq (1) -> Long duration/interval (Slow changes)
        
        // Level 1: 30s base
        // Level 10: 2s base
        int freqLevel = volumeFreq;
        int baseDelay = 30000 - ((freqLevel - 1) * 3000); // 30000 down to 3000 roughly
        if (baseDelay < 2000) baseDelay = 2000;
        
        int variance = baseDelay / 2;
        int fadeTime = baseDelay / 2 + random.nextInt(variance); 
        int nextDelay = fadeTime + (baseDelay / 2) + random.nextInt(variance);

        fadeVolume(currentVolume, targetVol, fadeTime, null);
        
        if (nextDelay < maxDurationAvailable) {
            chaosHandler.postDelayed(() -> scheduleVolumeDrift(maxDurationAvailable - nextDelay), nextDelay);
        }
    }

    private void initNotificationBuilder() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        
        Intent stopIntent = new Intent(this, ChaosService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_desc))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0))
                .setOngoing(true)
                .setOnlyAlertOnce(true); 
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
        // Convert linear 0-1 to logarithmic perception for MediaPlayer
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
        if (phaseTimer != null) {
            phaseTimer.cancel();
            phaseTimer = null;
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