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

    var isLocalhost = (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1');

    function prStatusHtml(data) {
        if (!data.gitRepo) return '<span class="git-info git-none">not a git repo</span>';
        if (!data.githubRepo) return '<span class="git-info git-none">' + (data.branch || '') + ' · no GitHub remote</span>';
        var html = '<span class="git-branch">' + data.branch + '</span>';
        if (data.error) return html + ' <span class="git-error">⚠ ' + data.error + '</span>';
        if (!data.pr) return html + ' <span class="git-info">no open PR</span>';
        var pr = data.pr;
        var stateClass = pr.state === 'OPEN' ? 'pr-open' : pr.state === 'MERGED' ? 'pr-merged' : 'pr-closed';
        var checks = '';
        if (pr.checksTotal > 0) {
            checks = ' <span class="ci-checks">';
            if (pr.checksPassed > 0) checks += '<span class="ci-pass">✓' + pr.checksPassed + '</span>';
            if (pr.checksPending > 0) checks += '<span class="ci-pending">⟳' + pr.checksPending + '</span>';
            if (pr.checksFailed > 0) checks += '<span class="ci-fail">✗' + pr.checksFailed + '</span>';
            checks += '</span>';
        }
        html += ' <a class="pr-link ' + stateClass + '" href="' + pr.url + '" target="_blank" onclick="event.stopPropagation()">#' + pr.number + ' ' + pr.title + '</a>' + checks;
        return html;
    }

    function renderCard(s) {
        var card = document.createElement('div');
        card.className = 'session-card' + (s.stale ? ' stale' : '');

        var name = displayName(s.name);
        var status = s.status.toLowerCase();

        // Determine open URL based on whether this is a local or remote session
        var openUrl;
        if (!s.instanceUrl) {
            openUrl = '/app/session.html?id=' + s.id + '&name=' + encodeURIComponent(name);
        } else {
            var peerInfo = peerTerminalModes[s.instanceUrl];
            if (peerInfo && peerInfo.terminalMode === 'PROXY') {
                // PROXY: browser connects via local proxy WebSocket
                // Note: resize silently fails for remote sessions (known Phase 2 limitation)
                openUrl = '/app/session.html?id=' + s.id
                    + '&name=' + encodeURIComponent(name)
                    + '&proxyPeer=' + encodeURIComponent(peerInfo.id);
            } else {
                // DIRECT: navigate browser to the remote Claudony instance
                openUrl = s.instanceUrl + '/app/session.html?id=' + s.id
                    + '&name=' + encodeURIComponent(name);
            }
        }

        var instanceBadge = s.instanceUrl
            ? '<span class="instance-badge">' + (s.instanceName || s.instanceUrl) + '</span>'
            : '';

        var staleBadge = s.stale
            ? '<div class="stale-badge">⏰ last seen ' + timeAgo(s.lastActive) + '</div>'
            : '';

        var itermBtn = (isLocalhost && !s.instanceUrl)
            ? '<button class="iterm-btn">Open in iTerm2</button>'
            : '';
        var hasWorkingDir = s.workingDir && s.workingDir !== 'unknown';
        var prBtn = (hasWorkingDir && !s.instanceUrl) ? '<button class="pr-btn">Check PR</button>' : '';

        card.innerHTML =
            '<div class="card-header">' +
                '<span class="card-name">' + name + '</span>' +
                '<span class="badge ' + status + '">' + status + '</span>' +
                instanceBadge +
            '</div>' +
            staleBadge +
            '<div class="card-dir">' + s.workingDir + '</div>' +
            '<div class="card-meta">Active ' + timeAgo(s.lastActive) + '</div>' +
            (hasWorkingDir && !s.instanceUrl ? '<div class="card-git"></div>' : '') +
            (s.instanceUrl ? '' : '<div class="card-services"></div>') +
            '<div class="card-actions">' +
                '<button class="open-btn">Open Terminal</button>' +
                prBtn +
                (s.instanceUrl ? '' : '<button class="svc-btn">Check Services</button>') +
                itermBtn +
                (s.instanceUrl ? '' : '<button class="danger delete-btn">Delete</button>') +
            '</div>';

        card.querySelector('.open-btn').addEventListener('click', function (e) {
            e.stopPropagation();
            window.location.href = openUrl;
        });

        if (hasWorkingDir && !s.instanceUrl) {
            card.querySelector('.pr-btn').addEventListener('click', function (e) {
                e.stopPropagation();
                var btn = e.target;
                var gitDiv = card.querySelector('.card-git');
                btn.disabled = true;
                btn.textContent = '…';
                fetch('/api/sessions/' + s.id + '/git-status')
                    .then(function (r) { requireAuth(r); return r.json(); })
                    .then(function (data) {
                        gitDiv.innerHTML = prStatusHtml(data);
                        btn.disabled = false;
                        btn.textContent = 'Check PR';
                    })
                    .catch(function () {
                        gitDiv.innerHTML = '<span class="git-error">⚠ fetch failed</span>';
                        btn.disabled = false;
                        btn.textContent = 'Check PR';
                    });
            });
        }

        if (!s.instanceUrl) {
            card.querySelector('.svc-btn').addEventListener('click', function (e) {
                e.stopPropagation();
                var btn = e.target;
                var svcDiv = card.querySelector('.card-services');
                btn.disabled = true;
                btn.textContent = '…';
                fetch('/api/sessions/' + s.id + '/service-health')
                    .then(function (r) { requireAuth(r); return r.json(); })
                    .then(function (ports) {
                        if (ports.length === 0) {
                            svcDiv.innerHTML = '<span class="svc-label">Services:</span><span class="svc-none">none detected</span>';
                        } else {
                            svcDiv.innerHTML = '<span class="svc-label">Services:</span>' + ports.map(function (p) {
                                return '<a class="svc-badge" href="http://localhost:' + p.port +
                                       '" target="_blank" onclick="event.stopPropagation()" title="port ' +
                                       p.port + ' responded in ' + p.responseMs + 'ms">● :' + p.port + '</a>';
                            }).join('');
                        }
                        btn.disabled = false;
                        btn.textContent = 'Check Services';
                    })
                    .catch(function () {
                        svcDiv.innerHTML = '<span class="svc-none">check failed</span>';
                        btn.disabled = false;
                        btn.textContent = 'Check Services';
                    });
            });
        }

        if (isLocalhost && !s.instanceUrl) {
            card.querySelector('.iterm-btn').addEventListener('click', function (e) {
                e.stopPropagation();
                fetch('/api/sessions/' + s.id + '/open-terminal', { method: 'POST' })
                    .then(function (r) {
                        requireAuth(r);
                        if (r.status === 503) alert('No terminal adapter available on this machine.');
                    });
            });
        }

        if (!s.instanceUrl) {
            card.querySelector('.delete-btn').addEventListener('click', function (e) {
                e.stopPropagation();
                if (!confirm('Delete session "' + name + '"?')) return;
                fetch('/api/sessions/' + s.id, { method: 'DELETE' })
                    .then(function (r) { requireAuth(r); loadSessions(); });
            });
        }

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

    // ─── Fleet panel ─────────────────────────────────────────────────────────

    var peerList = document.getElementById('peer-list');
    var addPeerDialog = document.getElementById('add-peer-dialog');
    var addPeerForm = document.getElementById('add-peer-form');

    // Map of peer URL → {id, terminalMode} for session card lookup
    var peerTerminalModes = {};

    function healthClass(health) {
        if (health === 'UP') return 'up';
        if (health === 'DOWN') return 'down';
        return 'unknown';
    }

    function circuitClass(state) {
        if (state === 'CLOSED') return 'closed';
        if (state === 'OPEN') return 'open';
        return 'half-open';
    }

    function circuitLabel(state) {
        if (state === 'HALF_OPEN') return 'half-open';
        return state.toLowerCase();
    }

    function renderPeer(p) {
        var card = document.createElement('div');
        card.className = 'peer-card';
        card.dataset.id = p.id;

        var lastSeen = p.lastSeen ? timeAgo(p.lastSeen) : 'never';
        var staleNote = p.health === 'DOWN' && p.sessionCount > 0
            ? '<span class="stale-badge">⏰ ' + lastSeen + '</span>'
            : '<span>' + lastSeen + '</span>';

        card.innerHTML =
            '<div class="peer-header">' +
                '<div class="health-dot ' + healthClass(p.health) + '"></div>' +
                '<span class="peer-name">' + (p.name || p.url) + '</span>' +
                '<span class="peer-source">' + p.source.toLowerCase() + '</span>' +
            '</div>' +
            '<div class="peer-url">' + p.url + '</div>' +
            '<div class="peer-meta">' +
                '<span class="circuit-label ' + circuitClass(p.circuitState) + '">' +
                    circuitLabel(p.circuitState) +
                '</span>' +
                staleNote +
            '</div>' +
            '<div class="peer-actions">' +
                '<button class="peer-ping-btn secondary">Ping</button>' +
                '<button class="peer-mode-btn secondary" title="Click to toggle">' +
                    p.terminalMode +
                '</button>' +
                (p.source !== 'CONFIG'
                    ? '<button class="peer-remove-btn danger">Remove</button>'
                    : '') +
            '</div>';

        card.querySelector('.peer-ping-btn').addEventListener('click', function (e) {
            e.stopPropagation();
            var btn = e.target;
            btn.disabled = true;
            btn.textContent = '…';
            fetch('/api/peers/' + p.id + '/ping', { method: 'POST' })
                .then(function (r) { requireAuth(r); })
                .finally(function () {
                    btn.disabled = false;
                    btn.textContent = 'Ping';
                    setTimeout(loadPeers, 2000);
                });
        });

        card.querySelector('.peer-mode-btn').addEventListener('click', function (e) {
            e.stopPropagation();
            var newMode = p.terminalMode === 'DIRECT' ? 'PROXY' : 'DIRECT';
            fetch('/api/peers/' + p.id, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ terminalMode: newMode })
            }).then(function (r) { requireAuth(r); loadPeers(); });
        });

        if (p.source !== 'CONFIG') {
            card.querySelector('.peer-remove-btn').addEventListener('click', function (e) {
                e.stopPropagation();
                if (!confirm('Remove peer "' + (p.name || p.url) + '"?')) return;
                fetch('/api/peers/' + p.id, { method: 'DELETE' })
                    .then(function (r) { requireAuth(r); loadPeers(); });
            });
        }

        return card;
    }

    function loadPeers() {
        fetch('/api/peers').then(function (r) {
            if (!requireAuth(r)) return null;
            return r.json();
        }).then(function (peers) {
            if (!peers) return;
            peerTerminalModes = {};
            peers.forEach(function (p) {
                peerTerminalModes[p.url] = { id: p.id, terminalMode: p.terminalMode };
            });

            peerList.innerHTML = '';
            if (peers.length === 0) {
                peerList.innerHTML = '<div class="peer-empty">No peers configured</div>';
            } else {
                peers.forEach(function (p) { peerList.appendChild(renderPeer(p)); });
            }
        });
    }

    loadPeers();
    setInterval(loadPeers, 10000);

    document.getElementById('add-peer-btn').addEventListener('click', function () {
        addPeerForm.reset();
        addPeerDialog.showModal();
    });

    document.getElementById('cancel-peer-btn').addEventListener('click', function () {
        addPeerDialog.close();
    });

    addPeerForm.addEventListener('submit', function (e) {
        e.preventDefault();
        var data = new FormData(addPeerForm);
        fetch('/api/peers', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                url: data.get('url'),
                name: data.get('name') || null,
                terminalMode: data.get('terminalMode')
            })
        }).then(function (r) {
            if (!requireAuth(r)) return;
            addPeerDialog.close();
            addPeerForm.reset();
            loadPeers();
        });
    });
})();
