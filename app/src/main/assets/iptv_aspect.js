(function (window, document) {
  'use strict';
  const modes = ['fill', 'contain', 'cover', '4:3', '21:9'];
  const key = 'iptv_fit_mode';
  const valid = mode => modes.includes(mode);
  function read() {
    try {
      const native = window.ThtvIptvAspect && window.ThtvIptvAspect.get();
      if (valid(native)) return native;
    } catch (_) {}
    try {
      const saved = window.localStorage.getItem(key);
      if (valid(saved)) return saved;
    } catch (_) {}
    return 'fill';
  }
  let current = read();
  function apply(mode, persist = true) {
    if (!valid(mode)) return false;
    current = mode;
    if (persist) {
      try { window.localStorage.setItem(key, mode); } catch (_) {}
      try { if (window.ThtvIptvAspect) window.ThtvIptvAspect.save(mode); } catch (_) {}
    }
    const video = document.getElementById('videoPlayer');
    if (video) {
      // A previous native fullscreen/style override must not out-rank IPTV's fit class.
      ['width', 'height', 'max-width', 'max-height', 'object-fit', 'aspect-ratio'].forEach(name => video.style.removeProperty(name));
      video.classList.remove('fit-contain', 'fit-cover', 'fit-fill', 'fit-4-3', 'fit-21-9');
      video.classList.add('fit-' + mode.replace(':', '-'));
    }
    return true;
  }
  function restore() { apply(read(), false); return current; }
  const video = document.getElementById('videoPlayer');
  if (video) ['loadedmetadata', 'emptied', 'resize', 'playing'].forEach(event => video.addEventListener(event, restore));
  window.addEventListener('pageshow', restore);
  window.ThtvIptvFit = { read, apply, restore, get mode() { return current; } };
})(window, document);
