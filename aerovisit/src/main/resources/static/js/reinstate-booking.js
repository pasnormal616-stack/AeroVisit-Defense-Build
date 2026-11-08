// ♻️ REINSTATE BOOKING dialog (Director, admin page). Opened by a row's "Reinstate booking" (data-reinstate-id); reads
// /admin/bookings/{id}/reinstate-options and fills #reinstateModal. Every text goes in with textContent (never as HTML).
// The server rechecks everything on submit (status, note, slot, seats, receipt).
(function () {
    var modal = document.getElementById('reinstateModal');
    if (!modal) return;
    var form = document.getElementById('reinstateForm');
    var $ = function (id) { return document.getElementById(id); };
    var dateSelect = $('reinstateNewDate');
    var windowSelect = $('reinstateNewWindow');
    var submit = $('reinstateSubmit');
    var data = null;

    function show(id, on) { $(id).classList.toggle('d-none', !on); }

    function option(select, value, label) {
        var o = document.createElement('option');
        o.value = value;
        o.textContent = label;
        select.appendChild(o);
    }

    function prettyDate(iso) {
        var d = new Date(iso + 'T00:00:00');
        return isNaN(d) ? iso : d.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' });
    }

    // The date the booking will be on: the original one, or the one picked
    function targetDate() {
        return data.originalAvailable ? data.date : dateSelect.value;
    }

    function fillWindows() {
        windowSelect.innerHTML = '';
        option(windowSelect, '', 'Choose a time window');
        data.openSlots.filter(function (s) { return s.date === dateSelect.value; }).forEach(function (s) {
            option(windowSelect, s.timeWindow, s.timeWindow + ' · ' + s.remaining + ' seats left');
        });
        windowSelect.disabled = !dateSelect.value;
    }

    function refresh() {
        if (!data) return;
        var date = targetDate();
        show('reinstateDuplicate', !!date && data.otherActiveDates.indexOf(date) >= 0);
        var note = '';
        if (date) {
            if (data.walkIn && date === data.today) note = 'Walk-in: no QR code. It appears on Walk-ins Today, ready to check in until its time window ends.';
            else if (data.walkIn) note = 'Moved to a later date, this walk-in becomes an advance booking: the visitor gets the QR code by email.';
            else note = 'The visitor gets the Entry QR Code by email, exactly like a normal approval.';
        }
        $('reinstateWalkInNote').textContent = note;
        show('reinstateWalkInNote', !!note);
        submit.disabled = !(data.originalAvailable || (dateSelect.value && windowSelect.value));
    }

    function fail(message) {
        show('reinstateLoading', false);
        show('reinstateBody', false);
        $('reinstateError').textContent = message;
        show('reinstateError', true);
    }

    modal.addEventListener('show.bs.modal', function (event) {
        var button = event.relatedTarget;
        var id = button && button.getAttribute('data-reinstate-id');
        data = null;
        submit.disabled = true;
        show('reinstateLoading', true);
        show('reinstateBody', false);
        show('reinstateError', false);
        form.reset();
        if (!id || !/^\d+$/.test(id)) { fail('Booking not found.'); return; }
        form.setAttribute('action', '/admin/bookings/' + id + '/reinstate');
        fetch('/admin/bookings/' + id + '/reinstate-options', { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
            .then(function (r) { return r.json().then(function (body) { return { ok: r.ok, body: body }; }); })
            .then(function (res) {
                if (!res.ok) { fail(res.body && res.body.error ? res.body.error : 'This booking cannot be reinstated.'); return; }
                data = res.body;
                $('reinstateName').textContent = data.name || '';
                $('reinstateEmail').textContent = data.email || '';
                $('reinstateDate').textContent = prettyDate(data.date);
                $('reinstateWindow').textContent = data.timeWindow || '';
                $('reinstateBreakdown').textContent = data.breakdown || (data.groupSize + ' visitor(s)');
                $('reinstateAmount').textContent = data.amount || '';
                $('reinstateBilling').textContent = data.billingNumber || '';
                show('reinstateOriginalOk', data.originalAvailable);
                show('reinstateMove', !data.originalAvailable);
                // Disabled selects are not sent, so an available original slot is what the server uses
                dateSelect.disabled = data.originalAvailable;
                windowSelect.disabled = true;
                if (!data.originalAvailable) {
                    $('reinstateProblem').textContent = data.originalProblem || 'is not available';
                    dateSelect.innerHTML = '';
                    option(dateSelect, '', 'Choose a date');
                    var seen = {};
                    data.openSlots.forEach(function (s) {
                        if (seen[s.date]) return;
                        seen[s.date] = true;
                        option(dateSelect, s.date, prettyDate(s.date));
                    });
                    show('reinstateNoSlots', data.openSlots.length === 0);
                    fillWindows();
                }
                show('reinstateLoading', false);
                show('reinstateBody', true);
                refresh();
            })
            .catch(function () { fail('The booking could not be loaded. Please try again.'); });
    });

    dateSelect.addEventListener('change', function () { fillWindows(); refresh(); });
    windowSelect.addEventListener('change', refresh);
    // One submit only: a second click can't send the form again (the server would refuse it anyway)
    form.addEventListener('submit', function () { submit.disabled = true; });
})();
