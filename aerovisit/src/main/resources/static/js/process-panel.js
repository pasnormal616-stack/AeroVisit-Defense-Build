// 🚶 PROCESS PANEL (fragments/process-panel.html), used by two pages:
//  - Guard Dashboard: filled from the clicked Process button's data-px-* attributes; its forms post normally.
//  - Gate scanner: AeroProcess.open(get, options) with the walk-in scan's "panel" data, over the running camera. The
//    forms are sent with fetch (X-Requested-With: fetch, answered with {status, message}), so the page never reloads.
// Text goes in with textContent and links with setAttribute, never HTML.
// States: waiting (no receipt yet: minutes left, no verify), proof (receipt uploaded: match tick + Verify & Check In,
// Reject), cleared (paid or free: ID/ban ticks + Check In), inside (Check Out). The main button stays disabled until every
// tick shown is on; the server checks them all again.
window.AeroProcess = (function () {
    var modal = document.getElementById('processModal');
    if (!modal) return null;
    var $ = function (id) { return document.getElementById(id); };
    var form = $('processForm'), submit = $('pxSubmit'), rejectForm = $('pxRejectForm');
    var get = function () { return ''; }; // reads one value of the open panel ('name', 'state', ...)
    var opts = {};      // scanner only: { ajax: true, onDone(message, kind), onDeny(), onClose() }
    var next = null;    // what to do once this panel has closed (open Deny Entry / Ban, or the scanner's deny step)
    var outcome = null; // scanner: the successful action, reported once the panel has closed

    function data(name) { return get(name) || ''; }
    function show(el, on) { el.hidden = !on; }
    function img(imgId, linkId, url) {
        var image = $(imgId), link = $(linkId);
        if (url) { image.setAttribute('src', url); link.setAttribute('href', url); }
        else { image.removeAttribute('src'); link.removeAttribute('href'); }
        show(link, !!url);
    }
    function showError(message) {
        $('pxError').textContent = message || '';
        show($('pxError'), !!message);
        if (message) $('pxError').scrollIntoView({ block: 'nearest' });
    }

    function visibleTicks() {
        return Array.prototype.filter.call(form.querySelectorAll('.av-process-tick'), function (t) { return !t.hidden; });
    }
    function syncSubmit() {
        submit.disabled = !visibleTicks().every(function (t) { return t.querySelector('input').checked; });
    }

    function minutesLeft() {
        var due = Date.parse(data('due'));
        return isNaN(due) ? null : Math.max(0, Math.ceil((due - Date.now()) / 60000));
    }

    function fill() {
        next = null;
        outcome = null;
        var state = data('state');
        form.reset();
        rejectForm.reset();
        show(rejectForm, false);
        showError('');
        $('pxRejectOther').hidden = true;
        $('pxRejectSubmit').disabled = true;

        $('pxName').textContent = data('name');
        $('pxRef').textContent = data('ref');
        $('pxWindow').textContent = data('window');
        $('pxGroup').textContent = data('group');
        $('pxTotal').textContent = 'Total ' + data('total');
        $('pxPayment').textContent = data('payment');
        $('pxBilling').textContent = data('billing') || '—';
        $('pxAmount').textContent = data('total');
        $('pxPhotoLabel').textContent = data('photo-label') || 'ID photo';
        img('pxReceipt', 'pxReceiptLink', data('receipt'));
        img('pxPhoto', 'pxPhotoLink', data('photo'));
        show($('pxReceiptCard'), !!data('billing'));
        $('pxVerified').textContent = data('verified');
        show($('pxVerified'), !!data('verified'));

        var ban = data('ban');
        show($('pxBanCard'), !!ban);
        $('pxBanName').textContent = ban;
        var banPhoto = data('ban-photo');
        if (banPhoto) $('pxBanPhoto').setAttribute('src', banPhoto); else $('pxBanPhoto').removeAttribute('src');
        show($('pxBanPhoto'), !!banPhoto);

        var waiting = state === 'waiting', inside = state === 'inside';
        var left = minutesLeft();
        $('pxWaiting').textContent = 'Waiting for the receipt' + (left === null ? '' : ': ' + left + ' min left (until ' + data('due-text') + ')');
        show($('pxWaiting'), waiting);
        $('pxInside').textContent = 'Inside since ' + data('inside');
        show($('pxInside'), inside);

        // Ticks: only those that apply
        show($('pxTickMatch'), state === 'proof');
        $('pxIdsText').textContent = data('ids-text');
        show($('pxTickIds'), !waiting && !inside && !!data('ids-text'));
        show($('pxTickBan'), !waiting && !inside && data('needs-ban') === 'true');

        form.setAttribute('action', data('process-url'));
        rejectForm.setAttribute('action', data('reject-url'));
        $('pxSubmitText').textContent = state === 'proof' ? 'Verify & Check In' : 'Check In';
        show(submit, state === 'proof' || state === 'cleared');
        show($('pxRejectToggle'), state === 'proof');
        show($('pxDeny'), !inside);
        $('pxCheckoutRef').value = data('ref');
        show($('pxCheckoutForm'), inside);
        syncSubmit();
    }

    // Guard Dashboard: Bootstrap passes the clicked Process button
    modal.addEventListener('show.bs.modal', function (e) {
        var button = e.relatedTarget;
        if (!button || !button.hasAttribute || !button.hasAttribute('data-px-state')) return;
        get = function (name) { return button.getAttribute('data-px-' + name); };
        opts = {};
        fill();
    });

    // 📷 Scanner: send a panel form with fetch. Success closes the panel (the scanner then shows the result and resumes
    // scanning); a refusal keeps it open with the reason on top, and its button works again.
    function send(f, kind, restore) {
        showError('');
        fetch(f.getAttribute('action'), {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'X-Requested-With': 'fetch', 'Accept': 'application/json' },
            body: new URLSearchParams(new FormData(f))
        }).then(function (res) {
            if (res.redirected) return { status: 'error', message: 'You were signed out. Sign in again: nothing was saved.' };
            if (res.status === 403) return { status: 'error', message: 'Not allowed: this walk-in can only be processed on its own day. Nothing was saved.' };
            if (!res.ok) return { status: 'error', message: 'The server could not do this (error ' + res.status + '). Nothing was saved.' };
            return res.json();
        }).then(function (answer) {
            if (answer.status === 'success') {
                outcome = { message: answer.message, kind: kind };
                bootstrap.Modal.getOrCreateInstance(modal).hide();
            } else {
                showError(answer.message);
                restore();
            }
        }).catch(function () {
            showError('Server unreachable: nothing was saved. Try again.');
            restore();
        });
    }

    form.addEventListener('change', syncSubmit);
    form.addEventListener('submit', function (e) {
        syncSubmit();
        if (submit.disabled || !form.getAttribute('action')) { e.preventDefault(); return; }
        submit.disabled = true; // one press
        if (opts.ajax) { e.preventDefault(); send(form, 'checkin', syncSubmit); }
    });

    // Reject receipt: a quick reason, or Other with the guard's own words
    $('pxRejectToggle').addEventListener('click', function () {
        show(rejectForm, rejectForm.hidden);
        if (!rejectForm.hidden) rejectForm.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    });
    function rejectReason() {
        var picked = rejectForm.querySelector('input[name="pxQuick"]:checked');
        if (!picked) return '';
        return picked.value === 'other' ? $('pxRejectOther').value.trim() : picked.value;
    }
    rejectForm.addEventListener('input', function () {
        var picked = rejectForm.querySelector('input[name="pxQuick"]:checked');
        $('pxRejectOther').hidden = !(picked && picked.value === 'other');
        $('pxRejectSubmit').disabled = !rejectReason();
    });
    rejectForm.addEventListener('submit', function (e) {
        var reason = rejectReason();
        if (!reason) { e.preventDefault(); return; }
        $('pxRejectReason').value = reason;
        if (opts.ajax) {
            e.preventDefault();
            $('pxRejectSubmit').disabled = true;
            send(rejectForm, 'reject', function () { $('pxRejectSubmit').disabled = !rejectReason(); });
        }
    });

    // Deny Entry and Ban: close this panel first, then open their own dialog (no dialog on top of a dialog). On the
    // scanner, Deny Entry is the scanner's own reason step (onDeny).
    function openAfter(targetId, attrs) {
        var carrier = document.createElement('button');
        Object.keys(attrs).forEach(function (k) { carrier.setAttribute(k, attrs[k]); });
        next = { id: targetId, carrier: carrier };
        bootstrap.Modal.getOrCreateInstance(modal).hide();
    }
    $('pxDeny').addEventListener('click', function () {
        if (opts.onDeny) {
            next = { run: opts.onDeny };
            bootstrap.Modal.getOrCreateInstance(modal).hide();
            return;
        }
        openAfter('denyModal', { 'data-deny-id': data('id'), 'data-deny-name': data('name') });
    });
    $('pxBan').addEventListener('click', function () {
        openAfter('banModal', { 'data-ban-url': data('ban-url'), 'data-ban-name': data('name') });
    });
    modal.addEventListener('hidden.bs.modal', function () {
        var after = next, done = outcome, o = opts;
        next = null;
        outcome = null;
        if (done && o.onDone) { o.onDone(done.message, done.kind); return; }
        if (after && after.run) { after.run(); return; }
        if (after) {
            var target = document.getElementById(after.id);
            if (target) bootstrap.Modal.getOrCreateInstance(target).show(after.carrier);
        }
        if (o.onClose) o.onClose(); // closed without an action (or Ban opened): the scanner resumes
    });

    // 📷 A link to ?process=<id> (e.g. an older scanner tab): open that walk-in's panel (or say why it can't open)
    var params = new URLSearchParams(location.search);
    var wanted = params.get('process');
    if (wanted && /^[0-9]{1,12}$/.test(wanted) && document.getElementById('gateTable')) {
        if (window.history && history.replaceState) history.replaceState(null, '', location.pathname + '?tab=walkins');
        var button = document.querySelector('.av-process-open[data-px-id="' + wanted + '"]');
        if (button) {
            bootstrap.Modal.getOrCreateInstance(modal).show(button);
        } else {
            var note = document.createElement('div');
            note.className = 'alert alert-warning av-scan-missing';
            note.setAttribute('role', 'alert');
            note.textContent = 'This walk-in can\'t be processed right now: it is no longer waiting at the gate. Check its row below.';
            var main = document.getElementById('main');
            if (main) main.insertBefore(note, main.firstChild);
        }
    }

    return {
        // Opens the panel with get(name) -> value; options as `opts` above
        open: function (getter, options) {
            get = getter;
            opts = options || {};
            fill();
            bootstrap.Modal.getOrCreateInstance(modal).show();
        }
    };
})();
