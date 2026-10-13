/*
 * AeroVisit theme controller — shared by Admin, Guard and Visitor portals.
 * Loaded synchronously in <head> so the correct palette is applied before first paint.
 * Persists the choice in localStorage and keeps every open tab of the same portal in sync.
 */
(function () {
    // One key per portal, so a visitor's toggle never changes the staff dashboards in the same browser (or back).
    // Staff pages (fragments/shell) all live under /admin, /guard and /login; everything else is the visitor portal.
    var STAFF = /^\/(admin|guard|login)(\/|$)/.test(location.pathname);
    var KEY = STAFF ? 'staff_theme' : 'visitor_theme';
    var LEGACY_KEY = 'aerovisit-theme'; // the old shared key: read once as a fallback so nobody loses their choice
    var root = document.documentElement;

    function read() {
        try { return localStorage.getItem(KEY) || localStorage.getItem(LEGACY_KEY); } catch (e) { return null; }
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
