(function () {
    var grid = document.getElementById('session-grid');
    var dialog = document.getElementById('new-session-dialog');
    var form = document.getElementById('new-session-form');

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
        if (r.status === 401) { window.location.href = '/auth/login'; return false; }
        return true;
    }

    function loadSessions() {
        fetch('/api/sessions').then(function (r) {
            if (!requireAuth(r)) return null;
            return r.json();
        }).then(function (sessions) {
            if (!sessions) return;
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

    document.getElementById('new-session-btn').addEventListener('click', function () { dialog.showModal(); });
    document.getElementById('cancel-btn').addEventListener('click', function () { dialog.close(); });

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        var data = new FormData(form);
        fetch('/api/sessions', {
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
    });
})();
