/*
 * AeroVisit Visitor Portal — presentation layer.
 * - ~200ms entrance fades (IntersectionObserver, no scroll listeners)
 * - Floating nav state + nav links that open in-page tabs
 * - Three-step booking flow layered over the ORIGINAL booking logic:
 *   it only observes the existing #registrationFormWrapper "locked-form" class and
 *   the #selectedDateBadge text (both driven by register.html's calendar/master
 *   booking code), so eventClick / toggleMasterBooking / capacity checks are untouched.
 */
(function () {
    'use strict';

    var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    /* ---------- Pre-loader: ~1.5s minimum, lifted on window load (hard cap 2.6s) ---------- */
    function initPreloader(done) {
        var pl = document.getElementById('vpPreloader');
        if (!pl) { done(); return; }
        document.documentElement.classList.add('vp-is-loading');
        var minMs = reduceMotion ? 300 : 1500;
        var elapsed = window.performance && performance.now ? performance.now() : 0;
        var finished = false;
        function finish() {
            if (finished) return;
            finished = true;
            pl.classList.add('is-done');
            pl.setAttribute('aria-busy', 'false');
            document.documentElement.classList.remove('vp-is-loading');
            setTimeout(done, reduceMotion ? 0 : 250);
            setTimeout(function () { if (pl.parentNode) pl.parentNode.removeChild(pl); }, 1200);
        }
        function afterMin() { setTimeout(finish, Math.max(0, minMs - elapsed)); }
        if (document.readyState === 'complete') afterMin();
        else window.addEventListener('load', afterMin);
        setTimeout(finish, 2600);
    }

    /* ---------- Background slideshow (only photos that actually load are shown) ---------- */
    function initBackdrop() {
        var root = document.getElementById('vpBackdrop');
        if (!root) return;
        var fallback = root.querySelector('.vp-slide--fallback');
        var candidates = Array.prototype.slice.call(root.querySelectorAll('.vp-slide[data-src]'));
        var caption = document.getElementById('vpSlideCaption');
        var SLIDE_MS = 6500;
        var slides = [];
        var index = -1;
        var timer = null;
        var settled = 0;

        if (fallback) fallback.classList.add('is-active');

        function show(i) {
            if (!slides.length) return;
            index = (i + slides.length) % slides.length;
            if (fallback) fallback.classList.remove('is-active');
            slides.forEach(function (s, k) { s.classList.toggle('is-active', k === index); });
            if (caption) renderCaption();
        }

        function renderCaption() {
            var s = slides[index];
            var text = caption.querySelector('.cap-text');
            var ticks = caption.querySelector('.cap-ticks');
            caption.classList.add('has-slides');
            caption.style.setProperty('--slide-ms', SLIDE_MS + 'ms');
            if (ticks.children.length !== slides.length) {
                ticks.innerHTML = '';
                slides.forEach(function () { ticks.appendChild(document.createElement('span')); });
            }
            Array.prototype.forEach.call(ticks.children, function (t, k) {
                t.classList.remove('is-active');
                if (k === index) { void t.offsetWidth; t.classList.add('is-active'); }
            });
            text.classList.add('is-swapping');
            setTimeout(function () {
                text.querySelector('.cap-kicker').textContent = s.dataset.kicker || '';
                text.querySelector('.cap-title').textContent = s.dataset.caption || '';
                text.classList.remove('is-swapping');
            }, 250);
        }

        function start() {
            if (timer || slides.length < 2 || reduceMotion) return;
            timer = setInterval(function () { show(index + 1); }, SLIDE_MS);
        }
        function stop() { clearInterval(timer); timer = null; }

        function settle() {
            settled++;
            if (settled !== candidates.length) return;
            // Keep original document order regardless of which image loaded first.
            slides = candidates.filter(function (c) { return c.dataset.ok === '1'; });
            if (!slides.length) return;              // no local photos yet: keep fallback
            show(0);
            start();
        }

        candidates.forEach(function (slide) {
            var img = new Image();
            img.onload = function () {
                slide.style.backgroundImage = 'url("' + slide.dataset.src + '")';
                slide.dataset.ok = '1';
                settle();
            };
            img.onerror = function () { slide.parentNode.removeChild(slide); settle(); };
            img.src = slide.dataset.src;
        });

        document.addEventListener('visibilitychange', function () {
            if (document.hidden) stop(); else start();
        });
    }

    /* ---------- Entrance reveals ---------- */
    function initReveals() {
        var items = document.querySelectorAll('.vp-reveal');
        if (!('IntersectionObserver' in window)) {
            items.forEach(function (el) { el.classList.add('is-in'); });
            return;
        }
        var io = new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                if (entry.isIntersecting) {
                    entry.target.classList.add('is-in');
                    io.unobserve(entry.target);
                }
            });
        }, { rootMargin: '0px 0px -8% 0px', threshold: 0.05 });
        var viewH = window.innerHeight || document.documentElement.clientHeight;
        items.forEach(function (el, i) {
            if (!el.style.getPropertyValue('--reveal-delay')) {
                el.style.setProperty('--reveal-delay', (el.dataset.revealOrder ? el.dataset.revealOrder * 110 : 0) + 'ms');
            }
            // Above the fold: reveal right away (no dependency on observer timing).
            if (el.getBoundingClientRect().top < viewH) el.classList.add('is-in');
            else io.observe(el);
        });
    }

    /* ---------- In-page tab links (hero "Book a Visit" etc.) ---------- */
    // The header bar is gone (only a floating toggle, see fragments/visitor :: topnav), so there is no scroll styling
    // left here: just the links that open a booking-panel tab and scroll to it.
    function initNav() {
        document.querySelectorAll('[data-open-tab]').forEach(function (link) {
            link.addEventListener('click', function (e) {
                var tabBtn = document.getElementById(link.dataset.openTab);
                if (!tabBtn || !window.bootstrap) return;
                e.preventDefault();
                bootstrap.Tab.getOrCreateInstance(tabBtn).show();
                var target = document.getElementById('booking');
                if (target) target.scrollIntoView({ behavior: 'smooth', block: 'start' });
            });
        });
    }

    /* ---------- Booking step flow ---------- */
    function initSteps() {
        var root = document.getElementById('bookingSteps');
        if (!root) return;

        var steps = Array.prototype.slice.call(root.querySelectorAll('.vp-step'));
        var pills = Array.prototype.slice.call(document.querySelectorAll('.vp-step-pill'));
        var wrapper = document.getElementById('registrationFormWrapper');
        var form = document.getElementById('mainRegForm');
        var badge = document.getElementById('selectedDateBadge');
        var dateInput = document.getElementById('formVisitDate');
        var timeInput = document.getElementById('formTimeWindow');
        var submitBtn = document.getElementById('submitBtn');
        var anchor = document.getElementById('booking');
        var current = 0;
        var reached = 0;

        function unlocked() { return wrapper && !wrapper.classList.contains('locked-form'); }

        function fieldsIn(step) {
            return Array.prototype.slice.call(step.querySelectorAll('input, select, textarea'))
                .filter(function (f) { return !f.disabled && f.type !== 'hidden' && f.offsetParent !== null; });
        }

        function stepValid(index) {
            var fields = fieldsIn(steps[index]);
            for (var i = 0; i < fields.length; i++) {
                if (!fields[i].checkValidity()) { fields[i].reportValidity(); return false; }
            }
            return true;
        }

        function render() {
            steps.forEach(function (s, i) {
                s.classList.toggle('is-active', i === current);
                s.setAttribute('aria-hidden', i === current ? 'false' : 'true');
            });
            pills.forEach(function (p, i) {
                p.classList.toggle('is-current', i === current);
                p.classList.toggle('is-done', i < current);
                p.disabled = i > reached || (i > 0 && !unlocked());
                if (i === current) p.setAttribute('aria-current', 'step'); else p.removeAttribute('aria-current');
            });
        }

        function scrollToAnchor() {
            if (!anchor) return;
            var top = anchor.getBoundingClientRect().top;
            if (top < 0 || top > window.innerHeight * 0.5) {
                anchor.scrollIntoView({ behavior: 'smooth', block: 'start' });
            }
        }

        function go(index, opts) {
            opts = opts || {};
            if (index === current || index < 0 || index >= steps.length) return;
            if (!opts.force && index > current) {
                if (current === 0 && !unlocked()) return;
                for (var i = current; i < index; i++) {
                    if (i > 0 && !stepValid(i)) { current = i; render(); return; }
                }
            }
            current = index;
            reached = Math.max(reached, index);
            render();
            scrollToAnchor();
            var focusTarget = steps[current].querySelector('.vp-step-head h2');
            if (focusTarget) focusTarget.focus({ preventScroll: true });
        }

        function refreshSummary() {
            var master = window.isMasterMode === true;
            var dateOut = document.getElementById('summaryDate');
            var winOut = document.getElementById('summaryWindow');
            var capOut = document.getElementById('summaryRemaining');
            var next0 = document.getElementById('stepNext0');
            if (dateOut) dateOut.textContent = master ? 'Set in next step' : (dateInput && dateInput.value) || '—';
            if (winOut) winOut.textContent = (timeInput && timeInput.value) || '—';
            if (capOut) capOut.textContent = master ? 'Group bypass' : (unlocked() && window.maxAllowedForSelectedDate ? window.maxAllowedForSelectedDate + ' seats' : '—');
            if (next0) next0.disabled = !unlocked();
        }

        // React to the original booking code without modifying it.
        if (wrapper && 'MutationObserver' in window) {
            new MutationObserver(function () {
                // Relocked: back to step 1. Already there (e.g. the calendar's day picker clearing an earlier time)?
                // Then only reset progress: go() would scroll to #booking and undo the scroll to the time list.
                if (!unlocked()) {
                    reached = 0;
                    if (current !== 0) { current = 1; go(0, { force: true }); }
                }
                refreshSummary();
                render();
            }).observe(wrapper, { attributes: true, attributeFilter: ['class'] });
        }
        if (badge && 'MutationObserver' in window) {
            new MutationObserver(refreshSummary).observe(badge, { childList: true, characterData: true, subtree: true });
        }

        root.addEventListener('click', function (e) {
            var next = e.target.closest('[data-step-next]');
            var prev = e.target.closest('[data-step-prev]');
            if (next) { e.preventDefault(); go(current + 1); }
            if (prev) { e.preventDefault(); go(current - 1, { force: true }); }
        });
        pills.forEach(function (p, i) {
            p.addEventListener('click', function () { if (!p.disabled) go(i, { force: i <= reached }); });
        });

        // Native validation cannot focus fields inside hidden steps: route to the step first.
        if (submitBtn && form) {
            submitBtn.addEventListener('click', function (e) {
                if (form.checkValidity()) return;
                e.preventDefault();
                var invalid = form.querySelector(':invalid');
                var owner = invalid ? invalid.closest('.vp-step') : null;
                var index = owner ? steps.indexOf(owner) : current;
                if (index !== current) { current = index; reached = Math.max(reached, index); render(); }
                setTimeout(function () { if (invalid) invalid.reportValidity(); }, 60);
            });

            // 🔒 DOUBLE-SUBMIT GUARD: the KYC check and the emails take a few seconds, so the first valid submit locks
            // the form. Listens to 'submit' (not 'click'): it only fires once validation has passed, and Enter is caught too.
            // Only the button is disabled. Disabled inputs are left out of a POST, so the fields, the photos and the
            // one-time kycNonce stay enabled and are sent exactly as before.
            var submitting = false;
            form.addEventListener('submit', function (e) {
                if (submitting) { e.preventDefault(); return; }
                submitting = true;
                submitBtn.disabled = true;
                submitBtn.setAttribute('aria-busy', 'true');
                submitBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>'
                    + 'Processing AI Verification... Please wait.';
            });
            // Back button: the browser can restore this page from its cache with the button still locked and a
            // kycNonce the server has already used up. Reload to get a fresh form and a fresh nonce.
            window.addEventListener('pageshow', function (e) {
                if (e.persisted) window.location.reload();
            });
        }

        refreshSummary();
        render();
    }

    /* ---------- Ready signal: runs cb once the radar pre-loader has lifted (right away on pages without one).
       Used by the welcome modal and Sgt. Aero's greeting so nothing opens underneath the pre-loader. ---------- */
    window.vpWhenReady = function (cb) {
        var html = document.documentElement;
        function check() {
            if (!html.classList.contains('vp-is-loading')) { cb(); return; }
            var mo = new MutationObserver(function () {
                if (html.classList.contains('vp-is-loading')) return;
                mo.disconnect();
                cb();
            });
            mo.observe(html, { attributes: true, attributeFilter: ['class'] });
        }
        // setTimeout: let initPreloader (DOMContentLoaded, below) set .vp-is-loading first
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function () { setTimeout(check, 0); });
        else setTimeout(check, 0);
    };

    document.addEventListener('DOMContentLoaded', function () {
        initBackdrop();
        initNav();
        initSteps();
        initPreloader(initReveals);
    });
})();
