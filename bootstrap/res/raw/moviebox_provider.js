/**
 * MovieBox provider for Cahaya (v1.25.0).
 *
 * Clean-room implementation of the MovieBox private API flow, written
 * from the live-verified request/response shapes (2026-11-05). NOT a
 * copy of the upstream Nuvio provider (that repo ships no LICENSE, so
 * its file is never bundled). The signing keys are the ones the app
 * itself embeds to talk to its own API.
 *
 * Flow: anonymous token (x-user header) -> search by title -> fuzzy
 * match (title + year) -> play-info (+ resourceDetectors fallback) ->
 * H5 web API (movie-box.co detail/play, v1.25.0) for MP4 incl. 1080p.
 * The H5 route bypasses the app-API play-info notice entirely.
 *
 * getStreams(tmdbId, type, season, episode, title, year)
 *   type: "movie" | "tv" (Cahaya passes "series" for TV — normalized)
 *   title/year come from Cinemeta meta; no TMDB lookup is needed.
 * Returns [{url, title, quality, headers, subtitles, name}] like the
 * other providers. Fail-soft: any step failing returns [].
 */

// ---------------- pure-JS MD5 (RFC 1321) ----------------
function md5cycle(x, k) {
  var a = x[0], b = x[1], c = x[2], d = x[3];
  a = ff(a, b, c, d, k[0], 7, -680876936);
  d = ff(d, a, b, c, k[1], 12, -389564586);
  c = ff(c, d, a, b, k[2], 17, 606105819);
  b = ff(b, c, d, a, k[3], 22, -1044525330);
  a = ff(a, b, c, d, k[4], 7, -176418897);
  d = ff(d, a, b, c, k[5], 12, 1200080426);
  c = ff(c, d, a, b, k[6], 17, -1473231341);
  b = ff(b, c, d, a, k[7], 22, -45705983);
  a = ff(a, b, c, d, k[8], 7, 1770035416);
  d = ff(d, a, b, c, k[9], 12, -1958414417);
  c = ff(c, d, a, b, k[10], 17, -42063);
  b = ff(b, c, d, a, k[11], 22, -1990404162);
  a = ff(a, b, c, d, k[12], 7, 1804603682);
  d = ff(d, a, b, c, k[13], 12, -40341101);
  c = ff(c, d, a, b, k[14], 17, -1502002290);
  b = ff(b, c, d, a, k[15], 22, 1236535329);
  a = gg(a, b, c, d, k[1], 5, -165796510);
  d = gg(d, a, b, c, k[6], 9, -1069501632);
  c = gg(c, d, a, b, k[11], 14, 643717713);
  b = gg(b, c, d, a, k[0], 20, -373897302);
  a = gg(a, b, c, d, k[5], 5, -701558691);
  d = gg(d, a, b, c, k[10], 9, 38016083);
  c = gg(c, d, a, b, k[15], 14, -660478335);
  b = gg(b, c, d, a, k[4], 20, -405537848);
  a = gg(a, b, c, d, k[9], 5, 568446438);
  d = gg(d, a, b, c, k[14], 9, -1019803690);
  c = gg(c, d, a, b, k[3], 14, -187363961);
  b = gg(b, c, d, a, k[8], 20, 1163531501);
  a = gg(a, b, c, d, k[13], 5, -1444681467);
  d = gg(d, a, b, c, k[2], 9, -51403784);
  c = gg(c, d, a, b, k[7], 14, 1735328473);
  b = gg(b, c, d, a, k[12], 20, -1926607734);
  a = hh(a, b, c, d, k[5], 4, -378558);
  d = hh(d, a, b, c, k[8], 11, -2022574463);
  c = hh(c, d, a, b, k[11], 16, 1839030562);
  b = hh(b, c, d, a, k[14], 23, -35309556);
  a = hh(a, b, c, d, k[1], 4, -1530992060);
  d = hh(d, a, b, c, k[4], 11, 1272893353);
  c = hh(c, d, a, b, k[7], 16, -155497632);
  b = hh(b, c, d, a, k[10], 23, -1094730640);
  a = hh(a, b, c, d, k[13], 4, 681279174);
  d = hh(d, a, b, c, k[0], 11, -358537222);
  c = hh(c, d, a, b, k[3], 16, -722521979);
  b = hh(b, c, d, a, k[6], 23, 76029189);
  a = hh(a, b, c, d, k[9], 4, -640364487);
  d = hh(d, a, b, c, k[12], 11, -421815835);
  c = hh(c, d, a, b, k[15], 16, 530742520);
  b = hh(b, c, d, a, k[2], 23, -995338651);
  a = ii(a, b, c, d, k[0], 6, -198630844);
  d = ii(d, a, b, c, k[7], 10, 1126891415);
  c = ii(c, d, a, b, k[14], 15, -1416354905);
  b = ii(b, c, d, a, k[5], 21, -57434055);
  a = ii(a, b, c, d, k[12], 6, 1700485571);
  d = ii(d, a, b, c, k[3], 10, -1894986606);
  c = ii(c, d, a, b, k[10], 15, -1051523);
  b = ii(b, c, d, a, k[1], 21, -2054922799);
  a = ii(a, b, c, d, k[8], 6, 1873313359);
  d = ii(d, a, b, c, k[15], 10, -30611744);
  c = ii(c, d, a, b, k[6], 15, -1560198380);
  b = ii(b, c, d, a, k[13], 21, 1309151649);
  a = ii(a, b, c, d, k[4], 6, -145523070);
  d = ii(d, a, b, c, k[11], 10, -1120210379);
  c = ii(c, d, a, b, k[2], 15, 718787259);
  b = ii(b, c, d, a, k[9], 21, -343485551);
  x[0] = add32(a, x[0]); x[1] = add32(b, x[1]);
  x[2] = add32(c, x[2]); x[3] = add32(d, x[3]);
}
function cmn(q, a, b, x, s, t) {
  a = add32(add32(a, q), add32(x, t));
  return add32((a << s) | (a >>> (32 - s)), b);
}
function ff(a, b, c, d, x, s, t) { return cmn((b & c) | (~b & d), a, b, x, s, t); }
function gg(a, b, c, d, x, s, t) { return cmn((b & d) | (c & ~d), a, b, x, s, t); }
function hh(a, b, c, d, x, s, t) { return cmn(b ^ c ^ d, a, b, x, s, t); }
function ii(a, b, c, d, x, s, t) { return cmn(c ^ (b | ~d), a, b, x, s, t); }
function md5blk(s) {
  var md5blks = [], i;
  for (i = 0; i < 64; i += 4) {
    md5blks[i >> 2] = s.charCodeAt(i) + (s.charCodeAt(i + 1) << 8)
      + (s.charCodeAt(i + 2) << 16) + (s.charCodeAt(i + 3) << 24);
  }
  return md5blks;
}
function md51(s) {
  var n = s.length, state = [1732584193, -271733879, -1732584194, 271733878],
    i, length, tail, tmp, lo, hi;
  for (i = 64; i <= n; i += 64) md5cycle(state, md5blk(s.substring(i - 64, i)));
  s = s.substring(i - 64);
  length = s.length;
  tail = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
  for (i = 0; i < length; i += 1) tail[i >> 2] |= s.charCodeAt(i) << ((i % 4) << 3);
  tail[i >> 2] |= 0x80 << ((i % 4) << 3);
  if (i > 55) { md5cycle(state, tail); tail = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]; }
  tmp = n * 8; tmp = tmp.toString(16).match(/(.*?)(.{0,8})$/);
  lo = parseInt(tmp[2], 16); hi = parseInt(tmp[1], 16) || 0;
  tail[14] = lo; tail[15] = hi;
  md5cycle(state, tail);
  return state;
}
function rhex(n) {
  var s = "", j;
  for (j = 0; j < 4; j += 1)
    s += ((n >> (j * 8 + 4)) & 0x0f).toString(16)
      + ((n >> (j * 8)) & 0x0f).toString(16);
  return s;
}
function hex(x) { for (var i = 0; i < x.length; i += 1) x[i] = rhex(x[i]); return x.join(""); }
function add32(a, b) { return (a + b) & 0xFFFFFFFF; }
function md5(s) { return hex(md51(s)); }
// MD5 of a byte array (for HMAC with binary keys)
function md5Bytes(bytes) {
  var s = "";
  for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i] & 0xFF);
  return md51(s);
}
function md5HexFromBytes(bytes) { return hex(md5Bytes(bytes)); }

