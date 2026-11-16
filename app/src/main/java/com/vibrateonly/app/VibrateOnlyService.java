package com.vibrateonly.app;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Always-running background service (Android keeps accessibility services alive and restarts
 * them after a reboot). Watches for Volume Up + Volume Down pressed together.
 *
 * While the screen is on, volume presses are held back for a split second to see whether the
 * other button follows. If it does, the mode toggles and the volume is left untouched; if not,
 * the press is passed on as a volume change (see {@link #adjustVolume}).
 */
public class VibrateOnlyService extends AccessibilityService {
    static VibrateOnlyService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Wait this long after headphones / a speaker disconnect before Vibrate Only comes back. */
    private static final long RESUME_DELAY_MS = 5_000;
    /** Media unmuted with the volume buttons is muted again this long after it stops playing. */
    private static final long REMUTE_DELAY_MS = 30_000;

    private AudioManager audio;

    /** Buttons whose press we swallowed, so we also swallow their release and repeats. */
    private final Set<Integer> consumedDown = new HashSet<>();
    /** Buttons held long enough to count as a normal press; their repeats change the volume. */
    private final Set<Integer> passthroughHeld = new HashSet<>();
    private int pendingKey;
    private boolean comboActive;

    private final Runnable forwardPending = () -> {
        int key = pendingKey;
        pendingKey = 0;
        if (key == 0) return;
        adjustVolume(key);
        if (consumedDown.contains(key)) passthroughHeld.add(key);
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(action)) {
                if (ModeController.isActive(context)
                        && audio.getRingerMode() != AudioManager.RINGER_MODE_VIBRATE) {
                    if (Prefs.keepVibrate(context)) {
                        try {
                            audio.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                        } catch (SecurityException e) {
                            ModeController.endedExternally(context);
                        }
                    } else {
                        ModeController.endedExternally(context);
                    }
                }
            }
        }
    };

    private final Runnable remuteMedia = () -> {
        if (!audio.isMusicActive() && AudioRouting.unmutedByUser(this)) {
            AudioRouting.muteAgain(this);
        }
    };

    /** Something started or stopped playing: re-mute media 30 s after it stops. */
    private final AudioManager.AudioPlaybackCallback playbackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(
                        java.util.List<android.media.AudioPlaybackConfiguration> configs) {
                    handler.removeCallbacks(remuteMedia);
                    if (!audio.isMusicActive() && AudioRouting.unmutedByUser(VibrateOnlyService.this)) {
                        handler.postDelayed(remuteMedia, REMUTE_DELAY_MS);
                    }
                }
            };

    /** Headphones / speaker gone for 5 s: Vibrate Only comes back (if it was on). */
    private final Runnable resumeAfterDisconnect = () -> {
        if (!AudioRouting.mediaDeviceConnected(this)) ModeController.setDeviceConnected(this, false);
    };

    /**
     * Headphones or a speaker connected: the phone behaves normally straight away. Disconnected:
     * after a short wait (so a Bluetooth hiccup doesn't count) Vibrate Only comes back.
     */
    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            checkDevices();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            checkDevices();
        }
    };

    private void checkDevices() {
        if (AudioRouting.mediaDeviceConnected(this)) {
            handler.removeCallbacks(resumeAfterDisconnect);
        handler.removeCallbacks(remuteMedia);
            ModeController.setDeviceConnected(this, true);
        } else if (ModeController.deviceConnected(this)) {
            handler.removeCallbacks(resumeAfterDisconnect);
            handler.postDelayed(resumeAfterDisconnect, RESUME_DELAY_MS);
        }
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        audio = getSystemService(AudioManager.class);

        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        audio.registerAudioDeviceCallback(deviceCallback, handler);
        audio.registerAudioPlaybackCallback(playbackCallback, handler);

        // Catch up with anything connected or disconnected while the service wasn't running.
        ModeController.setDeviceConnected(this, AudioRouting.mediaDeviceConnected(this));
        ModeController.apply(this);
        Home.register(this);
        KeepAlive.start(this);
    }

    /** Called by the app screens after permissions or settings change. */
    void refresh() {
        // Nothing to update here any more. (The two-button press only works with the screen on: with the screen off,
        // Android keeps both volume buttons pressed together for its own shortcuts, so no app sees the pair.)
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (audio != null) {
            unregisterReceiver(receiver);
            audio.unregisterAudioDeviceCallback(deviceCallback);
            audio.unregisterAudioPlaybackCallback(playbackCallback);
        }
        handler.removeCallbacks(forwardPending);
        handler.removeCallbacks(resumeAfterDisconnect);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            passthroughHeld.remove(key);
            if (consumedDown.remove(key)) {
                if (consumedDown.isEmpty()) comboActive = false;
                return true;
            }
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

        if (event.getRepeatCount() > 0) {
            if (!consumedDown.contains(key)) return false;
            if (passthroughHeld.contains(key)) adjustVolume(key);
            return true;
        }

        // Ringing or in a call: let the buttons do their usual job (e.g. silence the ringer).
        if (audio.getMode() != AudioManager.MODE_NORMAL) return false;

        if (comboActive) {
            consumedDown.add(key);
            return true;
        }

        if (pendingKey != 0 && pendingKey != key) {
            // The other button arrived in time: this is the combo.
            handler.removeCallbacks(forwardPending);
            pendingKey = 0;
            comboActive = true;
            consumedDown.add(key);
            ModeController.buttonsPressed(this);
            return true;
        }

        if (pendingKey == key) {
            // Same button tapped twice very fast: let the first tap through right away.
            handler.removeCallbacks(forwardPending);
            adjustVolume(key);
        }
        pendingKey = key;
        consumedDown.add(key);
        handler.postDelayed(forwardPending, Prefs.comboWindowMs(this));
        return true;
    }

    /**
     * A single (non-combo) volume press. Normally does what the button would have done; while the
     * mode is in force, changes the alarm volume instead so vibrate is kept.
     */
    private void adjustVolume(int key) {
        int direction = key == KeyEvent.KEYCODE_VOLUME_UP
                ? AudioManager.ADJUST_RAISE
                : AudioManager.ADJUST_LOWER;
        if (ModeController.isActive(this)) {
            AudioRouting.adjustWhileActive(this, direction, true);
            return;
        }
        audio.adjustSuggestedStreamVolume(
                direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
    }

    private boolean isScreenOn() {
        return getSystemService(PowerManager.class).isInteractive();
    }
}
