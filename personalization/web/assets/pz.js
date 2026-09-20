/* 개인화 프론트 공용 런타임.
   지금은 fixture(목 계약)를 읽는다. 실험이 끝나면 같은 모양의 실 API로 교체한다.
   목과 실데이터를 화면이 스스로 구분하도록 MOCK 배지를 헤더에 강제한다. */
(function (global) {
  'use strict';

  // 실 API가 붙기 전까지 true. fixture에는 "_mock": true 가 박혀 있다.
  var MOCK = true;
  var API = '/api/v1/personalization';

  // ---------- 유틸 ----------
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function qs(name) { return new URLSearchParams(location.search).get(name); }
  function num(n, digits) {
    if (n == null || isNaN(n)) return '—';
    return Number(n).toLocaleString('ko-KR', { maximumFractionDigits: digits == null ? 0 : digits });
  }
  function ms(n) { return n == null ? '—' : num(n, n < 10 ? 1 : 0) + 'ms'; }
  function pct(n, d) { return n == null ? '—' : num(n, d == null ? 1 : d) + '%'; }
  function gradient(seed) {
    var h = (Number(String(seed).replace(/\D/g, '') || 0) * 47) % 360;
    return 'linear-gradient(140deg,hsl(' + h + ',45%,56%),hsl(' + ((h + 40) % 360) + ',50%,40%))';
  }

  // ---------- 개발자 로그 드로어 ----------
  var logN = 0;
  function ensureDrawer() {
    if (document.getElementById('logbar')) return;
    var el = document.createElement('div');
    el.id = 'logbar';
    el.innerHTML =
      '<div class="bar" id="logbar-bar"><span class="t">DEVELOPER LOG — 데이터 출처 · 요청</span>' +
      '<span class="t" id="logCnt" style="color:#6EA8FF">0</span>' +
      '<div class="spacer"></div><span class="t">↕</span></div>' +
      '<pre class="log" id="log"><span class="dim">// fixture 로드와 (나중에) 실제 API 호출이 여기에 기록됩니다</span></pre>';
    document.body.appendChild(el);
    document.getElementById('logbar-bar').addEventListener('click', function () { el.classList.toggle('open'); });
  }
  function log(cls, txt) {
    var box = document.getElementById('log');
    if (!box) return;
    box.insertAdjacentHTML('beforeend', '\n<span class="' + cls + '">' + esc(txt) + '</span>');
    box.scrollTop = box.scrollHeight;
    var c = document.getElementById('logCnt');
    if (c) c.textContent = ++logN;
  }

  // ---------- fixture / api ----------
  async function fixture(name) {
    log('req', '→ fixture ' + name + '.json');
    try {
      var res = await fetch('fixtures/' + name + '.json', { cache: 'no-store' });
      var data = await res.json();
      log('ok', '✓ ' + name + ' (' + (data._mock ? 'MOCK' : 'REAL') + ')');
      return data;
    } catch (e) {
      log('err', '✗ fixture 로드 실패: ' + e);
      return null;
    }
  }

  /** 실 API가 붙으면 fixture 대신 이걸 쓴다. 지금은 자리만 잡아 둔다. */
  async function api(method, path, body) {
    log('req', '→ ' + method + ' ' + path + (body ? ' ' + JSON.stringify(body) : ''));
    var res = await fetch(API + path, {
      method: method,
      headers: { 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined
    });
    var data = null;
    try { data = await res.json(); } catch (e) { /* 비어 있을 수 있다 */ }
    log(res.ok ? 'ok' : 'err', (res.ok ? '✓ ' : '✗ ') + res.status);
    return { ok: res.ok, status: res.status, data: data };
  }

  // ---------- 헤더 ----------
  function header(active) {
    var el = document.getElementById('app-top');
    if (!el) return;
    el.className = 'topbar';
    el.innerHTML =
      '<div class="container topbar-in">' +
        '<a class="brand" href="index.html">BE<b>-commerce</b></a>' +
        '<span class="tag">PERSONALIZATION</span>' +
        '<nav class="nav">' +
          '<a href="index.html"' + (active === 'home' ? ' class="on"' : '') + '>개인화 홈</a>' +
          '<a href="console.html"' + (active === 'console' ? ' class="on"' : '') + '>실험 콘솔</a>' +
          '<a href="../docs/">설계</a>' +
        '</nav>' +
        '<div class="spacer"></div>' +
        '<span class="mock' + (MOCK ? '' : ' off') + '" id="mock-badge">' + (MOCK ? 'MOCK 데이터' : '실데이터') + '</span>' +
      '</div>';
  }

  // ---------- 차트 (인라인 SVG, 의존성 없음) ----------
  /**
   * @param {Element} el
   * @param {{name:string, cls:string, points:[number,number][]}[]} series
   */
  function line(el, series, opts) {
    opts = opts || {};
    var W = opts.width || 720, H = opts.height || 240, P = { t: 14, r: 14, b: 34, l: 52 };
    var pts = series.flatMap(function (s) { return s.points; });
    if (!pts.length) { el.innerHTML = '<p class="muted">데이터가 없습니다.</p>'; return; }

    var xs = pts.map(function (p) { return p[0]; }), ys = pts.map(function (p) { return p[1]; });
    var xMin = opts.xMin != null ? opts.xMin : Math.min.apply(null, xs);
    var xMax = opts.xMax != null ? opts.xMax : Math.max.apply(null, xs);
    var yMin = opts.yMin != null ? opts.yMin : Math.min.apply(null, ys);
    var yMax = opts.yMax != null ? opts.yMax : Math.max.apply(null, ys);
    if (xMax === xMin) xMax = xMin + 1;
    if (yMax === yMin) yMax = yMin + 1;
    // y축은 0에서 시작하지 않을 수 있다(작은 차이를 보려는 그래프가 많다). opts.y0=true면 0부터.
    if (opts.y0) yMin = 0;

    var iw = W - P.l - P.r, ih = H - P.t - P.b;
    var X = function (v) { return P.l + (v - xMin) / (xMax - xMin) * iw; };
    var Y = function (v) { return P.t + (1 - (v - yMin) / (yMax - yMin)) * ih; };

    var svg = ['<svg class="chart" viewBox="0 0 ' + W + ' ' + H + '" role="img" preserveAspectRatio="xMidYMid meet">'];
    for (var i = 0; i <= 4; i++) {
      var yv = yMin + (yMax - yMin) * i / 4, y = Y(yv);
      svg.push('<line class="gridline" x1="' + P.l + '" y1="' + y + '" x2="' + (W - P.r) + '" y2="' + y + '"/>');
      svg.push('<text class="tick" x="' + (P.l - 8) + '" y="' + (y + 3) + '" text-anchor="end">' +
        num(yv, yMax - yMin < 10 ? 1 : 0) + '</text>');
    }
    var xTicks = opts.xTicks || [xMin, (xMin + xMax) / 2, xMax];
    xTicks.forEach(function (xv) {
      var x = X(xv);
      svg.push('<text class="tick" x="' + x + '" y="' + (H - 12) + '" text-anchor="middle">' + num(xv, 0) + '</text>');
    });
    svg.push('<line class="axis" x1="' + P.l + '" y1="' + (H - P.b) + '" x2="' + (W - P.r) + '" y2="' + (H - P.b) + '"/>');
    series.forEach(function (s) {
      var d = s.points.map(function (p, idx) {
        return (idx ? 'L' : 'M') + X(p[0]).toFixed(1) + ' ' + Y(p[1]).toFixed(1);
      }).join(' ');
      svg.push('<path class="series ' + (s.cls || 's-a') + '" d="' + d + '"/>');
      s.points.forEach(function (p) {
        svg.push('<circle class="' + (s.cls || 's-a') + '" cx="' + X(p[0]).toFixed(1) + '" cy="' + Y(p[1]).toFixed(1) +
          '" r="2.6" fill="currentColor" stroke="none"/>');
      });
    });
    if (opts.xLabel) svg.push('<text class="label" x="' + (W / 2) + '" y="' + (H - 1) + '" text-anchor="middle">' + esc(opts.xLabel) + '</text>');
    svg.push('</svg>');
    el.innerHTML = svg.join('');
  }

  function legend(el, series) {
    el.innerHTML = series.map(function (s) {
      return '<span><i class="' + (s.cls || 's-a') + '" style="background:currentColor"></i>' + esc(s.name) + '</span>';
    }).join('');
  }

  function table(el, cols, rows) {
    el.innerHTML = '<table class="t"><thead><tr>' +
      cols.map(function (c) { return '<th' + (c.num ? ' class="num"' : '') + '>' + esc(c.label) + '</th>'; }).join('') +
      '</tr></thead><tbody>' +
      rows.map(function (r) {
        return '<tr>' + cols.map(function (c) {
          var v = r[c.key];
          return '<td' + (c.num ? ' class="num"' : '') + '>' + (c.fmt ? c.fmt(v) : esc(v)) + '</td>';
        }).join('') + '</tr>';
      }).join('') +
      '</tbody></table>';
  }

  function kpi(label, value, desc, kind) {
    return '<div class="kpi ' + (kind || '') + '"><div class="k">' + esc(label) + '</div>' +
      '<div class="v">' + value + '</div><div class="d">' + esc(desc || '') + '</div></div>';
  }

  var STATUS = {
    idea: { cls: 'b-', label: '설계' },
    todo: { cls: 'b-', label: '측정 전' },
    running: { cls: 'b-warn', label: '측정 중' },
    done: { cls: 'b-ok', label: '측정 완료' }
  };
  function statusBadge(s) {
    var st = STATUS[s] || STATUS.todo;
    return '<span class="badge ' + st.cls + '">' + st.label + '</span>';
  }

  function footer(text) {
    var el = document.getElementById('app-foot');
    if (!el) return;
    el.className = 'container foot-note';
    el.innerHTML = text || '';
  }

  document.addEventListener('DOMContentLoaded', function () { ensureDrawer(); });

  global.PZ = {
    MOCK: MOCK, esc: esc, qs: qs, num: num, ms: ms, pct: pct, gradient: gradient,
    fixture: fixture, api: api, log: log,
    header: header, footer: footer, line: line, legend: legend, table: table, kpi: kpi, statusBadge: statusBadge
  };
})(window);