// HMAC-MD5, returns Base64 (WebView has btoa built in)
function hmacMd5B64(keyStr, dataStr) {
  var key = [];
  for (var i = 0; i < keyStr.length; i++) key.push(keyStr.charCodeAt(i) & 0xFF);
  if (key.length > 64) {
    var h = md5Bytes(key);
    key = [];
    for (var j = 0; j < 4; j++) {
      key.push(h[j] & 0xFF, (h[j] >> 8) & 0xFF, (h[j] >> 16) & 0xFF, (h[j] >> 24) & 0xFF);
    }
  }
  var oPad = [], iPad = [];
  for (var k = 0; k < 64; k++) {
    var b = k < key.length ? key[k] : 0;
    oPad.push(b ^ 0x5C); iPad.push(b ^ 0x36);
  }
  var data = [];
  for (var m = 0; m < dataStr.length; m++) data.push(dataStr.charCodeAt(m) & 0xFF);
  var inner = md5Bytes(iPad.concat(data));
  var innerBytes = [];
  for (var n = 0; n < 4; n++) {
    innerBytes.push(inner[n] & 0xFF, (inner[n] >> 8) & 0xFF,
      (inner[n] >> 16) & 0xFF, (inner[n] >> 24) & 0xFF);
  }
  var outer = md5Bytes(oPad.concat(innerBytes));
  var out = [];
  for (var p = 0; p < 4; p++) {
    out.push(outer[p] & 0xFF, (outer[p] >> 8) & 0xFF,
      (outer[p] >> 16) & 0xFF, (outer[p] >> 24) & 0xFF);
  }
  var bin = "";
  for (var q = 0; q < out.length; q++) bin += String.fromCharCode(out[q]);
  return btoa(bin);
}

// UTF-8 encode a JS string to a byte-string (charCodeAt = byte)
function utf8Bytes(s) {
  return unescape(encodeURIComponent(s));
}

