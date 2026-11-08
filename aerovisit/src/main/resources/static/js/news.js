// 📰 Visitor News feed (register.html, tab "News"; content from Website Content > News).
// - Pages of 5 posts come from GET /news/feed?page=N as server-rendered HTML (every caption already escaped by th:text),
//   only after the News tab is first opened. An IntersectionObserver loads the next page near the end; "Load more" is
//   the fallback.
// - Each post: swipeable carousel (CSS scroll-snap) with dots, arrows (mouse) and Left/Right keys; tap a photo for the
//   full-screen viewer; "…more" for long captions.
(function () {
    'use strict';

    var pane = document.getElementById('news');
    var tab = document.getElementById('news-tab');
    var feed = document.getElementById('newsFeed');
    if (!pane || !tab || !feed) return;

    var sentinel = document.getElementById('newsSentinel');
    var moreBtn = document.getElementById('newsLoadMore');
    var emptyMsg = document.getElementById('newsEmpty');
    var endMsg = document.getElementById('newsEnd');
    var status = document.getElementById('newsStatus');

    var nextPage = 0;
    var loading = false;
    var done = false;
    var started = false;
    var shown = 0;

    function announce(text) {
        if (!status) return;
        status.textContent = '';
        window.setTimeout(function () { status.textContent = text; }, 30);
    }

    // ---------- Infinite scroll ----------

    function load() {
        if (loading || done) return;
        loading = true;
        feed.setAttribute('aria-busy', 'true');
        if (moreBtn) { moreBtn.disabled = true; moreBtn.textContent = 'Loading…'; }
        fetch('/news/feed?page=' + nextPage, { credentials: 'same-origin', headers: { 'X-Requested-With': 'XMLHttpRequest' } })
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.text();
            })
            .then(function (html) {
                var holder = document.createElement('template');
                holder.innerHTML = html; // server-rendered and escaped; nothing from the caption is HTML
                var page = holder.content.querySelector('.vp-news-page');
                var added = 0;
                if (page) {
                    Array.prototype.slice.call(page.querySelectorAll('article.vp-news-post')).forEach(function (post) {
                        if (document.getElementById(post.id)) return; // already on the page
                        feed.appendChild(post);
                        initPost(post);
                        added++;
                    });
                    var np = page.getAttribute('data-next-page');
                    if (np === null) done = true; else nextPage = parseInt(np, 10);
                } else {
                    done = true;
                }
                shown += added;
                if (added) announce(added === 1 ? '1 more post loaded.' : added + ' more posts loaded.');
                updateFooter();
            })
            .catch(function () {
                announce('News could not be loaded. Check your connection, then press Load more.');
                if (moreBtn) moreBtn.hidden = false;
            })
            .then(function () {
                loading = false;
                feed.setAttribute('aria-busy', 'false');
                if (moreBtn) { moreBtn.disabled = false; moreBtn.textContent = 'Load more'; }
                // A short page that does not fill the screen: keep going while the sentinel is still visible
                if (!done && sentinelVisible()) load();
            });
    }

    function updateFooter() {
        if (emptyMsg) emptyMsg.hidden = !(done && shown === 0);
        if (endMsg) endMsg.hidden = !(done && shown > 0);
        if (moreBtn) moreBtn.hidden = done;
    }

    function sentinelVisible() {
        if (!sentinel || pane.offsetParent === null) return false;
        var r = sentinel.getBoundingClientRect();
        return r.top < window.innerHeight + 400;
    }

    var observer = null;
    if (sentinel && 'IntersectionObserver' in window) {
        observer = new IntersectionObserver(function (entries) {
            if (started && entries.some(function (e) { return e.isIntersecting; })) load();
        }, { rootMargin: '400px 0px' });
    }

    function start() {
        if (started) return;
        started = true;
        if (observer) observer.observe(sentinel);
        load();
    }

    if (moreBtn) moreBtn.addEventListener('click', function () { start(); load(); });
    tab.addEventListener('shown.bs.tab', start);
    // News is the tab the page opens on: load the first posts right away
    if (tab.classList.contains('active')) start();

    // ---------- One post ----------

    function initPost(post) {
        initCarousel(post);
        initCaption(post);
    }

    function initCarousel(post) {
        var carousel = post.querySelector('.vp-news-carousel');
        if (!carousel) return;
        var track = carousel.querySelector('.vp-news-track');
        var slides = track.querySelectorAll('.vp-news-slide');
        var dots = post.querySelectorAll('[data-news-dot]');
        var prev = carousel.querySelector('[data-news-prev]');
        var next = carousel.querySelector('[data-news-next]');
        var count = carousel.querySelector('.vp-news-count');

        // The first photo sets the frame's shape, kept between 4:5 (portrait) and 1.91:1 (landscape), as on Instagram
        var first = track.querySelector('img');
        var w = first && parseInt(first.getAttribute('width'), 10);
        var h = first && parseInt(first.getAttribute('height'), 10);
        if (w > 0 && h > 0) {
            var ratio = Math.min(1.91, Math.max(0.8, w / h));
            carousel.style.setProperty('--vp-news-ratio', String(ratio));
        }

        function index() {
            return track.clientWidth ? Math.round(track.scrollLeft / track.clientWidth) : 0;
        }
        function go(i, instant) {
            var target = Math.max(0, Math.min(slides.length - 1, i));
            track.scrollTo({ left: target * track.clientWidth, behavior: instant ? 'auto' : 'smooth' });
        }
        function sync() {
            var i = index();
            Array.prototype.forEach.call(dots, function (dot, d) {
                if (d === i) dot.setAttribute('aria-current', 'true'); else dot.removeAttribute('aria-current');
            });
            Array.prototype.forEach.call(slides, function (slide, s) {
                slide.setAttribute('aria-hidden', s === i ? 'false' : 'true');
                var btn = slide.querySelector('[data-news-zoom]');
                if (btn) btn.tabIndex = s === i ? 0 : -1;
            });
            if (prev) prev.disabled = i <= 0;
            if (next) next.disabled = i >= slides.length - 1;
            if (count) count.textContent = (i + 1) + '/' + slides.length;
        }
        var frame = 0;
        track.addEventListener('scroll', function () {
            if (frame) return;
            frame = window.requestAnimationFrame(function () { frame = 0; sync(); });
        }, { passive: true });
        if (prev) prev.addEventListener('click', function () { go(index() - 1); });
        if (next) next.addEventListener('click', function () { go(index() + 1); });
        Array.prototype.forEach.call(dots, function (dot) {
            dot.addEventListener('click', function () { go(parseInt(dot.getAttribute('data-index'), 10)); });
        });
        carousel.addEventListener('keydown', function (e) {
            if (e.key === 'ArrowLeft') { e.preventDefault(); go(index() - 1); }
            else if (e.key === 'ArrowRight') { e.preventDefault(); go(index() + 1); }
        });
        Array.prototype.forEach.call(track.querySelectorAll('[data-news-zoom]'), function (btn) {
            btn.addEventListener('click', function () {
                openViewer(track.querySelectorAll('img'), parseInt(btn.getAttribute('data-index'), 10), btn);
            });
        });
        sync();
    }

    function initCaption(post) {
        var caption = post.querySelector('.vp-news-caption');
        var more = post.querySelector('[data-news-more]');
        if (!caption || !more) return;
        function measure() {
            // Only a caption longer than its 3 clamped lines gets "…more"
            if (caption.scrollHeight > caption.clientHeight + 1) more.hidden = false;
            else caption.classList.remove('is-clamped');
        }
        window.requestAnimationFrame(function () {
            if (caption.clientHeight > 0) measure();
            else tab.addEventListener('shown.bs.tab', function () { window.requestAnimationFrame(measure); }, { once: true });
        });
        more.addEventListener('click', function () {
            var open = caption.classList.toggle('is-clamped') === false;
            more.setAttribute('aria-expanded', open ? 'true' : 'false');
            more.textContent = open ? 'less' : '…more';
        });
    }

    // ---------- Full-screen viewer ----------

    var viewer = document.getElementById('vpNewsViewer');
    var vTrack = document.getElementById('vpNewsViewerTrack');
    var vPrev = document.getElementById('vpNewsViewerPrev');
    var vNext = document.getElementById('vpNewsViewerNext');
    var vCount = document.getElementById('vpNewsViewerCount');
    var opener = null;
    var startAt = 0;

    function vIndex() {
        return vTrack.clientWidth ? Math.round(vTrack.scrollLeft / vTrack.clientWidth) : 0;
    }
    function vGo(i, instant) {
        var n = vTrack.children.length;
        var target = Math.max(0, Math.min(n - 1, i));
        vTrack.scrollTo({ left: target * vTrack.clientWidth, behavior: instant ? 'auto' : 'smooth' });
    }
    function vSync() {
        var n = vTrack.children.length;
        var i = vIndex();
        if (vCount) vCount.textContent = n > 1 ? (i + 1) + ' of ' + n : '';
        if (vPrev) vPrev.hidden = n < 2 || i <= 0;
        if (vNext) vNext.hidden = n < 2 || i >= n - 1;
    }

    function openViewer(images, index, button) {
        if (!viewer || !vTrack || !window.bootstrap) return;
        opener = button;
        startAt = index || 0;
        vTrack.textContent = '';
        Array.prototype.forEach.call(images, function (img) {
            var figure = document.createElement('figure');
            figure.className = 'vp-news-viewer-slide';
            var big = document.createElement('img');
            big.src = img.getAttribute('src');
            big.alt = img.getAttribute('alt') || '';
            big.decoding = 'async';
            figure.appendChild(big);
            vTrack.appendChild(figure);
        });
        bootstrap.Modal.getOrCreateInstance(viewer).show();
    }

    if (viewer && vTrack) {
        viewer.addEventListener('shown.bs.modal', function () {
            vGo(startAt, true);
            vSync();
            vTrack.focus();
        });
        viewer.addEventListener('hidden.bs.modal', function () {
            vTrack.textContent = '';
            if (opener && document.body.contains(opener)) opener.focus(); // back where the visitor was
            opener = null;
        });
        var vFrame = 0;
        vTrack.addEventListener('scroll', function () {
            if (vFrame) return;
            vFrame = window.requestAnimationFrame(function () { vFrame = 0; vSync(); });
        }, { passive: true });
        viewer.addEventListener('keydown', function (e) {
            if (e.key === 'ArrowLeft') { e.preventDefault(); vGo(vIndex() - 1); }
            else if (e.key === 'ArrowRight') { e.preventDefault(); vGo(vIndex() + 1); }
        });
        if (vPrev) vPrev.addEventListener('click', function () { vGo(vIndex() - 1); });
        if (vNext) vNext.addEventListener('click', function () { vGo(vIndex() + 1); });
    }

})();
