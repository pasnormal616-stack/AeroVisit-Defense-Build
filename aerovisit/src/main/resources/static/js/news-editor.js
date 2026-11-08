// 📰 Website Content > News editor (news-editor.html). Photos are uploaded one per request (each stays under the 15 MB
// multipart limit): 1) save the post's fields (gets its id), 2) upload each new photo with its alt text, 3) save again
// with the final photo order and the chosen action (Publish needs the photos to exist first). Without new photos the
// form is submitted normally. The server checks everything again; its messages are shown as they are.
// The preview is built with textContent only, so a caption can never become HTML here either.
(function () {
    'use strict';

    var form = document.getElementById('newsEditorForm');
    if (!form) return;

    var MAX_PHOTOS = parseInt(form.getAttribute('data-max-photos'), 10) || 10;
    var MAX_BYTES = (parseInt(form.getAttribute('data-max-mb'), 10) || 10) * 1024 * 1024;
    var PINNED = form.getAttribute('data-pinned') === 'true';
    var TYPES = { 'image/jpeg': 1, 'image/png': 1, 'image/webp': 1 };
    var EXTENSIONS = /\.(jpe?g|png|webp)$/i;

    var idInput = document.getElementById('newsPostId');
    var orderInput = document.getElementById('newsPhotoOrder');
    var caption = document.getElementById('newsCaption');
    var counter = document.getElementById('newsCaptionCount');
    var dateInput = document.getElementById('newsDate');
    var list = document.getElementById('newsPhotoList');
    var noPhotos = document.getElementById('newsNoPhotos');
    var fileInput = document.getElementById('newsPhotoInput');
    var progress = document.getElementById('newsProgress');
    var errors = document.getElementById('newsErrors');
    var flash = document.getElementById('newsFlash');
    var preview = document.getElementById('newsPreview');
    var queued = new Map(); // row -> File
    var submitter = null;
    var busy = false;

    // A message carried over the reload after a JavaScript save
    try {
        var carried = sessionStorage.getItem('av_news_flash');
        if (carried && flash) { flash.textContent = carried; flash.hidden = false; }
        sessionStorage.removeItem('av_news_flash');
    } catch (e) { /* storage blocked: no message, the page still works */ }

    function showErrors(messages) {
        if (!errors) return;
        errors.textContent = '';
        messages.forEach(function (m) {
            var line = document.createElement('div');
            line.textContent = m;
            errors.appendChild(line);
        });
        errors.hidden = messages.length === 0;
    }

    // ---------- Caption counter ----------

    function count() {
        var n = caption.value.replace(/\r\n/g, '\n').trim().length;
        counter.textContent = n.toLocaleString('en-US') + ' / ' + (caption.maxLength > 0 ? caption.maxLength : 2200).toLocaleString('en-US');
    }

    // ---------- Photo rows ----------

    function rows() { return Array.prototype.slice.call(list.querySelectorAll('.av-news-photo')); }
    function liveRows() { return rows().filter(function (r) { return !r.classList.contains('is-removed'); }); }

    function refresh() {
        var live = liveRows();
        orderInput.value = live.filter(function (r) { return r.hasAttribute('data-photo-id'); })
            .map(function (r) { return r.getAttribute('data-photo-id'); }).join(',');
        if (noPhotos) noPhotos.hidden = live.length > 0;
        var all = rows();
        all.forEach(function (row, i) {
            var up = row.querySelector('[data-move="up"]');
            var down = row.querySelector('[data-move="down"]');
            if (up) up.disabled = i === 0;
            if (down) down.disabled = i === all.length - 1;
        });
        renderPreview();
    }

    function wireRow(row) {
        row.querySelector('[data-move="up"]').addEventListener('click', function () {
            var prev = row.previousElementSibling;
            if (prev) { list.insertBefore(row, prev); this.focus(); refresh(); }
        });
        row.querySelector('[data-move="down"]').addEventListener('click', function () {
            var next = row.nextElementSibling;
            if (next) { list.insertBefore(next, row); this.focus(); refresh(); }
        });
        row.querySelector('[data-remove]').addEventListener('click', function () {
            if (queued.has(row)) { // not uploaded yet: just drop it
                var img = row.querySelector('img');
                if (img) URL.revokeObjectURL(img.src);
                queued.delete(row);
                var next = row.nextElementSibling || row.previousElementSibling;
                row.remove();
                if (next) next.querySelector('[data-remove]').focus(); else fileInput.focus();
                refresh();
                return;
            }
            var removed = row.classList.toggle('is-removed');
            this.setAttribute('aria-pressed', removed ? 'true' : 'false');
            this.setAttribute('aria-label', removed ? 'Keep photo (undo remove)' : 'Remove photo');
            this.title = removed ? 'Keep photo' : 'Remove photo';
            var alt = row.querySelector('input[type="text"]');
            if (alt) alt.disabled = removed;
            var flag = row.querySelector('input[name="removePhoto"]');
            if (removed && !flag) {
                flag = document.createElement('input');
                flag.type = 'hidden';
                flag.name = 'removePhoto';
                flag.value = row.getAttribute('data-photo-id');
                row.appendChild(flag);
            } else if (!removed && flag) {
                flag.remove();
            }
            refresh();
        });
        row.querySelector('input[type="text"]').addEventListener('input', renderPreview);

        // Drag to reorder (mouse); the arrow buttons do the same from the keyboard or a phone
        row.addEventListener('dragstart', function (e) {
            row.classList.add('is-dragging');
            e.dataTransfer.effectAllowed = 'move';
            try { e.dataTransfer.setData('text/plain', ''); } catch (err) { /* old browsers */ }
        });
        row.addEventListener('dragend', function () {
            row.classList.remove('is-dragging');
            rows().forEach(function (r) { r.classList.remove('is-drop-target'); });
            refresh();
        });
        row.addEventListener('dragover', function (e) {
            var dragging = list.querySelector('.is-dragging');
            if (!dragging || dragging === row) return;
            e.preventDefault();
            var box = row.getBoundingClientRect();
            var after = e.clientY > box.top + box.height / 2;
            list.insertBefore(dragging, after ? row.nextElementSibling : row);
        });
    }

    function addQueuedRow(file) {
        var row = document.createElement('li');
        row.className = 'av-news-photo is-queued';
        row.draggable = true;

        var handle = document.createElement('span');
        handle.className = 'av-news-handle';
        handle.setAttribute('aria-hidden', 'true');
        handle.innerHTML = '<i class="bi bi-grip-vertical"></i>';

        var img = document.createElement('img');
        img.src = URL.createObjectURL(file);
        img.alt = 'New photo: ' + file.name;

        var altWrap = document.createElement('span');
        altWrap.className = 'av-news-photo-alt';
        var label = document.createElement('label');
        label.className = 'visually-hidden';
        var altId = 'alt-new-' + Math.random().toString(36).slice(2);
        label.htmlFor = altId;
        label.textContent = 'Photo description (alt text) for ' + file.name;
        var alt = document.createElement('input');
        alt.type = 'text';
        alt.id = altId;
        alt.className = 'form-control-custom';
        alt.maxLength = 250;
        alt.placeholder = 'Describe the photo (optional alt text)';
        var note = document.createElement('small');
        note.className = 'av-hint d-block';
        note.textContent = 'New · ' + file.name + ' · uploads when you save';
        altWrap.appendChild(label);
        altWrap.appendChild(alt);
        altWrap.appendChild(note);

        var tools = document.createElement('span');
        tools.className = 'av-news-photo-tools';
        tools.innerHTML =
            '<button type="button" class="btn btn-sm btn-light p-1" data-move="up" title="Move earlier" aria-label="Move photo earlier"><i class="bi bi-arrow-up"></i></button>' +
            '<button type="button" class="btn btn-sm btn-light p-1" data-move="down" title="Move later" aria-label="Move photo later"><i class="bi bi-arrow-down"></i></button>' +
            '<button type="button" class="btn btn-sm btn-light text-danger p-1" data-remove title="Remove photo" aria-label="Remove photo"><i class="bi bi-trash"></i></button>';

        row.appendChild(handle);
        row.appendChild(img);
        row.appendChild(altWrap);
        row.appendChild(tools);
        list.appendChild(row);
        queued.set(row, file);
        wireRow(row);
    }

    fileInput.addEventListener('change', function () {
        var problems = [];
        Array.prototype.forEach.call(fileInput.files, function (file) {
            if (!TYPES[file.type] && !EXTENSIONS.test(file.name)) {
                problems.push('"' + file.name + '" is not a JPG, PNG or WebP image.');
            } else if (file.size > MAX_BYTES) {
                problems.push('"' + file.name + '" is larger than ' + (MAX_BYTES / 1048576) + ' MB.');
            } else if (liveRows().length >= MAX_PHOTOS) {
                problems.push('"' + file.name + '" was not added: a post can have at most ' + MAX_PHOTOS + ' photos.');
            } else {
                addQueuedRow(file);
            }
        });
        fileInput.value = '';
        showErrors(problems);
        refresh();
    });

    // ---------- Preview (same classes as the visitor feed) ----------

    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) node.className = className;
        if (text != null) node.textContent = text;
        return node;
    }

    function dateLabel(iso) {
        if (!/^\d{4}-\d{2}-\d{2}$/.test(iso || '')) return '';
        var p = iso.split('-');
        var d = new Date(Date.UTC(+p[0], +p[1] - 1, +p[2]));
        return d.toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric', timeZone: 'UTC' });
    }

    var previewTimer = 0;
    function renderPreview() {
        window.clearTimeout(previewTimer);
        previewTimer = window.setTimeout(buildPreview, 60);
    }

    function buildPreview() {
        if (!preview) return;
        preview.textContent = '';
        var post = el('article', 'vp-news-post');

        var head = el('header', 'vp-news-head');
        var avatar = el('span', 'vp-news-avatar');
        var logo = el('img', 'paf-logo');
        logo.src = '/images/paflogo.png';
        logo.alt = '';
        avatar.appendChild(logo);
        var by = el('span', 'vp-news-by');
        by.appendChild(el('span', 'vp-news-name', 'PAF Aerospace Museum'));
        by.appendChild(el('time', 'vp-news-date', dateLabel(dateInput.value)));
        head.appendChild(avatar);
        head.appendChild(by);
        if (PINNED) head.appendChild(el('span', 'vp-news-pin', 'Pinned'));
        post.appendChild(head);

        var live = liveRows();
        if (live.length) {
            var carousel = el('div', 'vp-news-carousel');
            var track = el('div', 'vp-news-track');
            live.forEach(function (row, i) {
                var source = row.querySelector('img');
                var altInput = row.querySelector('input[type="text"]');
                var slide = el('figure', 'vp-news-slide');
                var frame = el('span', 'vp-news-zoom');
                var img = el('img');
                img.src = source.src;
                img.alt = (altInput && altInput.value.trim()) || ('Photo ' + (i + 1) + ' of ' + live.length);
                if (i === 0 && source.naturalWidth && source.naturalHeight) {
                    carousel.style.setProperty('--vp-news-ratio', String(Math.min(1.91, Math.max(0.8, source.naturalWidth / source.naturalHeight))));
                }
                frame.appendChild(img);
                slide.appendChild(frame);
                track.appendChild(slide);
            });
            carousel.appendChild(track);
            if (live.length > 1) carousel.appendChild(el('span', 'vp-news-count', '1/' + live.length));
            post.appendChild(carousel);
            if (live.length > 1) {
                var dots = el('div', 'vp-news-dots');
                live.forEach(function (row, i) {
                    var dot = el('span', 'vp-news-dot');
                    if (i === 0) dot.setAttribute('aria-current', 'true');
                    dots.appendChild(dot);
                });
                post.appendChild(dots);
            }
        }

        var body = el('div', 'vp-news-body');
        var text = caption.value.replace(/\r\n/g, '\n').trim();
        if (text) body.appendChild(el('p', 'vp-news-caption', text)); // textContent: shown exactly as typed
        post.appendChild(body);
        preview.appendChild(post);
    }

    // ---------- Save ----------

    form.addEventListener('click', function (e) {
        var btn = e.target.closest('button[type="submit"]');
        if (btn) submitter = btn;
    });

    function post(url, data) {
        return fetch(url, {
            method: 'POST',
            body: data,
            credentials: 'same-origin',
            headers: window.AeroCsrf ? AeroCsrf.headers({ 'X-Requested-With': 'XMLHttpRequest' }) : { 'X-Requested-With': 'XMLHttpRequest' }
        }).then(function (r) {
            return r.json().catch(function () { return { ok: false, message: 'The server did not answer (status ' + r.status + '). Please try again.' }; });
        });
    }

    function fields(action) {
        var data = new FormData(form);
        data.set('action', action);
        return data;
    }

    function setBusy(on) {
        busy = on;
        Array.prototype.forEach.call(form.querySelectorAll('button[type="submit"]'), function (b) { b.disabled = on; });
    }

    form.addEventListener('submit', function (e) {
        refresh();
        if (busy) { e.preventDefault(); return; }
        var pending = rows().filter(function (r) { return queued.has(r); });
        var requested = (e.submitter || submitter || {}).value || 'draft';
        if (requested === 'publish' && liveRows().length === 0) {
            e.preventDefault();
            showErrors(['Add at least one photo before publishing this post.']);
            fileInput.focus();
            return;
        }
        if (pending.length === 0) {
            // No new photos: an ordinary form post (the clicked button's action is sent with it)
            setBusy(false);
            return;
        }
        e.preventDefault();
        setBusy(true);
        showErrors([]);
        var problems = [];

        // 1) Fields first (a new post is created as a draft; a published one stays published)
        progress.textContent = 'Saving the post…';
        post(form.getAttribute('action'), fields('keep')).then(function (res) {
            if (!res.ok) throw new Error(res.message);
            idInput.value = res.id;
            // 2) Each new photo, one request at a time
            var chain = Promise.resolve();
            pending.forEach(function (row, i) {
                chain = chain.then(function () {
                    progress.textContent = 'Uploading photo ' + (i + 1) + ' of ' + pending.length + '…';
                    var data = new FormData();
                    data.append('photo', queued.get(row));
                    var altInput = row.querySelector('input[type="text"]');
                    data.append('alt', altInput ? altInput.value : '');
                    return post('/admin/content/news/' + res.id + '/photos', data).then(function (up) {
                        if (up.ok && up.photo) {
                            queued.delete(row);
                            row.classList.remove('is-queued');
                            row.setAttribute('data-photo-id', up.photo.id);
                        } else {
                            problems.push(up.message || 'A photo could not be uploaded.');
                            row.remove();
                        }
                    });
                });
            });
            return chain.then(function () {
                // 3) Final order (new photos included) and the chosen action
                refresh();
                progress.textContent = 'Saving the photo order…';
                return post(form.getAttribute('action'), fields(requested));
            }).then(function (final) {
                if (!final.ok) problems.push(final.message);
                var message = final.ok ? final.message : 'Post saved, but: ' + problems.join(' ');
                if (final.ok && problems.length) message += ' Not uploaded: ' + problems.join(' ');
                try { sessionStorage.setItem('av_news_flash', message); } catch (err) { /* ignore */ }
                window.location.assign('/admin/content/news/' + res.id + '/edit');
            });
        }).catch(function (err) {
            progress.textContent = '';
            showErrors([err && err.message ? err.message : 'The post could not be saved. Please try again.']);
            setBusy(false);
        });
    });

    caption.addEventListener('input', function () { count(); renderPreview(); });
    dateInput.addEventListener('input', renderPreview);
    rows().forEach(wireRow);
    count();
    refresh();
})();
