/* 스토어프론트 공용 런타임 — 모든 화면이 이 파일 하나를 공유한다.
   API 호출(Bearer·멱등키·개발자 로그), 인증, 장바구니, 카탈로그 캐시, 헤더/푸터 주입을 담는다.

   이 저장소의 다른 화면과 같은 원칙을 지킨다: 화면은 미리 준비한 응답을 그리지 않고
   실제 API 응답을 그린다. 하단 개발자 로그에서 전 요청·응답을 확인할 수 있다. */
(function (global) {
  'use strict';

  var API = '/api/v1';
  var KEY = { token: 'becommerce.token', refresh: 'becommerce.refreshToken', cart: 'becommerce.cart' };

  // ---------- 유틸 ----------
  function won(n) { return '₩' + Number(n || 0).toLocaleString('ko-KR'); }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function uuid() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
      var r = Math.random() * 16 | 0;
      return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
    });
  }
  function qs(name) { return new URLSearchParams(location.search).get(name); }
  function fmtDate(iso) {
    if (!iso) return '—';
    var d = new Date(iso);
    if (isNaN(d)) return '—';
    var p = function (n) { return String(n).padStart(2, '0'); };
    return d.getFullYear() + '.' + p(d.getMonth() + 1) + '.' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  }
  /** 상품 id로 결정적 그라디언트 — 이미지가 안 뜰 때 카드가 비지 않게 한다. */
  function gradientFor(seed) {
    var h = (Number(seed) * 47) % 360;
    return 'linear-gradient(140deg,hsl(' + h + ',48%,54%),hsl(' + ((h + 42) % 360) + ',52%,38%))';
  }

  // ---------- 개발자 로그 드로어 ----------
  var logN = 0;
  function ensureDrawer() {
    if (document.getElementById('logbar')) return;
    var el = document.createElement('div');
    el.id = 'logbar';
    el.innerHTML =
      '<div class="bar" id="logbar-bar"><span class="t">DEVELOPER LOG — 실제 요청 · 응답</span>' +
      '<span class="cnt" id="logCnt">0</span><div class="spacer"></div><span class="t">↕</span></div>' +
      '<pre class="log" id="log"><span class="dim">// 여기에 요청·응답이 기록됩니다</span></pre>';
    document.body.appendChild(el);
    document.getElementById('logbar-bar').addEventListener('click', function () {
      el.classList.toggle('open');
    });
  }
  function logLine(cls, txt) {
    var box = document.getElementById('log');
    if (!box) return;
    box.insertAdjacentHTML('beforeend', '\n<span class="' + cls + '">' + esc(txt) + '</span>');
    box.scrollTop = box.scrollHeight;
    var cnt = document.getElementById('logCnt');
    if (cnt) cnt.textContent = ++logN;
  }

  // ---------- API ----------
  /**
   * @param {string} method
   * @param {string} path  '/products' 처럼 /api/v1 뒤 경로만
   * @param {{body?:object, noAuth?:boolean, idempotency?:boolean}} [opts]
   */
  async function api(method, path, opts) {
    opts = opts || {};
    var headers = { 'Content-Type': 'application/json' };
    var token = auth.token();
    if (!opts.noAuth && token) headers['Authorization'] = 'Bearer ' + token;
    if (method !== 'GET' && opts.idempotency !== false) headers['Idempotency-Key'] = uuid();

    logLine('req', '→ ' + method + ' ' + path + (opts.body ? ' ' + JSON.stringify(opts.body) : ''));
    var res;
    try {
      res = await fetch(API + path, {
        method: method,
        headers: headers,
        body: opts.body ? JSON.stringify(opts.body) : undefined
      });
    } catch (e) {
      logLine('err', '✗ 네트워크 오류 ' + e);
      return { ok: false, status: 0, data: null, networkError: true };
    }
    var text = await res.text();
    var data = null;
    try { data = text ? JSON.parse(text) : null; } catch (e) { data = text; }
    logLine(res.ok ? 'ok' : 'err', (res.ok ? '✓ ' : '✗ ') + res.status + ' ' +
      (data && typeof data === 'object' ? JSON.stringify(data) : data));
    return { ok: res.ok, status: res.status, data: data };
  }

  // ---------- 인증 ----------
  function decodeJwt(token) {
    try {
      var payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      return JSON.parse(decodeURIComponent(escape(atob(payload))));
    } catch (e) { return {}; }
  }
  var auth = {
    token: function () { return localStorage.getItem(KEY.token); },
    refreshToken: function () { return localStorage.getItem(KEY.refresh); },
    isLoggedIn: function () { return !!localStorage.getItem(KEY.token); },
    userId: function () {
      var t = this.token();
      return t ? decodeJwt(t).sub : null;
    },
    role: function () {
      var t = this.token();
      var roles = t ? decodeJwt(t).roles : null;
      return roles && roles.length ? roles[0].replace('ROLE_', '') : null;
    },
    save: function (token, refreshToken) {
      localStorage.setItem(KEY.token, token);
      if (refreshToken) localStorage.setItem(KEY.refresh, refreshToken);
    },
    clear: function () {
      localStorage.removeItem(KEY.token);
      localStorage.removeItem(KEY.refresh);
    },
    async login(username, password) {
      var r = await api('POST', '/auth/login', { body: { username: username, password: password }, noAuth: true });
      if (r.ok && r.data && r.data.token) this.save(r.data.token, r.data.refreshToken);
      return r;
    },
    async signup(email, password) {
      return api('POST', '/members/signup', { body: { email: email, password: password }, noAuth: true });
    },
    async logout() {
      if (this.isLoggedIn()) {
        await api('POST', '/auth/logout', { body: { refreshToken: this.refreshToken() } });
      }
      this.clear();
      // 앞 사람의 찜이 다음 사용자 화면에 남으면 안 된다.
      wishlist.clear();
    },
    /** 결제·주문처럼 로그인이 필요한 화면의 진입 가드. 로그인 후 돌아올 곳을 남긴다. */
    require: function () {
      if (this.isLoggedIn()) return true;
      var next = location.pathname.split('/').pop() + location.search;
      location.href = 'login.html?next=' + encodeURIComponent(next);
      return false;
    }
  };

  // ---------- 장바구니 (localStorage) ----------
  var cart = {
    all: function () {
      try { return JSON.parse(localStorage.getItem(KEY.cart)) || []; } catch (e) { return []; }
    },
    _save: function (items) {
      localStorage.setItem(KEY.cart, JSON.stringify(items));
      updateCartBadge();
    },
    add: function (product, qty) {
      qty = Math.max(1, Number(qty) || 1);
      var items = this.all();
      var found = items.find(function (i) { return i.productId === product.productId; });
      if (found) found.quantity += qty;
      else items.push({
        productId: product.productId, name: product.name, price: product.price,
        imageUrl: product.imageUrl, brand: product.brand, quantity: qty
      });
      this._save(items);
    },
    setQty: function (productId, qty) {
      qty = Number(qty) || 1;
      var items = this.all();
      var it = items.find(function (i) { return i.productId === productId; });
      if (it) it.quantity = Math.max(1, qty);
      this._save(items);
    },
    remove: function (productId) {
      this._save(this.all().filter(function (i) { return i.productId !== productId; }));
    },
    clear: function () { this._save([]); },
    count: function () { return this.all().reduce(function (n, i) { return n + i.quantity; }, 0); },
    subtotal: function () { return this.all().reduce(function (n, i) { return n + i.price * i.quantity; }, 0); },
    /** 주문 생성용 — 서버는 productId·quantity만 받는다(가격은 서버가 확정). */
    toOrderLines: function () {
      return this.all().map(function (i) { return { productId: i.productId, quantity: i.quantity }; });
    }
  };

  function updateCartBadge() {
    var el = document.getElementById('cart-badge');
    if (!el) return;
    var n = cart.count();
    el.textContent = n;
    el.hidden = n === 0;
  }

  // ---------- 위시리스트(찜) ----------
  /* 장바구니와 달리 서버에 저장한다. 찜은 개인화 신호라 브라우저에만 두면 쓸 수 없기 때문이다.
     그래서 로그인이 필요하고, 비로그인은 로그인 화면으로 유도한다(장바구니와 정책이 갈리는 지점).
     하트 상태는 그리드마다 그려지므로 상품 id 집합을 한 번 받아 캐시하고, 화면의 하트를 다시 칠한다. */
  var wishlistItems = [];
  var wishlistIds = new Set();
  var wishlistLoaded = false;

  var wishlist = {
    /** 내 찜 목록(서버). 하트 상태 캐시도 함께 갱신한다. force면 다시 받는다. */
    all: async function (force) {
      if (!this.loggedIn()) {
        wishlistItems = [];
        wishlistIds = new Set();
        wishlistLoaded = true;
        return [];
      }
      if (wishlistLoaded && !force) return wishlistItems;
      var r = await api('GET', '/wishlist');
      // 실패를 빈 목록으로 위장하지 않는다 — 하트는 꺼진 채로 두고 목록 화면이 오류를 보여준다.
      if (!r.ok) return wishlistItems;
      wishlistItems = Array.isArray(r.data) ? r.data : [];
      wishlistIds = new Set(wishlistItems.map(function (i) { return Number(i.productId); }));
      wishlistLoaded = true;
      return wishlistItems;
    },
    loggedIn: function () { return auth.isLoggedIn(); },
    has: function (productId) { return wishlistIds.has(Number(productId)); },
    ready: function () { return wishlistLoaded; },
    count: function () { return wishlistIds.size; },
    /** 추가 — 이미 있으면 서버가 그대로 성공을 돌려준다(멱등). */
    add: async function (productId) {
      if (!this.loggedIn()) { wishlistLoginPrompt(); return false; }
      var r = await api('POST', '/wishlist', { body: { productId: Number(productId) } });
      if (!r.ok) { toast(wishlistMessage(r, '찜하지 못했습니다'), 'err'); }
      await this.all(true);
      this.sync();
      return r.ok;
    },
    /** 삭제 — 찜하지 않은 상품이어도 서버가 204를 돌려준다(멱등). */
    remove: async function (productId) {
      if (!this.loggedIn()) { wishlistLoginPrompt(); return false; }
      var r = await api('DELETE', '/wishlist/' + Number(productId));
      if (!r.ok) { toast(wishlistMessage(r, '찜을 해제하지 못했습니다'), 'err'); }
      await this.all(true);
      this.sync();
      return r.ok;
    },
    toggle: async function (productId) {
      return this.has(productId) ? this.remove(productId) : this.add(productId);
    },
    /** 화면의 모든 하트를 캐시에 맞춰 다시 칠한다. 상세 버튼은 라벨까지 바꾼다. */
    sync: function () {
      var nodes = document.querySelectorAll('[data-wish]');
      for (var i = 0; i < nodes.length; i++) {
        var on = wishlistIds.has(Number(nodes[i].getAttribute('data-wish')));
        nodes[i].classList.toggle('on', on);
        nodes[i].setAttribute('aria-pressed', on ? 'true' : 'false');
        var label = nodes[i].querySelector('.wish-label');
        if (label) label.textContent = on ? '찜함' : '찜';
      }
    },
    /** 로그아웃 시 남은 캐시를 지운다 — 다음 사용자에게 앞 사람의 찜이 보이면 안 된다. */
    clear: function () {
      wishlistItems = [];
      wishlistIds = new Set();
      wishlistLoaded = false;
      this.sync();
    }
  };

  function wishlistMessage(r, fallback) {
    return (r && r.data && r.data.message) || fallback;
  }

  /** 비로그인 찜 — 로컬로 흉내내지 않고 로그인으로 유도한다(장바구니와 다른 지점). */
  function wishlistLoginPrompt() {
    toast('찜하려면 로그인이 필요합니다', 'err');
    var next = location.pathname.split('/').pop() + location.search;
    location.href = 'login.html?next=' + encodeURIComponent(next);
  }

  /**
   * 하트 클릭은 document 한 곳에서 위임한다. 상품 카드는 그리드마다 다시 그려지는데,
   * 카드마다 리스너를 달면 다시 그릴 때마다 새로 붙여야 하고 중복 바인딩이 생기기 쉽다.
   */
  function onWishClick(e) {
    var btn = e.target && e.target.closest ? e.target.closest('[data-wish]') : null;
    if (!btn) return;
    // 카드 전체가 <a>라서 막지 않으면 찜 클릭이 상세 이동으로 새어 나간다.
    e.preventDefault();
    e.stopPropagation();
    wishlist.toggle(btn.getAttribute('data-wish'));
  }

  /** 그리드가 비동기로 그려진 뒤에도 하트 상태를 맞춘다 — 렌더 시점을 각 화면이 알 필요가 없다. */
  function observeWishButtons() {
    if (!global.MutationObserver) return;
    var scheduled = false;
    new MutationObserver(function () {
      if (scheduled) return;
      scheduled = true;
      setTimeout(function () { scheduled = false; wishlist.sync(); }, 0);
    }).observe(document.body, { childList: true, subtree: true });
  }

  // ---------- 카탈로그 캐시 ----------
  var categoriesCache = null;
  var treeCache = null;
  async function categories(force) {
    if (categoriesCache && !force) return categoriesCache;
    var r = await api('GET', '/categories', { noAuth: true });
    categoriesCache = r.ok && Array.isArray(r.data) ? r.data : [];
    return categoriesCache;
  }

  /**
   * 대분류 + 중분류를 한 번에. **부모 다음에 그 자식들**이 오는 순서를 서버가 정한다.
   * 77행(대분류 5 + 중분류 72)이라 한 번에 받아도 작아서 사이드바를 요청 하나로 그린다.
   */
  async function categoryTree(force) {
    if (treeCache && !force) return treeCache;
    var r = await api('GET', '/categories?tree=true', { noAuth: true });
    treeCache = r.ok && Array.isArray(r.data) ? r.data : [];
    return treeCache;
  }

  /**
   * 필터 패널용 패싯 — 색상·상품 종류의 값과 개수를 받는다. params 는 /products 와 같은 필터 객체다.
   * 개수는 **자기 축을 뺀** 나머지 필터로 세어 오므로, 한 값을 고른 상태에서도 다른 값의 개수가 보인다.
   */
  async function facets(params) {
    var p = params || {};
    var query = Object.keys(p).filter(function (k) { return p[k] != null && p[k] !== ''; })
      .map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(p[k]); }).join('&');
    var r = await api('GET', '/products/facets' + (query ? '?' + query : ''), { noAuth: true });
    return r.ok && r.data ? r.data : { colours: [], productTypes: [] };
  }

  // ---------- 렌더 조각 ----------
  /** 상품 카드 HTML. 사진은 상품별 그라디언트에 블렌딩돼 장식 타일이 되고, 실패하면 그라디언트만 남는다. */
  function productCard(p) {
    var initial = (p.name || '?').trim().charAt(0);
    var tags = '';
    if (!p.inStock) tags += '<span class="tag tag-dim">품절</span>';
    else if (p.featured) tags += '<span class="tag">추천</span>';
    var visual = p.imageUrl
      ? '<img src="' + esc(p.imageUrl) + '" alt="' + esc(p.name) + '" loading="lazy" onerror="this.style.display=\'none\'">'
      : '<span class="fallback">' + esc(initial) + '</span>';
    // 하트는 목록·검색·홈이 모두 이 함수를 쓰므로 여기 한 곳에만 붙이면 전 표면에 적용된다.
    // 카드 전체가 <a>라 클릭이 상세로 새지 않게 위임 핸들러(onWishClick)가 막는다.
    var wish = '<button type="button" class="wish-btn" data-wish="' + p.productId +
      '" aria-label="찜" aria-pressed="false" title="찜">♥</button>';
    return '' +
      '<a class="card' + (p.inStock ? '' : ' sold') + '" href="product.html?id=' + p.productId + '">' +
        '<div class="thumb" style="background:' + gradientFor(p.productId) + '">' + visual + tags + wish + '</div>' +
        '<div class="card-body">' +
          '<span class="brand">' + esc(p.brand || p.categoryName || '') + '</span>' +
          '<span class="name">' + esc(p.name) + '</span>' +
          '<div class="price-row"><span class="price">' + won(p.price) + '</span></div>' +
        '</div>' +
      '</a>';
  }

  function skeletonGrid(n) {
    var out = '';
    for (var i = 0; i < (n || 8); i++) out += '<div class="skeleton"></div>';
    return out;
  }

  var STATUS_CLASS = {
    PAID: 'b-green', DONE: 'b-green', ACTIVE: 'b-green', RELEASED: 'b-green',
    PENDING_PAYMENT: 'b-amber', PAYMENT_IN_PROGRESS: 'b-amber', UNKNOWN: 'b-amber',
    PARTIAL_CANCELED: 'b-amber', IN_PROGRESS: 'b-amber', PENDING: 'b-amber',
    CANCELED: 'b-red', EXPIRED: 'b-red', FAILED: 'b-red', ABORTED: 'b-red', REFUNDED: 'b-red'
  };
  function statusBadge(status) {
    return '<span class="badge ' + (STATUS_CLASS[status] || 'b-dim') + '">' + esc(status || '—') + '</span>';
  }

  // ---------- 헤더·푸터 ----------
  function renderHeader(active) {
    var el = document.getElementById('app-header');
    if (!el) return;
    el.className = 'site-header';
    el.innerHTML = '' +
      '<div class="container header-inner">' +
        '<a class="logo" href="index.html">BE<b>-commerce</b></a>' +
        '<span class="brand-tag">STORE</span>' +
        '<nav class="site-nav" id="nav-cats"></nav>' +
        '<form class="header-search" onsubmit="return Store.search(this)">' +
          '<input name="q" placeholder="상품·브랜드 검색" value="' + esc(qs('q') || '') + '" aria-label="검색">' +
          '<button type="submit" aria-label="검색">⌕</button>' +
        '</form>' +
        '<div class="header-actions">' +
          '<a class="icon-btn" href="orders.html">주문내역</a>' +
          '<a class="icon-btn" href="cart.html">장바구니<span class="cart-badge" id="cart-badge" hidden>0</span></a>' +
          '<a class="icon-btn" href="login.html" id="acct-btn">로그인</a>' +
        '</div>' +
      '</div>';
    categories().then(function (list) {
      var nav = document.getElementById('nav-cats');
      if (!nav) return;
      nav.innerHTML = list.map(function (c) {
        return '<a href="category.html?code=' + encodeURIComponent(c.code) + '"' +
          (active === c.code ? ' class="on"' : '') + '>' + esc(c.name) + '</a>';
      }).join('');
    });
    renderAccount();
    updateCartBadge();
  }

  function renderAccount() {
    var btn = document.getElementById('acct-btn');
    if (!btn) return;
    btn.textContent = auth.isLoggedIn() ? '마이페이지' : '로그인';
    btn.href = auth.isLoggedIn() ? 'orders.html' : 'login.html';
  }

  function renderFooter() {
    var el = document.getElementById('app-footer');
    if (!el) return;
    el.className = 'site-footer';
    el.innerHTML = '' +
      '<div class="container">' +
        '<div class="footer-inner">' +
          '<div><div class="logo" style="margin-bottom:10px">BE<b>-commerce</b></div>' +
            '<p class="footer-note">실제 주문·결제 API에 연결된 스토어프론트입니다. ' +
            '주문 생성부터 승인·복구까지 서버 상태를 그대로 보여줍니다.</p></div>' +
          '<div><h4>쇼핑</h4><div class="footer-links" id="footer-cats"></div></div>' +
          '<div><h4>개발자</h4><div class="footer-links">' +
            '<a href="console.html">결제 콘솔</a>' +
            '<a href="admin.html">운영 백오피스</a>' +
            '<a href="https://github.com/dj258255/BE-commerce">저장소</a>' +
          '</div></div>' +
        '</div>' +
        '<div class="footer-bottom">' +
          '<span>로컬 데모 · 결제는 모의 PG로 처리됩니다</span>' +
          '<span class="mono">Spring Modulith · JWT · Outbox</span>' +
        '</div>' +
      '</div>';
    categories().then(function (list) {
      var box = document.getElementById('footer-cats');
      if (!box) return;
      box.innerHTML = list.map(function (c) {
        return '<a href="category.html?code=' + encodeURIComponent(c.code) + '">' + esc(c.name) + '</a>';
      }).join('');
    });
  }

  // ---------- 토스트 ----------
  function toast(message, kind) {
    var wrap = document.querySelector('.toast-wrap');
    if (!wrap) {
      wrap = document.createElement('div');
      wrap.className = 'toast-wrap';
      document.body.appendChild(wrap);
    }
    var el = document.createElement('div');
    el.className = 'toast' + (kind ? ' ' + kind : '');
    el.textContent = message;
    wrap.appendChild(el);
    setTimeout(function () { el.remove(); }, 3600);
  }

  function search(form) {
    var q = form.q.value.trim();
    location.href = 'search.html' + (q ? '?q=' + encodeURIComponent(q) : '');
    return false;
  }

  // ---------- 부트 ----------
  document.addEventListener('DOMContentLoaded', function () {
    ensureDrawer();
    updateCartBadge();
    observeWishButtons();
    document.addEventListener('click', onWishClick);
    // 로그인 상태면 찜을 미리 받아 하트를 칠한다. 비로그인이면 부르지 않는다 — 401을 만들 이유가 없다.
    if (auth.isLoggedIn()) wishlist.all().then(function () { wishlist.sync(); });
  });

  global.Store = {
    API: API, won: won, esc: esc, uuid: uuid, qs: qs, fmtDate: fmtDate,
    gradientFor: gradientFor, api: api, auth: auth, cart: cart, wishlist: wishlist,
    categories: categories, categoryTree: categoryTree, facets: facets,
    productCard: productCard, skeletonGrid: skeletonGrid, statusBadge: statusBadge,
    renderHeader: renderHeader, renderFooter: renderFooter, renderAccount: renderAccount,
    updateCartBadge: updateCartBadge, toast: toast, search: search, logLine: logLine
  };
})(window);
