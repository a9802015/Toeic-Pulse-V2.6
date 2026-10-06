/* TOEIC Pulse - voice settings (speech rate + read Chinese meaning). Exposes window.TPVoice.
 * Loaded before the app bundle; works in the browser (Web Speech) and in the Android app (TPNotify TTS). */
(function () {
  var K_RATE = 'toeic_speech_rate_v1', K_ZH = 'toeic_speech_read_zh_v1';
  var MIN_RATE = 0.5, MAX_RATE = 1.5, DEF_RATE = 0.9;
  var gen = 0, pendingZh = null, pendingGen = -1;

  function num(v, d) { var n = parseFloat(v); return isFinite(n) ? n : d; }
  function clampRate(r) { return Math.max(MIN_RATE, Math.min(MAX_RATE, Math.round(r * 100) / 100)); }

  function rate() {
    try { return clampRate(num(localStorage.getItem(K_RATE), DEF_RATE)); } catch (e) { return DEF_RATE; }
  }
  function setRate(r) { try { localStorage.setItem(K_RATE, String(clampRate(r))); } catch (e) {} }
  function readZh() { try { return localStorage.getItem(K_ZH) === '1'; } catch (e) { return false; } }
  function setReadZh(on) { try { localStorage.setItem(K_ZH, on ? '1' : '0'); } catch (e) {} }

  /* "基準、標竿；參照…來衡量" -> "基準". Takes only the first (most common) sense:
   * cuts at the first separator and drops bracketed notes like （常用於…） or [美]. */
  function primaryMeaning(zh) {
    var s = String(zh || '')
      .replace(/[（(\[【〔][^）)\]】〕]*[）)\]】〕]/g, '')
      .replace(/^\s*(n|v|adj|adv|prep|conj|pron)\.\s*/i, '');
    var first = s.split(/[、，,；;／/|｜。．\n]|\s{2,}|…/)[0] || '';
    first = first.replace(/^[\s\-–—:：]+|[\s\-–—:：]+$/g, '');
    return first || s.trim();
  }

  // Most generated entries in the word bank carry a placeholder instead of a real meaning
  // ("XXX領域的核心實用商務語彙"); never read those aloud.
  function isPlaceholder(zh) { return /領域的核心實用商務語彙\s*$/.test(String(zh || '')); }

  function speakZhNow(text, r) {
    if (!text) return;
    if (window.TPN && window.TPN.speak) {
      window.TPN.speak(text, 'zh-TW', r).catch(function () {});
      return;
    }
    if (!('speechSynthesis' in window)) return;
    try {
      var u = new SpeechSynthesisUtterance(text);
      u.lang = 'zh-TW'; u.rate = r; u.pitch = 1;
      var vs = window.speechSynthesis.getVoices() || [];
      var v = vs.find(function (x) { return /zh[-_]TW/i.test(x.lang); }) ||
              vs.find(function (x) { return /zh[-_](HK|Hant)/i.test(x.lang); }) ||
              vs.find(function (x) { return /^zh/i.test(x.lang); });
      if (v) u.voice = v;
      window.speechSynthesis.speak(u);
    } catch (e) {}
  }

  window.TPVoice = {
    MIN_RATE: MIN_RATE, MAX_RATE: MAX_RATE, DEF_RATE: DEF_RATE,
    rate: rate, setRate: setRate,
    readZh: readZh, setReadZh: setReadZh,
    primaryMeaning: primaryMeaning,
    isPlaceholder: isPlaceholder,
    /* Called by the app for every new English utterance; returns its generation id. */
    begin: function (zhFull) {
      gen += 1;
      pendingZh = readZh() && zhFull && !isPlaceholder(zhFull) ? primaryMeaning(zhFull) : null;
      pendingGen = gen;
      return gen;
    },
    current: function () { return gen; },
    /* Called when the English audio for generation g has finished normally. */
    finish: function (g) {
      if (g !== gen || g !== pendingGen || !pendingZh) return;
      var t = pendingZh; pendingZh = null;
      setTimeout(function () { if (g === gen) speakZhNow(t, rate()); }, 250);
    },
    cancel: function () { gen += 1; pendingZh = null; }
  };
})();
