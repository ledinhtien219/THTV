(function () {
  window.__thtvSystemVoiceResult = function (expectedQuery) {
    var page = new URL(window.location.href);
    if (!/(^|\.)youtube\.com$/.test(page.hostname) || page.pathname !== '/results' ||
        page.searchParams.get('search_query') !== expectedQuery) return 'WAIT';
    var links = document.querySelectorAll(
      'ytm-video-with-context-renderer a[href], ytm-compact-video-renderer a[href], ' +
      'ytd-video-renderer a[href], ytm-rich-item-renderer a[href], a.media-item-thumbnail-container[href]'
    );
    for (var i = 0; i < links.length; i++) {
      var link = links[i];
      if (link.closest('ytd-ad-slot-renderer, ytm-ad-slot-renderer, ytd-promoted-video-renderer, ytm-promoted-video-renderer, ytd-display-ad-renderer, ytm-display-ad-renderer')) continue;
      try {
        var target = new URL(link.href, page.href);
        if (target.protocol !== 'https:' || !/(^|\.)youtube\.com$/.test(target.hostname) || target.pathname !== '/watch' || !target.searchParams.get('v')) continue;
        return target.href;
      } catch (_) {}
    }
    return 'WAIT';
  };
})();
