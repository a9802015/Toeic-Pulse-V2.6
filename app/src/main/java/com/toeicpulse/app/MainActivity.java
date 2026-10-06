package com.toeicpulse.app;

import android.content.Intent;
import android.os.Bundle;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Register the custom notification plugin before the bridge is created.
        registerPlugin(TPNotifyPlugin.class);
        super.onCreate(savedInstanceState);
        captureOpenedWord(getIntent());
    }

    /** App already running (background or foreground) and a notification was tapped. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureOpenedWord(intent);
        notifyWebView();
    }

    @Override
    public void onResume() {
        super.onResume();
        notifyWebView();
    }

    /** Remember which word's notification was tapped so the web app can jump to that card. */
    private void captureOpenedWord(Intent intent) {
        if (intent == null) return;
        String w = intent.getStringExtra(TPNotifyCore.EXTRA_OPEN_WORD);
        if (w != null && !w.isEmpty()) {
            TPNotifyPlugin.pendingOpenWord = w;
            intent.removeExtra(TPNotifyCore.EXTRA_OPEN_WORD);
        }
    }

    /**
     * Capacitor does not send resume events to the page by itself, so push a check into the WebView.
     * If the page is not loaded yet (cold start) this is a no-op and the page checks on mount instead.
     */
    private void notifyWebView() {
        if (TPNotifyPlugin.pendingOpenWord == null) return;
        Bridge b = getBridge();
        if (b == null) return;
        b.eval("window.__tpCheckOpen && window.__tpCheckOpen()", null);
    }
}
