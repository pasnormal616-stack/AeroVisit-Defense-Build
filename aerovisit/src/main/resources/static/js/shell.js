/*
 * AeroVisit command shell helpers (Admin & Guard).
 * - Live PHT clock in the header
 * - Off-canvas sidebar on narrow screens
 * - Chart.js palette/typography bound to theme tokens, re-applied on theme change
 * Load after chart.js (when present) and before any inline chart code.
 */
(function () {
    function cssVar(name) {
        return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    }

    function hexToRgba(hex, alpha) {
        var h = hex.replace('#', '');
        if (h.length === 3) h = h.split('').map(function (c) { return c + c; }).join('');
        var n = parseInt(h, 16);
        return 'rgba(' + ((n >> 16) & 255) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + alpha + ')';
    }

    // Resolve a token reference such as '--chart-1' or '--chart-1/0.12' to a colour string.
    function resolve(ref) {
        if (typeof ref !== 'string' || ref.indexOf('--') !== 0) return ref;
        var parts = ref.split('/');
        var value = cssVar(parts[0]);
        return parts[1] ? hexToRgba(value, parseFloat(parts[1])) : value;
    }

    function applyChartDefaults() {
        if (!window.Chart) return;
        // Same family as the rest of the admin UI: the --font-ops token in theme.css (Jost), so fonts are defined in one place
        Chart.defaults.font.family = cssVar('--font-ops') || "'Jost', system-ui, sans-serif";
        Chart.defaults.color = cssVar('--text-muted');
        Chart.defaults.borderColor = cssVar('--chart-grid');
    }

    // Datasets may carry `avColors: { borderColor: '--chart-1', backgroundColor: ['--chart-1', '--chart-grid'] }`
    // so their colours follow the active theme.
    function themeChart(chart) {
        var opts = chart.options || {};
        var muted = cssVar('--text-muted');
        var grid = cssVar('--chart-grid');
        if (opts.scales) {
            Object.keys(opts.scales).forEach(function (key) {
                var s = opts.scales[key];
                if (!s) return;
                s.ticks = s.ticks || {};
                s.ticks.color = muted;
                if (s.grid && s.grid.display !== false) s.grid.color = grid;
            });
        }
        if (opts.plugins && opts.plugins.legend && opts.plugins.legend.labels) {
            opts.plugins.legend.labels.color = muted;
        }
        (chart.data.datasets || []).forEach(function (ds) {
            if (!ds.avColors) return;
            Object.keys(ds.avColors).forEach(function (prop) {
                var ref = ds.avColors[prop];
                ds[prop] = Array.isArray(ref) ? ref.map(resolve) : resolve(ref);
            });
        });
        chart.update('none');
    }

    function themeAllCharts() {
        if (!window.Chart) return;
        applyChartDefaults();
        var instances = Chart.instances || {};
        Object.keys(instances).forEach(function (id) { themeChart(instances[id]); });
    }

    applyChartDefaults();

    window.AeroShell = { cssVar: cssVar, color: resolve, themeChart: themeChart, themeAllCharts: themeAllCharts };

    document.addEventListener('aerovisit:themechange', themeAllCharts);

    document.addEventListener('DOMContentLoaded', function () {
        // Live clock (Philippine Standard Time)
        var clock = document.getElementById('avClock');
        if (clock && window.Intl) {
            var fmt = new Intl.DateTimeFormat('en-CA', {
                timeZone: 'Asia/Manila', year: 'numeric', month: '2-digit', day: '2-digit',
                hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
            });
            var tick = function () { clock.textContent = fmt.format(new Date()).replace(',', '') + ' PHT'; };
            tick();
            setInterval(tick, 1000);
        }

        // Off-canvas sidebar (< 992px)
        var sidebar = document.querySelector('.av-sidebar');
        var toggles = document.querySelectorAll('[data-sidebar-toggle]');
        toggles.forEach(function (btn) {
            btn.addEventListener('click', function () {
                var open = sidebar.classList.toggle('is-open');
                btn.setAttribute('aria-expanded', open ? 'true' : 'false');
            });
        });
        // 📱 Tapping the dimmed page beside the open menu, or pressing Escape, closes it
        var closeSidebar = function () {
            if (!sidebar || !sidebar.classList.contains('is-open')) return;
            sidebar.classList.remove('is-open');
            toggles.forEach(function (btn) { btn.setAttribute('aria-expanded', 'false'); });
        };
        document.addEventListener('click', function (e) {
            if (sidebar && !sidebar.contains(e.target) && !e.target.closest('[data-sidebar-toggle]')) closeSidebar();
        });
        document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeSidebar(); });

        // Charts created by inline page scripts run before this; theme them once they exist.
        themeAllCharts();
    });
})();
