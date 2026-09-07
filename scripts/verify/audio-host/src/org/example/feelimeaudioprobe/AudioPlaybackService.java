package org.example.feelimeaudioprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Tiny foreground media player used by the production IME ASR gate.
 *
 * It deliberately has no Activity.  The injector copies a WAV into this
 * package's private files directory and starts this service directly, so the
 * currently focused IME editor remains focused while the phone speaker
 * produces the test signal for the microphone.
 */
public final class AudioPlaybackService extends Service {
    public static final String ACTION_PLAY =
            "org.example.feelimeaudioprobe.action.PLAY";
    public static final String EXTRA_REQUEST_ID =
            "org.example.feelimeaudioprobe.extra.REQUEST_ID";

    private static final String TAG = "FeelimeAudioProbe";
    private static final String CHANNEL_ID = "feelime-audio-probe-playback";
    private static final int NOTIFICATION_ID = 170175;
    private static final String AUDIO_NAME = "probe-audio.wav";

    private MediaPlayer player;
    private String activeRequestId;
    private int activeStartId;
    private long startedAtMs;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String requestId = intent == null
                ? "missing-request-id"
                : intent.getStringExtra(EXTRA_REQUEST_ID);
        if (requestId == null || requestId.length() == 0) {
            requestId = "missing-request-id";
        }
        // The injector sends one request at a time.  Releasing a previous
        // player keeps a retried request from mixing two signals; callbacks
        // are identity-checked so an old completion cannot stop the retry.
        releasePlayer();
        activeRequestId = requestId;
        activeStartId = startId;
        startedAtMs = 0L;

        try {
            startForegroundCompat();
            playPrivateFile(requestId, startId);
        } catch (Exception error) {
            finishWithError(requestId, startId, error);
        }
        return START_NOT_STICKY;
    }

    private void startForegroundCompat() {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Feelime audio probe")
                .setContentText("Playing test audio through the speaker")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void playPrivateFile(final String requestId, final int startId)
            throws IOException {
        final File source = new File(getFilesDir(), AUDIO_NAME);
        if (!source.isFile() || source.length() <= 44L) {
            throw new IOException("audio file is missing or empty: " + source);
        }

        final MediaPlayer next = new MediaPlayer();
        player = next;
        next.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        next.setOnPreparedListener(prepared -> {
            if (player != prepared || !requestId.equals(activeRequestId)) {
                prepared.release();
                return;
            }
            startedAtMs = android.os.SystemClock.elapsedRealtime();
            Log.i(TAG, "requestId=" + requestId + " state=started durationMs="
                    + prepared.getDuration());
            prepared.start();
        });
        next.setOnCompletionListener(completed -> {
            if (player != completed || !requestId.equals(activeRequestId)) {
                completed.release();
                return;
            }
            long elapsed = startedAtMs == 0L ? 0L
                    : android.os.SystemClock.elapsedRealtime() - startedAtMs;
            Log.i(TAG, "requestId=" + requestId + " state=completed durationMs="
                    + completed.getDuration() + " elapsedMs=" + elapsed);
            finish(completed, startId);
        });
        next.setOnErrorListener((failed, what, extra) -> {
            if (player != failed || !requestId.equals(activeRequestId)) {
                failed.release();
                return true;
            }
            Log.e(TAG, "requestId=" + requestId + " state=error what=" + what
                    + " extra=" + extra);
            finish(failed, startId);
            return true;
        });
        next.setDataSource(source.getAbsolutePath());
        next.prepareAsync();
        Log.i(TAG, "requestId=" + requestId + " state=preparing bytes="
                + source.length());
    }

    private void finish(MediaPlayer finished, int startId) {
        if (player == finished) {
            player = null;
        }
        try {
            finished.reset();
        } catch (RuntimeException ignored) {
            // Release below is the authoritative cleanup path.
        }
        finished.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
    }

    private void finishWithError(String requestId, int startId, Exception error) {
        Log.e(TAG, "requestId=" + requestId + " state=error message="
                + String.valueOf(error.getMessage()), error);
        releasePlayer();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
    }

    private void releasePlayer() {
        MediaPlayer old = player;
        player = null;
        if (old != null) {
            try {
                old.stop();
            } catch (RuntimeException ignored) {
                // A player that failed during prepare cannot be stopped.
            }
            old.release();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Feelime audio probe", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Temporary audio playback for Feelime ASR verification");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
