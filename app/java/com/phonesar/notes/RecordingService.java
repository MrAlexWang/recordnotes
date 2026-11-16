package com.phonesar.notes;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.Locale;

/**
 * 前台录音服务：桌面快捷方式可直接在后台开始录音，
 * 通知栏可以暂停 / 继续 / 结束，录音不依赖界面存活。
 */
public class RecordingService extends Service {

    private static final String TAG = "RecordingService";

    public static final String ACTION_START = "com.phonesar.notes.REC_START";
    public static final String ACTION_PAUSE = "com.phonesar.notes.REC_PAUSE";
    public static final String ACTION_RESUME = "com.phonesar.notes.REC_RESUME";
    public static final String ACTION_STOP = "com.phonesar.notes.REC_STOP";
    /** 发给界面的状态广播 */
    public static final String ACTION_STATE = "com.phonesar.notes.REC_STATE";
    public static final String EXTRA_STATE = "state";

    public static final String PREF = "app";
    public static final String PENDING_FILE = "pending_file";
    public static final String PENDING_DUR = "pending_dur";
    public static final String PENDING_TS = "pending_ts";

    private static final String CHANNEL_ID = "recording";
    private static final int NOTIF_ID = 1001;

    private static RecordingService instance;

    private Recorder recorder;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean stopping;

    public static boolean isRunning() {
        return instance != null && instance.recorder != null && instance.recorder.isRecording();
    }

    public static boolean isPaused() {
        return instance != null && instance.recorder != null && instance.recorder.isPaused();
    }

    public static long elapsedMs() {
        return instance == null || instance.recorder == null ? 0 : instance.recorder.elapsedMs();
    }

    /** 统一的发命令入口。 */
    public static void send(Context ctx, String action) {
        Intent i = new Intent(ctx, RecordingService.class).setAction(action);
        if (ACTION_START.equals(action)) {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } else {
            try {
                ctx.startService(i);
            } catch (Exception e) {
                Log.w(TAG, "send " + action + " failed: " + e.getMessage());
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        recorder = new Recorder(new File(getFilesDir(), "audio"));
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        Log.w(TAG, "action=" + action);

        if (ACTION_START.equals(action)) {
            if (recorder.isRecording()) {
                broadcast(recorder.isPaused() ? "paused" : "recording");
                return START_STICKY;
            }
            startForeground(NOTIF_ID, buildNotification());
            try {
                recorder.start("rec_" + System.currentTimeMillis());
                startTicker();
                broadcast("recording");
            } catch (Exception e) {
                Log.e(TAG, "start failed", e);
                broadcast("error");
                stopForeground(true);
                stopSelf();
            }
        } else if (ACTION_PAUSE.equals(action)) {
            if (recorder.isRecording() && recorder.pause()) {
                broadcast("paused");
                notifyUpdate();
            } else if (recorder.isRecording()) {
                broadcast("nopause");
            }
        } else if (ACTION_RESUME.equals(action)) {
            if (recorder.isRecording() && recorder.resume()) {
                broadcast("resumed");
                notifyUpdate();
            }
        } else if (ACTION_STOP.equals(action)) {
            stopAndSave();
        }
        return START_STICKY;
    }

    private void stopAndSave() {
        if (stopping) return;
        if (!recorder.isRecording()) {
            stopForeground(true);
            stopSelf();
            return;
        }
        stopping = true;
        long dur = recorder.stop();
        File f = recorder.output();
        handler.removeCallbacks(ticker);

        if (f != null && f.exists() && f.length() > 0) {
            SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
            sp.edit()
                    .putString(PENDING_FILE, f.getName())
                    .putLong(PENDING_DUR, dur)
                    .putLong(PENDING_TS, System.currentTimeMillis())
                    .apply();
        }
        broadcast("stopped");
        stopForeground(true);
        stopSelf();
    }

    private void startTicker() {
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 1000);
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (recorder == null || !recorder.isRecording()) return;
            notifyUpdate();
            handler.postDelayed(this, 1000);
        }
    };

    private void broadcast(String state) {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName())
                .putExtra(EXTRA_STATE, state)
                .putExtra("duration", recorder == null ? 0L : recorder.elapsedMs());
        sendBroadcast(i);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "录音", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("录音进行中的状态与操作");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private Notification buildNotification() {
        boolean paused = recorder.isPaused();
        long ms = recorder.elapsedMs();

        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent pauseResume = PendingIntent.getService(this, 11,
                new Intent(this, RecordingService.class)
                        .setAction(paused ? ACTION_RESUME : ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 12,
                new Intent(this, RecordingService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle(paused ? "录音已暂停" : "正在录音")
                .setContentText(clock(ms))
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null,
                        paused ? "继续" : "暂停", pauseResume).build())
                .addAction(new Notification.Action.Builder(null, "结束", stop).build());
        return b.build();
    }

    private void notifyUpdate() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        } catch (Exception e) {
            Log.w(TAG, "notify failed: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        /* 被系统杀掉时也要把已有录音落盘，避免丢文件 */
        if (recorder != null && recorder.isRecording()) {
            long dur = recorder.stop();
            File f = recorder.output();
            if (f != null && f.exists() && f.length() > 0) {
                getSharedPreferences(PREF, MODE_PRIVATE).edit()
                        .putString(PENDING_FILE, f.getName())
                        .putLong(PENDING_DUR, dur)
                        .putLong(PENDING_TS, System.currentTimeMillis())
                        .apply();
            }
            broadcast("stopped");
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
