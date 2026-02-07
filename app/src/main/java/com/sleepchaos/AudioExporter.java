package com.sleepchaos;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Random;

public class AudioExporter {

    private static final String TAG = "AudioExporter";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int BIT_DEPTH = 16;

    public interface ExportCallback {
        void onSuccess(String path);
        void onError(String error);
    }

    public static void exportChaosAudio(Context context, Uri sourceUri, int durationMins, 
                                        int playLevel, int pauseLevel, 
                                        float minVol, float maxVol, int volFreq,
                                        ExportCallback callback) {
        new Thread(() -> {
            MediaExtractor extractor = new MediaExtractor();
            MediaCodec decoder = null;
            RandomAccessFile raf = null;

            try {
                // 1. Setup Source
                extractor.setDataSource(context, sourceUri, null);
                int trackIndex = selectAudioTrack(extractor);
                if (trackIndex < 0) {
                    callback.onError("No audio track found in file");
                    return;
                }
                extractor.selectTrack(trackIndex);
                MediaFormat format = extractor.getTrackFormat(trackIndex);
                String mime = format.getString(MediaFormat.KEY_MIME);

                // 2. Setup Decoder
                decoder = MediaCodec.createDecoderByType(mime);
                decoder.configure(format, null, null, 0);
                decoder.start();

                // 3. Setup Output File (WAV)
                File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                File appDir = new File(musicDir, "SleepChaos");
                if (!appDir.exists()) appDir.mkdirs();
                File outFile = new File(appDir, "chaos_mix_" + System.currentTimeMillis() + ".wav");
                raf = new RandomAccessFile(outFile, "rw");

                writeWavHeader(raf, 0, SAMPLE_RATE, CHANNELS, BIT_DEPTH);

                // 4. Processing Loop
                long totalBytesWritten = 0;
                long targetDurationBytes = (long) durationMins * 60 * SAMPLE_RATE * CHANNELS * (BIT_DEPTH / 8);
                
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputDone = false;
                boolean outputDone = false;
                Random random = new Random();

                // State Machine for Chaos Play/Pause
                boolean isPlaying = true;
                long stateEndTimeBytes = 0;
                long currentSegmentBytes = msToBytes(ChaosService.getPlayDuration(playLevel));
                stateEndTimeBytes = currentSegmentBytes;

                // State Machine for Volume Drift
                float currentVolume = (minVol + maxVol) / 2;
                float targetVolume = currentVolume;
                long volumeDriftBytesRemaining = 0;
                float volumeStepPerByte = 0;

                while (!outputDone && totalBytesWritten < targetDurationBytes) {
                    
                    // State Management (Play vs Pause)
                    if (totalBytesWritten >= stateEndTimeBytes) {
                        isPlaying = !isPlaying; // Toggle Play/Pause
                        long nextDurationMs = isPlaying ? 
                                ChaosService.getPlayDuration(playLevel) : 
                                ChaosService.getPauseDuration(pauseLevel);
                        stateEndTimeBytes += msToBytes(nextDurationMs);
                    }

                    if (!isPlaying) {
                        // PAUSE STATE
                        int silenceChunk = 4096;
                        byte[] silence = new byte[silenceChunk];
                        raf.write(silence);
                        totalBytesWritten += silenceChunk;
                    } else {
                        // PLAY STATE
                        if (!inputDone) {
                            int inputIndex = decoder.dequeueInputBuffer(10000);
                            if (inputIndex >= 0) {
                                ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                                int sampleSize = extractor.readSampleData(inputBuffer, 0);
                                if (sampleSize < 0) {
                                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                                    sampleSize = extractor.readSampleData(inputBuffer, 0);
                                    if (sampleSize < 0) {
                                        inputDone = true;
                                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    } else {
                                        decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                                        extractor.advance();
                                    }
                                } else {
                                    decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                                    extractor.advance();
                                }
                            }
                        }

                        int outputIndex = decoder.dequeueOutputBuffer(info, 10000);
                        if (outputIndex >= 0) {
                            ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
                            byte[] chunk = new byte[info.size];
                            outputBuffer.get(chunk);
                            outputBuffer.clear();

                            // Volume Drift Logic
                            if (volumeDriftBytesRemaining <= 0) {
                                // Pick new target volume and duration based on freq
                                float range = maxVol - minVol;
                                targetVolume = minVol + (random.nextFloat() * range);
                                
                                int baseDelay = 30000 - ((volFreq - 1) * 3000);
                                if (baseDelay < 2000) baseDelay = 2000;
                                int variance = baseDelay / 2;
                                int driftDurationMs = baseDelay / 2 + random.nextInt(variance);
                                
                                volumeDriftBytesRemaining = msToBytes(driftDurationMs);
                                float volDiff = targetVolume - currentVolume;
                                volumeStepPerByte = volDiff / volumeDriftBytesRemaining;
                            }

                            // Apply volume with interpolation
                            chunk = adjustVolumeSmooth(chunk, currentVolume, volumeStepPerByte);
                            
                            // Update volume state
                            currentVolume += (volumeStepPerByte * chunk.length);
                            volumeDriftBytesRemaining -= chunk.length;
                            
                            // Clamp
                            if (currentVolume > 1.0f) currentVolume = 1.0f;
                            if (currentVolume < 0.0f) currentVolume = 0.0f;

                            raf.write(chunk);
                            totalBytesWritten += chunk.length;
                            decoder.releaseOutputBuffer(outputIndex, false);
                        }
                    }
                }

                // 5. Finalize
                raf.seek(0);
                writeWavHeader(raf, totalBytesWritten, SAMPLE_RATE, CHANNELS, BIT_DEPTH);
                
                extractor.release();
                decoder.stop();
                decoder.release();
                raf.close();

                callback.onSuccess(outFile.getAbsolutePath());

            } catch (Exception e) {
                Log.e(TAG, "Export failed", e);
                callback.onError(e.getMessage());
            } finally {
               try { if (raf != null) raf.close(); } catch (IOException e) {}
            }
        }).start();
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("audio/")) return i;
        }
        return -1;
    }

    private static long msToBytes(long ms) {
        return (ms * SAMPLE_RATE * CHANNELS * 2) / 1000;
    }

    // Process chunk while interpolating volume to avoid steps/clicks
    private static byte[] adjustVolumeSmooth(byte[] pcmData, float startVolume, float stepPerByte) {
        byte[] adjusted = new byte[pcmData.length];
        float currentVol = startVolume;
        
        // 16-bit PCM processing
        for (int i = 0; i < pcmData.length; i += 2) {
            short sample = (short) ((pcmData[i] & 0xFF) | (pcmData[i + 1] << 8));
            
            // Linear to Log approximation for better perception? 
            // Standard AudioTrack uses linear, but perception is log. 
            // We apply linear here for simplicity in raw PCM math, matching "setVolume".
            // To match Service "setLogarithmicVolume", we should square it.
            float logVol = currentVol * currentVol;
            
            sample = (short) (sample * logVol);
            adjusted[i] = (byte) (sample & 0xFF);
            adjusted[i + 1] = (byte) ((sample >> 8) & 0xFF);
            
            currentVol += (stepPerByte * 2); 
        }
        return adjusted;
    }

    private static void writeWavHeader(RandomAccessFile out, long totalAudioLen, long longSampleRate, int channels, long byteRate) throws IOException {
        long totalDataLen = totalAudioLen + 36;
        long bitrate = longSampleRate * channels * byteRate / 8;
        
        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0;
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (longSampleRate & 0xff);
        header[25] = (byte) ((longSampleRate >> 8) & 0xff);
        header[26] = (byte) ((longSampleRate >> 16) & 0xff);
        header[27] = (byte) ((longSampleRate >> 24) & 0xff);
        header[28] = (byte) (bitrate & 0xff);
        header[29] = (byte) ((bitrate >> 8) & 0xff);
        header[30] = (byte) ((bitrate >> 16) & 0xff);
        header[31] = (byte) ((bitrate >> 24) & 0xff);
        header[32] = (byte) (channels * 16 / 8); header[33] = 0;
        header[34] = 16; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);
        out.write(header, 0, 44);
    }
}