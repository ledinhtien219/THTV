(function () {
    function player() {
        if (!/(^|\.)youtube\.com$/i.test(location.hostname) || location.pathname !== '/watch') return null;
        return document.getElementById('movie_player');
    }
    window.__thtvVideoQuality = {
        options: function () {
            var p = player();
            if (!p || typeof p.getAvailableQualityLevels !== 'function' || typeof p.setPlaybackQualityRange !== 'function') return null;
            var levels = p.getAvailableQualityLevels();
            if (!Array.isArray(levels) || !levels.length) return null;
            return {page: location.href, levels: levels, current: typeof p.getPlaybackQuality === 'function' ? p.getPlaybackQuality() : ''};
        },
        select: function (level, page) {
            var p = player();
            if (location.href !== page || !p || typeof p.setPlaybackQualityRange !== 'function' ||
                typeof p.getAvailableQualityLevels !== 'function' || p.getAvailableQualityLevels().indexOf(level) === -1) return false;
            p.setPlaybackQualityRange(level, level);
            return true;
        }
    };
})();
