/**
 * MovieBox TV provider for Cahaya (v1.22.0).
 *
 * Same MovieBox account system as the phone provider, but talks to
 * the TV backend (tv.aoneroom.com /wefeed-tv-bff/*) instead of the
 * phone backend (api3.aoneroom.com /wefeed-mobile-bff/*).
 *
 * Why: the phone backend serves a ~23 s "this version will be
 * discontinued" notice video as the ONLY play-info stream for many
 * titles (and no direct download for new K-dramas). The TV backend
 * returns real direct MP4 resources in play-info/v2's `resources[]`
 * (verified live 2026-11-05: Inception 720p; Doctor X S1E1 in
 * 360p/480p/720p/1080p — the phone backend had zero for Doctor X).
 *
 * Signing: same x-tr-signature scheme (HMAC-MD5, same secret bytes,
 * same canonical format as the phone provider — URLSearchParams query,
 * json headers, bodyLen="" for GET). TV client identity
 * (com.community.mbox.tv 1.1.10.0901.03 / 50040016) for all calls,
 * including the token fetch (verified: the token endpoint accepts it).
 * Token + search still use the phone endpoints (same subjectIds work
 * on both backends — verified for Inception and Doctor X).
 *
 * Flow: anonymous token (x-user header) -> phone search by title ->
 * fuzzy match (title + year) -> TV play-info/v2 per dub -> resources[]
 * (direct MP4) with playback headers. Subtitles: phone caption
 * endpoints (same subjectId/resourceId); often empty — the app's own
 * online subtitle sources cover the rest.
 *
 * getStreams(tmdbId, type, season, episode, title, year)
 * Returns [{url, title, quality, headers, subtitles, name}].
 * Fail-soft: any step failing returns [].
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
var API_BASE = "https://api3.aoneroom.com";       // phone backend (token + search + detail + captions)
var TV_BASE = "https://tv.aoneroom.com";         // TV backend (play-info/v2)
var PLAYER_BASE = "https://moviebox.ph";
var TOKEN_URL = "https://apig.inmoviebox.com/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1";
var PLAYER_UA = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36";
// Signing key the app embeds (base64 of the UTF-8 key string).
var SECRET_KEY = atob(atob("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="));
var DEVICE_ID = "";
var BRAND = "Skyworth";
var MODEL = "B866F";
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
    package_name: "com.community.mbox.tv",
    version_name: "1.1.10.0901.03",
    version_code: 50040016,
    os: "android", os_version: "11",
    device_id: DEVICE_ID,
    install_store: "official",
    gaid: "1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d",
    brand: BRAND, model: MODEL,
    system_language: "en", net: "NETWORK_WIFI",
    region: "ID", timezone: "Asia/Jakarta", sp_code: ""
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
      "User-Agent": "com.community.mbox.tv/50040016 (Linux; U; Android 11; en_ID; B866F; Build/RD2A.211001.002; Cronet/145.0.7582.0)",
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
    "User-Agent": "com.community.mbox.tv/50040016 (Linux; U; Android 11; en_ID; B866F; Build/RD2A.211001.002; Cronet/145.0.7582.0)",
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

async function fetchSubtitles(subjectId, streamId, langLabel) {
  var subs = [];
  try {
    var u1 = API_BASE + "/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId="
      + subjectId + "&streamId=" + encodeURIComponent(streamId);
    var r1 = await mbRequest("GET", u1, null);
    var caps1 = r1.data && r1.data.data && r1.data.data.extCaptions;
    if (Array.isArray(caps1)) caps1.forEach(function (c) {
      if (c.url) subs.push({ url: c.url, language: c.language || c.lanName || c.lan || "en",
        name: (c.lanName || c.language || "Subtitle") + " (" + langLabel + ")",
        headers: { "Referer": API_BASE } });
    });
  } catch (e) { /* ignore */ }
  try {
    var u2 = API_BASE + "/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId="
      + subjectId + "&resourceId=" + encodeURIComponent(streamId) + "&episode=0";
    var r2 = await mbRequest("GET", u2, null);
    var caps2 = r2.data && r2.data.data && r2.data.data.extCaptions;
    if (Array.isArray(caps2)) caps2.forEach(function (c) {
      if (c.url) subs.push({ url: c.url, language: c.lan || c.lanName || c.language || "en",
        name: (c.lanName || c.lan || "Subtitle") + " (" + langLabel + ")",
        headers: { "Referer": API_BASE } });
    });
  } catch (e) { /* ignore */ }
  return subs;
}

