/*
 * AeroVisit theme controller — shared by Admin, Guard and Visitor portals.
 * Loaded synchronously in <head> so the correct palette is applied before first paint.
 * Persists the operator's choice in localStorage and keeps every open tab in sync.
 */
(function () {
    var KEY = 'aerovisit-theme';
    var root = document.documentElement;

    function read() {
        try { return localStorage.getItem(KEY); } catch (e) { return null; }
    }
    function write(value) {
        try { localStorage.setItem(KEY, value); } catch (e) { /* private mode: session-only */ }
    }
    function systemTheme() {
        return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    }
    function current() {
        return root.getAttribute('data-theme') || 'light';
    }
    function syncToggles(theme) {
        var buttons = document.querySelectorAll('[data-theme-toggle]');
        for (var i = 0; i < buttons.length; i++) {
            buttons[i].setAttribute('aria-pressed', theme === 'dark' ? 'true' : 'false');
            buttons[i].setAttribute('aria-label', theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode');
            buttons[i].setAttribute('title', theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode');
        }
    }
    function apply(theme, notify) {
        root.setAttribute('data-theme', theme);
        root.setAttribute('data-bs-theme', theme);   // Bootstrap 5.3 component theming
        syncToggles(theme);
        if (notify) {
            document.dispatchEvent(new CustomEvent('aerovisit:themechange', { detail: { theme: theme } }));
        }
    }

    apply(read() || systemTheme(), false);
    root.classList.remove('no-js');

    window.AeroTheme = {
        get: current,
        set: function (theme) { write(theme); apply(theme, true); },
        toggle: function () { this.set(current() === 'dark' ? 'light' : 'dark'); }
    };

    document.addEventListener('DOMContentLoaded', function () {
        syncToggles(current());
        document.addEventListener('click', function (e) {
            var btn = e.target.closest ? e.target.closest('[data-theme-toggle]') : null;
            if (btn) { e.preventDefault(); window.AeroTheme.toggle(); }
        });
    });

    // Follow the OS only while the user has not made an explicit choice.
    if (window.matchMedia) {
        var mq = window.matchMedia('(prefers-color-scheme: dark)');
        var onChange = function () { if (!read()) apply(systemTheme(), true); };
        if (mq.addEventListener) mq.addEventListener('change', onChange); else if (mq.addListener) mq.addListener(onChange);
    }

    // Keep multiple open AeroVisit tabs (e.g. Guard scanner + dashboard) in sync.
    window.addEventListener('storage', function (e) {
        if (e.key === KEY && e.newValue && e.newValue !== current()) apply(e.newValue, true);
    });
})();
