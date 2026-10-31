/*
 * StreamiaX host for lnreader JS plugins, evaluated in QuickJS before libs.js.
 *
 * Native side is the `__native` object bound by JsNovelRuntime.kt. Everything native is
 * synchronous; async plugin code still works because Promise below runs on a JS job queue that
 * drain() empties. quickjs-android 0.9.2 never runs its own pending-job queue, so native Promise
 * callbacks would never fire. Drop this Promise once the engine runs that queue itself.
 *
 * The require() table, @libs constants and the storage fallback to pluginSettings defaults are
 * adapted from mangayomi (lib/eval/lnreader, Apache-2.0, https://github.com/kodjodevf/mangayomi);
 * storage and fetch semantics follow lnreader's src/plugins/helpers.
 */
(function (g) {
  'use strict';

  var console = {};
  ['log', 'info', 'warn', 'error', 'debug'].forEach(function (k) {
    console[k] = function () {
      __native.log(Array.prototype.map.call(arguments, function (a) {
        if (typeof a === 'string') return a;
        try { return JSON.stringify(a); } catch (e) { return String(a); }
      }).join(' '));
    };
  });
  g.console = console;

  // ---- Promise on a JS job queue ---------------------------------------------------------

  var jobs = [];
  var timers = [];
  var timerSeq = 0;

  var PENDING = 0, FULFILLED = 1, REJECTED = 2;

  function Promise(executor) {
    if (!(this instanceof Promise)) throw new TypeError('Promise must be called with new');
    this._state = PENDING;
    this._value = undefined;
    this._handlers = [];
    var self = this, done = false;
    try {
      executor(
        function (v) { if (!done) { done = true; resolveWith(self, v); } },
        function (e) { if (!done) { done = true; settle(self, REJECTED, e); } }
      );
    } catch (e) {
      if (!done) { done = true; settle(self, REJECTED, e); }
    }
  }

  function settle(p, state, value) {
    if (p._state !== PENDING) return;
    p._state = state;
    p._value = value;
    var hs = p._handlers;
    p._handlers = null;
    for (var i = 0; i < hs.length; i++) schedule(p, hs[i]);
  }

  function resolveWith(p, v) {
    if (v === p) return settle(p, REJECTED, new TypeError('Chaining cycle detected for promise'));
    if (v && (typeof v === 'object' || typeof v === 'function')) {
      var then;
      try { then = v.then; } catch (e) { return settle(p, REJECTED, e); }
      if (typeof then === 'function') {
        jobs.push(function () {
          var called = false;
          try {
            then.call(v,
              function (x) { if (!called) { called = true; resolveWith(p, x); } },
              function (e) { if (!called) { called = true; settle(p, REJECTED, e); } });
          } catch (e) {
            if (!called) { called = true; settle(p, REJECTED, e); }
          }
        });
        return;
      }
    }
    settle(p, FULFILLED, v);
  }

  function schedule(p, h) {
    jobs.push(function () {
      var cb = p._state === FULFILLED ? h.onFulfilled : h.onRejected;
      if (typeof cb !== 'function') {
        if (p._state === FULFILLED) resolveWith(h.promise, p._value);
        else settle(h.promise, REJECTED, p._value);
        return;
      }
      var r;
      try { r = cb(p._value); } catch (e) { settle(h.promise, REJECTED, e); return; }
      resolveWith(h.promise, r);
    });
  }

  Promise.prototype.then = function (onFulfilled, onRejected) {
    var next = new Promise(function () {});
    var h = { onFulfilled: onFulfilled, onRejected: onRejected, promise: next };
    if (this._state === PENDING) this._handlers.push(h);
    else schedule(this, h);
    return next;
  };
  Promise.prototype['catch'] = function (onRejected) { return this.then(undefined, onRejected); };
  Promise.prototype['finally'] = function (f) {
    return this.then(
      function (v) { return Promise.resolve(f()).then(function () { return v; }); },
      function (e) { return Promise.resolve(f()).then(function () { throw e; }); });
  };
  Promise.resolve = function (v) {
    return v instanceof Promise ? v : new Promise(function (res) { res(v); });
  };
  Promise.reject = function (e) { return new Promise(function (res, rej) { rej(e); }); };
  Promise.all = function (items) {
    return new Promise(function (res, rej) {
      var list = Array.from(items), out = new Array(list.length), left = list.length;
      if (!left) return res(out);
      list.forEach(function (it, i) {
        Promise.resolve(it).then(function (v) { out[i] = v; if (--left === 0) res(out); }, rej);
      });
    });
  };
  Promise.allSettled = function (items) {
    return Promise.all(Array.from(items).map(function (it) {
      return Promise.resolve(it).then(
        function (v) { return { status: 'fulfilled', value: v }; },
        function (e) { return { status: 'rejected', reason: e }; });
    }));
  };
  Promise.race = function (items) {
    return new Promise(function (res, rej) {
      Array.from(items).forEach(function (it) { Promise.resolve(it).then(res, rej); });
    });
  };
  Promise.any = function (items) {
    return new Promise(function (res, rej) {
      var list = Array.from(items), errors = [], left = list.length;
      if (!left) return rej(new Error('All promises were rejected'));
      list.forEach(function (it, i) {
        Promise.resolve(it).then(res, function (e) {
          errors[i] = e;
          if (--left === 0) rej(new Error('All promises were rejected'));
        });
      });
    });
  };
  g.Promise = Promise;

  g.setTimeout = function (fn, ms) {
    var id = ++timerSeq;
    var args = Array.prototype.slice.call(arguments, 2);
    timers.push({ id: id, at: Date.now() + (+ms || 0), fn: fn, args: args });
    return id;
  };
  g.clearTimeout = function (id) {
    timers = timers.filter(function (t) { return t.id !== id; });
  };
  g.setInterval = function () { throw new Error('setInterval is not supported'); };
  g.clearInterval = function () {};

  /**
   * Runs queued jobs, then due timers, until [isDone] says the caller has its answer or the
   * [deadline] (epoch millis) passes. A plugin that keeps rescheduling timers stops there.
   */
  function drain(isDone, deadline) {
    for (;;) {
      for (var i = 0; i < jobs.length; i++) {
        // Promise chains that never end are checked too, every 1024 jobs
        if ((i & 1023) === 1023 && Date.now() >= deadline) break;
        jobs[i]();
      }
      jobs.length = 0;
      if (isDone() || !timers.length || Date.now() >= deadline) return;
      timers.sort(function (a, b) { return a.at - b.at || a.id - b.id; });
      var t = timers.shift();
      var wait = Math.min(t.at, deadline) - Date.now();
      if (wait > 0) __native.sleep(Math.min(wait, 5000));
      if (Date.now() >= deadline) return;
      t.fn.apply(null, t.args);
    }
  }

  // ---- web APIs plugins expect -----------------------------------------------------------

  g.atob = function (s) { return __native.atob(String(s)); };
  g.btoa = function (s) { return __native.btoa(String(s)); };

  // UTF-8 only, which is all plugins ask for.
  function TextEncoder() { this.encoding = 'utf-8'; }
  TextEncoder.prototype.encode = function (s) {
    var bin = unescape(encodeURIComponent(String(s === undefined ? '' : s)));
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  };
  function TextDecoder() { this.encoding = 'utf-8'; }
  TextDecoder.prototype.decode = function (bytes) {
    if (!bytes) return '';
    var u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes.buffer || bytes);
    var bin = '';
    for (var i = 0; i < u8.length; i += 0x8000) bin += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
    try { return decodeURIComponent(escape(bin)); } catch (e) { return bin; }
  };
  g.TextEncoder = TextEncoder;
  g.TextDecoder = TextDecoder;

  function FormData() { this._data = []; }
  FormData.prototype.append = function (k, v) { this._data.push([String(k), String(v)]); };
  FormData.prototype['delete'] = function (k) {
    this._data = this._data.filter(function (e) { return e[0] !== String(k); });
  };
  FormData.prototype.set = function (k, v) { this['delete'](k); this.append(k, v); };
  FormData.prototype.get = function (k) {
    for (var i = 0; i < this._data.length; i++) if (this._data[i][0] === String(k)) return this._data[i][1];
    return null;
  };
  FormData.prototype.getAll = function (k) {
    return this._data.filter(function (e) { return e[0] === String(k); }).map(function (e) { return e[1]; });
  };
  FormData.prototype.has = function (k) { return this.get(k) !== null; };
  FormData.prototype.entries = function () { return this._data.slice()[Symbol.iterator](); };
  FormData.prototype[Symbol.iterator] = FormData.prototype.entries;
  FormData.prototype.forEach = function (cb, thisArg) {
    var self = this;
    this._data.forEach(function (e) { cb.call(thisArg, e[1], e[0], self); });
  };
  g.FormData = FormData;

  function Headers(init) {
    this._map = {};
    if (!init) return;
    var self = this;
    if (init instanceof Headers) init.forEach(function (v, k) { self.append(k, v); });
    else if (Array.isArray(init)) init.forEach(function (e) { self.append(e[0], e[1]); });
    else Object.keys(init).forEach(function (k) { self.append(k, init[k]); });
  }
  Headers.prototype.append = function (k, v) {
    k = String(k).toLowerCase();
    this._map[k] = this._map[k] !== undefined ? this._map[k] + ', ' + v : String(v);
  };
  Headers.prototype.set = function (k, v) { this._map[String(k).toLowerCase()] = String(v); };
  Headers.prototype.get = function (k) {
    var v = this._map[String(k).toLowerCase()];
    return v === undefined ? null : v;
  };
  Headers.prototype.has = function (k) { return this.get(k) !== null; };
  Headers.prototype['delete'] = function (k) { delete this._map[String(k).toLowerCase()]; };
  Headers.prototype.forEach = function (cb, thisArg) {
    var m = this._map;
    Object.keys(m).forEach(function (k) { cb.call(thisArg, m[k], k); });
  };
  Headers.prototype.entries = function () {
    var m = this._map;
    return Object.keys(m).map(function (k) { return [k, m[k]]; })[Symbol.iterator]();
  };
  Headers.prototype[Symbol.iterator] = Headers.prototype.entries;
  g.Headers = Headers;

  function Blob(b64, type) { this._b64 = b64; this.type = type || ''; }
  g.Blob = Blob;

  function FileReader() { this.result = null; }
  FileReader.prototype._finish = function (result) {
    var self = this;
    g.setTimeout(function () {
      self.result = result;
      if (self.onload) self.onload({ target: self });
      if (self.onloadend) self.onloadend({ target: self });
    }, 0);
  };
  FileReader.prototype.readAsDataURL = function (blob) {
    this._finish('data:' + (blob.type || 'application/octet-stream') + ';base64,' + blob._b64);
  };
  FileReader.prototype.readAsText = function (blob) {
    this._finish(__native.b64ToUtf8(blob._b64));
  };
  g.FileReader = FileReader;

  function Response(r) {
    this.status = r.status;
    this.statusText = r.statusText || '';
    this.ok = r.status >= 200 && r.status <= 299;
    this.url = r.url;
    this.redirected = !!r.redirected;
    this.headers = new Headers(r.headers);
    this._body = r.body;
    this._body64 = r.body64;
  }
  Response.prototype.text = function () {
    var self = this;
    return Promise.resolve(self._body !== undefined ? self._body : __native.b64ToUtf8(self._body64));
  };
  Response.prototype.json = function () {
    return this.text().then(function (t) { return JSON.parse(t); });
  };
  Response.prototype.blob = function () {
    var b64 = this._body64 !== undefined ? this._body64 : __native.utf8ToB64(this._body);
    return Promise.resolve(new Blob(b64, this.headers.get('content-type')));
  };
  Response.prototype.clone = function () { return this; };

  // lnreader's makeInit() defaults, minus Accept-Encoding: OkHttp only gunzips what it asked for.
  var DEFAULT_HEADERS = {
    'Connection': 'keep-alive',
    'Accept': '*/*',
    'Accept-Language': '*',
    'Sec-Fetch-Mode': 'cors',
    'Cache-Control': 'max-age=0',
  };

  function doFetch(url, init, encoding) {
    init = init || {};
    var headers = new Headers(DEFAULT_HEADERS);
    new Headers(init.headers).forEach(function (v, k) { headers.set(k, v); });
    headers['delete']('accept-encoding');
    if (init.referrer && !headers.has('referer')) headers.set('referer', String(init.referrer));
    var req = {
      url: String(url),
      method: String(init.method || 'GET').toUpperCase(),
      headers: headers._map,
    };
    if (encoding) req.encoding = encoding;
    var body = init.body;
    if (body instanceof FormData) {
      req.form = body._data;
    } else if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) {
      req.body = body.toString();
      if (!headers.has('content-type')) req.headers['content-type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
    } else if (body !== undefined && body !== null) {
      req.body = String(body);
    }
    return new Promise(function (resolve, reject) {
      var r = JSON.parse(__native.fetch(JSON.stringify(req)));
      if (r.error) reject(new TypeError('Network request failed: ' + r.error));
      else resolve(new Response(r));
    });
  }

  function fetchApi(url, init) { return doFetch(url, init); }
  function fetchText(url, init, encoding) {
    return doFetch(url, init, encoding).then(
      function (res) { return res.ok ? res.text() : ''; },
      function () { return ''; });
  }
  function fetchProto() { return Promise.reject(new Error('fetchProto is not supported')); }
  g.fetch = fetchApi;

  // ---- @libs and require() ---------------------------------------------------------------

  var NovelStatus = {
    Unknown: 'Unknown',
    Ongoing: 'Ongoing',
    Completed: 'Completed',
    Licensed: 'Licensed',
    PublishingFinished: 'Publishing Finished',
    Cancelled: 'Cancelled',
    OnHiatus: 'On Hiatus',
    STUB: 'STUB',
    Inactive: 'Inactive',
  };

  var FilterTypes = {
    TextInput: 'Text',
    Picker: 'Picker',
    CheckboxGroup: 'Checkbox',
    Switch: 'Switch',
    ExcludableCheckboxGroup: 'XCheckbox',
  };

  var filterInputs = {
    FilterTypes: FilterTypes,
    isPickerValue: function (q) { return q.type === FilterTypes.Picker && typeof q.value === 'string'; },
    isCheckboxValue: function (q) { return q.type === FilterTypes.CheckboxGroup && Array.isArray(q.value); },
    isSwitchValue: function (q) { return q.type === FilterTypes.Switch && typeof q.value === 'boolean'; },
    isTextValue: function (q) { return q.type === FilterTypes.TextInput && typeof q.value === 'string'; },
    isXCheckboxValue: function (q) {
      return q.type === FilterTypes.ExcludableCheckboxGroup && typeof q.value === 'object' && !Array.isArray(q.value);
    },
  };

  var defaultCover = 'https://github.com/LNReader/lnreader-plugins/blob/main/icons/src/coverNotAvailable.jpg?raw=true';

  function isUrlAbsolute(url) {
    if (url) {
      if (url.indexOf('//') === 0) return true;
      if (url.indexOf('://') === -1) return false;
      if (url.indexOf('.') === -1) return false;
      if (url.indexOf('/') === -1) return false;
      if (url.indexOf(':') > url.indexOf('/')) return false;
      if (url.indexOf('://') < url.indexOf('.')) return true;
    }
    return false;
  }

  /** The plugin being loaded or called; storage reads its pluginSettings defaults lazily. */
  var current = { id: '', plugin: null };

  /** The native side prefixes keys with the plugin id (JsNovelRuntime), so keys here are the plugin's own. */
  function makeStorage() {
    var storage = {
      set: function (key, value, expires) {
        __native.storageSet(String(key), JSON.stringify({
          created: new Date(),
          value: value,
          expires: expires instanceof Date ? expires.getTime() : expires,
        }));
      },
      get: function (key, raw) {
        var stored = __native.storageGet(String(key));
        if (stored) {
          var item = JSON.parse(stored);
          if (item.expires && Date.now() > item.expires) {
            storage['delete'](key);
            return undefined;
          }
          return raw ? item : item.value;
        }
        var plugin = current.plugin;
        var setting = plugin && plugin.pluginSettings && plugin.pluginSettings[key];
        if (setting && setting.value !== undefined) {
          return raw ? { created: new Date(), value: setting.value } : setting.value;
        }
        return undefined;
      },
      'delete': function (key) { __native.storageDelete(String(key)); },
      getAllKeys: function () { return JSON.parse(__native.storageKeys()); },
      clearAll: function () { storage.getAllKeys().forEach(function (k) { storage['delete'](k); }); },
    };
    // WebView storage capture is not implemented, so these are always empty.
    var empty = { get: function () { return undefined; } };
    return { storage: storage, localStorage: empty, sessionStorage: empty };
  }

  function requireFor() {
    return function (name) {
      switch (name) {
        case '@libs/fetch': return { fetchApi: fetchApi, fetchText: fetchText, fetchProto: fetchProto };
        case '@libs/novelStatus': return { NovelStatus: NovelStatus };
        case '@libs/filterInputs': return filterInputs;
        case '@libs/defaultCover': return { defaultCover: defaultCover };
        case '@libs/isAbsoluteUrl': return { isUrlAbsolute: isUrlAbsolute };
        case '@libs/storage': return makeStorage();
        case '@/types/constants': return { NovelStatus: NovelStatus, defaultCover: defaultCover };
        case 'cheerio': return g.__libs.cheerio;
        case 'htmlparser2': return g.__libs.htmlparser2;
        case 'dayjs': return g.__libs.dayjs;
        case 'urlencode': return { encode: encodeURIComponent, decode: decodeURIComponent };
        default: throw new Error('module ' + name + ' is not supported');
      }
    };
  }

  // ---- entry points used by JsNovelRuntime ------------------------------------------------

  /** Evaluates a plugin's CommonJS source and keeps its default export as the current plugin. */
  g.__load = function (pluginId, code) {
    var module = { exports: {} };
    current.id = pluginId;
    current.plugin = null;
    new Function('exports', 'module', 'require', code)(module.exports, module, requireFor());
    var plugin = module.exports['default'] || module.exports;
    if (!plugin || typeof plugin.parseNovel !== 'function') throw new Error('not an lnreader plugin: ' + pluginId);
    current.plugin = plugin;
    return true;
  };

  /**
   * Calls plugin[method](...args) and returns JSON {ok, value} or {ok: false, error}. Gives up
   * waiting at [deadline] (epoch millis); the native side fails fetches past it too.
   */
  g.__call = function (method, argsJson, deadline) {
    var plugin = current.plugin;
    var outcome = null;
    try {
      var args = JSON.parse(argsJson);
      if (method === 'popularNovels') {
        args[1] = { showLatestNovels: !!(args[1] && args[1].showLatestNovels), filters: plugin.filters };
      }
      if (typeof plugin[method] !== 'function') throw new Error(method + ' is not implemented by the plugin');
      Promise.resolve(plugin[method].apply(plugin, args)).then(
        function (v) { outcome = { ok: true, value: v === undefined ? null : v }; },
        function (e) { outcome = { ok: false, error: (e && e.stack ? e + '\n' + e.stack : String(e)) }; });
      drain(function () { return outcome !== null; }, deadline);
    } catch (e) {
      jobs.length = 0;
      outcome = { ok: false, error: (e && e.stack ? e + '\n' + e.stack : String(e)) };
    }
    timers = [];
    if (outcome === null) {
      outcome = { ok: false, error: method + (Date.now() >= deadline ? ' timed out' : ' never settled') };
    }
    return JSON.stringify(outcome);
  };
})(globalThis);