async function getStreamLinks(subjectId, season, episode, mediaTitle, mediaType) {
  var detailUrl = API_BASE + "/wefeed-mobile-bff/subject-api/get?subjectId=" + subjectId;
  var detailRes = await mbRequest("GET", detailUrl, null);
  if (!detailRes.data || !detailRes.data.data) return [];
  var subjectData = detailRes.data.data;
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
    "Origin": PLAYER_BASE, "Referer": PLAYER_BASE + "/",
    "User-Agent": PLAYER_UA,
    "x-request-lang": "en", "x-vip-restrict": "0", "x-no-high-risk-restrict": "0"
  };
  // v1.23.3: the file CDN (bcdnt.hakunaymatata.com) answers HTTP 429 to
  // any request carrying Referer: https://moviebox.ph/ — bare requests
  // return 200/206 (verified from the home IP). The play-info API still
  // needs the full playbackHeaders, but the FILE request gets only
  // Origin + UA, or mpv fails to open the stream and the player exits.
  var fileHeaders = { "Origin": PLAYER_BASE, "User-Agent": PLAYER_UA };
  var allStreams = [];
  for (var i = 0; i < subjectIds.length; i++) {
    var item = subjectIds[i];
    try {
      // TV play-info/v2 — returns real direct MP4s in data.resources[]
      // (verified: Inception 720p, Doctor X S1E1 360p-1080p).
      var params = "subjectId=" + item.id + "&se=" + season + "&ep=" + episode + "&streamSignType=1"
        + "&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1";
      var playUrl = TV_BASE + "/wefeed-tv-bff/subject/play-info/v2?" + params;
      var playRes = await mbRequest("GET", playUrl, null, playbackHeaders);
      var playData = playRes.data && playRes.data.data;
      if (!playData) continue;
      // resources[] is the TV shape: {url, resolution, codec, duration, resourceId}
      var resources = Array.isArray(playData.resources) ? playData.resources : [];
      for (var j = 0; j < resources.length; j++) {
        var res = resources[j];
        var rawUrl = res.url || "";
        if (!rawUrl) continue;
        if (rawUrl.indexOf("b164fbfb4347792950bdfbfb563d39d9") >= 0) continue;
        if (/\/other\/\d{4}\/\d{2}\/\d{2}\//.test(rawUrl)) continue;
        var qn = qualityNum(res.resolution || res.quality);
        var quality = qn ? qn + "p" : "Auto";
        var audio = audioLabel({}, item.lang);
        var subs = [];
        if (res.resourceId) subs = await fetchSubtitles(item.id, String(res.resourceId), item.lang);
        allStreams.push({
          name: "MovieBox TV",
          title: mediaTitle + (season > 0 ? " S" + season + "E" + episode : "")
            + " - " + quality + " (" + audio + ") [MP4]",
          url: rawUrl, quality: quality,
          headers: Object.assign({}, fileHeaders),
          subtitles: subs, provider: "movieboxtv"
        });
      }
      // streams[] fallback (same shape as the phone play-info)
      var streamsList = collectStreams(playData);
      for (var k = 0; k < streamsList.length; k++) {
        var stream = streamsList[k];
        var rawUrl2 = stream.url || stream.playUrl || stream.resourceLink || stream.streamUrl || "";
        if (!rawUrl2) continue;
        if (rawUrl2.indexOf("b164fbfb4347792950bdfbfb563d39d9") >= 0) continue;
        if (/\/other\/\d{4}\/\d{2}\/\d{2}\//.test(rawUrl2)) continue;
        var fmt2 = stream.format
          ? (["DASH", "HLS", "MP4", "MKV"].indexOf(String(stream.format).toUpperCase()) >= 0
            ? String(stream.format).toUpperCase() : formatType(rawUrl2))
          : formatType(rawUrl2);
        var qn2 = qualityNum(stream.resolutions || stream.resolution || stream.quality);
        var quality2 = qn2 ? qn2 + "p" : "Auto";
        var streamId2 = stream.id || (item.id + "|" + season + "|" + episode);
        var subs2 = await fetchSubtitles(item.id, streamId2, item.lang);
        allStreams.push({
          name: "MovieBox TV",
          title: mediaTitle + (season > 0 ? " S" + season + "E" + episode : "")
            + " - " + quality2 + " (" + audioLabel(stream, item.lang) + ") [" + fmt2 + "]",
          url: rawUrl2, quality: quality2,
          headers: Object.assign({}, fileHeaders),
          subtitles: subs2, provider: "movieboxtv"
        });
      }
    } catch (e) {
      console.log("[MovieBox TV Stream Fetch Error] ID: " + item.id + " " + e.message);
    }
  }
  var rank = { "2160p": 2160, "4k": 2160, "1440p": 1440, "1080p": 1080,
    "720p": 720, "480p": 480, "360p": 360, "240p": 240, "auto": 1 };
  var seenUrl = {};
  allStreams = allStreams.filter(function (s) {
    if (seenUrl[s.url]) return false;
    seenUrl[s.url] = true; return true;
  });
  allStreams.sort(function (a, b) {
    return (rank[(b.quality || "").toLowerCase()] || 0)
      - (rank[(a.quality || "").toLowerCase()] || 0);
  });
  return allStreams;
}

// ---------------- entry point ----------------
async function getStreams(tmdbId, mediaType, seasonNum, episodeNum, title, year) {
  try {
    var type = mediaType === "tv" || mediaType === "series" ? "tv" : "movie";
    var season = type === "tv" ? (seasonNum || 1) : 0;
    var episode = type === "tv" ? (episodeNum || 1) : 0;
    if (!title) { console.log("[MovieBox TV] no title, skipping"); return []; }
    console.log("[MovieBox TV] searching: " + title + " (" + year + ")");
    var subjects = await searchMovieBox(title);
    var best = findBestMatch(subjects, title, year, type);
    if (!best) {
      console.log("[MovieBox TV] no match for: " + title);
      return [];
    }
    console.log("[MovieBox TV] match: " + best.title + " id=" + best.subjectId);
    return await getStreamLinks(best.subjectId, season, episode, title, type);
  } catch (e) {
    console.log("[MovieBox TV] error: " + (e && e.message ? e.message : e));
    return [];
  }
}

module.exports = { getStreams };
