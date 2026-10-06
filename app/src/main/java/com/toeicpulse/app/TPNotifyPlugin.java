package com.toeicpulse.app;

import android.Manifest;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(
    name = "TPNotify",
    permissions = { @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = TPNotifyPlugin.ALIAS) }
)
public class TPNotifyPlugin extends Plugin {

    /** Word id from the notification the user tapped; set by MainActivity, read once by the web app. */
    static volatile String pendingOpenWord = null;

    @PluginMethod
    public void consumeOpenedWord(PluginCall call) {
        JSObject r = new JSObject();
        String w = pendingOpenWord;
        pendingOpenWord = null;
        if (w != null) r.put("wordId", w);
        call.resolve(r);
    }


    static final String ALIAS = "notifications";

    // ---- Text-to-speech (Android WebView has no Web Speech API) ----
    private TextToSpeech tts;
    private int ttsStatus = 0; // 0 = initializing, 1 = ready, -1 = failed
    private final List<PluginCall> pendingSpeak = new ArrayList<>();
    private final Map<String, PluginCall> activeSpeak = new HashMap<>();
    private int utteranceSeq = 0;

    @Override
    public void load() {
        TPNotifyCore.ensureChannels(getContext());
        initTts();
    }

    private void initTts() {
        tts = new TextToSpeech(getContext().getApplicationContext(), status -> {
            synchronized (pendingSpeak) {
                ttsStatus = status == TextToSpeech.SUCCESS ? 1 : -1;
                if (ttsStatus == 1) {
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String id) {}

                        @Override
                        public void onDone(String id) {
                            finishSpeak(id, true);
                        }

                        @Override
                        @SuppressWarnings("deprecation")
                        public void onError(String id) {
                            finishSpeak(id, false);
                        }

                        @Override
                        public void onStop(String id, boolean interrupted) {
                            finishSpeak(id, false);
                        }
                    });
                }
                for (PluginCall c : pendingSpeak) doSpeak(c);
                pendingSpeak.clear();
            }
        });
    }

    private void finishSpeak(String id, boolean ok) {
        PluginCall c;
        synchronized (activeSpeak) {
            c = activeSpeak.remove(id);
        }
        if (c != null) {
            JSObject r = new JSObject();
            r.put("done", ok);
            c.resolve(r);
        }
    }

    private void doSpeak(PluginCall call) {
        if (ttsStatus != 1 || tts == null) {
            call.reject("此裝置沒有可用的文字轉語音引擎，請到系統設定安裝 Google 語音服務");
            return;
        }
        String text = call.getString("text", "");
        String lang = call.getString("lang", "en-US");
        float rate = call.getFloat("rate", 0.9f);
        boolean zh = lang != null && lang.toLowerCase(Locale.ROOT).startsWith("zh");
        Locale locale = zh ? Locale.TRADITIONAL_CHINESE : ("en-GB".equals(lang) ? Locale.UK : Locale.US);
        int avail = tts.setLanguage(locale);
        if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (zh) {
                // Fall back to any installed Chinese voice (Mandarin / Simplified).
                avail = tts.setLanguage(Locale.CHINESE);
                if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) {
                    avail = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                }
                if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) {
                    call.reject("此裝置的語音引擎沒有中文語音，請到系統設定 > 文字轉語音 下載中文語音資料");
                    return;
                }
            } else {
                tts.setLanguage(Locale.ENGLISH);
            }
        }
        rate = Math.max(0.3f, Math.min(2.0f, rate));
        tts.setSpeechRate(rate);
        String id = "tp-" + (++utteranceSeq);
        synchronized (activeSpeak) {
            // A new utterance flushes the previous one; resolve those calls.
            for (PluginCall old : activeSpeak.values()) {
                JSObject r = new JSObject();
                r.put("done", false);
                old.resolve(r);
            }
            activeSpeak.clear();
            activeSpeak.put(id, call);
        }
        int res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
        if (res != TextToSpeech.SUCCESS) {
            finishSpeak(id, false);
        }
    }

    @PluginMethod
    public void speak(PluginCall call) {
        synchronized (pendingSpeak) {
            if (ttsStatus == 0) {
                pendingSpeak.add(call);
                return;
            }
        }
        doSpeak(call);
    }

    @PluginMethod
    public void stopSpeak(PluginCall call) {
        if (tts != null && ttsStatus == 1) tts.stop();
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }

    private JSObject permissionResult() {
        JSObject r = new JSObject();
        boolean granted = TPNotifyCore.canPost(getContext());
        String display;
        if (granted) {
            display = "granted";
        } else if (Build.VERSION.SDK_INT >= 33 && !TPNotifyCore.hasRuntimePermission(getContext())) {
            PermissionState st = getPermissionState(ALIAS);
            display = st == PermissionState.DENIED ? "denied" : "prompt";
        } else {
            // Runtime permission OK but notifications switched off in system settings.
            display = "denied";
        }
        r.put("granted", granted);
        r.put("display", display);
        return r;
    }

    @PluginMethod
    public void checkPermission(PluginCall call) {
        call.resolve(permissionResult());
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && !TPNotifyCore.hasRuntimePermission(getContext())) {
            requestPermissionForAlias(ALIAS, call, "onPermissionResult");
            return;
        }
        call.resolve(permissionResult());
    }

    @PermissionCallback
    private void onPermissionResult(PluginCall call) {
        call.resolve(permissionResult());
    }

    @PluginMethod
    public void show(PluginCall call) {
        int id = call.getInt("id", 1);
        String title = call.getString("title", "TOEIC Pulse");
        String body = call.getString("body", "");
        boolean sound = call.getBoolean("sound", true);
        boolean posted = TPNotifyCore.post(getContext(), id, title, body, sound, call.getString("wordId", null));
        JSObject r = new JSObject();
        r.put("posted", posted);
        call.resolve(r);
    }

    @PluginMethod
    public void schedule(PluginCall call) {
        JSArray items = call.getArray("items", new JSArray());
        boolean sound = call.getBoolean("sound", true);
        TPNotifyCore.scheduleAll(getContext(), items, sound);
        JSObject r = new JSObject();
        r.put("scheduled", items.length());
        call.resolve(r);
    }

    @PluginMethod
    public void cancelAll(PluginCall call) {
        TPNotifyCore.cancelAll(getContext());
        call.resolve();
    }

    /** Native Android clock-face time picker, always in 24-hour mode. Resolves {cancelled, hour, minute}. */
    @PluginMethod
    public void pickTime(PluginCall call) {
        final int hour = Math.max(0, Math.min(23, call.getInt("hour", 9)));
        final int minute = Math.max(0, Math.min(59, call.getInt("minute", 0)));
        if (getActivity() == null) {
            call.reject("No activity");
            return;
        }
        getActivity().runOnUiThread(() -> {
            final boolean[] done = { false };
            TimePickerDialog dlg = new TimePickerDialog(
                getActivity(),
                android.R.style.Theme_Material_Dialog,
                (view, h, m) -> {
                    if (done[0]) return;
                    done[0] = true;
                    JSObject r = new JSObject();
                    r.put("cancelled", false);
                    r.put("hour", h);
                    r.put("minute", m);
                    call.resolve(r);
                },
                hour,
                minute,
                true // 24-hour view, regardless of the phone's 12/24h setting
            );
            dlg.setOnDismissListener(d -> {
                if (done[0]) return;
                done[0] = true;
                JSObject r = new JSObject();
                r.put("cancelled", true);
                call.resolve(r);
            });
            dlg.show();
        });
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        String pkg = getContext().getPackageName();
        Intent i;
        if (Build.VERSION.SDK_INT >= 26) {
            i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
        } else {
            i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(i);
        } catch (Exception e) {
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(fallback);
        }
        call.resolve();
    }
}
