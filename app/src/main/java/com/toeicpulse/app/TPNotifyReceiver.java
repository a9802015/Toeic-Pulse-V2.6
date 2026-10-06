package com.toeicpulse.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires a scheduled word notification (works while the app is closed). */
public class TPNotifyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !TPNotifyCore.ACTION_FIRE.equals(intent.getAction())) return;
        int id = intent.getIntExtra(TPNotifyCore.EXTRA_ID, 1);
        String title = intent.getStringExtra(TPNotifyCore.EXTRA_TITLE);
        String body = intent.getStringExtra(TPNotifyCore.EXTRA_BODY);
        boolean sound = intent.getBooleanExtra(TPNotifyCore.EXTRA_SOUND, true);
        String wordId = intent.getStringExtra(TPNotifyCore.EXTRA_WORD);
        long later = TPNotifyCore.reserveSlot(context);
        if (later > 0) {
            // Another word was just shown: push this one back by 1 min + random seconds.
            TPNotifyCore.setAlarmAt(context, id, title, body, sound, wordId, later);
            return;
        }
        TPNotifyCore.post(context, id, title, body, sound, wordId);
    }
}