// ---------------- API constants ----------------
var API_BASE = "https://api3.aoneroom.com";
var TV_BASE = "https://tv.aoneroom.com";   // v1.34.3: caption fallback (see below)
var PLAYER_BASE = "https://moviebox.ph";
var TOKEN_URL = "https://apig.inmoviebox.com/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1";
var PLAYER_UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36";
// v1.25.0: H5 web backend (movie-box.co) — the CloudStream CNCVerse
// plugin's route to real streams incl. 1080p MP4. No token, no signing.
var H5_BASE = "https://movie-box.co";
var H5_DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36";
// v1.26.0: home rows (ranking lists) — the rows CloudStream shows for
// the MovieBox provider. Note (verified live 2026-11-07): the
// ranking-list endpoint currently returns the same 20 subjects for
// every categoryType tested — the sections still render as separate
// rows at his word (deleted once in v1.26.3, restored in v1.26.4).
// v1.28.0: only Trending is kept — "Trending in Cinema" and "Top
// Series This Week" were deleted at his word (same list anyway).
var RANK_BASE = "https://apig.inmoviebox.com";
var HOME_SECTIONS = [
  ["Trending", "4516404531735022304"]
];

// v1.29.0: the 10 country list rows (1.27.0–1.28.0, POST
// /wefeed-mobile-bff/subject-api/list) are deleted with the source
// switch — only the Trending ranking row is merged into the Cahaya
// home now. The list code lives on in the 1.28.0 APK if he ever
// wants the rows back.
// Signing key the app embeds (base64 of the UTF-8 key string).
var SECRET_KEY = atob(atob("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="));
var DEVICE_ID = "";
var BRAND = "samsung";
var MODEL = "SM-S918B";
var bearerToken = null;

function initSession() {
  if (DEVICE_ID) return;
  var chars = "0123456789abcdef";
  for (var i = 0; i < 32; i++) DEVICE_ID += chars[Math.floor(Math.random() * 16)];
}

function xClientToken(ts) {
  var s = String(ts);
  return s + "," + md5(s.split("").reverse().join(""));
}

function canonical(method, accept, contentType, url, body, ts) {
  var path = "", query = "";
  try {
    var u = new URL(url);
    path = u.pathname;
    var keys = [];
    u.searchParams.forEach(function (_, k) { if (keys.indexOf(k) < 0) keys.push(k); });
    keys.sort();
    var parts = [];
    keys.forEach(function (k) {
      u.searchParams.getAll(k).forEach(function (v) { parts.push(k + "=" + v); });
    });
    query = parts.join("&");
  } catch (e) {
    var q = url.indexOf("?");
    if (q >= 0) {
      path = url.substring(0, q).replace(/^https?:\/\/[^\/]+/, "");
      query = url.substring(q + 1).split("&").sort().join("&");
    } else {
      path = url.replace(/^https?:\/\/[^\/]+/, "");
    }
  }
  var canonUrl = query ? path + "?" + query : path;
  var bodyHash = "", bodyLen = "";
  if (body) {
    var b = utf8Bytes(body);
    bodyHash = md5(b);
    bodyLen = String(b.length);
  }
  return method.toUpperCase() + "\n" + (accept || "") + "\n" + (contentType || "")
    + "\n" + bodyLen + "\n" + ts + "\n" + bodyHash + "\n" + canonUrl;
}

function xTrSignature(method, accept, contentType, url, body, ts) {
  var canon = canonical(method, accept, contentType, url, body, ts);
  return ts + "|2|" + hmacMd5B64(SECRET_KEY, utf8Bytes(canon));
}

function clientInfo() {
  return JSON.stringify({
    package_name: "com.community.mbox.in",
    version_name: "4.0.03.1028.03",
    version_code: 52120138,
    os: "android", os_version: "14",
    device_id: DEVICE_ID,
    install_store: "official",
    gaid: "1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d",
    brand: BRAND, model: MODEL,
    system_language: "en", net: "NETWORK_WIFI",
    region: "IN", timezone: "Asia/Calcutta", sp_code: ""
  });
}

function jwtExp(token) {
  try {
    var parts = token.split(".");
    if (parts.length < 2) return 0;
    var b64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    while (b64.length % 4) b64 += "=";
    return JSON.parse(atob(b64)).exp || 0;
  } catch (e) { return 0; }
}

async function getToken() {
  if (bearerToken && jwtExp(bearerToken) > Date.now() / 1000 + 3600) return bearerToken;
  initSession();
  var ts = Date.now();
  var accept = "application/json";
  var res = await fetch(TOKEN_URL, {
    method: "GET",
    headers: {
      "Accept": accept,
      "Content-Type": "application/json",
      "x-client-token": xClientToken(ts),
      "x-tr-signature": xTrSignature("GET", accept, "application/json", TOKEN_URL, null, ts),
      "User-Agent": "com.community.mbox.in/52120138 (Linux; U; Android 14; en_IN; SM-S918B; Build/UD1A.230803.041; Cronet/145.0.7582.0)",
      "x-client-info": clientInfo(),
      "x-client-status": "0"
    }
  });
  var xUser = res.headers && res.headers.get ? res.headers.get("x-user") : null;
  if (xUser) {
    try {
      var tok = JSON.parse(xUser).token;
      if (tok && jwtExp(tok) > Date.now() / 1000 + 3600) { bearerToken = tok; return tok; }
    } catch (e) { /* fall through */ }
  }
  return bearerToken || "";
}

