package com.toeicpulse.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shared notification logic used by the plugin and the broadcast receivers. */
public final class TPNotifyCore {

    static final String PREFS = "tp_notify";
    static final String KEY_ITEMS = "items";
    static final String KEY_SOUND = "sound";
    static final String CH_SOUND = "toeic_words";
    static final String CH_SILENT = "toeic_words_silent";
    static final String ACTION_FIRE = "com.toeicpulse.app.NOTIFY_FIRE";
    static final String EXTRA_ID = "tp_id";
    static final String EXTRA_TITLE = "tp_title";
    static final String EXTRA_BODY = "tp_body";
    static final String EXTRA_SOUND = "tp_sound";
    static final String EXTRA_WORD = "tp_word";
    /** Extra on the MainActivity intent: id of the word shown in the tapped notification. */
    static final String EXTRA_OPEN_WORD = "tp_open_word";

    private TPNotifyCore() {}

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel withSound = new NotificationChannel(CH_SOUND, "單字推播提醒", NotificationManager.IMPORTANCE_DEFAULT);
        withSound.setDescription("依設定時段定時推播多益單字");
        NotificationChannel silent = new NotificationChannel(CH_SILENT, "單字推播提醒（靜音）", NotificationManager.IMPORTANCE_LOW);
        silent.setDescription("關閉通知音效時使用");
        silent.setSound(null, null);
        nm.createNotificationChannel(withSound);
        nm.createNotificationChannel(silent);
    }

    static boolean hasRuntimePermission(Context c) {
        if (Build.VERSION.SDK_INT < 33) return true;
        return c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean canPost(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled() && hasRuntimePermission(c);
    }

    @SuppressWarnings("deprecation")
    static boolean post(Context c, int id, String title, String body, boolean sound) {
        return post(c, id, title, body, sound, null);
    }

    @SuppressWarnings("deprecation")
    static boolean post(Context c, int id, String title, String body, boolean sound, String wordId) {
        if (!canPost(c)) return false;
        ensureChannels(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;

        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (wordId != null && !wordId.isEmpty()) open.putExtra(EXTRA_OPEN_WORD, wordId);
        PendingIntent contentIntent = PendingIntent.getActivity(
            c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(c, sound ? CH_SOUND : CH_SILENT);
        } else {
            b = new Notification.Builder(c);
            b.setPriority(sound ? Notification.PRIORITY_DEFAULT : Notification.PRIORITY_LOW);
            if (sound) b.setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
        }
        b.setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title == null ? "TOEIC Pulse" : title)
            .setContentText(body == null ? "" : body)
            .setStyle(new Notification.BigTextStyle().bigText(body == null ? "" : body))
            .setColor(0xFF14B8A6)
            .setAutoCancel(true)
            .setContentIntent(contentIntent);
        try {
            nm.notify(id, b.build());
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static PendingIntent alarmIntent(Context c, int id, String title, String body, boolean sound, int flags) {
        return alarmIntent(c, id, title, body, sound, null, flags);
    }

    private static PendingIntent alarmIntent(Context c, int id, String title, String body, boolean sound, String wordId, int flags) {
        Intent i = new Intent(c, TPNotifyReceiver.class);
        i.setAction(ACTION_FIRE);
        i.putExtra(EXTRA_ID, id);
        i.putExtra(EXTRA_TITLE, title);
        i.putExtra(EXTRA_BODY, body);
        i.putExtra(EXTRA_SOUND, sound);
        if (wordId != null) i.putExtra(EXTRA_WORD, wordId);
        return PendingIntent.getBroadcast(c, id, i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void setAlarm(Context c, JSONObject it, boolean sound) {
        long at = it.optLong("at", 0);
        if (at <= System.currentTimeMillis()) return;
        setAlarmAt(c, it.optInt("id"), it.optString("title"), it.optString("body"), sound, it.optString("wordId", null), at);
    }

    /** Exact alarm when allowed, so the planned (second-level random) time is kept and alarms are not batched together. */
    static void setAlarmAt(Context c, int id, String title, String body, boolean sound, String wordId, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = alarmIntent(c, id, title, body, sound, wordId, PendingIntent.FLAG_UPDATE_CURRENT);
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static final String KEY_LAST_FIRE = "last_fire";
    static final long MIN_GAP_MS = 60_000L;      // at least 1 minute between two notifications
    static final long JITTER_MS = 45_000L;       // plus 0-45 s random seconds

    /**
     * Reserve a time slot for a notification that is firing now.
     * Returns 0 if it may be shown immediately, otherwise the (random-second) time it should be re-armed for,
     * so several alarms that the system delivers together are spread out instead of popping up at once.
     */
    static synchronized long reserveSlot(Context c) {
        SharedPreferences sp = prefs(c);
        long now = System.currentTimeMillis();
        long last = sp.getLong(KEY_LAST_FIRE, 0);
        if (now - last >= MIN_GAP_MS) {
            sp.edit().putLong(KEY_LAST_FIRE, now).commit();
            return 0;
        }
        long next = Math.max(last, now) + MIN_GAP_MS + (long) (Math.random() * JITTER_MS);
        sp.edit().putLong(KEY_LAST_FIRE, next).commit();
        return next;
    }

    static synchronized void scheduleAll(Context c, JSONArray items, boolean sound) {
        cancelAll(c);
        JSONArray kept = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            setAlarm(c, it, sound);
            kept.put(it);
        }
        prefs(c).edit().putString(KEY_ITEMS, kept.toString()).putBoolean(KEY_SOUND, sound).apply();
    }

    static synchronized void cancelAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        JSONArray old = stored(c);
        for (int i = 0; i < old.length(); i++) {
            JSONObject it = old.optJSONObject(i);
            if (it == null) continue;
            PendingIntent pi = alarmIntent(c, it.optInt("id"), null, null, true, PendingIntent.FLAG_NO_CREATE);
            if (pi != null) {
                if (am != null) am.cancel(pi);
                pi.cancel();
            }
        }
        prefs(c).edit().remove(KEY_ITEMS).apply();
    }

    /** Re-arm stored alarms (after reboot or app update). */
    static synchronized void restore(Context c) {
        boolean sound = prefs(c).getBoolean(KEY_SOUND, true);
        JSONArray items = stored(c);
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null) setAlarm(c, it, sound);
        }
    }

    private static JSONArray stored(Context c) {
        try {
            return new JSONArray(prefs(c).getString(KEY_ITEMS, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
