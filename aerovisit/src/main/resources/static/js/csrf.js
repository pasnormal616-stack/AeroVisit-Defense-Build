// 🛡️ CSRF helper for fetch() calls. The page prints the session's token into <meta name="_csrf"> and the header name
// (X-CSRF-TOKEN) into <meta name="_csrf_header">. AeroCsrf.headers({...}) returns those fetch headers plus the token
// header; every POST / PUT / PATCH / DELETE sent with fetch must use it, or Spring Security answers 403.
(function () {
    function meta(name) {
        var el = document.querySelector('meta[name="' + name + '"]');
        return el ? el.getAttribute('content') : null;
    }

    window.AeroCsrf = {
        headers: function (extra) {
            var headers = {};
            Object.keys(extra || {}).forEach(function (key) { headers[key] = extra[key]; });
            var token = meta('_csrf');
            var header = meta('_csrf_header');
            if (token && header) headers[header] = token;
            return headers;
        }
    };
})();
