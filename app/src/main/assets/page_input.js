(function () {
    if (window.__thtvPageInput) return;
    var target = null, sequence = 0, documentId = Date.now() + '-' + Math.random();
    function editable(el) {
        return el && el.isConnected && !el.disabled && !el.readOnly &&
            (el.isContentEditable || el.tagName === 'TEXTAREA' ||
                (el.tagName === 'INPUT' && /^(text|search|email|url|tel|number)$/.test(el.type)));
    }
    window.__thtvPageInput = {
        capture: function (el) {
            if (!editable(el)) return null;
            if (!target || target.el !== el || target.page !== location.href) {
                target = {el: el, page: location.href, token: documentId + '-' + (++sequence)};
            }
            return {token: target.token, value: el.isContentEditable ? el.textContent || '' : el.value || ''};
        },
        clear: function () { target = null; },
        fill: function (token, value) {
            if (!target || target.token !== token || target.page !== location.href || !editable(target.el)) return 'NO_TARGET';
            var el = target.el;
            try {
                if (el.isContentEditable) el.textContent = value;
                else {
                    var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                    var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                    setter.call(el, value);
                }
                // Match text entry only. The page's own Search/Go button submits it.
                target = null;
                el.dispatchEvent(new Event('input', {bubbles: true}));
                return 'OK';
            } catch (_) { return 'ERROR'; }
        }
    };
})();
