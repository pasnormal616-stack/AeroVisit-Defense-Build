// 🧲 DUTY ROSTER, DRAG AND DROP (Slots & Staff → Duty Roster, Director only).
// One date at a time: its booking-slot windows are drop zones, the Staff Directory is a pool of names.
//  - Drag a name from the pool onto a window: assigned. Several names may share a window, and one name may cover
//    several windows the same day (the server still refuses a real overlap for the same person).
//  - Drag a placed name onto another window: moved. Drag it back onto the pool, or press its ×: removed.
//  - No drag and drop (phones) or keyboard: tap/Enter a name, then tap/Enter a window ("Place here"), or the pool.
// Every change shows at once and is saved in the background (no reload, no spinner). If the server refuses it, the
// name goes back where it was and the reason shows under the windows.
// The page gives the first day inline (window.AV_ROSTER.day: today unless ?day=); DutyRoster.open(date) loads another
// in place. window.onRosterChange(type, entry) lets the page keep its calendar and Shift Directory in step.
(function () {
    'use strict';
    var cfg = window.AV_ROSTER || {};
    var panel = document.getElementById('rosterDayPanel');
    if (!panel || !cfg.day) return;

    var label = document.getElementById('rosterDayLabel');
    var hint = document.getElementById('rosterDayHint');
    var windowsBox = document.getElementById('rosterWindowsBox');
    var pool = document.getElementById('rosterPool');
    var poolHint = document.getElementById('rosterPoolHint');
    var errorBox = document.getElementById('rosterError');
    var picker = document.getElementById('rosterDayPicker');
    var form = document.getElementById('rosterDayForm');
    var todayBtn = document.getElementById('rosterTodayBtn');

    var day = null;        // the date shown, as /admin/staff/roster/day returns it
    var dragging = null;   // {name} from the pool, or {id} of a placed name
    var picked = null;     // the same, chosen by a tap / Enter and waiting for a window
    var loadSeq = 0;
    var tempSeq = 0;
    var errorTimer = null;

    function key(name) { return String(name || '').trim().replace(/\s+/g, ' ').toLowerCase(); }

    function changed(type, entry) {
        if (typeof window.onRosterChange === 'function') {
            try { window.onRosterChange(type, entry); } catch (e) { /* the page's extras never block a save */ }
        }
    }

    function showError(message) {
        errorBox.textContent = message;
        errorBox.hidden = false;
        clearTimeout(errorTimer);
        errorTimer = setTimeout(function () { errorBox.hidden = true; }, 8000);
    }

    // A background POST; resolves with the JSON body, rejects with the server's reason (signed out, refused, offline)
    function post(url, params) {
        return fetch(url, {
            method: 'POST',
            credentials: 'same-origin',
            headers: AeroCsrf.headers({ 'Content-Type': 'application/x-www-form-urlencoded', 'X-Requested-With': 'fetch' }),
            body: new URLSearchParams(params)
        }).then(function (res) {
            return res.text().then(function (text) {
                var body = {};
                try { body = JSON.parse(text); } catch (e) { /* "OK" or a sign-in page */ }
                if (!res.ok || res.redirected) throw new Error(body.error || 'Not saved. Please refresh the page and try again.');
                return body;
            });
        }, function () { throw new Error('Not saved: no connection. Please try again.'); });
    }

    function entryById(id) {
        for (var i = 0; i < day.entries.length; i++) if (String(day.entries[i].id) === String(id)) return day.entries[i];
        return null;
    }

    // ---- Drawing ----

    function chip(entry) {
        var li = document.createElement('li');
        li.className = 'av-roster-chip';
        li.dataset.id = entry.id;
        var name = document.createElement('span');
        name.className = 'av-roster-chip-name';
        name.textContent = entry.staffName;
        li.appendChild(name);
        var canMove = day.editable && !entry.pending;
        if (canMove) {
            li.draggable = true;
            li.tabIndex = 0;
            li.setAttribute('role', 'button');
            li.setAttribute('aria-pressed', picked && String(picked.id) === String(entry.id) ? 'true' : 'false');
            li.title = 'Drag to another window to move, or onto the staff list to remove';
            li.addEventListener('dragstart', function (e) { startDrag(e, { id: entry.id }); });
            li.addEventListener('dragend', endDrag);
            li.addEventListener('click', function (e) { if (!e.target.closest('button')) pick({ id: entry.id }); });
            li.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick({ id: entry.id }); } });
        }
        if (!entry.pending && (day.editable || day.past)) {
            var x = document.createElement('button');
            x.type = 'button';
            x.className = 'av-roster-chip-x';
            x.setAttribute('aria-label', 'Remove ' + entry.staffName);
            x.title = 'Remove';
            x.innerHTML = '<i class="bi bi-x-lg" aria-hidden="true"></i>';
            x.addEventListener('click', function () { unassign(entry.id); });
            li.appendChild(x);
        }
        return li;
    }

    function zone(win) {
        var section = document.createElement('section');
        section.className = 'av-roster-window';
        section.dataset.window = win.window;
        var head = document.createElement('div');
        head.className = 'av-roster-window-head';
        var title = document.createElement('h3');
        title.className = 'h6 fw-bold mb-0 av-mono text-break';
        title.textContent = win.summary;
        head.appendChild(title);
        if (day.editable) {
            var place = document.createElement('button');
            place.type = 'button';
            place.className = 'btn btn-sm btn-light av-roster-place';
            place.textContent = 'Place here';
            place.hidden = !picked;
            place.setAttribute('aria-label', 'Place the chosen name on ' + win.times);
            place.addEventListener('click', function () { placePicked(win.window); });
            head.appendChild(place);
        }
        section.appendChild(head);
        var list = document.createElement('ul');
        list.className = 'av-roster-chips list-unstyled mb-0';
        var mine = day.entries.filter(function (e) { return e.shiftTime === win.window; });
        mine.forEach(function (e) { list.appendChild(chip(e)); });
        if (!mine.length) {
            var empty = document.createElement('li');
            empty.className = 'av-hint av-roster-empty';
            empty.textContent = day.editable ? 'Drop a name here.' : 'No one assigned.';
            list.appendChild(empty);
        }
        section.appendChild(list);
        if (day.editable) dropTarget(section, function () { return win.window; });
        return section;
    }

    function render() {
        label.textContent = day.label;
        if (picker) picker.value = day.date;
        hint.textContent = !day.open ? day.closedReason + '.'
            : day.past ? 'This date is in the past: names can be removed here, or corrected in the Shift Directory.'
            : 'Drag a name onto a time window. Drag it onto another window to move it, or back to the staff list to remove it.';
        panel.classList.toggle('is-picking', !!picked);
        windowsBox.textContent = '';
        if (!day.windows.length) {
            var none = document.createElement('p');
            none.className = 'av-empty mb-0';
            none.textContent = day.closedReason || 'No booking slots on this date';
            windowsBox.appendChild(none);
        }
        day.windows.forEach(function (w) { windowsBox.appendChild(zone(w)); });

        // Names on a window that is not one of the date's slots (an older label, or a removed slot)
        var known = day.windows.map(function (w) { return w.window; });
        var others = day.entries.filter(function (e) { return known.indexOf(e.shiftTime) < 0; });
        if (others.length) {
            var section = document.createElement('section');
            section.className = 'av-roster-window av-roster-window--other';
            var title = document.createElement('h3');
            title.className = 'h6 fw-bold mb-1';
            title.textContent = 'Other times';
            section.appendChild(title);
            var list = document.createElement('ul');
            list.className = 'av-roster-chips list-unstyled mb-0';
            others.forEach(function (e) {
                var li = chip(e);
                var sub = document.createElement('span');
                sub.className = 'av-cell-sub';
                sub.textContent = e.shiftTime;
                li.insertBefore(sub, li.lastChild && li.lastChild.tagName === 'BUTTON' ? li.lastChild : null);
                list.appendChild(li);
            });
            section.appendChild(list);
            windowsBox.appendChild(section);
        }

        pool.textContent = '';
        poolHint.textContent = day.editable
            ? (day.staff.length ? 'Drag a name onto a time window. On a phone: tap a name, then "Place here".' : 'Add staff in the Staff Directory first.')
            : 'Names can only be added to an open date from today on.';
        day.staff.forEach(function (name) {
            var li = document.createElement('li');
            li.className = 'av-roster-chip av-roster-chip--pool';
            li.textContent = name;
            li.draggable = true;
            li.tabIndex = 0;
            li.setAttribute('role', 'button');
            li.setAttribute('aria-pressed', picked && picked.name === name ? 'true' : 'false');
            li.addEventListener('dragstart', function (e) { startDrag(e, { name: name }); });
            li.addEventListener('dragend', endDrag);
            li.addEventListener('click', function () { pick({ name: name }); });
            li.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick({ name: name }); } });
            pool.appendChild(li);
        });
    }

    // ---- Drag and drop, and tap to place ----

    function startDrag(e, what) {
        dragging = what;
        picked = null;
        try { e.dataTransfer.setData('text/plain', what.name || String(what.id)); e.dataTransfer.effectAllowed = 'move'; } catch (err) { /* old browsers */ }
        panel.classList.add('is-dragging');
    }

    function endDrag() {
        dragging = null;
        panel.classList.remove('is-dragging');
        panel.querySelectorAll('.is-over').forEach(function (el) { el.classList.remove('is-over'); });
    }

    function dropTarget(el, windowOf) {
        el.addEventListener('dragover', function (e) { if (dragging) { e.preventDefault(); el.classList.add('is-over'); } });
        el.addEventListener('dragleave', function (e) { if (!el.contains(e.relatedTarget)) el.classList.remove('is-over'); });
        el.addEventListener('drop', function (e) {
            e.preventDefault();
            var what = dragging;
            endDrag();
            if (what) apply(what, windowOf());
        });
        el.addEventListener('click', function (e) {
            if (picked && !e.target.closest('.av-roster-chip, button')) placePicked(windowOf());
        });
    }

    function pick(what) {
        var same = picked && ((what.name && picked.name === what.name) || (what.id != null && String(picked.id) === String(what.id)));
        picked = same ? null : what;
        render();
    }

    function placePicked(win) {
        var what = picked;
        picked = null;
        if (what) apply(what, win); else render();
    }

    // win = a window label, or null for the pool (remove)
    function apply(what, win) {
        if (what.name) {
            if (win) assign(what.name, win); else render();
        } else if (what.id != null) {
            if (win) move(what.id, win); else unassign(what.id);
        }
    }

    // ---- Saving (optimistic) ----

    function assign(name, win) {
        var d = day;
        if (d.entries.some(function (e) { return e.shiftTime === win && key(e.staffName) === key(name); })) {
            showError(name + ' is already on that window.');
            render();
            return;
        }
        var temp = { id: 'new-' + (++tempSeq), staffName: name, dutyDate: d.date, shiftTime: win, pending: true };
        d.entries.push(temp);
        render();
        post('/admin/staff/roster/assign', { staffName: name, dutyDate: d.date, shiftTime: win })
            .then(function (saved) {
                temp.id = saved.id;
                temp.staffName = saved.staffName;
                delete temp.pending;
                if (day === d) render();
                changed('added', { id: saved.id, staffName: saved.staffName, dutyDate: d.date, shiftTime: win });
            })
            .catch(function (err) {
                d.entries.splice(d.entries.indexOf(temp), 1);
                if (day === d) { render(); showError(err.message); }
            });
    }

    function move(id, win) {
        var d = day;
        var entry = entryById(id);
        if (!entry || entry.pending || entry.shiftTime === win) { render(); return; }
        var before = entry.shiftTime;
        entry.shiftTime = win;
        entry.pending = true;
        render();
        post('/admin/staff/roster/move', { id: id, shiftTime: win })
            .then(function () {
                delete entry.pending;
                if (day === d) render();
                changed('moved', { id: entry.id, staffName: entry.staffName, dutyDate: d.date, shiftTime: win });
            })
            .catch(function (err) {
                entry.shiftTime = before;
                delete entry.pending;
                if (day === d) { render(); showError(err.message); }
            });
    }

    function unassign(id) {
        var d = day;
        var entry = entryById(id);
        if (!entry || entry.pending) { render(); return; }
        var at = d.entries.indexOf(entry);
        d.entries.splice(at, 1);
        if (picked && String(picked.id) === String(id)) picked = null;
        render();
        post('/admin/staff/ajax-delete/' + encodeURIComponent(id), {})
            .then(function () { changed('removed', { id: entry.id, staffName: entry.staffName, dutyDate: d.date, shiftTime: entry.shiftTime }); })
            .catch(function (err) {
                d.entries.splice(Math.min(at, d.entries.length), 0, entry);
                if (day === d) { render(); showError(err.message); }
            });
    }

    // The pool is a drop target too: a placed name dropped there is removed
    function poolDrop() {
        var box = document.getElementById('rosterPoolBox');
        box.addEventListener('dragover', function (e) { if (dragging && dragging.id != null) { e.preventDefault(); box.classList.add('is-over'); } });
        box.addEventListener('dragleave', function (e) { if (!box.contains(e.relatedTarget)) box.classList.remove('is-over'); });
        box.addEventListener('drop', function (e) {
            e.preventDefault();
            var what = dragging;
            endDrag();
            if (what && what.id != null) unassign(what.id);
        });
        box.addEventListener('click', function (e) {
            if (picked && picked.id != null && !e.target.closest('.av-roster-chip')) { var id = picked.id; picked = null; unassign(id); }
        });
    }

    // ---- Opening a date (no page navigation) ----

    function show(data) {
        day = data;
        picked = null;
        errorBox.hidden = true;
        render();
    }

    function open(date, scroll) {
        var seq = ++loadSeq;
        var url = '/admin/staff?' + (cfg.showPast ? 'past=1&' : '') + 'day=' + encodeURIComponent(date) + '#roster';
        fetch('/admin/staff/roster/day?date=' + encodeURIComponent(date), {
            credentials: 'same-origin', cache: 'no-store', headers: { 'X-Requested-With': 'fetch' }
        })
            .then(function (res) { return res.ok && !res.redirected ? res.json() : Promise.reject(); })
            .then(function (data) {
                if (seq !== loadSeq) return; // a later click won
                show(data);
                try { history.replaceState(null, '', url); } catch (e) { /* file:// or an old browser */ }
                if (scroll) panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
            })
            .catch(function () { if (seq === loadSeq) location.href = url; }); // fall back to opening the page on that day
    }

    // The entry was removed elsewhere on the page (the Shift Directory's trash button)
    function forget(id) {
        var entry = day && entryById(id);
        if (!entry) return;
        day.entries.splice(day.entries.indexOf(entry), 1);
        render();
    }

    if (form) form.addEventListener('submit', function (e) { e.preventDefault(); if (picker.value) open(picker.value); });
    if (picker) picker.addEventListener('change', function () { if (picker.value) open(picker.value); });
    if (todayBtn) todayBtn.addEventListener('click', function (e) { e.preventDefault(); open(cfg.today); });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && picked) { picked = null; render(); } });

    poolDrop();
    show(cfg.day);
    window.DutyRoster = { open: open, forget: forget, date: function () { return day && day.date; } };
})();
