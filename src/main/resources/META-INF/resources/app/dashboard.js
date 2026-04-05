(function () {
    var grid = document.getElementById('session-grid');
    var dialog = document.getElementById('new-session-dialog');
    var form = document.getElementById('new-session-form');
    var nameInput = form.querySelector('[name="name"]');
    var nameError = document.getElementById('name-error');
    var overwriteBtn = document.getElementById('overwrite-btn');
    var sessions = [];

    function timeAgo(iso) {
        var diff = Date.now() - new Date(iso).getTime();
        var m = Math.floor(diff / 60000);
        if (m < 1) return 'just now';
        if (m < 60) return m + 'm ago';
        var h = Math.floor(m / 60);
        if (h < 24) return h + 'h ago';
        return Math.floor(h / 24) + 'd ago';
    }

    function displayName(name) {
        return name.replace(/^remotecc-/, '');
    }

    function renderCard(s) {
        var card = document.createElement('div');
        card.className = 'session-card';
        var name = displayName(s.name);
        var status = s.status.toLowerCase();
        var openUrl = '/app/session.html?id=' + s.id + '&name=' + encodeURIComponent(name);

        card.innerHTML =
            '<div class="card-header">' +
                '<span class="card-name">' + name + '</span>' +
                '<span class="badge ' + status + '">' + status + '</span>' +
            '</div>' +
            '<div class="card-dir">' + s.workingDir + '</div>' +
            '<div class="card-meta">Active ' + timeAgo(s.lastActive) + '</div>' +
            '<div class="card-actions">' +
                '<button class="open-btn">Open Terminal</button>' +
                '<button class="danger delete-btn">Delete</button>' +
            '</div>';

        card.querySelector('.open-btn').addEventListener('click', function (e) {
            e.stopPropagation();
            window.location.href = openUrl;
        });
        card.querySelector('.delete-btn').addEventListener('click', function (e) {
            e.stopPropagation();
            if (!confirm('Delete session "' + name + '"?')) return;
            fetch('/api/sessions/' + s.id, { method: 'DELETE' }).then(function (r) { requireAuth(r); loadSessions(); });
        });
        card.addEventListener('click', function () { window.location.href = openUrl; });
        return card;
    }

    function requireAuth(r) {
        if (r.status === 401) { showAuthDialog(); return false; }
        return true;
    }

    function showAuthDialog() {
        if (document.getElementById('auth-overlay')) return; // already shown
        var overlay = document.createElement('div');
        overlay.id = 'auth-overlay';
        overlay.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,0.85);display:flex;align-items:center;justify-content:center;z-index:9999';
        overlay.innerHTML =
            '<div style="background:#2a2a2a;border-radius:12px;padding:2rem;text-align:center;width:300px;box-shadow:0 8px 32px rgba(0,0,0,0.5)">' +
                '<h2 style="color:#fff;margin:0 0 0.5rem;font-size:1.2rem">Not authenticated</h2>' +
                '<p style="color:#aaa;margin:0 0 1.5rem;font-size:0.9rem">Dev mode: click below to log in instantly.<br>Production: use passkey login.</p>' +
                '<button id="dev-login-btn" style="width:100%;padding:0.75rem;margin-bottom:0.75rem;background:#4c9aff;color:#fff;border:none;border-radius:6px;font-size:1rem;cursor:pointer;font-weight:600">Quick Dev Login</button>' +
                '<button onclick="window.location.href=\'/auth/login\'" style="width:100%;padding:0.6rem;background:transparent;color:#888;border:1px solid #444;border-radius:6px;font-size:0.9rem;cursor:pointer">Sign in with Passkey</button>' +
            '</div>';
        document.body.appendChild(overlay);
        document.getElementById('dev-login-btn').addEventListener('click', function () {
            fetch('/auth/dev-login', { method: 'POST' }).then(function (r) {
                if (r.ok) {
                    document.body.removeChild(overlay);
                    loadSessions();
                } else if (r.status === 404) {
                    // Not in dev mode — fall back to passkey login
                    window.location.href = '/auth/login';
                }
            });
        });
    }

    function validateName(value) {
        var exists = sessions.some(function (s) { return displayName(s.name) === value.trim(); });
        nameError.textContent = exists ? 'A session named \u201c' + value.trim() + '\u201d already exists' : '';
        nameError.style.display = exists ? 'block' : 'none';
        overwriteBtn.style.display = exists ? 'inline-block' : 'none';
    }

    nameInput.addEventListener('input', function () { validateName(nameInput.value); });

    document.getElementById('new-session-btn').addEventListener('click', function () {
        form.reset();
        nameError.style.display = 'none';
        overwriteBtn.style.display = 'none';
        dialog.showModal();
    });

    function loadSessions() {
        fetch('/api/sessions').then(function (r) {
            if (!requireAuth(r)) return null;
            return r.json();
        }).then(function (data) {
            if (!data) return;
            sessions = data;
            grid.innerHTML = '';
            if (sessions.length === 0) {
                grid.innerHTML =
                    '<div class="empty-state">' +
                        '<p>No active sessions</p>' +
                        '<button onclick="document.getElementById(\'new-session-btn\').click()">+ Create your first session</button>' +
                    '</div>';
            } else {
                sessions.forEach(function (s) { grid.appendChild(renderCard(s)); });
            }
        });
    }

    loadSessions();
    setInterval(loadSessions, 5000);

    document.getElementById('cancel-btn').addEventListener('click', function () { dialog.close(); });

    function submitSession(overwrite) {
        var data = new FormData(form);
        var url = '/api/sessions' + (overwrite ? '?overwrite=true' : '');
        fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: data.get('name'), workingDir: data.get('workingDir') })
        }).then(function (r) {
            if (!requireAuth(r)) return null;
            return r.json();
        }).then(function (s) {
            if (!s) return;
            dialog.close();
            form.reset();
            loadSessions();
        });
    }

    form.addEventListener('submit', function (e) { e.preventDefault(); submitSession(false); });
    overwriteBtn.addEventListener('click', function () { submitSession(true); });
})();
