/* TOEIC Pulse - native (Capacitor/Android) bridge helpers.
 * Loaded before the app bundle. Exposes window.TPN only inside the Android app. */
(function () {
  var cap = window.Capacitor;
  var isNative = !!(cap && typeof cap.isNativePlatform === 'function' && cap.isNativePlatform() && typeof cap.nativePromise === 'function');

  // The native app must not use the PWA service worker: its cache-first strategy can keep
  // serving an old bundle after the APK is updated. Remove any registration and its caches.
  if (isNative) {
    try {
      if ('serviceWorker' in navigator) {
        navigator.serviceWorker.getRegistrations().then(function (regs) {
          regs.forEach(function (r) { r.unregister(); });
        }).catch(function () {});
      }
      if (window.caches && caches.keys) {
        caches.keys().then(function (keys) { keys.forEach(function (k) { caches.delete(k); }); }).catch(function () {});
      }
    } catch (e) {}
  }

  if (!isNative) { window.TPN = null; return; }

  function call(method, opts) { return cap.nativePromise('TPNotify', method, opts || {}); }

  function hm(s) { var p = String(s || '').split(':'); return (parseInt(p[0], 10) || 0) * 60 + (parseInt(p[1], 10) || 0); }
  function inWindow(d, st, en) { var m = d.getHours() * 60 + d.getMinutes(); return st <= en ? (m >= st && m <= en) : (m >= st || m <= en); }
  function dayOk(d, days) { return !days || !days.length || days.indexOf(d.getDay()) >= 0; }

  /* Build the notification plan for the next few days, honouring the time window,
   * active weekdays, frequency (per-word daily cap + random gap) and "stop when goal completed".
   * "ultra" = 20 per word per day: the gap adapts to the window length and word count so every
   * word can actually reach 20 sends, and the plan covers fewer days (Android allows ~500 alarms). */
  function buildPlan(settings, cands, completedToday) {
    settings = settings || {};
    if (!cands || !cands.length) return [];
    var freq = settings.frequency || 'high';
    var perWord = ({ ultra: 20, high: 7, medium: 5, low: 3 })[freq] || 7;
    var st = hm(settings.startTime || '09:00'), en = hm(settings.endTime || '21:00');
    var winMin = st <= en ? (en - st) : (1440 - st + en);
    if (winMin <= 0) winMin = 1440;
    var maxItems = freq === 'ultra' ? 400 : 64;
    var days = freq === 'ultra' ? 2 : 3;
    var ultraAvg = Math.max(3, Math.min(30, winMin / (cands.length * perWord)));
    var gap = function () {
      var min = freq === 'ultra' ? ultraAvg * (0.7 + Math.random() * 0.6)
        : freq === 'high' ? 15 + Math.random() * 20
        : freq === 'medium' ? 30 + Math.random() * 30 : 60 + Math.random() * 45;
      return min * 60000;
    };
    var now = Date.now(), end = now + days * 864e5, t = now + gap();
    var todayKey = new Date(now).toDateString();
    var out = [], counts = {}, dayKey = null, rr = 0, step = (freq === 'ultra' ? 1 : 5) * 60000;
    while (t < end && out.length < maxItems) {
      var d = new Date(t), k = d.toDateString();
      if (!dayOk(d, settings.activeDays) || !inWindow(d, st, en) ||
          (completedToday && settings.stopWhenGoalCompleted && k === todayKey)) { t += step; continue; }
      if (k !== dayKey) { dayKey = k; counts = {}; }
      var best = null;
      for (var i = 0; i < cands.length; i++) {
        var c = cands[(rr + i) % cands.length], n = counts[c.id] || 0;
        if (n < perWord && (!best || n < (counts[best.id] || 0))) best = c;
      }
      if (!best) { t += step; continue; }
      counts[best.id] = (counts[best.id] || 0) + 1; rr++;
      out.push({ id: 5000 + out.length, at: Math.round(t), title: best.title, body: best.body, wordId: best.id });
      t += gap();
    }
    return out;
  }

  window.TPN = {
    isNative: true,
    check: function () { return call('checkPermission'); },
    request: function () { return call('requestPermission'); },
    show: function (id, title, body, sound, wordId) { return call('show', { id: id, title: title, body: body, sound: sound !== false, wordId: wordId || null }); },
    /* Word id of the notification the user tapped to open the app (null if none). */
    consumeOpenedWord: function () { return call('consumeOpenedWord', {}).then(function (r) { return (r && r.wordId) || null; }); },
    schedule: function (items, sound) { return call('schedule', { items: items || [], sound: sound !== false }); },
    cancelAll: function () { return call('cancelAll'); },
    openSettings: function () { return call('openSettings'); },
    /* Opens the Android 24-hour clock picker. Resolves "HH:MM", or null if cancelled. */
    pickTime: function (hhmm) {
      var p = String(hhmm || '09:00').split(':');
      return call('pickTime', { hour: parseInt(p[0], 10) || 0, minute: parseInt(p[1], 10) || 0 }).then(function (r) {
        if (!r || r.cancelled) return null;
        return String(r.hour).padStart(2, '0') + ':' + String(r.minute).padStart(2, '0');
      });
    },
    speak: function (text, lang, rate) { return call('speak', { text: text, lang: lang || 'en-US', rate: rate || 0.9 }); },
    stopSpeak: function () { return call('stopSpeak'); },
    buildPlan: buildPlan
  };
})();