async function mbRequest(method, url, body, extraHeaders, isTokenFetch) {
  initSession();
  var ts = Date.now();
  var accept = "application/json";
  var contentType = (extraHeaders && extraHeaders["Content-Type"])
    || (body ? "application/json; charset=utf-8" : "application/json");
  var headers = {
    "Accept": accept,
    "Content-Type": contentType,
    "x-client-token": xClientToken(ts),
    "x-tr-signature": xTrSignature(method, accept, contentType, url, body, ts),
    "User-Agent": "com.community.mbox.in/52120138 (Linux; U; Android 14; en_IN; SM-S918B; Build/UD1A.230803.041; Cronet/145.0.7582.0)",
    "x-client-info": clientInfo(),
    "x-client-status": "0"
  };
  if (extraHeaders) for (var k in extraHeaders) headers[k] = extraHeaders[k];
  if (!isTokenFetch) {
    var tok = await getToken();
    if (tok) headers["Authorization"] = "Bearer " + tok;
  }
  var res = await fetch(url, { method: method, headers: headers, body: body || undefined });
  var data = null;
  try { data = await res.json(); } catch (e) { data = null; }
  return { status: res.status, ok: res.ok, data: data, headers: res.headers };
}

// ---------------- search + match ----------------
function normalizeTitle(s) {
  return (s || "").toLowerCase().replace(/[^a-z0-9\u4e00-\u9fff]/g, "")
    .replace(/^the/, "").trim();
}

function findBestMatch(subjects, title, year, mediaType) {
  var norm = normalizeTitle(title);
  var targetType = mediaType === "movie" ? 1 : 2;
  var best = null, bestScore = 0;
  for (var i = 0; i < subjects.length; i++) {
    var sub = subjects[i];
    if (sub.subjectType !== targetType) continue;
    var nt = normalizeTitle(sub.title);
    var y = sub.year || (sub.releaseDate ? sub.releaseDate.substring(0, 4) : null);
    var score = 0;
    if (nt === norm) score += 50;
    else if (nt.indexOf(norm) >= 0 || norm.indexOf(nt) >= 0) score += 15;
    if (year && y && String(year) === String(y)) score += 35;
    if (score > bestScore) { bestScore = score; best = sub; }
  }
  return bestScore >= 40 ? best : null;
}

async function searchMovieBox(query) {
  var url = API_BASE + "/wefeed-mobile-bff/subject-api/search/v2";
  var body = JSON.stringify({ page: 1, perPage: 20, keyword: query, restrictKid: 1 });
  var r = await mbRequest("POST", url, body);
  var out = [];
  try {
    var results = r.data && r.data.data && r.data.data.results;
    if (results) results.forEach(function (g) {
      if (g.subjects) out = out.concat(g.subjects);
    });
  } catch (e) { /* ignore */ }
  return out;
}

// ---------------- play-info ----------------
function formatType(url) {
  if (/\.m3u8/i.test(url)) return "HLS";
  if (/\.mpd/i.test(url)) return "DASH";
  if (/\.mp4/i.test(url)) return "MP4";
  if (/\.mkv/i.test(url)) return "MKV";
  return "HLS";
}
function qualityNum(v) {
  var m = String(v || "").match(/(\d{3,4})/);
  return m ? parseInt(m[1], 10) : 0;
}
function audioLabel(stream, fallback) {
  var raw = stream.languageName || stream.lanName || stream.language
    || stream.lan || fallback || "Unknown";
  var lang = String(raw).replace(/\bdub\b/gi, " ").replace(/\baudio\b/gi, " ")
    .replace(/[_-]+/g, " ").replace(/\s+/g, " ").trim();
  var names = { ar: "Arabic", bn: "Bengali", de: "German", en: "English",
    es: "Spanish", fr: "French", hi: "Hindi", id: "Indonesian", it: "Italian",
    ja: "Japanese", ko: "Korean", ml: "Malayalam", mr: "Marathi",
    pt: "Portuguese", ru: "Russian", ta: "Tamil", te: "Telugu", th: "Thai",
    tr: "Turkish", ur: "Urdu", vi: "Vietnamese", zh: "Chinese" };
  var code = (lang.match(/^([a-z]{2,3})(?:\s|$)/i) || [])[1];
  if (code && names[code.toLowerCase()]) lang = names[code.toLowerCase()];
  else if (lang) lang = lang.charAt(0).toUpperCase() + lang.slice(1);
  return lang + " Audio";
}

// H5 (movie-box.co) helpers — v1.25.0.
function h5Slug(detailUrl) {
  try {
    var parts = new URL(detailUrl).pathname.split("/").filter(function (s) { return s; });
    return parts.length ? parts[parts.length - 1] : "";
  } catch (e) { return ""; }
}
// Best resolution from the H5 detail seasons list (fallback only —
// the MP4 entries usually carry their own resolutions field).
function h5QualityFor(seasons, season, episode) {
  var best = 0;
  if (!Array.isArray(seasons)) return 0;
  seasons.forEach(function (s) {
    if (s.se != null && s.se !== season) return;
    if (!Array.isArray(s.resolutions)) return;
    s.resolutions.forEach(function (r) {
      var v = parseInt(r.resolution, 10);
      if (v > best) best = v;
    });
  });
  return best;
}

