(function () {
    if (window.__thtvPageInput) return;

    var target = null, sequence = 0, documentId = Date.now() + '-' + Math.random();

    function editable(el) {
        return el && el.isConnected && !el.disabled && !el.readOnly &&
            (el.isContentEditable || el.tagName === 'TEXTAREA' ||
                (el.tagName === 'INPUT' && /^(text|search|email|url|tel|number)$/.test(el.type)));
    }

    function attr(el, name) {
        try { return (el.getAttribute(name) || '').toLowerCase(); } catch (_) { return ''; }
    }

    function isSearchLike(el) {
        if (!el) return false;
        var type = (el.type || '').toLowerCase();
        var name = attr(el, 'name');
        var role = attr(el, 'role');
        var aria = attr(el, 'aria-label');
        var placeholder = attr(el, 'placeholder');
        var inputMode = attr(el, 'inputmode');
        var form = null;
        try { form = el.form || (el.closest ? el.closest('form') : null); } catch (_) {}

        if (type === 'search' || role === 'searchbox') return true;
        if (/^(q|query|search|keyword|keywords|search_query)$/.test(name)) return true;
        if (inputMode === 'search') return true;
        if (/(search|tìm|google|youtube|kênh)/i.test(aria + ' ' + placeholder)) return true;

        try {
            if (form) {
                var formRole = attr(form, 'role');
                var formAction = attr(form, 'action');
                if (formRole === 'search' || /search|query/i.test(formAction)) return true;
            }
        } catch (_) {}

        return false;
    }

    function readValue(el) {
        return el.isContentEditable ? (el.textContent || '') : (el.value || '');
    }

    function writeValue(el, value) {
        if (el.isContentEditable) {
            el.textContent = value;
        } else {
            var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
            var descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
            if (descriptor && descriptor.set) descriptor.set.call(el, value);
            else el.value = value;
        }
        el.dispatchEvent(new Event('input', {bubbles: true}));
        el.dispatchEvent(new Event('change', {bubbles: true}));
    }

    function sameTarget(token) {
        return target &&
            target.token === token &&
            target.page === location.href &&
            editable(target.el);
    }

    window.__thtvPageInput = {
        capture: function (el) {
            if (!editable(el)) return null;

            if (!target || target.el !== el || target.page !== location.href) {
                target = {
                    el: el,
                    page: location.href,
                    token: documentId + '-' + (++sequence),
                    searchLike: isSearchLike(el)
                };
            }

            return {
                token: target.token,
                value: readValue(el),
                hint: el.getAttribute('placeholder') || el.getAttribute('aria-label') || 'Nhập nội dung...',
                action: target.searchLike ? 'search' : 'input'
            };
        },

        clear: function () {
            target = null;
        },

        install: function () {
            if (window.__thtvSharedKeyboardInstalled) return;
            window.__thtvSharedKeyboardInstalled = true;

            document.addEventListener('click', function (event) {
                var path = event.composedPath ? event.composedPath() : [event.target];
                var node = path[0];
                var el = node && node.closest ? node.closest('input,textarea,[contenteditable="true"]') : null;
                var input = window.__thtvPageInput.capture(el);

                if (input && window.CarHudInput && window.CarHudInput.openKeyboard) {
                    event.preventDefault();
                    event.stopImmediatePropagation();
                    window.CarHudInput.openKeyboard(input.token, input.value, input.hint, input.action);
                }
            }, true);
        },

        fill: function (token, value) {
            if (!sameTarget(token)) return 'NO_TARGET';
            var el = target.el;
            try {
                writeValue(el, value);
                try { el.focus({preventScroll: true}); } catch (_) { try { el.focus(); } catch (_) {} }
                target = null;
                return 'OK';
            } catch (_) {
                return 'ERROR';
            }
        },

        submit: function (token, value) {
            if (!sameTarget(token)) return 'NO_TARGET';

            var el = target.el;
            var searchLike = target.searchLike;
            try {
                writeValue(el, value);
                try { el.focus({preventScroll: true}); } catch (_) { try { el.focus(); } catch (_) {} }

                if (!searchLike) {
                    target = null;
                    return 'OK';
                }

                var form = null;
                try { form = el.form || (el.closest ? el.closest('form') : null); } catch (_) {}

                // Prefer native form submission because it works with Google and
                // other search pages without routing through THTV/YouTube logic.
                if (form && typeof form.requestSubmit === 'function') {
                    target = null;
                    form.requestSubmit();
                    return 'SUBMITTED';
                }

                if (form && typeof form.submit === 'function') {
                    target = null;
                    form.submit();
                    return 'SUBMITTED';
                }

                // Fallback for search widgets without a real <form>.
                try {
                    if (typeof KeyboardEvent !== 'undefined') {
                        el.dispatchEvent(new KeyboardEvent('keydown', {
                            key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true
                        }));
                        el.dispatchEvent(new KeyboardEvent('keyup', {
                            key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true
                        }));
                    }
                } catch (_) {}

                target = null;
                return 'SUBMITTED';
            } catch (_) {
                return 'ERROR';
            }
        }
    };
})();
