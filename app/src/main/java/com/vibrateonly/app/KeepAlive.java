package com.vibrateonly.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * "Always ready": a foreground service (with a small silent notification) so Android and
 * Samsung's battery saver never put Vibrate Only to sleep. Optionally keeps the processor awake so
 * location checks aren't delayed while the phone is locked, and checks every minute that the home
 * location check is still running. Uses more battery, by design.
 */
public class KeepAlive extends Service {
    private static final String CHANNEL = "ready";
    private static final String CHANNEL_HIDDEN = "ready_hidden";
    private static final int ID = 7;
    /** How often to make sure the home check is still running (it adjusts its own pace in between). */
    private static final long WATCH_MS = 15 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private String shownChannel;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            Home.startBackup(KeepAlive.this); // no-op if it's already running
            handler.postDelayed(this, WATCH_MS);
        }
    };

    /**
     * Starts, updates or stops "Always ready" to match the settings. Safe to call any time. With
     * the notification shown it runs as a foreground service; with it hidden, the same keep-awake
     * and home-check watchdog run inside the app's accessibility service (which Android keeps
     * running anyway), so there's no notification at all.
     */
    static void start(Context c) {
        Context app = c.getApplicationContext();
        Intent intent = new Intent(app, KeepAlive.class);
        if (Prefs.alwaysReady(app) && Prefs.readyNotification(app)) {
            Background.stop();
            try {
                app.startForegroundService(intent);
            } catch (RuntimeException ignored) {
                // Not allowed right now (e.g. from the background); tried again later.
            }
        } else {
            app.stopService(intent);
            if (Prefs.alwaysReady(app)) Background.start(app); else Background.stop();
        }
    }

    /** "Always ready" without a notification: wake lock and home-check watchdog, no service. */
    private static final class Background {
        private static final Handler handler = new Handler(Looper.getMainLooper());
        private static PowerManager.WakeLock wakeLock;
        private static Context app;
        private static final Runnable watchdog = new Runnable() {
            @Override
            public void run() {
                if (app == null) return;
                Home.startBackup(app);
                handler.postDelayed(this, WATCH_MS);
            }
        };

        static void start(Context context) {
            app = context;
            if (Prefs.keepAwake(context)) {
                if (wakeLock == null) {
                    wakeLock = context.getSystemService(PowerManager.class)
                            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibrateonly:ready");
                    wakeLock.setReferenceCounted(false);
                    wakeLock.acquire();
                }
            } else {
                release();
            }
            handler.removeCallbacks(watchdog);
            handler.post(watchdog);
        }

        static void stop() {
            handler.removeCallbacks(watchdog);
            release();
        }

        private static void release() {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.alwaysReady(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel visible = new NotificationChannel(CHANNEL, "Always ready",
                NotificationManager.IMPORTANCE_MIN);
        visible.setDescription("Keeps Vibrate Only running. You can hide this in the app.");
        visible.setShowBadge(false);
        nm.createNotificationChannel(visible);
        // A switched-off channel: the service still runs, but its notification is never shown.
        NotificationChannel hidden = new NotificationChannel(CHANNEL_HIDDEN, "Always ready (hidden)",
                NotificationManager.IMPORTANCE_NONE);
        hidden.setShowBadge(false);
        nm.createNotificationChannel(hidden);

        String channel = Prefs.readyNotification(this) ? CHANNEL : CHANNEL_HIDDEN;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("Vibrate Only is running")
                .setContentText("Watching the buttons, headphones and home")
                .setOngoing(true)
                .setContentIntent(open)
                .build();
        if (shownChannel != null && !shownChannel.equals(channel)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        shownChannel = channel;
        try {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Prefs.keepAwake(this)) {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager.class)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibrateonly:ready");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } else {
            releaseWakeLock();
        }
        handler.removeCallbacks(watchdog);
        handler.post(watchdog);
        return START_STICKY;
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(watchdog);
        releaseWakeLock();
        super.onDestroy();
    }
}