function collectStreams(playData) {
  var streams = Array.isArray(playData.streams) ? playData.streams.slice() : [];
  [["netDash", "DASH"], ["netHls", "HLS"]].forEach(function (pair) {
    var key = pair[0], fmt = pair[1];
    var value = playData[key] != null ? playData[key]
      : (playData.data ? playData.data[key] : null);
    var values = Array.isArray(value) ? value : (value ? [value] : []);
    values.forEach(function (item) {
      if (typeof item === "string") streams.push({ url: item, format: fmt });
      else if (item && typeof item === "object") {
        if (item.url || item.playUrl || item.resourceLink || item.streamUrl)
          streams.push(Object.assign({}, item, { format: item.format || fmt }));
        else Object.keys(item).forEach(function (res) {
          var u = item[res];
          if (typeof u === "string" && /^https?:\/\//i.test(u))
            streams.push({ url: u, resolution: res, format: fmt });
          else if (u && typeof u === "object")
            streams.push(Object.assign({}, u, { resolution: u.resolution || res, format: u.format || fmt }));
        });
      }
    });
  });
  var seen = {};
  return streams.filter(function (s) {
    var key = s.url || s.playUrl || s.resourceLink || s.streamUrl;
    if (!key || seen[key]) return false;
    seen[key] = true; return true;
  });
}

// v1.34.0: MovieBox's own subtitles, wired into the player.
// Verified live 2026-11-11 (phone, home IP):
//  - phone play-info has NO subTitleList; subtitles come only from
//    the two caption endpoints below.
//  - get-stream-captions?subjectId&streamId=<stream.id> is the
//    reliable one: Avatar 13 caps, Wednesday S1E1 15 caps.
//  - get-ext-captions?subjectId&resourceId&episode works for movies
//    (Avatar 13) but returned 0 for Wednesday S1E1 — kept as fallback.
//  - URLs are CloudFront-signed .srt, plain-GET fetchable; some
//    entries are dead (404) and fail soft at download time.
//  - language is the CODE (c.lan, e.g. "in_id", "en") so the ranker
//    can match the preferred language; the label is the display
//    name + MovieBox.
async function fetchSubtitles(subjectId, streamId, langLabel, episode) {
  var subs = [];
  var seen = {};
  function pushCaps(caps) {
    if (!Array.isArray(caps)) return;
    caps.forEach(function (c) {
      if (!c.url || seen[c.url]) return;
      seen[c.url] = true;
      var code = c.lan || c.language || "en";
      var disp = c.lanName || c.language || code;
      subs.push({ url: c.url, language: code,
        name: disp + " · MovieBox" });
    });
  }
  try {
    var u1 = API_BASE + "/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId="
      + subjectId + "&streamId=" + encodeURIComponent(streamId);
    var r1 = await mbRequest("GET", u1, null);
    pushCaps(r1.data && r1.data.data && r1.data.data.extCaptions);
  } catch (e) { /* ignore */ }
  try {
    var u2 = API_BASE + "/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId="
      + subjectId + "&resourceId=" + encodeURIComponent(streamId)
      + "&episode=" + (episode || 0);
    var r2 = await mbRequest("GET", u2, null);
    pushCaps(r2.data && r2.data.data && r2.data.data.extCaptions);
  } catch (e) { /* ignore */ }
  return subs;
}

async function getStreamLinks(subjectId, season, episode, mediaTitle, mediaType) {
  var detailUrl = API_BASE + "/wefeed-mobile-bff/subject-api/get?subjectId=" + subjectId;
  var detailRes = await mbRequest("GET", detailUrl, null);
  if (!detailRes.data || !detailRes.data.data) return [];
  var subjectData = detailRes.data.data;
  var detailPath = subjectData.detailPath || subjectData.detail_path
    || subjectData.path || subjectData.slug || "";
  var webBase = PLAYER_BASE;
  var referer = webBase + "/";
  if (detailPath) {
    var pageUrl = webBase + "/movies/" + detailPath.replace(/^\/+/, "").replace(/^movies\//, "")
      + "?id=" + subjectId + "&type=/movie/detail&detailSe=&detailEp=&lang=en";
    referer = pageUrl;
  }
  var subjectIds = [];
  var originalLang = "Original";
  if (Array.isArray(subjectData.dubs)) {
    subjectData.dubs.forEach(function (dub) {
      if (dub.subjectId == subjectId) originalLang = dub.lanName || "Original";
      else subjectIds.push({ id: dub.subjectId, lang: dub.lanName });
    });
  }
  subjectIds.unshift({ id: subjectId, lang: originalLang });
  var playbackHeaders = {
    "Origin": webBase, "Referer": referer, "User-Agent": PLAYER_UA,
    "x-request-lang": "en", "x-vip-restrict": "0", "x-no-high-risk-restrict": "0"
  };
  // v1.23.3: same as the TV provider — the file CDN 429s requests
  // carrying the moviebox.ph Referer. File requests get Origin + UA
  // only (plus the per-stream sign Cookie when the backend supplies one).
  var fileHeaders = { "Origin": webBase, "User-Agent": PLAYER_UA };
  var allStreams = [];
  // v1.25.0: H5 detail once per title — returns the per-dub detailPaths
  // (subject.dubs[].detailPath) the H5 play calls need, plus the
  // seasons/resolutions list used as a quality fallback. No token.
  var h5DubPaths = null, h5Seasons = null, mainSlug = h5Slug(subjectData.detailUrl || "");
  if (mainSlug) {
    try {
      var h5DetailRes = await fetch(H5_BASE + "/wefeed-h5api-bff/detail?detailPath="
        + encodeURIComponent(mainSlug), {
        method: "GET",
        headers: {
          "Accept": "application/json",
          "Origin": H5_BASE,
          "Referer": "https://mzfi.me/",
          "User-Agent": H5_DESKTOP_UA,
          "x-client-info": JSON.stringify({ timezone: "Asia/Calcutta" })
        }
      });
      var h5Detail = null;
      try { h5Detail = await h5DetailRes.json(); } catch (e3) { h5Detail = null; }
      var h5Subject = h5Detail && h5Detail.data && h5Detail.data.subject;
      if (h5Subject && Array.isArray(h5Subject.dubs)) {
        h5DubPaths = {};
        h5Subject.dubs.forEach(function (d) {
          if (d.subjectId && d.detailPath) h5DubPaths[String(d.subjectId)] = d.detailPath;
        });
      }
      var h5Resource = h5Detail && h5Detail.data && h5Detail.data.resource;
      if (h5Resource && Array.isArray(h5Resource.seasons)) h5Seasons = h5Resource.seasons;
    } catch (eH5d) {
      console.log("[MovieBox H5] detail failed: " + eH5d.message);
    }
  }
  for (var i = 0; i < subjectIds.length; i++) {
    var item = subjectIds[i];
    try {
      var params = "subjectId=" + item.id + "&se=" + season + "&ep=" + episode + "&streamSignType=1";
      if (detailPath) params += "&detailPath=" + encodeURIComponent(detailPath);
      params += "&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1";
      var playUrl = API_BASE + "/wefeed-mobile-bff/subject-api/play-info?" + params;
      var playRes = await mbRequest("GET", playUrl, null, playbackHeaders);
      var playData = playRes.data && playRes.data.data;
      if (!playData) continue;
      var streamsList = collectStreams(playData);
      // v1.34.1: this dub's captions, fetched ONCE from the first
      // usable stream's id and attached to EVERY stream from the dub
      // — app-API, detectors fallback and H5 alike. (v1.34.0 only
      // attached them to app-API streams, so the H5 1080p rows — the
      // ones that actually play — had none.)
      var dubSubs = [];
      var dubStreamId = null;
      for (var si = 0; si < streamsList.length; si++) {
        var sv = streamsList[si];
        var rv = sv.url || sv.playUrl || sv.resourceLink || sv.streamUrl || "";
        if (!rv) continue;
        if (rv.indexOf("b164fbfb4347792950bdfbfb563d39d9") >= 0) continue;
        if (/\/other\/\d{4}\/\d{2}\/\d{2}\//.test(rv)) continue;
        if (!sv.id) continue;
        dubStreamId = String(sv.id);
        break;
      }
      // v1.34.3: the app-API play-info can come back empty (311 bytes,
      // no streams) for a title — Sunshine Through the Rain S1E1 does
      // this every time — leaving streamsList empty and dubStreamId
      // null, so the H5 streams got zero captions. The TV play-info
      // (same title, same episode) reliably returns a resource, and
      // captions are per-title, so its resourceId fetches the same
      // captions. Use it as the fallback id.
      if (!dubStreamId) {
        try {
          var tvParams = "subjectId=" + item.id + "&se=" + season + "&ep=" + episode
            + "&streamSignType=1&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1";
          var tvRes = await mbRequest("GET", TV_BASE + "/wefeed-tv-bff/subject/play-info/v2?" + tvParams, null, playbackHeaders);
          var tvData = tvRes.data && tvRes.data.data;
          var tvResources = tvData && Array.isArray(tvData.resources) ? tvData.resources : [];
          for (var ti = 0; ti < tvResources.length; ti++) {
            if (tvResources[ti].resourceId) { dubStreamId = String(tvResources[ti].resourceId); break; }
          }
        } catch (eTv) { /* fall through with no captions */ }
      }
      if (dubStreamId) dubSubs = await fetchSubtitles(item.id, dubStreamId, item.lang, episode);
      var hasValid = false;
      for (var j = 0; j < streamsList.length; j++) {
        var stream = streamsList[j];
        var rawUrl = stream.url || stream.playUrl || stream.resourceLink || stream.streamUrl || "";
        if (!rawUrl) continue;
        if (rawUrl.indexOf("b164fbfb4347792950bdfbfb563d39d9") >= 0) continue;
        // v1.21.2: the backend serves a ~23 s "this version will be
        // discontinued" notice video as the ONLY play-info stream when
        // it deems the client old. Notice URLs live under
        // /other/YYYY/MM/DD/ on macdn — real streams never do.
        // Skipping them lets the resourceDetectors fallback below run.
        if (/\/other\/\d{4}\/\d{2}\/\d{2}\//.test(rawUrl)) continue;
        var fmt = stream.format
          ? (["DASH", "HLS", "MP4", "MKV"].indexOf(String(stream.format).toUpperCase()) >= 0
            ? String(stream.format).toUpperCase() : formatType(rawUrl))
          : formatType(rawUrl);
        var qn = qualityNum(stream.resolutions || stream.resolution || stream.quality);
        var quality = qn ? qn + "p" : "Auto";
        var audio = audioLabel(stream, item.lang);
        var subs = dubSubs;
        var hdrs = Object.assign({}, fileHeaders);
        var signCookie = stream.signCookie || null;
        var signKey = stream.signHeaderKey || stream.sign_header_key || "Cookie";
        if (signCookie) hdrs[signKey] = signCookie;
        allStreams.push({
          name: "MovieBox",
          title: mediaTitle + (season > 0 ? " S" + season + "E" + episode : "")
            + " - " + quality + " (" + audio + ") [" + fmt + "]",
          url: rawUrl, quality: quality, headers: hdrs,
          subtitles: subs, provider: "moviebox"
        });
        hasValid = true;
      }
      if (!hasValid) {
        var detectors = playData.resourceDetectors || subjectData.resourceDetectors;
        if (Array.isArray(detectors)) detectors.forEach(function (det) {
          // v1.21.2: the direct file URL lives on the detector itself
          // (downloadUrl, signed, ~1.6 GB for a movie). resolutionList
          // is often empty — the old code only looked there and missed it.
          if (det.downloadUrl) {
            var q = (det.resourceLink || "").match(/(\d{3,4})[pP]/);
            var dq = q ? q[1] + "p" : "Auto";
            allStreams.push({
              name: "MovieBox",
              title: mediaTitle + (season > 0 ? " S" + season + "E" + episode : "")
                + " - " + dq + " (" + audioLabel({}, item.lang) + ") [Direct]",
              url: det.downloadUrl, quality: dq,
              headers: Object.assign({}, fileHeaders), subtitles: dubSubs, provider: "moviebox"
            });
          }
          if (!Array.isArray(det.resolutionList)) return;
          det.resolutionList.forEach(function (v) {
            if (!v.resourceLink) return;
            var se = v.se != null ? v.se : 0, ep = v.ep != null ? v.ep : 0;
            if ((season > 0 || episode > 0) && (se !== season || ep !== episode)) return;
            allStreams.push({
              name: "MovieBox",
              title: mediaTitle + (season > 0 ? " S" + season + "E" + episode : "")
                + " - " + (v.resolution ? v.resolution + "p" : "Auto")
                + " (" + audioLabel({}, item.lang) + ") [Fallback]",
              url: v.resourceLink, quality: v.resolution ? v.resolution + "p" : "Auto",
              headers: Object.assign({}, fileHeaders), subtitles: dubSubs, provider: "moviebox"
            });
          });
        });
      }
      // v1.25.0: H5 web-backend streams (movie-box.co), additive per dub.
      // The H5 file CDN (bcdnxw) REQUIRES the movie-box.co Referer +
      // Origin — fileHeaders (Origin moviebox.ph, no Referer) gets HTTP
      // 429 there (verified live 2026-11-07) — so H5 links carry their
      // own headers. DASH is NOT emitted: the manifest answered 403
      // from home IP (unverifiable); the MP4 carries the same 1080p.
      try {
        if (mainSlug) {
          var dubPath = (h5DubPaths && h5DubPaths[String(item.id)]) || mainSlug;
          var h5PlayUrl = H5_BASE + "/wefeed-h5api-bff/subject/play?subjectId=" + item.id
            + "&se=" + season + "&ep=" + episode + "&detailPath=" + encodeURIComponent(dubPath)
            + "&streamSignType=1&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1";
          var h5Referer = H5_BASE + "/movies/" + dubPath + "?id=" + item.id
            + "&type=/movie/detail&detailSe=&detailEp=&lang=en";
          var h5PlayRes = await fetch(h5PlayUrl, {
            method: "GET",
            headers: {
              "Accept": "application/json",
              "Origin": H5_BASE,
              "Referer": h5Referer,
              "User-Agent": PLAYER_UA
            }
          });
          var h5PlayData = null;
          try { h5PlayData = await h5PlayRes.json(); } catch (e2) { h5PlayData = null; }
          var h5Play = h5PlayData && h5PlayData.data;
          if (h5Play && h5Play.hasResource && Array.isArray(h5Play.streams)) {
            // Quality: MP4 entries carry resolutions ("1080"); the DASH
            // entry's URL carries the token (_1080_). Prefer the MP4's
            // own field, then DASH, then the H5 detail seasons.
            var dashQ = 0;
            if (Array.isArray(h5Play.dash)) h5Play.dash.forEach(function (d) {
              var m = String(d.url || "").match(/_(\d{3,4})_/);
              if (m) dashQ = Math.max(dashQ, parseInt(m[1], 10));
            });
            h5Play.streams.forEach(function (st) {
              var u = st.url || "";
              if (!u) return;
              if (/\/other\/\d{4}\/\d{2}\/\d{2}\//.test(u)) return;
              var q = qualityNum(st.resolutions || st.resolution || st.quality) || dashQ
                || h5QualityFor(h5Seasons, season, episode);
              var hq = q ? q + "p" : "Auto";
              var hdrs = { "Origin": H5_BASE, "Referer": h5Referer, "User-Agent": PLAYER_UA };
              if (st.signCookie) hdrs[st.signHeaderKey || "Cookie"] = st.signCookie;
              allStreams.push({
                name: "MovieBox",
                title: mediaTitle + (season > 0 ? " S" + season + "E" + episode : "")
                  + " - " + hq + " (" + audioLabel({}, item.lang) + ") [MP4] [H5]",
                url: u, quality: hq, headers: hdrs, subtitles: dubSubs, provider: "moviebox"
              });
            });
          }
        }
      } catch (eH5) {
        console.log("[MovieBox H5] play failed for " + item.id + ": " + eH5.message);
      }
    } catch (e) {
      console.log("[MovieBox Stream Fetch Error] ID: " + item.id + " " + e.message);
    }
  }
  var rank = { "2160p": 2160, "4k": 2160, "1440p": 1440, "1080p": 1080,
    "720p": 720, "480p": 480, "360p": 360, "240p": 240, "auto": 1 };
  // v1.21.2: dedupe by URL — the resourceDetectors fallback emits one
  // row per dub, but they all share the same downloadUrl.
  var seenUrl = {};
  allStreams = allStreams.filter(function (s) {
    if (seenUrl[s.url]) return false;
    seenUrl[s.url] = true; return true;
  });
  allStreams.sort(function (a, b) {
    return (rank[(b.quality || "").toLowerCase()] || 0)
      - (rank[(a.quality || "").toLowerCase()] || 0);
  });
  // v1.34.2 diag: how many streams carry provider subtitles (and how
  // many subs each) — the picker still shows "None found" on 1.34.1.
  try {
    var _diag = allStreams.map(function (s) {
      return (s.provider || "?") + ":" + (s.quality || "?") + "="
        + ((s.subtitles && s.subtitles.length) || 0);
    }).join(", ");
    console.log("[MovieBox] return " + allStreams.length + " streams | " + _diag);
  } catch (eD) { }
  return allStreams;
}

// ---------------- home rows (v1.26.0) ----------------
// The rows CloudStream shows for the MovieBox provider, from the same
// ranking-list endpoint the token fetch uses. Each section is one
// categoryType; subjects carry title, releaseDate, cover poster,
// subjectId and subjectType (1 = movie, 2 = series). Fail-soft per
// section: a dead section is skipped, the rest still render.
// v1.26.4: after the ranking calls, the app-API detail is fetched
// once per unique subjectId (in parallel) and attached to the item
// (desc/genre/rating/episodes). The detail is the fallback metadata
// for titles Cinemeta/TMDB cannot resolve — without it those titles
// were dropped from the rows even though MovieBox can play them.
// A failed detail fetch just leaves the fields empty.
async function getHomeRows() {
  var rows = [];
  for (var i = 0; i < HOME_SECTIONS.length; i++) {
    try {
      var url = RANK_BASE + "/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType="
        + HOME_SECTIONS[i][1] + "&page=1&perPage=20";
      var r = await mbRequest("GET", url, null);
      var subs = r.data && r.data.data && r.data.data.subjects;
      if (!Array.isArray(subs) || !subs.length) continue;
      var items = [];
      subs.forEach(function (s) {
        if (!s.title) return;
        var y = 0;
        if (s.releaseDate) y = parseInt(String(s.releaseDate).substring(0, 4), 10) || 0;
        items.push({
          title: s.title, year: y,
          poster: s.cover && s.cover.url ? s.cover.url : "",
          subjectId: String(s.subjectId || ""),
          type: s.subjectType === 1 ? "movie" : "series"
        });
      });
      if (items.length) rows.push({ name: HOME_SECTIONS[i][0], items: items });
    } catch (e) {
      console.log("[MovieBox home] section failed: " + HOME_SECTIONS[i][0] + " " + e.message);
    }
  }
  // MovieBox detail fallback, one call per unique title, in parallel.
  var seen = {};
  var ids = [];
  rows.forEach(function (r) {
    r.items.forEach(function (it) {
      if (it.subjectId && !seen[it.subjectId]) { seen[it.subjectId] = true; ids.push(it.subjectId); }
    });
  });
  var byId = {};
  await Promise.all(ids.map(async function (sid) {
    try {
      var r = await mbRequest("GET", API_BASE + "/wefeed-mobile-bff/subject-api/get?subjectId=" + sid, null);
      var d = r.data && r.data.data;
      if (!d) return;
      var eps = 0;
      if (d.resourceDetectors && d.resourceDetectors[0] && d.resourceDetectors[0].totalEpisode) {
        eps = parseInt(d.resourceDetectors[0].totalEpisode, 10) || 0;
      }
      var rating = "";
      if (d.imdbRatingValue !== undefined && d.imdbRatingValue !== null && d.imdbRatingValue !== "") rating = String(d.imdbRatingValue);
      else if (d.imdbRate) rating = String(d.imdbRate);
      byId[sid] = {
        desc: d.description || "",
        genre: d.genre || "",
        rating: rating,
        episodes: eps
      };
    } catch (e) { /* leave the item without detail */ }
  }));
  rows.forEach(function (r) {
    r.items.forEach(function (it) {
      var det = byId[it.subjectId];
      if (det) {
        it.desc = det.desc; it.genre = det.genre;
        it.rating = det.rating; it.episodes = det.episodes;
      }
    });
  });
  return rows;
}

// ---------------- entry point ----------------
async function getStreams(tmdbId, mediaType, seasonNum, episodeNum, title, year) {
  try {
    var type = mediaType === "tv" || mediaType === "series" ? "tv" : "movie";
    var season = type === "tv" ? (seasonNum || 1) : 0;
    var episode = type === "tv" ? (episodeNum || 1) : 0;
    if (!title) { console.log("[MovieBox] no title, skipping"); return []; }
    console.log("[MovieBox] searching: " + title + " (" + year + ")");
    var subjects = await searchMovieBox(title);
    var best = findBestMatch(subjects, title, year, type);
    if (!best) {
      console.log("[MovieBox] no match for: " + title);
      return [];
    }
    console.log("[MovieBox] match: " + best.title + " id=" + best.subjectId);
    return await getStreamLinks(best.subjectId, season, episode, title, type);
  } catch (e) {
    console.log("[MovieBox] error: " + (e && e.message ? e.message : e));
    return [];
  }
}

module.exports = { getStreams, getHomeRows };
