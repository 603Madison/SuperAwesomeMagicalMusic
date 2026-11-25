/*
 * The lite player's script. Plain ES5 (Safari 9, Chrome 49, Android 5's WebView), no libraries.
 *
 * Pages are made on the server and work without it. With it:
 *  - links and forms between the lite pages load only the page's content (XMLHttpRequest) and
 *    swap it in, so the one <audio> element, in the player at the bottom, keeps playing;
 *  - tapping a song plays it, and the list it's in after it (a queue, with shuffle and repeat);
 *  - Now playing, live radio's "what's on", the person's own stations' skip and thumbs, Pandora
 *    stations' thumbs, listening history, and this browser as one of the person's devices.
 */
(function () {
  'use strict';

  var doc = document;
  var win = window;
  var root = doc.documentElement;
  root.className += ' js';
  var main = doc.getElementById('main');
  var bar = doc.getElementById('player');
  if (!main || !bar || !win.XMLHttpRequest) return;

  // ---- Small helpers ----

  function each(list, fn) {
    for (var i = 0; i < list.length; i++) fn(list[i], i);
  }

  function closest(el, test) {
    while (el && el !== doc) {
      if (el.nodeType === 1 && test(el)) return el;
      el = el.parentNode;
    }
    return null;
  }

  function tagIs(name) {
    return function (el) { return el.nodeName === name; };
  }

  function make(tag, cls, text) {
    var el = doc.createElement(tag);
    if (cls) el.className = cls;
    if (text != null) el.appendChild(doc.createTextNode(String(text)));
    return el;
  }

  function setText(el, text) {
    if (el) el.textContent = text == null ? '' : String(text);
  }

  function now() {
    return new Date().getTime();
  }

  function store(key, value) {
    try {
      if (value === undefined) return JSON.parse(win.localStorage.getItem(key));
      win.localStorage.setItem(key, JSON.stringify(value));
    } catch (e) {
      // Storage off or full: it just isn't kept.
    }
    return null;
  }

  function cookie(name) {
    var m = new RegExp('(?:^|; )' + name + '=([^;]*)').exec(doc.cookie);
    return m ? decodeURIComponent(m[1]) : null;
  }

  function setCookie(name, value) {
    doc.cookie = name + '=' + encodeURIComponent(value) + '; path=/; max-age=31536000; samesite=lax' +
      (location.protocol === 'https:' ? '; secure' : '');
  }

  function randomId() {
    var out = '';
    var bytes = null;
    try {
      bytes = new Uint8Array(12);
      (win.crypto || win.msCrypto).getRandomValues(bytes);
    } catch (e) {
      bytes = null;
    }
    for (var i = 0; i < 12; i++) {
      var b = bytes ? bytes[i] : Math.floor(Math.random() * 256);
      out += (b < 16 ? '0' : '') + b.toString(16);
    }
    return out;
  }

  function clock(seconds) {
    if (!(seconds >= 0) || !isFinite(seconds)) return '0:00';
    var s = Math.floor(seconds);
    var m = Math.floor(s / 60);
    var h = Math.floor(m / 60);
    var ss = (s % 60 < 10 ? '0' : '') + (s % 60);
    if (h > 0) return h + ':' + (m % 60 < 10 ? '0' : '') + (m % 60) + ':' + ss;
    return m + ':' + ss;
  }

  // ---- The server ----

  function request(method, url, body, headers, done) {
    var x = new XMLHttpRequest();
    x.open(method, url, true);
    if (method !== 'GET') x.setRequestHeader('X-SAMM', '1');
    for (var k in headers) {
      if (headers.hasOwnProperty(k)) x.setRequestHeader(k, headers[k]);
    }
    x.onreadystatechange = function () {
      if (x.readyState === 4) done(x);
    };
    x.send(body == null ? null : body);
    return x;
  }

  /** The API, as the main web player uses it: JSON, with the header other sites can't send. */
  function api(method, url, data, ok, fail) {
    var body = data === undefined ? null : JSON.stringify(data);
    request(method, url, body, body == null ? {} : { 'Content-Type': 'application/json' }, function (x) {
      var json = null;
      try {
        json = x.responseText ? JSON.parse(x.responseText) : null;
      } catch (e) {
        json = null;
      }
      if (x.status >= 200 && x.status < 300) {
        if (ok) ok(json);
      } else if (fail) {
        fail((json && json.error) || (x.status ? 'The server answered ' + x.status : 'Can\'t reach the server'));
      }
    });
  }

  var q = encodeURIComponent;

  // ---- Who and where ----

  var tz = '';
  try {
    tz = Intl.DateTimeFormat().resolvedOptions().timeZone || '';
  } catch (e) {
    tz = '';
  }
  if (tz && cookie('samm_lite_tz') !== tz) setCookie('samm_lite_tz', tz);

  var deviceId = store('samm.lite.device');
  if (typeof deviceId !== 'string' || !/^[A-Za-z0-9_-]{8,64}$/.test(deviceId)) {
    deviceId = randomId();
    store('samm.lite.device', deviceId);
  }
  if (cookie('samm_lite_device') !== deviceId) setCookie('samm_lite_device', deviceId);

  function kbps() {
    var k = parseInt(cookie('samm_lite_kbps') || '0', 10);
    return isNaN(k) ? 0 : k;
  }

  function deviceName() {
    var ua = navigator.userAgent;
    var browser = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Silk\//.test(ua) ? 'Silk' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
    var os = /iPad/.test(ua) ? 'iPad' : /iPhone|iPod/.test(ua) ? 'iPhone' : /Android/.test(ua) ? 'Android' : /CrOS/.test(ua) ? 'Chromebook' :
      /Mac OS X/.test(ua) ? 'Mac' : /Windows/.test(ua) ? 'Windows' : /Linux|X11/.test(ua) ? 'Linux' : '';
    return os ? browser + ' on ' + os : browser;
  }

  // ---- Songs, as the pages give them ----

  /** A song from a page's link (its fields, base64url in the link's t=). */
  function decode(token) {
    if (!token) return null;
    var s = token.replace(/-/g, '+').replace(/_/g, '/');
    while (s.length % 4) s += '=';
    var a;
    try {
      a = JSON.parse(decodeURIComponent(escape(win.atob(s))));
    } catch (e) {
      return null;
    }
    if (!a || !a.length) return null;
    var t = { source: a[0], sourceId: a[1], title: a[2], artist: a[3] };
    if (a[4] != null) t.album = a[4];
    if (a[5] != null) t.durationMs = a[5];
    if (a[6] != null) t.artworkUrl = a[6];
    if (a[7] != null) t.isrc = a[7];
    if (a[8] != null) t.via = a[8];
    return t;
  }

  function tokenOf(link) {
    var m = /[?&]t=([^&#]+)/.exec(link.getAttribute('href') || '');
    return m ? m[1] : null;
  }

  function sized(url, px) {
    if (!url || url.charAt(0) !== '/') return '';
    return url.indexOf('/api/art?') === 0 ? url + '&px=' + px : url;
  }

  function two(value) {
    var at = value.indexOf(':');
    return at < 0 ? [value, ''] : [value.slice(0, at), value.slice(at + 1)];
  }

  function isPersonal(t) {
    return t && t.source === 'RADIO' && t.via === 'personal';
  }

  /** How a live item plays (the same as the main player's liveStreamOf); null for a song. */
  function liveOf(t) {
    var via = t.via;
    if (!via) return null;
    var parts;
    if (t.source === 'SIRIUSXM' && t.sourceId.indexOf('channel:') === 0) {
      parts = two(t.sourceId.slice(8));
      if (!parts[0] || !parts[1]) return null;
      return { url: '/api/live/sxm/' + q(via) + '/channel/' + q(parts[0]) + '/' + q(parts[1]), hls: true, live: true, kind: 'channel' };
    }
    if (t.source === 'SIRIUSXM' && t.sourceId.indexOf('stationtrack:') === 0) {
      parts = two(t.sourceId.slice(13));
      if (!parts[1]) return null;
      return { url: '/api/live/sxm/' + q(via) + '/station-track/' + q(parts[1]), hls: false, live: false, kind: 'station-track' };
    }
    if (isPersonal(t)) {
      return { url: '/api/live/radio/personal/station/' + q(t.sourceId) + '/stream?tz=' + q(tz || 'UTC'), hls: false, live: true, kind: 'personal' };
    }
    if (t.source === 'RADIO') {
      return { url: '/api/live/radio/' + q(via) + '/station/' + q(t.sourceId.replace(/^hls:/, '')) + '/stream', hls: t.sourceId.indexOf('hls:') === 0, live: true, kind: 'radio' };
    }
    return null;
  }

  /** A Pandora station track kept as the song itself (its link expires). */
  function asSong(t) {
    if (t.source === 'SIRIUSXM' && t.sourceId.indexOf('stationtrack:') === 0) {
      return { source: t.source, sourceId: 'song:' + two(t.sourceId.slice(13))[1], title: t.title, artist: t.artist, album: t.album, durationMs: t.durationMs, artworkUrl: t.artworkUrl };
    }
    return t;
  }

  // ---- The player ----

  var audio = doc.createElement('audio');
  audio.preload = 'auto';
  var canHls = !!(audio.canPlayType && audio.canPlayType('application/vnd.apple.mpegurl'));
  var CODECS = (function () {
    var c = [];
    function can(type) {
      return !!(audio.canPlayType && audio.canPlayType(type));
    }
    if (can('audio/flac')) c.push('flac');
    if (can('audio/mpeg')) c.push('mp3');
    if (can('audio/mp4; codecs="mp4a.40.2"') && can('audio/aac')) c.push('aac');
    if (can('audio/webm; codecs="opus"') && can('audio/ogg; codecs="opus"')) c.push('opus');
    if (can('audio/ogg; codecs="vorbis"')) c.push('vorbis');
    if (can('audio/mp4; codecs="alac"')) c.push('alac');
    if (can('audio/wav')) c.push('wav');
    return c;
  })();

  var P = {
    queue: [],
    index: -1,
    repeat: 'off',
    shuffle: false,
    playing: false,
    buffering: false,
    error: null,
    position: 0,
    duration: 0,
    current: null,
    liveNow: null,
    heardBefore: [],
    personal: null,
    thumbs: {},
    volume: 1
  };
  var loadToken = 0;
  var offset = 0;
  var failures = 0;
  var listener = null;

  function track() {
    return P.queue[P.index] || null;
  }

  function load(i, autoplay, startAt) {
    var t = P.queue[i];
    if (!t) return;
    reportListen();
    listened = 0;
    liveMark = 0;
    reported = false;
    var token = ++loadToken;
    audio.pause();
    P.index = i;
    P.current = null;
    P.liveNow = null;
    P.heardBefore = [];
    P.personal = null;
    P.error = null;
    P.buffering = true;
    P.playing = !!autoplay;
    P.position = startAt || 0;
    P.duration = (t.durationMs || 0) / 1000;
    offset = 0;
    stopLive();
    changed();
    var live = liveOf(t);
    if (live) {
      if (live.hls && !canHls) {
        fail('This browser can\'t play SiriusXM\'s live channels (they need HLS, which Safari plays). Radio and your own stations still play.');
        return;
      }
      var src = live.url;
      listener = null;
      if (live.kind === 'personal') {
        listener = randomId().slice(0, 10);
        src += '&listener=' + listener;
      }
      begin(token, { url: src, base: src, live: live.live, kind: live.kind, seekable: !live.live, track: t }, autoplay, 0);
      return;
    }
    api('POST', '/api/play/resolve', { track: t, kbps: kbps(), codecs: CODECS, thorough: false }, function (r) {
      if (token !== loadToken) return;
      var url = r.streamUrl;
      if (startAt > 0 && !r.seekable) {
        url += '&start=' + startAt.toFixed(1);
        offset = startAt;
      }
      begin(token, { url: url, base: r.streamUrl, live: false, kind: null, seekable: r.seekable, track: r.track || t }, autoplay, startAt);
    }, function (message) {
      if (token === loadToken) fail(message);
    });
  }

  function begin(token, current, autoplay, startAt) {
    P.current = current;
    audio.src = current.url;
    if (startAt > 0 && current.seekable) {
      var seekOnce = function () {
        audio.removeEventListener('loadedmetadata', seekOnce);
        try {
          audio.currentTime = startAt;
        } catch (e) {
          // Not seekable after all: it plays from the start.
        }
      };
      audio.addEventListener('loadedmetadata', seekOnce);
    }
    if (current.live) {
      P.duration = 0;
      P.position = 0;
    }
    if (autoplay) playAudio();
    else P.buffering = false;
    if (current.kind) startLive(track());
    if (!current.live) prefetchMore();
    changed();
  }

  function playAudio() {
    var p;
    try {
      p = audio.play();
    } catch (e) {
      p = null;
    }
    if (p && typeof p.then === 'function') {
      p.then(null, function (err) {
        // A browser that won't start without a tap: it waits for one.
        if (err && err.name === 'NotAllowedError') {
          P.playing = false;
          P.buffering = false;
          changed();
        }
      });
    }
  }

  function fail(message) {
    failures++;
    P.error = message;
    P.buffering = false;
    P.playing = false;
    changed();
    // What can't play is skipped, as the app does, but not a whole queue of failures.
    var token = loadToken;
    if (failures < 4 && P.index + 1 < P.queue.length) {
      win.setTimeout(function () {
        if (token === loadToken) load(P.index + 1, true, 0);
      }, 1500);
    }
  }

  function nextIndex() {
    if (P.index + 1 < P.queue.length) return P.index + 1;
    if (P.repeat === 'all' && P.queue.length) return 0;
    return -1;
  }

  function shuffled(list) {
    var copy = list.slice();
    for (var i = copy.length - 1; i > 0; i--) {
      var j = Math.floor(Math.random() * (i + 1));
      var x = copy[i];
      copy[i] = copy[j];
      copy[j] = x;
    }
    return copy;
  }

  var player = {
    playTracks: function (tracks, start, shuffle) {
      if (!tracks.length) return;
      var first = tracks[start || 0];
      var list = shuffle ? shuffled(tracks) : tracks;
      P.queue = list.slice(0, 1000);
      P.shuffle = !!shuffle;
      failures = 0;
      load(shuffle ? 0 : Math.max(0, P.queue.indexOf(first)), true, 0);
    },
    playNext: function (t) {
      if (P.index < 0) return player.playTracks([t], 0);
      P.queue.splice(P.index + 1, 0, t);
      changed();
    },
    addToQueue: function (t) {
      P.queue.push(t);
      if (P.index < 0) load(0, true, 0);
      else changed();
    },
    toggle: function () {
      if (P.playing && !audio.paused) player.pause();
      else player.play();
    },
    play: function () {
      if (P.index < 0 || !track()) return;
      // After a reload, or an error: the song is looked up first (where it was).
      if (!P.current || !audio.src || (isPersonal(track()) && !P.playing)) return load(P.index, true, P.position);
      P.playing = true;
      playAudio();
      changed();
    },
    pause: function () {
      audio.pause();
      P.playing = false;
      // One of the person's own stations: let go of it; playing again tunes in to what's on then.
      if (isPersonal(track())) {
        audio.removeAttribute('src');
        try {
          audio.load();
        } catch (e) {
          // Nothing to load.
        }
        P.current = null;
      }
      changed();
    },
    next: function () {
      var t = track();
      if (t && isPersonal(t)) return personalSkip();
      var n = nextIndex();
      if (n >= 0) load(n, true, 0);
    },
    previous: function () {
      if (P.position > 3 || P.index <= 0) player.seek(0);
      else load(P.index - 1, true, 0);
    },
    seek: function (seconds) {
      var cur = P.current;
      if (!cur || cur.live) return;
      var to = Math.max(0, P.duration > 0 ? Math.min(seconds, P.duration - 0.5) : seconds);
      if (cur.seekable) {
        try {
          audio.currentTime = to;
        } catch (e) {
          // Not yet: it plays on.
        }
      } else {
        // The server starts the song again from there.
        offset = to;
        audio.src = cur.base + '&start=' + to.toFixed(1);
        P.position = to;
        if (P.playing) playAudio();
      }
      changed();
    },
    jump: function (i) {
      failures = 0;
      load(i, true, 0);
    },
    cycleRepeat: function () {
      P.repeat = P.repeat === 'off' ? 'all' : P.repeat === 'all' ? 'one' : 'off';
      changed();
    },
    toggleShuffle: function () {
      P.shuffle = !P.shuffle;
      // Shuffle mixes what's still to come; turned off, the order stays as it is now.
      if (P.shuffle) P.queue = P.queue.slice(0, P.index + 1).concat(shuffled(P.queue.slice(P.index + 1)));
      changed();
    }
  };

  // ---- Audio events ----

  var lastTick = 0;
  var listened = 0;
  var reported = false;
  var liveMark = 0;

  audio.addEventListener('timeupdate', function () {
    var t = now();
    if (!audio.paused && lastTick) listened += Math.min(t - lastTick, 2000);
    lastTick = t;
    P.position = offset + (audio.currentTime || 0);
    tick();
  });
  audio.addEventListener('durationchange', function () {
    if (isFinite(audio.duration) && audio.duration > 0 && P.current && !P.current.live) P.duration = offset + audio.duration;
  });
  audio.addEventListener('playing', function () {
    P.playing = true;
    P.buffering = false;
    P.error = null;
    failures = 0;
    changed();
  });
  audio.addEventListener('pause', function () {
    lastTick = 0;
    if (!audio.ended && P.playing) {
      P.playing = false;
      changed();
    }
  });
  audio.addEventListener('waiting', function () {
    P.buffering = true;
    changed();
  });
  audio.addEventListener('ended', function () {
    reportListen();
    if (P.repeat === 'one') return load(P.index, true, 0);
    var n = nextIndex();
    if (n >= 0) load(n, true, 0);
    else {
      P.playing = false;
      P.position = 0;
      changed();
    }
  });
  audio.addEventListener('error', function () {
    if (!audio.getAttribute('src') || !P.current) return;
    fail('The song stopped loading');
  });
  bar.appendChild(audio);

  // ---- Listening history (and Wrapped), counted as the main player counts it ----

  function reportListen() {
    var t = track();
    var cur = P.current;
    if (!t || !cur) return;
    if (cur.live) return endLiveStretch();
    if (reported) return;
    var length = t.durationMs || P.duration * 1000;
    var needed = Math.max(30000, Math.min(length / 2 || 30000, 240000));
    if (listened < needed) return;
    reported = true;
    api('POST', '/api/history', { listens: [{ track: asSong(cur.track || t), playedAt: now() - Math.round(listened), listenedMs: Math.round(listened), client: 'web' }] });
  }

  /** Live radio counts too: each stretch of 30 seconds or more, as the song if it's known. */
  function endLiveStretch() {
    var t = track();
    if (!t || !P.current || !P.current.live) return;
    var ms = Math.round(listened - liveMark);
    liveMark = listened;
    if (ms < 30000) return;
    var song = P.liveNow && P.liveNow.song ? asSong(P.liveNow.song) : t;
    api('POST', '/api/history', { listens: [{ track: song, playedAt: now() - ms, listenedMs: ms, client: 'web', station: t.title }] });
  }

  // ---- Live radio: what's on, skips and thumbs ----

  var livePoll = null;
  var fetchingMore = false;

  function stopLive() {
    if (livePoll) win.clearInterval(livePoll);
    livePoll = null;
  }

  function startLive(t) {
    stopLive();
    var live = liveOf(t);
    if (!live) return;
    if (live.kind === 'station-track') {
      var ids = two(t.sourceId.slice(13));
      api('POST', '/api/live/sxm/' + q(t.via) + '/station-track/' + q(ids[0]) + '/' + q(ids[1]) + '/progress', { elapsedMs: 0, start: true });
    } else {
      // Counted for the person's top stations, as everywhere else.
      api('POST', '/api/live/played', { track: t });
    }
    pollLive();
    livePoll = win.setInterval(pollLive, live.kind === 'station-track' ? 30000 : live.kind === 'personal' ? 5000 : 10000);
  }

  function heard(info) {
    var before = P.liveNow;
    if (before && info && before.title === info.title && before.artist === info.artist) {
      P.liveNow = info;
      return;
    }
    endLiveStretch();
    if (before) P.heardBefore = [before].concat(P.heardBefore).slice(0, 10);
    P.liveNow = info;
    changed();
  }

  function pollLive() {
    var t = track();
    var live = t && liveOf(t);
    if (!t || !live) return;
    var mine = t;
    var still = function () {
      return track() === mine;
    };
    if (live.kind === 'channel') {
      var ch = two(t.sourceId.slice(8));
      var at = now();
      // Safari knows the stream's own clock: what's heard, not what's newest.
      try {
        if (audio.getStartDate && !isNaN(audio.getStartDate().getTime())) at = audio.getStartDate().getTime() + audio.currentTime * 1000;
      } catch (e) {
        at = now();
      }
      api('GET', '/api/live/sxm/' + q(t.via) + '/now?type=' + q(ch[0]) + '&id=' + q(ch[1]) + '&at=' + Math.round(at), undefined, function (r) {
        if (still()) heard(r && r.title ? { title: r.title, artist: r.artist, artworkUrl: r.artworkUrl, song: r.song } : null);
      });
    } else if (live.kind === 'radio') {
      api('GET', '/api/live/radio/' + q(t.via) + '/station/' + q(t.sourceId.replace(/^hls:/, '')) + '/now', undefined, function (r) {
        if (!still()) return;
        var text = (r && r.title) || '';
        var i = text.indexOf(' - ');
        var n = r && r.now;
        heard(text ? { title: i > 0 ? text.slice(i + 3) : text, artist: i > 0 ? text.slice(0, i) : '', artworkUrl: n && n.artworkUrl, song: n && n.song } : null);
      });
    } else if (live.kind === 'personal') {
      var url = '/api/radio/personal/' + q(t.sourceId) + '/now?tz=' + q(tz || 'UTC');
      if (listener) url += '&listener=' + q(listener) + '&pos=' + Math.round((audio.currentTime || 0) * 1000);
      api('GET', url, undefined, function (r) {
        if (!still() || !r) return;
        P.personal = { songKey: r.songKey, thumb: r.thumb };
        heard(r.now ? { title: r.now.title, artist: r.now.artist, artworkUrl: r.now.artworkUrl, song: r.now.song } : null);
        changed();
        // Far behind the station (stalls while loading): tuned in again, to hear what's on.
        if (P.playing && r.behindMs > 25000 && now() - caughtUpAt > 60000) {
          caughtUpAt = now();
          load(P.index, true, 0);
        }
      });
    } else if (P.playing) {
      var ids = two(t.sourceId.slice(13));
      api('POST', '/api/live/sxm/' + q(t.via) + '/station-track/' + q(ids[0]) + '/' + q(ids[1]) + '/progress', { elapsedMs: Math.round((audio.currentTime || 0) * 1000), start: false });
    }
  }

  var caughtUpAt = 0;

  /** Near the end of a Pandora station's songs: more from SiriusXM. */
  function prefetchMore() {
    var last = P.queue[P.queue.length - 1];
    if (fetchingMore || !last || P.index < P.queue.length - 3) return;
    if (!(last.source === 'SIRIUSXM' && last.sourceId.indexOf('stationtrack:') === 0 && last.via)) return;
    fetchingMore = true;
    api('POST', '/api/live/sxm/' + q(last.via) + '/station', { stationId: two(last.sourceId.slice(13))[0], name: last.album, more: true }, function (r) {
      fetchingMore = false;
      if (r && r.tracks && r.tracks.length) {
        P.queue = P.queue.concat(r.tracks);
        changed();
      }
    }, function () {
      fetchingMore = false;
    });
  }

  function personalSkip() {
    var t = track();
    var key = P.personal && P.personal.songKey;
    api('POST', '/api/radio/personal/' + q(t.sourceId) + '/skip' + (key ? '?key=' + q(key) : ''), {}, function (r) {
      if (track() !== t) return;
      if (r && r.retune) load(P.index, true, 0);
      else win.setTimeout(pollLive, 1500);
    }, showError);
  }

  /** Thumbs on one of the person's own stations, or on a Pandora station's song (down skips it). */
  function thumb(direction) {
    var t = track();
    if (!t) return;
    if (isPersonal(t)) {
      var p = P.personal;
      if (!p || !p.songKey) return;
      var next = p.thumb === direction ? 'NONE' : direction;
      p.thumb = next === 'NONE' ? null : next;
      changed();
      api('POST', '/api/radio/personal/' + q(t.sourceId) + '/thumb', { key: p.songKey, thumb: next }, function () {
        if (next === 'DOWN') win.setTimeout(pollLive, 1500);
      }, showError);
      return;
    }
    if (!t.via || t.sourceId.indexOf('stationtrack:') !== 0) return;
    var ids = two(t.sourceId.slice(13));
    var given = P.thumbs[ids[1]] === direction ? 'NONE' : direction;
    if (given === 'NONE') delete P.thumbs[ids[1]];
    else P.thumbs[ids[1]] = given;
    changed();
    api('POST', '/api/live/sxm/' + q(t.via) + '/station-track/' + q(ids[0]) + '/' + q(ids[1]) + '/thumb',
      { thumb: given, elapsedMs: Math.round((audio.currentTime || 0) * 1000) }, function () {
        if (given === 'DOWN') player.next();
      }, showError);
  }

  function thumbState() {
    var t = track();
    if (!t) return null;
    if (isPersonal(t)) return P.personal && P.personal.songKey ? (P.personal.thumb || 'NONE') : null;
    if (t.sourceId.indexOf('stationtrack:') === 0) return P.thumbs[two(t.sourceId.slice(13))[1]] || 'NONE';
    return null;
  }

  function showError(message) {
    P.error = message;
    changed();
  }

  // ---- What's shown: the player at the bottom, and Now playing ----

  var ICONS = {
    play: 'M8 5v14l11-7z',
    pause: 'M6 5h4v14H6zM14 5h4v14h-4z',
    prev: 'M6 5h2v14H6zM9.5 12L18 5v14z',
    next: 'M16 5h2v14h-2zM6 5l8.5 7L6 19z'
  };

  function iconButton(act, label, cls) {
    var b = make('button', 'ic' + (cls ? ' ' + cls : ''));
    b.type = 'button';
    b.setAttribute('data-act', act);
    b.setAttribute('aria-label', label);
    b.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d=""/></svg>';
    return b;
  }

  function setIcon(b, name, label) {
    var path = b.getElementsByTagName('path')[0];
    if (path) path.setAttribute('d', ICONS[name]);
    if (label) b.setAttribute('aria-label', label);
  }

  var ui = {};
  (function buildBar() {
    var line = make('div', 'pb-line');
    ui.fill = make('i');
    line.appendChild(ui.fill);
    bar.appendChild(line);
    var link = make('a', 'pb-now');
    link.href = '/lite/now';
    ui.img = make('img', 'cv');
    ui.img.alt = '';
    link.appendChild(ui.img);
    var text = make('span', 'tx');
    ui.title = make('b');
    ui.sub = make('small');
    text.appendChild(ui.title);
    text.appendChild(ui.sub);
    link.appendChild(text);
    bar.appendChild(link);
    ui.prev = iconButton('prev', 'Previous');
    ui.toggle = iconButton('toggle', 'Play', 'main');
    ui.next = iconButton('next', 'Next');
    setIcon(ui.prev, 'prev');
    setIcon(ui.next, 'next');
    bar.appendChild(ui.prev);
    bar.appendChild(ui.toggle);
    bar.appendChild(ui.next);
  })();

  /** What to show for what's playing: on live radio, what's on, under the station's name. */
  function shown() {
    var t = track();
    if (!t) return null;
    var base = (P.current && P.current.track) || t;
    var on = P.liveNow;
    var line = on ? (on.artist ? on.artist + ' · ' : '') + base.title : base.artist;
    if (P.error) line = P.error;
    else if (P.buffering && P.playing) line = 'Loading… ' + line;
    return { title: on ? on.title : base.title, line: line, art: (on && on.artworkUrl) || base.artworkUrl, track: base };
  }

  var lastImg = '';

  function setImg(img, url, px) {
    var src = sized(url, px);
    if (img.getAttribute('data-src') === src) return;
    img.setAttribute('data-src', src);
    if (src) img.src = src;
    else img.removeAttribute('src');
  }

  function render() {
    var s = shown();
    bar.hidden = !s;
    doc.body.className = s ? 'has-player' : '';
    if (!s) return;
    setText(ui.title, s.title);
    setText(ui.sub, s.line);
    if (lastImg !== s.art) {
      lastImg = s.art;
      setImg(ui.img, s.art, 104);
    }
    var on = P.playing;
    setIcon(ui.toggle, on ? 'pause' : 'play', on ? 'Pause' : 'Play');
    ui.next.disabled = nextIndex() < 0 && !isPersonal(track());
    tick();
    renderNow();
    markCurrent();
    updateMediaSession(s);
  }

  function tick() {
    var pct = P.duration > 0 ? Math.min(100, 100 * P.position / P.duration) : 0;
    ui.fill.style.width = pct + '%';
    if (np) {
      setText(np.pos, P.current && P.current.live ? 'Live' : clock(P.position));
      setText(np.dur, P.current && P.current.live ? '' : clock(P.duration));
      if (!seeking) np.seek.value = P.duration > 0 ? Math.round(1000 * P.position / P.duration) : 0;
    }
  }

  // Now playing's page: built when it's shown, kept up to date while it is.
  var np = null;
  var seeking = false;

  function buildNow() {
    var holder = doc.getElementById('now');
    if (!holder) {
      np = null;
      return;
    }
    holder.innerHTML = '';
    var box = make('div', 'np');
    np = { holder: holder };
    np.art = make('img', 'art');
    np.art.alt = '';
    np.title = make('h2');
    np.line = make('p');
    np.status = make('p', 'muted');
    np.links = make('p');
    np.seek = make('input');
    np.seek.type = 'range';
    np.seek.min = '0';
    np.seek.max = '1000';
    np.seek.setAttribute('aria-label', 'Position in the song');
    var times = make('p', 'muted');
    np.pos = make('span');
    np.dur = make('span');
    times.appendChild(np.pos);
    times.appendChild(doc.createTextNode(' / '));
    times.appendChild(np.dur);
    var controls = make('div', 'acts');
    np.prev = iconButton('prev', 'Previous');
    np.toggle = iconButton('toggle', 'Play', 'main');
    np.next = iconButton('next', 'Next');
    setIcon(np.prev, 'prev');
    setIcon(np.next, 'next');
    controls.appendChild(np.prev);
    controls.appendChild(np.toggle);
    controls.appendChild(np.next);
    var modes = make('div', 'acts');
    np.shuffle = make('button', 'btn', 'Shuffle');
    np.shuffle.type = 'button';
    np.shuffle.setAttribute('data-act', 'shuffle');
    np.repeat = make('button', 'btn', 'Repeat');
    np.repeat.type = 'button';
    np.repeat.setAttribute('data-act', 'repeat');
    modes.appendChild(np.shuffle);
    modes.appendChild(np.repeat);
    np.live = make('div', 'acts');
    np.up = make('button', 'btn', 'Thumbs up');
    np.up.type = 'button';
    np.up.setAttribute('data-act', 'up');
    np.down = make('button', 'btn', 'Thumbs down');
    np.down.type = 'button';
    np.down.setAttribute('data-act', 'down');
    np.skip = make('button', 'btn', 'Skip');
    np.skip.type = 'button';
    np.skip.setAttribute('data-act', 'next');
    np.live.appendChild(np.up);
    np.live.appendChild(np.down);
    np.live.appendChild(np.skip);
    np.earlierTitle = make('h2', null, 'Earlier');
    np.earlier = make('ul', 'facts');
    np.queueTitle = make('h2', null, 'Up next');
    np.queue = make('ol', 'tracks');
    np.empty = make('p', 'muted', 'Nothing is playing. Find something in Search, Playlists or Radio.');
    var parts = [np.empty, np.art, np.title, np.line, np.status, np.seek, times, controls, modes, np.live, np.links, np.earlierTitle, np.earlier, np.queueTitle, np.queue];
    each(parts, function (p) { box.appendChild(p); });
    holder.appendChild(box);
    np.queueKey = '';
    np.seek.addEventListener('input', function () { seeking = true; });
    np.seek.addEventListener('change', function () {
      seeking = false;
      if (P.duration > 0) player.seek(P.duration * np.seek.value / 1000);
    });
    renderNow();
  }

  function renderNow() {
    if (!np) return;
    if (!doc.getElementById('now')) {
      np = null;
      return;
    }
    var s = shown();
    var has = !!s;
    each([np.art, np.title, np.line, np.status, np.seek, np.prev.parentNode, np.shuffle.parentNode, np.links], function (el) { el.hidden = !has; });
    np.empty.hidden = has;
    if (!s) {
      np.live.hidden = true;
      np.earlierTitle.hidden = true;
      np.earlier.hidden = true;
      np.queueTitle.hidden = true;
      np.queue.hidden = true;
      return;
    }
    setImg(np.art, s.art, 480);
    setText(np.title, s.title);
    setText(np.line, s.line);
    var live = P.current && P.current.live;
    setText(np.status, P.error ? '' : P.buffering && P.playing ? 'Loading…' : live ? 'Live' : '');
    np.seek.hidden = !!live || !(P.duration > 0);
    setIcon(np.toggle, P.playing ? 'pause' : 'play', P.playing ? 'Pause' : 'Play');
    np.next.disabled = nextIndex() < 0 && !isPersonal(track());
    np.shuffle.setAttribute('aria-pressed', P.shuffle ? 'true' : 'false');
    setText(np.repeat, P.repeat === 'off' ? 'Repeat: off' : P.repeat === 'all' ? 'Repeat: all' : 'Repeat: this song');
    np.repeat.setAttribute('aria-pressed', P.repeat === 'off' ? 'false' : 'true');
    var thumbs = thumbState();
    np.live.hidden = thumbs == null && !isPersonal(track());
    np.up.hidden = np.down.hidden = thumbs == null;
    np.up.setAttribute('aria-pressed', thumbs === 'UP' ? 'true' : 'false');
    np.down.setAttribute('aria-pressed', thumbs === 'DOWN' ? 'true' : 'false');
    np.skip.hidden = !isPersonal(track());
    // Links to the song's page (add to a playlist, its artist and album).
    np.links.innerHTML = '';
    var song = P.liveNow ? P.liveNow.song : (live ? null : s.track);
    if (song) {
      var a = make('a', 'btn', P.liveNow ? 'This song' : 'Add to a playlist, and more');
      a.href = '/lite/song?t=' + encodeToken(song);
      np.links.appendChild(a);
    }
    if (!live && s.track.artist) {
      var ar = make('a', 'btn', 'Artist');
      ar.href = '/lite/artist?name=' + q(s.track.artist);
      np.links.appendChild(ar);
    }
    // What was on before (live radio).
    np.earlierTitle.hidden = np.earlier.hidden = !P.heardBefore.length;
    np.earlier.innerHTML = '';
    each(P.heardBefore, function (h) {
      np.earlier.appendChild(make('li', null, (h.artist ? h.artist + ' · ' : '') + h.title));
    });
    // The queue, from the song playing on (a long one, up to 40 of it).
    var key = P.index + '|' + P.queue.length + '|' + (P.queue[P.index] && P.queue[P.index].sourceId) + '|' + (P.queue[P.queue.length - 1] && P.queue[P.queue.length - 1].sourceId);
    var showQueue = P.queue.length > 1;
    np.queueTitle.hidden = np.queue.hidden = !showQueue;
    if (showQueue && key !== np.queueKey) {
      np.queueKey = key;
      np.queue.innerHTML = '';
      for (var i = P.index + 1; i < P.queue.length && i <= P.index + 40; i++) {
        var t = P.queue[i];
        var li = make('li');
        var link = make('a', 'jump');
        link.href = '#';
        link.setAttribute('data-jump', String(i));
        var img = make('img', 'cv');
        img.alt = '';
        setImg(img, t.artworkUrl, 96);
        link.appendChild(img);
        var tx = make('span', 'tx');
        tx.appendChild(make('b', null, t.title));
        tx.appendChild(make('small', null, t.artist));
        link.appendChild(tx);
        li.appendChild(link);
        np.queue.appendChild(li);
      }
      if (P.index + 1 >= P.queue.length) np.queue.appendChild(make('li', 'muted', 'Nothing after this one.'));
    }
  }

  /** A song's token, as the server makes them (for links to its page). */
  function encodeToken(t) {
    var a = [t.source, t.sourceId, t.title, t.artist, t.album == null ? null : t.album, t.durationMs == null ? null : t.durationMs,
      t.artworkUrl == null ? null : t.artworkUrl, t.isrc == null ? null : t.isrc, t.via == null ? null : t.via];
    return win.btoa(unescape(encodeURIComponent(JSON.stringify(a)))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  /** The song playing, marked in the lists on the page. */
  function markCurrent() {
    var t = track();
    var id = t ? t.sourceId : null;
    each(main.querySelectorAll('a.t'), function (a) {
      var li = closest(a, tagIs('LI'));
      if (!li) return;
      var mine = id && a.getAttribute('data-id') === id;
      if (!a.hasAttribute('data-id')) {
        var d = decode(tokenOf(a));
        a.setAttribute('data-id', d ? d.sourceId : '');
        mine = id && d && d.sourceId === id;
      }
      li.className = mine ? 'current' : li.className === 'current' ? '' : li.className;
    });
  }

  function updateMediaSession(s) {
    var ms = navigator.mediaSession;
    if (!ms || !win.MediaMetadata) return;
    try {
      var art = sized(s.art, 512);
      ms.metadata = new win.MediaMetadata({ title: s.title, artist: P.liveNow ? P.liveNow.artist : s.track.artist, album: s.track.album || '', artwork: art ? [{ src: art, sizes: '512x512' }] : [] });
    } catch (e) {
      // Not every browser takes every field.
    }
  }

  if (navigator.mediaSession) {
    each([['play', player.play], ['pause', player.pause], ['nexttrack', player.next], ['previoustrack', player.previous]], function (pair) {
      try {
        navigator.mediaSession.setActionHandler(pair[0], function () { pair[1](); });
      } catch (e) {
        // Not this one.
      }
    });
  }

  // Everything that changes what's shown (or what other devices see) comes through here.
  var saveTimer = null;

  function changed() {
    render();
    report();
    if (!saveTimer) {
      saveTimer = win.setTimeout(function () {
        saveTimer = null;
        save();
      }, 1000);
    }
  }

  // ---- Keeping the queue across visits ----

  function save() {
    store('samm.lite.player', { queue: P.queue.slice(0, 1000), index: P.index, position: P.position, repeat: P.repeat, shuffle: P.shuffle });
  }

  (function restore() {
    var saved = store('samm.lite.player');
    if (!saved || !saved.queue || !saved.queue.length) return;
    P.queue = saved.queue;
    P.index = Math.min(saved.index, saved.queue.length - 1);
    P.position = saved.position || 0;
    P.repeat = saved.repeat || 'off';
    P.shuffle = !!saved.shuffle;
    var t = track();
    P.duration = t && t.durationMs ? t.durationMs / 1000 : 0;
  })();

  win.addEventListener('beforeunload', function () {
    reportListen();
    save();
  });

  // ---- This browser as one of the person's devices ----

  var socket = null;
  var retries = 0;
  var lastSent = '';
  var lastSentAt = 0;
  var lastPosition = 0;

  function openSocket() {
    if (!win.WebSocket || socket) return;
    var ws;
    try {
      ws = new win.WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/api/connect/ws?id=' + q(deviceId) + '&name=' + q(deviceName()) + '&kind=web');
    } catch (e) {
      return;
    }
    socket = ws;
    ws.onopen = function () {
      retries = 0;
      lastSent = '';
      report();
    };
    ws.onmessage = function (event) {
      var m;
      try {
        m = JSON.parse(String(event.data));
      } catch (e) {
        return;
      }
      if (m && m.type === 'command' && m.command) command(m.command);
    };
    ws.onclose = function () {
      if (socket === ws) socket = null;
      win.setTimeout(openSocket, Math.min(30000, 1000 * Math.pow(2, retries++)));
    };
  }

  function command(c) {
    switch (c.action) {
      case 'play': player.play(); break;
      case 'pause': player.pause(); break;
      case 'next': player.next(); break;
      case 'previous': player.previous(); break;
      case 'seek': if (c.positionMs != null) player.seek(c.positionMs / 1000); break;
      case 'volume': if (c.volume != null) audio.volume = Math.max(0, Math.min(1, c.volume)); break;
      case 'take':
        if (c.state && c.state.queue && c.state.queue.length) {
          P.queue = c.state.queue;
          load(Math.min(Math.max(c.state.index, 0), P.queue.length - 1), c.state.playing, Math.max(0, (c.state.positionMs || 0) / 1000));
        }
        break;
    }
  }

  /** Tells the person's other devices what's playing here: when it changes, and now and then. */
  function report() {
    if (!socket || socket.readyState !== 1) return;
    var t = track();
    var state = {
      track: t || undefined,
      queue: P.queue.slice(0, 500),
      index: P.index,
      positionMs: Math.round(P.position * 1000),
      durationMs: Math.round(P.duration * 1000),
      playing: P.playing,
      volume: audio.volume,
      live: !!(P.current && P.current.live),
      repeat: P.repeat
    };
    var text = JSON.stringify({ type: 'state', state: state });
    var same = text.replace(/"positionMs":\d+/, '') === lastSent.replace(/"positionMs":\d+/, '');
    var expected = lastPosition + (P.playing ? (now() - lastSentAt) / 1000 : 0);
    if (same && Math.abs(P.position - expected) < 3 && !(P.playing && now() - lastSentAt > 15000)) return;
    lastSent = text;
    lastSentAt = now();
    lastPosition = P.position;
    socket.send(text);
  }

  win.setInterval(report, 5000);
  openSocket();

  // ---- Pages: loaded into the page, not by the browser, so the music plays on ----

  var loading = null;
  var shownUrl = location.href;
  var devicesTimer = null;

  function isLitePath(href) {
    return /^\/lite(\/|\?|$)/.test(href) && !/^\/lite\/lite\.(css|js)/.test(href);
  }

  function pathOf(link) {
    var href = link.getAttribute('href') || '';
    if (href.charAt(0) === '/') return href;
    var full = link.href || '';
    var origin = location.protocol + '//' + location.host;
    return full.indexOf(origin) === 0 ? full.slice(origin.length) : '';
  }

  function go(url, push, method, body) {
    if (loading) loading.abort();
    root.className += ' busy';
    var headers = { 'X-Lite': '1' };
    if (method === 'POST') headers['Content-Type'] = 'application/x-www-form-urlencoded';
    loading = request(method || 'GET', url, body, headers, function (x) {
      loading = null;
      root.className = root.className.replace(/ busy/g, '');
      if (!x.status) {
        if (method !== 'POST') location.href = url;
        else showError('Can\'t reach the server');
        return;
      }
      var html = x.responseText;
      var at = x.getResponseHeader('X-Lite-Url');
      var title = x.getResponseHeader('X-Lite-Title');
      if (at) {
        title = decodeURIComponent(title || '');
        title = title ? title + ' · Music' : 'Music';
      } else {
        // A whole page: its content is between the markers.
        var a = html.indexOf('<!--lite-->');
        var b = html.indexOf('<!--/lite-->');
        if (a < 0 || b < a) {
          location.href = x.responseURL || url;
          return;
        }
        var m = /<title>([^<]*)<\/title>/.exec(html);
        title = m ? entities(m[1]) : 'Music';
        html = html.slice(a + 11, b);
        at = x.responseURL || url;
      }
      // A form answered with a page of its own (an error): the address stays as it was.
      var moved = !(method === 'POST' && at.split('?')[0] === url.split('?')[0]);
      show(html, title);
      if (moved) {
        if (push && win.history.pushState) win.history.pushState({ lite: 1 }, '', at);
        else if (win.history.replaceState) win.history.replaceState({ lite: 1 }, '', at);
        shownUrl = location.href;
      }
      afterPage();
    });
  }

  function entities(text) {
    var t = doc.createElement('textarea');
    t.innerHTML = text;
    return t.value;
  }

  function show(html, title) {
    np = null;
    main.innerHTML = html;
    doc.title = title;
    win.scrollTo(0, 0);
    try {
      main.focus();
    } catch (e) {
      // Not focusable here.
    }
  }

  /** What a page needs once it's in: Now playing, a song to play, the SiriusXM note, devices' refresh. */
  function afterPage() {
    var path = location.pathname;
    each(doc.querySelectorAll('.bar a'), function (a) {
      a.className = a.getAttribute('href') === path ? 'here' : '';
    });
    if (devicesTimer) win.clearTimeout(devicesTimer);
    devicesTimer = null;
    buildNow();
    // A song's own page (from a link, or with the script off before): it plays in the player instead.
    var here = doc.getElementById('play-here');
    if (here) {
      var t = decode(here.getAttribute('data-t'));
      var el = here.getElementsByTagName('audio')[0];
      if (el) {
        el.pause();
        el.removeAttribute('src');
        try {
          el.load();
        } catch (e) {
          // Nothing loaded.
        }
        here.parentNode.removeChild(here);
      }
      var cur = track();
      if (t && !(cur && cur.sourceId === t.sourceId && cur.via === t.via && P.playing)) player.playTracks([t], 0);
    }
    if (!canHls) each(main.querySelectorAll('.hls-note'), function (n) { n.hidden = false; });
    // The devices page keeps up with the devices.
    if (path === '/lite/devices') {
      devicesTimer = win.setTimeout(function () {
        if (location.pathname === '/lite/devices' && !loading) go(location.pathname, false);
      }, 6000);
    }
    markCurrent();
  }

  function serialize(form) {
    var parts = [];
    each(form.elements, function (el) {
      if (!el.name || el.disabled) return;
      var type = (el.type || '').toLowerCase();
      if (type === 'submit' || type === 'button' || type === 'file') return;
      if ((type === 'checkbox' || type === 'radio') && !el.checked) return;
      parts.push(q(el.name) + '=' + q(el.value));
    });
    return parts.join('&');
  }

  /** The list [el] is in, as songs to play: just the one, or it and the rest of the list. */
  function playFrom(link) {
    var t = decode(tokenOf(link));
    if (!t) return;
    var list = closest(link, function (el) { return el.hasAttribute('data-list'); });
    if (!list || list.getAttribute('data-list') === 'single') return player.playTracks([t], 0);
    var tracks = [];
    var start = 0;
    each(list.querySelectorAll('a.t'), function (a) {
      if (a === link) start = tracks.length;
      var d = decode(tokenOf(a));
      if (d) tracks.push(d);
    });
    player.playTracks(tracks, start);
    playedList(list);
  }

  function playAll(shuffle) {
    var list = main.querySelector('[data-list]');
    if (!list) return;
    var tracks = [];
    each(list.querySelectorAll('a.t'), function (a) {
      var d = decode(tokenOf(a));
      if (d) tracks.push(d);
    });
    player.playTracks(tracks, 0, shuffle);
    playedList(list);
  }

  /** A playlist that started playing, for the lists of playlists played lately. */
  function playedList(list) {
    var holder = closest(list, function (el) { return el.hasAttribute('data-playlist'); });
    if (holder) api('POST', '/api/playlists/' + q(holder.getAttribute('data-playlist')) + '/played', {});
  }

  doc.addEventListener('click', function (e) {
    if (e.defaultPrevented || e.button > 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    var button = closest(e.target, tagIs('BUTTON'));
    if (button && button.type !== 'submit') {
      var act = button.getAttribute('data-act');
      if (act) {
        e.preventDefault();
        if (act === 'toggle') player.toggle();
        else if (act === 'next') player.next();
        else if (act === 'prev') player.previous();
        else if (act === 'shuffle') player.toggleShuffle();
        else if (act === 'repeat') player.cycleRepeat();
        else if (act === 'up') thumb('UP');
        else if (act === 'down') thumb('DOWN');
        return;
      }
      if (button.getAttribute('data-play') === 'shuffle') return playAll(true);
      var queue = button.getAttribute('data-queue');
      if (queue) {
        var t = decode(button.getAttribute('data-t'));
        if (!t) return;
        if (queue === 'next') player.playNext(t);
        else player.addToQueue(t);
        setText(button, queue === 'next' ? 'Plays next' : 'Added');
        return;
      }
      if (button.className.indexOf('opt') >= 0) {
        var row = closest(button, tagIs('LI'));
        var song = row && row.querySelector('a.t');
        if (song) go(pathOf(song), true);
      }
      return;
    }
    var link = closest(e.target, tagIs('A'));
    if (!link || link.target || link.hasAttribute('download')) return;
    if (link.getAttribute('data-jump')) {
      e.preventDefault();
      return player.jump(parseInt(link.getAttribute('data-jump'), 10));
    }
    if (/(^| )t( |$)/.test(link.className)) {
      e.preventDefault();
      return playFrom(link);
    }
    if (link.getAttribute('data-play') === 'all') {
      e.preventDefault();
      return playAll(false);
    }
    if (link.hasAttribute('data-near') && navigator.geolocation) {
      e.preventDefault();
      navigator.geolocation.getCurrentPosition(function (pos) {
        go('/lite/radio/stations?lat=' + pos.coords.latitude.toFixed(3) + '&lon=' + pos.coords.longitude.toFixed(3), true);
      }, function () {
        showError('Location wasn\'t shared, so stations near you can\'t be found');
      }, { timeout: 15000, maximumAge: 600000 });
      return;
    }
    var path = pathOf(link);
    if (path && isLitePath(path)) {
      e.preventDefault();
      go(path, true);
    }
  }, false);

  doc.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form || form.hasAttribute('data-full')) return;
    var action = form.getAttribute('action') || location.pathname;
    if (!isLitePath(action)) return;
    e.preventDefault();
    var data = serialize(form);
    if ((form.getAttribute('method') || 'get').toLowerCase() === 'post') go(action, true, 'POST', data);
    else go(action + (action.indexOf('?') < 0 ? '?' : '&') + data, true);
  }, false);

  win.addEventListener('popstate', function () {
    // Old Safari fires this when a page first loads: only a real change of page is loaded.
    if (location.href === shownUrl) return;
    shownUrl = location.href;
    go(location.pathname + location.search, false);
  }, false);

  if (win.history.replaceState) win.history.replaceState({ lite: 1 }, '', location.href);
  render();
  afterPage();
})();
