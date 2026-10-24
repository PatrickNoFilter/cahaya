// Minimal Cheerio-compatible shim for providers running inside Cahaya's
// WebView JS runtime. Written from scratch for Cahaya; covers only the
// subset of the Cheerio API that the first supported provider (4khdhub)
// actually uses. Not a copy of Cheerio.
// Supported: cheerio.load(html) -> $
//   $(selector), $(selector, contextElement), $(element), $(wrapper)
//   .each(fn), .find(sel), .filter(fn), .attr(name), .text(), .html(),
//   .first(), .length, [index]
//   :contains("text") pseudo-selector inside find / $.
window.__cheerio = (function () {
  function makeWrapper(els) {
    var w = els.slice();
    w.each = function (fn) {
      for (var i = 0; i < w.length; i++) {
        if (fn.call(w[i], i, w[i]) === false) break;
      }
      return w;
    };
    w.find = function (sel) {
      var out = [];
      for (var i = 0; i < w.length; i++) {
        var found = query(w[i], sel);
        for (var j = 0; j < found.length; j++) out.push(found[j]);
      }
      return makeWrapper(out);
    };
    w.filter = function (fn) {
      var out = [];
      for (var i = 0; i < w.length; i++) {
        if (fn.call(w[i], i, w[i])) out.push(w[i]);
      }
      return makeWrapper(out);
    };
    w.attr = function (name) {
      if (!w.length) return undefined;
      return w[0].getAttribute(name);
    };
    w.text = function () {
      var t = "";
      for (var i = 0; i < w.length; i++) t += w[i].textContent;
      return t;
    };
    w.html = function () {
      return w.length ? w[0].innerHTML : null;
    };
    w.first = function () {
      return makeWrapper(w.length ? [w[0]] : []);
    };
    // Cheerio .map(fn(index, el)): fn's return values (null/undefined
    // dropped) become a new wrapper; .get() unwraps it to a plain
    // array. Without these, a native Array.map() result has no .get()
    // and the provider dies with ".get is not a function" — the exact
    // failure seen on the box 2026-10-24 for "Love of Your Life".
    w.map = function (fn) {
      var out = [];
      for (var i = 0; i < w.length; i++) {
        var v = fn.call(w[i], i, w[i]);
        if (v !== null && v !== undefined) out.push(v);
      }
      return makeWrapper(out);
    };
    w.get = function (i) {
      if (i === undefined) return w.slice();
      return w[i];
    };
    return w;
  }

  function query(root, sel) {
    // :contains("...") is not a real CSS selector; handle it by hand.
    var m = sel.match(/^(.*):contains\((?:"([^"]*)"|'([^']*)'|([^)]*))\)\s*$/);
    var scope = root;
    if (scope && scope.nodeType === 9) scope = scope.documentElement;
    if (m) {
      var base = (m[1] || "*").trim() || "*";
      var needle = m[2] !== undefined ? m[2] : (m[3] !== undefined ? m[3] : m[4]);
      var els = scope.querySelectorAll(base);
      var out = [];
      for (var i = 0; i < els.length; i++) {
        if (els[i].textContent.indexOf(needle) >= 0) out.push(els[i]);
      }
      return out;
    }
    var list = scope.querySelectorAll(sel);
    var res = [];
    for (var k = 0; k < list.length; k++) res.push(list[k]);
    return res;
  }

  function load(html) {
    var doc = new DOMParser().parseFromString(html, "text/html");
    function $(sel, ctx) {
      if (typeof sel === "string") {
        var root = doc;
        if (ctx) {
          if (ctx.nodeType) root = ctx;
          else if (ctx.length !== undefined && ctx.length > 0 && ctx[0] && ctx[0].nodeType) root = ctx[0];
        }
        return makeWrapper(query(root, sel));
      }
      if (sel && sel.nodeType) return makeWrapper([sel]);
      if (sel && sel.length !== undefined && typeof sel !== "string") {
        var arr = [];
        for (var i = 0; i < sel.length; i++) arr.push(sel[i]);
        return makeWrapper(arr);
      }
      return makeWrapper([]);
    }
    return $;
  }

  return { load: load };
})();
