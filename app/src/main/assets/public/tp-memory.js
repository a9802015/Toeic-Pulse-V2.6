/* TOEIC Pulse - selectable memory / spaced-repetition methods.
 * Loaded before the app bundle (web + Android). Exposes window.TPMem.
 * Every method keeps the same progress fields the app already uses (stage 0-6, nextReviewAt, ...),
 * so the stats page, daily word picker and notifications keep working whichever method is chosen.
 * Switching methods keeps each word's progress; the new method takes over from the next swipe. */
(function () {
  var KEY = 'toeic_pulse_memory_method_v1';
  var DAY = 864e5, MIN = 6e4, HOUR = 36e5;

  var METHODS = {
    ebbinghaus: {
      name: '艾賓豪斯',
      label: '艾賓豪斯遺忘曲線',
      desc: '1→2→4→7→15→30 天',
      detail: '依遺忘曲線在記憶快消失前安排複習。連續答對 2 次升一級，答錯降一級。（原本的預設方法）',
      labels: ['初次學習 (0天)', '第1次複習 (+1天)', '第2次複習 (+2天)', '第3次複習 (+4天)', '第4次複習 (+7天)', '第5次複習 (+15天)', '長期記憶 (+30天)']
    },
    sm2: {
      name: 'SM-2',
      label: 'SM-2 (Anki 演算法)',
      desc: '依難易度自動調整間隔',
      detail: 'SuperMemo / Anki 使用的演算法。每個單字有自己的「容易度」：常答對的間隔會越拉越長（1→6→15→40 天…），常答錯的會變短並更常出現。',
      labels: ['新卡片', '間隔 ≤1天', '間隔 ≤2天', '間隔 ≤4天', '間隔 ≤7天', '間隔 ≤15天', '長期記憶 (>15天)']
    },
    leitner: {
      name: '萊特納盒子',
      label: '萊特納盒子法',
      desc: '答對升盒，答錯回第 1 盒',
      detail: '單字放在 7 個盒子裡，盒子越後面複習越少（0/1/3/7/14/30/60 天）。答對往後移一盒，答錯直接回到第 1 盒，嚴格逼你真正記住。',
      labels: ['第1盒 (當天)', '第2盒 (+1天)', '第3盒 (+3天)', '第4盒 (+7天)', '第5盒 (+14天)', '第6盒 (+30天)', '第7盒 (+60天)']
    },
    pimsleur: {
      name: '皮姆斯勒',
      label: '皮姆斯勒分級回想',
      desc: '當天短間隔反覆回想',
      detail: '語言學家 Pimsleur 的分級間隔：先在同一天內用很短的間隔反覆回想，再逐步拉長到數天。適合新單字的「當天鞏固」。答錯退兩級。',
      labels: ['初次學習', '+10分鐘', '+1小時', '+5小時', '+1天', '+5天', '+25天']
    },
    cram: {
      name: '考前衝刺',
      label: '考前衝刺模式',
      desc: '最長 5 天，高頻輪替',
      detail: '適合考試前 1~2 週：間隔很短（6小時→半天→1→2→3→5 天），所有單字都會頻繁出現。答錯立刻回到起點。',
      labels: ['衝刺起點', '+6小時', '+12小時', '+1天', '+2天', '+3天', '+5天']
    }
  };
  var ORDER = ['ebbinghaus', 'sm2', 'leitner', 'pimsleur', 'cram'];

  var STEP_MS = {
    leitner: [0, 1, 3, 7, 14, 30, 60].map(function (d) { return d * DAY; }),
    pimsleur: [0, 10 * MIN, HOUR, 5 * HOUR, DAY, 5 * DAY, 25 * DAY],
    cram: [0, 6 * HOUR, 12 * HOUR, DAY, 2 * DAY, 3 * DAY, 5 * DAY]
  };

  function get() {
    try { var v = localStorage.getItem(KEY); if (v && METHODS[v]) return v; } catch (e) {}
    return 'ebbinghaus';
  }
  function set(id) {
    if (!METHODS[id]) return;
    try { localStorage.setItem(KEY, id); } catch (e) {}
    listeners.forEach(function (f) { try { f(id); } catch (e) {} });
  }
  var listeners = [];

  function clampStage(s) { return Math.max(0, Math.min(6, s | 0)); }

  /* A review that comes too early (less than half the current interval) does not promote:
   * swiping the same card 10 times in one session should not jump it to a 60-day interval. */
  function dueEnough(p, nowMs, curMs) {
    if (!curMs) return true;
    var last = Date.parse(p.lastReviewedAt || '') || 0;
    return nowMs - last >= curMs * 0.5;
  }

  function stageFromDays(d) {
    if (d <= 0) return 0;
    if (d <= 1) return 1; if (d <= 2) return 2; if (d <= 4) return 3;
    if (d <= 7) return 4; if (d <= 15) return 5; return 6;
  }

  /* Returns the updated progress object after one swipe (right = remembered). */
  function review(p, right, nowIso) {
    var now = new Date(nowIso || new Date().toISOString()), nowMs = now.getTime();
    var rs = p.rightSwipes + (right ? 1 : 0), ls = p.leftSwipes + (right ? 0 : 1), tot = rs + ls;
    var err = tot > 0 ? Number((ls / tot).toFixed(3)) : 0;
    var stage = clampStage(p.stage), cons = p.consecutiveRight || 0;
    var m = get(), extra = {}, nextMs;

    if (m === 'sm2') {
      var ease = typeof p.ease === 'number' ? p.ease : 2.5;
      var reps = p.reps | 0, ivl = typeof p.intervalDays === 'number' ? p.intervalDays : 0;
      if (right) {
        cons += 1;
        if (dueEnough(p, nowMs, ivl * DAY)) {
          reps += 1;
          ivl = reps === 1 ? 1 : reps === 2 ? 6 : Math.min(365, Math.round(ivl * ease));
          ease = Math.min(3.0, ease + 0.05);
        }
      } else {
        cons = 0; reps = 0; ivl = 0;
        ease = Math.max(1.3, ease - 0.2);
      }
      stage = stageFromDays(ivl);
      nextMs = nowMs + (ivl > 0 ? ivl * DAY : 10 * MIN);
      extra = { ease: Number(ease.toFixed(2)), reps: reps, intervalDays: ivl };
    } else if (STEP_MS[m]) {
      var steps = STEP_MS[m];
      if (right) {
        cons += 1;
        if (dueEnough(p, nowMs, steps[stage]) && stage < 6) stage += 1;
      } else {
        cons = 0;
        stage = m === 'pimsleur' ? Math.max(0, stage - 2) : 0;
      }
      nextMs = nowMs + (steps[stage] || 10 * MIN);
    } else {
      // Ebbinghaus: original app behaviour, unchanged.
      var D = [0, 1, 2, 4, 7, 15, 30];
      if (right) { cons += 1; if (cons % 2 === 0 && stage < 6) stage += 1; }
      else { cons = 0; stage = Math.max(0, stage - 1); }
      nextMs = nowMs + (D[stage] || 1) * DAY;
    }

    var mastered = stage >= 5 || (rs >= 8 && err < 0.2);
    var out = {};
    for (var k in p) if (Object.prototype.hasOwnProperty.call(p, k)) out[k] = p[k];
    for (var x in extra) out[x] = extra[x];
    out.stage = stage;
    out.lastReviewedAt = now.toISOString();
    out.nextReviewAt = new Date(nextMs).toISOString();
    out.totalReviews = tot;
    out.rightSwipes = rs;
    out.leftSwipes = ls;
    out.consecutiveRight = cons;
    out.errorRate = err;
    out.isMastered = mastered;
    return out;
  }

  function labels() { return METHODS[get()].labels; }
  // Array-like view of the current method's stage labels (used as Fe[stage] / Fe.map in the bundle).
  var labelsProxy = new Proxy([], {
    get: function (_, prop) {
      var L = labels(), v = L[prop];
      return typeof v === 'function' ? v.bind(L) : v;
    }
  });

  window.TPMem = {
    methods: METHODS,
    order: ORDER,
    get: get,
    set: set,
    onChange: function (f) { listeners.push(f); return function () { listeners = listeners.filter(function (g) { return g !== f; }); }; },
    name: function () { return METHODS[get()].name; },
    info: function (id) { return METHODS[id || get()]; },
    labels: labels,
    labelsProxy: labelsProxy,
    review: review
  };
})();
