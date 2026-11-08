// Home page behaviour: Tutorial video, Copy buttons, the interactive lineage illustration,
// "Your system" download hints and the benchmark chart tooltip. Plain JS, no libraries.
(function () {
  'use strict';

  /* Tutorial video. Browsers only autoplay muted video, so it starts muted with a sound button,
     and it plays only while on screen. Until the video has a frame (or if the file is missing),
     the stage shows a branded fallback instead of a broken player. */
  function initTutorial() {
    var video = document.getElementById('tutorial-video');
    var sound = document.getElementById('tutorial-sound');
    if (!video || !sound) return;
    var figure = video.closest('.tutorial');

    function ready() { figure.classList.add('is-ready'); }
    function unavailable() {
      figure.classList.remove('is-ready');
      video.removeAttribute('controls');
      video.pause();
    }
    function play() { var p = video.play(); if (p && p.catch) p.catch(function () {}); }
    function syncSound() {
      var on = !video.muted;
      sound.setAttribute('aria-pressed', on ? 'true' : 'false');
      sound.querySelector('span').textContent = on ? 'Mute' : 'Turn on sound';
    }

    video.addEventListener('loadeddata', ready);
    video.addEventListener('error', unavailable);
    if (video.readyState >= 2) ready();
    if (video.error || video.networkState === 3 /* NETWORK_NO_SOURCE */) unavailable();

    sound.addEventListener('click', function () {
      video.muted = !video.muted;
      if (!video.muted) play();
      syncSound();
    });
    video.addEventListener('volumechange', syncSound);

    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (entries) {
        entries.forEach(function (e) {
          if (video.error) return;
          if (e.isIntersecting) play(); else video.pause();
        });
      }, { threshold: 0.35 }).observe(video);
    } else {
      play();
    }
  }

  /* Copy buttons: copy the block's text and confirm with "Copied" (visible and announced). */
  function initCopy() {
    var status = document.getElementById('copy-status');
    function fallbackCopy(text) {
      var ta = document.createElement('textarea');
      ta.value = text;
      ta.setAttribute('readonly', '');
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      var ok = false;
      try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
      document.body.removeChild(ta);
      return ok ? Promise.resolve() : Promise.reject(new Error('copy failed'));
    }
    function copyText(text) {
      if (navigator.clipboard && window.isSecureContext) {
        return navigator.clipboard.writeText(text).catch(function () { return fallbackCopy(text); });
      }
      return fallbackCopy(text);
    }
    Array.prototype.forEach.call(document.querySelectorAll('.code .copy'), function (button) {
      var label = button.querySelector('span');
      var timer = null;
      button.addEventListener('click', function () {
        var code = button.closest('.code').querySelector('pre');
        var text = code.textContent.replace(/\s+$/, '') + '\n';
        copyText(text).then(function () {
          button.classList.add('is-copied');
          label.textContent = 'Copied';
          if (status) status.textContent = 'Copied to the clipboard';
        }, function () {
          label.textContent = 'Press Ctrl+C';
          if (status) status.textContent = 'Copy failed. Select the text and press Ctrl+C or Cmd+C.';
        }).then(function () {
          clearTimeout(timer);
          timer = setTimeout(function () {
            button.classList.remove('is-copied');
            label.textContent = 'Copy';
            if (status) status.textContent = '';
          }, 2000);
        });
      });
    });
  }

  /* Interactive column-lineage illustration (Examples). */
  function initLineage() {
    var NS = 'http://www.w3.org/2000/svg';
    var COLX = [4, 253, 502], W = 200, HEAD = 28, ROW = 24;
    var svg = document.getElementById('graph');
    if (!svg) return;

    var nodes = {
      orders:    { name: 'sales.orders',        role: 'source',     col: 0, y: 12,  cols: ['order_id', 'customer_id', 'amount', 'status'] },
      customers: { name: 'crm.customers',       role: 'source',     col: 0, y: 206, cols: ['customer_id', 'tier'] },
      paid:      { name: 'paid_orders',         role: 'temp table', col: 1, y: 36,  cols: ['order_id', 'customer_id', 'amount'] },
      target:    { name: 'dw.customer_revenue', role: 'target',     col: 2, y: 128, cols: ['customer_id', 'tier', 'revenue'] }
    };
    // from/to: [node, column] or [node] for the table header.
    var edges = {
      e1: { from: ['orders', 'order_id'],       to: ['paid', 'order_id'] },
      e2: { from: ['orders', 'customer_id'],    to: ['paid', 'customer_id'] },
      e3: { from: ['orders', 'amount'],         to: ['paid', 'amount'] },
      e4: { from: ['orders', 'status'],         to: ['paid'],              indirect: true, label: 'WHERE' },
      e5: { from: ['paid', 'amount'],           to: ['target', 'revenue'], label: 'SUM' },
      e6: { from: ['customers', 'customer_id'], to: ['target', 'customer_id'] },
      e7: { from: ['customers', 'tier'],        to: ['target', 'tier'] },
      e8: { from: ['paid', 'customer_id'],      to: ['target'],            indirect: true, label: 'JOIN' }
    };
    var traces = {
      revenue: {
        edges: ['e3', 'e5'], indirect: ['e4', 'e8'],
        rows: ['orders.amount', 'paid.amount', 'target.revenue'], indirectRows: ['orders.status', 'paid.customer_id'],
        marks: ['s-oamt', 's-sum', 't-rev'], indirectMarks: ['s-filter', 's-join'],
        caption: '<code>revenue</code> is <code>SUM(amount)</code> over <code>paid_orders</code>, a temporary table that copies <code>sales.orders.amount</code> for rows where <code>status = \'PAID\'</code>. The join on <code>customer_id</code> decides which customer each sum belongs to.'
      },
      tier: {
        edges: ['e7'], indirect: ['e8'],
        rows: ['customers.tier', 'target.tier'], indirectRows: ['paid.customer_id'],
        marks: ['s-ctier', 't-tier'], indirectMarks: ['s-join'],
        caption: '<code>tier</code> is copied unchanged from <code>crm.customers.tier</code>. Because of the inner join, only customers with at least one paid order get a row.'
      },
      customer_id: {
        edges: ['e6'], indirect: ['e2', 'e8'],
        rows: ['customers.customer_id', 'target.customer_id'], indirectRows: ['orders.customer_id', 'paid.customer_id'],
        marks: ['s-ccid', 't-cid'], indirectMarks: ['s-ocid', 's-join'],
        caption: '<code>customer_id</code> is taken from <code>crm.customers</code>. It is matched against <code>paid_orders.customer_id</code>, which comes from <code>sales.orders</code> in the first statement.'
      }
    };

    function el(tag, attrs, parent) {
      var e = document.createElementNS(NS, tag);
      for (var k in attrs) e.setAttribute(k, attrs[k]);
      if (parent) parent.appendChild(e);
      return e;
    }
    function anchor(ref, side) {
      var n = nodes[ref[0]];
      var x = COLX[n.col] + (side === 'out' ? W : 0);
      var y = ref.length > 1 ? n.y + HEAD + ROW * n.cols.indexOf(ref[1]) + ROW / 2 : n.y + HEAD / 2;
      return [x, y];
    }

    var edgeLayer = el('g', {}, svg);
    var nodeLayer = el('g', {}, svg);
    var labelLayer = el('g', {}, svg);
    var edgeEls = {}, labelEls = {}, rowEls = {};

    Object.keys(edges).forEach(function (id) {
      var e = edges[id], a = anchor(e.from, 'out'), b = anchor(e.to, 'in'), dx = (b[0] - a[0]) / 2;
      edgeEls[id] = el('path', {
        class: 'edge' + (e.indirect ? ' indirect' : ''),
        d: 'M' + a[0] + ' ' + a[1] + ' C' + (a[0] + dx) + ' ' + a[1] + ' ' + (b[0] - dx) + ' ' + b[1] + ' ' + b[0] + ' ' + b[1]
      }, edgeLayer);
      if (e.label) {
        var mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2, w = e.label.length * 7 + 10;
        var g = el('g', {}, labelLayer);
        var bg = el('rect', { class: 'edge-label-bg', x: mx - w / 2, y: my - 9, width: w, height: 18, rx: 3 }, g);
        var t = el('text', { class: 'edge-label', x: mx, y: my + 4, 'text-anchor': 'middle' }, g);
        t.textContent = e.label;
        labelEls[id] = [bg, t];
      }
    });

    Object.keys(nodes).forEach(function (id) {
      var n = nodes[id], x = COLX[n.col], h = HEAD + ROW * n.cols.length;
      var g = el('g', {}, nodeLayer);
      el('rect', { class: 'node-box', x: x + 0.5, y: n.y + 0.5, width: W - 1, height: h - 1, rx: 6 }, g);
      el('path', { class: 'node-head', d: 'M' + (x + 1) + ' ' + (n.y + HEAD) + ' V' + (n.y + 6) + ' Q' + (x + 1) + ' ' + (n.y + 1) + ' ' + (x + 6) + ' ' + (n.y + 1) + ' H' + (x + W - 6) + ' Q' + (x + W - 1) + ' ' + (n.y + 1) + ' ' + (x + W - 1) + ' ' + (n.y + 6) + ' V' + (n.y + HEAD) + ' Z' }, g);
      var title = el('text', { class: 'title', x: x + 10, y: n.y + 18 }, g);
      title.textContent = n.name;
      var role = el('text', { class: 'role', x: x + W - 10, y: n.y + 18, 'text-anchor': 'end' }, g);
      role.textContent = n.role;
      n.cols.forEach(function (c, i) {
        var key = id + '.' + c;
        var rg = el('g', { class: 'row' }, g);
        el('rect', { class: 'row-bg', x: x + 1, y: n.y + HEAD + ROW * i, width: W - 2, height: ROW }, rg);
        var t = el('text', { x: x + 12, y: n.y + HEAD + ROW * i + 16 }, rg);
        t.textContent = c;
        if (id === 'target') {
          rg.setAttribute('class', 'row pick');
          rg.setAttribute('tabindex', '0');
          rg.setAttribute('role', 'button');
          rg.setAttribute('aria-label', 'Trace ' + c);
          rg.addEventListener('click', function () { select(c); });
          rg.addEventListener('keydown', function (ev) {
            if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); select(c); }
          });
        }
        rowEls[key] = rg;
      });
    });

    var chips = document.querySelectorAll('.chip[data-trace]');
    var marks = document.querySelectorAll('.sql mark[data-m]');
    var caption = document.getElementById('trace-caption');

    function toggle(node, cls, on) {
      var c = (node.getAttribute('class') || '').split(' ').filter(function (s) { return s && s !== cls; });
      if (on) c.push(cls);
      node.setAttribute('class', c.join(' '));
    }
    function has(list, v) { return list.indexOf(v) !== -1; }

    function select(name) {
      var t = traces[name];
      if (!t) return;
      Object.keys(edgeEls).forEach(function (id) {
        var lit = has(t.edges, id) || has(t.indirect, id);
        toggle(edgeEls[id], 'lit', lit);
        if (labelEls[id]) labelEls[id].forEach(function (n) { toggle(n, 'lit', lit); });
      });
      Object.keys(rowEls).forEach(function (key) {
        toggle(rowEls[key], 'lit', has(t.rows, key));
        toggle(rowEls[key], 'lit-indirect', has(t.indirectRows, key));
      });
      Array.prototype.forEach.call(marks, function (m) {
        var id = m.getAttribute('data-m');
        m.classList.toggle('lit', has(t.marks, id));
        m.classList.toggle('lit-indirect', has(t.indirectMarks, id));
      });
      Array.prototype.forEach.call(chips, function (c) {
        c.setAttribute('aria-pressed', c.getAttribute('data-trace') === name ? 'true' : 'false');
      });
      caption.innerHTML = t.caption;
    }

    Array.prototype.forEach.call(chips, function (c) {
      c.addEventListener('click', function () { select(c.getAttribute('data-trace')); });
    });
    select('revenue');
  }

  /* Mark the download files that match the visitor's system. */
  function initPlatform() {
    var ua = navigator.userAgent || '';
    var platform = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || '';
    var prefix = null;
    if (/Win/i.test(platform) || /Windows/i.test(ua)) prefix = 'windows';
    else if (/Mac/i.test(platform) || /Macintosh/i.test(ua)) prefix = 'mac';
    else if (/Linux/i.test(platform) && !/Android/i.test(ua)) prefix = 'linux';
    if (!prefix) return;
    Array.prototype.forEach.call(document.querySelectorAll('.files li[data-platform="' + prefix + '"]'), function (li) {
      li.classList.add('recommended');
    });
  }

  /* Benchmark chart: one tooltip per benchmark row, on hover and keyboard focus. */
  function initChart() {
    var tip = document.getElementById('chart-tip');
    if (!tip) return;
    var wrap = tip.parentNode;
    function show(row) {
      tip.innerHTML = '';
      var name = document.createElement('strong');
      name.textContent = row.getAttribute('data-name');
      tip.appendChild(name);
      [['before', 'Before', 'var(--chart-before)'], ['after', 'After', 'var(--chart-after)']].forEach(function (s) {
        var line = document.createElement('div');
        var key = document.createElement('span');
        key.className = 'k';
        key.style.background = s[2];
        line.appendChild(key);
        line.appendChild(document.createTextNode(s[1] + ': ' + row.getAttribute('data-' + s[0])));
        tip.appendChild(line);
      });
      tip.hidden = false;
      var r = row.getBoundingClientRect(), w = wrap.getBoundingClientRect();
      var left = Math.min(Math.max(0, r.left - w.left + r.width * 0.45), w.width - tip.offsetWidth);
      tip.style.left = Math.max(0, left) + 'px';
      tip.style.top = (r.top - w.top + 22) + 'px';
    }
    function hide() { tip.hidden = true; }
    Array.prototype.forEach.call(document.querySelectorAll('.bench'), function (row) {
      row.addEventListener('mouseenter', function () { show(row); });
      row.addEventListener('mouseleave', hide);
      row.addEventListener('focus', function () { show(row); });
      row.addEventListener('blur', hide);
    });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') hide(); });
  }

  function init() {
    initTutorial();
    initCopy();
    initLineage();
    initPlatform();
    initChart();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
