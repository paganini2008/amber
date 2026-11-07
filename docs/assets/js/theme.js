// Light/dark theme: follows the visitor's system setting until they pick one with the toggle.
// Loaded in <head> (not deferred) so the stored choice applies before the first paint.
(function () {
  var KEY = 'amber-theme';
  var root = document.documentElement;
  var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

  function stored() {
    try { return localStorage.getItem(KEY); } catch (e) { return null; }
  }
  function store(value) {
    try { localStorage.setItem(KEY, value); } catch (e) { /* private mode: keep it for this page only */ }
  }
  function effective() {
    var explicit = root.getAttribute('data-theme');
    if (explicit === 'light' || explicit === 'dark') return explicit;
    return media && media.matches ? 'dark' : 'light';
  }
  function sync() {
    var dark = effective() === 'dark';
    root.classList.toggle('theme-dark', dark);
    var button = document.querySelector('.theme-toggle');
    if (button) {
      button.setAttribute('aria-label', dark ? 'Switch to light theme' : 'Switch to dark theme');
      button.setAttribute('title', dark ? 'Switch to light theme' : 'Switch to dark theme');
    }
  }

  var choice = stored();
  if (choice === 'light' || choice === 'dark') root.setAttribute('data-theme', choice);
  sync();

  if (media) {
    var onChange = function () { sync(); };
    if (media.addEventListener) media.addEventListener('change', onChange);
    else if (media.addListener) media.addListener(onChange);
  }

  document.addEventListener('DOMContentLoaded', function () {
    sync();
    var button = document.querySelector('.theme-toggle');
    if (!button) return;
    button.addEventListener('click', function () {
      var next = effective() === 'dark' ? 'light' : 'dark';
      root.setAttribute('data-theme', next);
      store(next);
      sync();
    });
  });
})();
