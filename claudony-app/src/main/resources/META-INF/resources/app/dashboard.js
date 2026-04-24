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
        return name.replace(/^claudony-/, '');
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

// ── Mesh Panel ───────────────────────────────────────────────────────────────

class MeshPanel {
    constructor() {
        this.panel = document.getElementById('mesh-panel');
        this.body = document.getElementById('mesh-body');
        this.expandBtn = document.getElementById('mesh-expand-btn');
        this.activeView = localStorage.getItem('mesh-view') || 'overview';
        this.collapsed = localStorage.getItem('mesh-collapsed') === 'true';
        this.data = { channels: [], instances: [], feed: [] };
        this.strategy = null;
        this._dockChannel  = null;
        this._dockType     = 'status';
        this._dockChannelEl  = document.getElementById('mesh-dock-channel');
        this._dockTypeEl     = document.getElementById('mesh-dock-type');
        this._dockTextareaEl = document.getElementById('mesh-dock-textarea');
        this._dockSendEl     = document.getElementById('mesh-dock-send');
        this._dockErrorEl    = document.getElementById('mesh-dock-error');
    }

    async init() {
        this._wireButtons();
        this._initDock();
        if (this.collapsed) this._applyCollapsed();
        this._renderActiveView(); // show empty state immediately
        try {
            const cfg = await fetch('/api/mesh/config').then(r => r.json());
            this.strategy = cfg.strategy === 'sse'
                ? new SseMeshStrategy('/api/mesh/events', this)
                : new PollingMeshStrategy('/api/mesh', cfg.interval, this);
            this.strategy.start();
        } catch (e) {
            // Server not available — panel shows empty state, no crash
        }
    }

    _wireButtons() {
        document.getElementById('mesh-collapse-btn')
            .addEventListener('click', () => this.collapse());
        document.getElementById('mesh-expand-btn')
            .addEventListener('click', () => this.expand());
        document.querySelectorAll('.mesh-view-btn').forEach(btn => {
            btn.addEventListener('click', () => this.switchView(btn.dataset.view));
        });
        this._updateViewBtns();
    }

    _updateViewBtns() {
        document.querySelectorAll('.mesh-view-btn').forEach(b => {
            b.classList.toggle('active', b.dataset.view === this.activeView);
        });
    }

    update(data) {
        this.data = data;
        this._updateDockChannels();
        this._renderActiveView();
    }

    switchView(name) {
        this.activeView = name;
        localStorage.setItem('mesh-view', name);
        this._updateViewBtns();
        this._renderActiveView();
    }

    _renderActiveView() {
        const views = { overview: OverviewView, channel: ChannelView, feed: FeedView };
        const view = views[this.activeView] || OverviewView;
        view.render(this.body, this.data, this);
    }

    collapse() {
        this.collapsed = true;
        localStorage.setItem('mesh-collapsed', 'true');
        this._applyCollapsed();
    }

    expand() {
        this.collapsed = false;
        localStorage.setItem('mesh-collapsed', 'false');
        this.panel.classList.remove('collapsed');
        this.expandBtn.style.display = 'none';
    }

    _applyCollapsed() {
        this.panel.classList.add('collapsed');
        this.expandBtn.style.display = '';
    }

    _initDock() {
        this._dockType = this._dockTypeEl.value;   // sync from DOM in case of browser session restore
        this._dockTypeEl.addEventListener('change', () => {
            this._dockType = this._dockTypeEl.value;
        });
        this._dockChannelEl.addEventListener('change', () => {
            this._dockChannel = this._dockChannelEl.value;
        });
        this._dockTextareaEl.addEventListener('keydown', e => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                this._send();
            }
        });
        this._dockSendEl.addEventListener('click', () => this._send());
    }

    _updateDockChannels() {
        const channels = this.data.channels || [];
        const hasChannels = channels.length > 0;

        this._dockChannelEl.disabled = !hasChannels;
        this._dockSendEl.disabled    = !hasChannels;

        if (!hasChannels) {
            this._dockChannelEl.innerHTML = '<option>— no channels —</option>';
            this._dockChannel = null;
            return;
        }

        // Sort by lastActivityAt descending (ISO-8601 strings sort lexicographically)
        const sorted = [...channels].sort((a, b) =>
            (b.lastActivityAt || '').localeCompare(a.lastActivityAt || ''));

        // Preserve selected channel if still present; else default to most recently active
        const names = sorted.map(c => c.name);
        if (!this._dockChannel || !names.includes(this._dockChannel)) {
            this._dockChannel = sorted[0].name;
        }

        this._dockChannelEl.innerHTML = sorted.map(c =>
            `<option value="${escapeHtml(c.name)}" ${c.name === this._dockChannel ? 'selected' : ''}>#${escapeHtml(c.name)}</option>`
        ).join('');
    }

    selectChannel(name) {
        this._dockChannel = name;
        if (this._dockChannelEl) {
            this._dockChannelEl.value = name;
        }
    }

    async _send() {
        const content = this._dockTextareaEl.value.trim();
        if (!content || !this._dockChannel) return;
        this._dockSendEl.disabled = true;
        try {
            const resp = await fetch(
                `/api/mesh/channels/${encodeURIComponent(this._dockChannel)}/messages`,
                {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ content, type: this._dockType }),
                }
            );
            if (!resp.ok) {
                const text = await resp.text();
                this._showDockError(text || `Send failed (${resp.status})`);
                return;
            }
            this._dockTextareaEl.value = '';
            this.strategy?.triggerPoll?.();
        } catch (e) {
            this._showDockError(e.message || 'Send failed');
        } finally {
            this._dockSendEl.disabled = false;
        }
    }

    _showDockError(msg) {
        this._dockErrorEl.textContent = msg;
        setTimeout(() => { this._dockErrorEl.textContent = ''; }, 4000);
    }
}

class PollingMeshStrategy {
    constructor(baseUrl, interval, panel) {
        this.baseUrl = baseUrl;
        this.interval = interval;
        this.panel = panel;
        this.timer = null;
    }

    start() {
        this._poll();
        this.timer = setInterval(() => this._poll(), this.interval);
    }

    stop() { clearInterval(this.timer); }

    triggerPoll() {
        clearInterval(this.timer);
        this._poll();
        this.timer = setInterval(() => this._poll(), this.interval);
    }

    async _poll() {
        try {
            const [channels, instances, feed] = await Promise.all([
                fetch(this.baseUrl + '/channels').then(r => r.json()),
                fetch(this.baseUrl + '/instances').then(r => r.json()),
                fetch(this.baseUrl + '/feed?limit=100').then(r => r.json()),
            ]);
            this.panel.update({ channels, instances, feed });
        } catch (e) {
            // Silently degrade — panel keeps showing last data
        }
    }
}

class SseMeshStrategy {
    constructor(url, panel) {
        this.url = url;
        this.panel = panel;
        this.source = null;
    }

    start() {
        this.source = new EventSource(this.url);
        this.source.addEventListener('mesh-update', e => {
            try { this.panel.update(JSON.parse(e.data)); } catch (_) {}
        });
    }

    stop() { this.source?.close(); }

    triggerPoll() { /* SSE is live — no action needed */ }
}

// ── HTML Escaping ────────────────────────────────────────────────────────────

function escapeHtml(str) {
    return String(str == null ? '' : str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#x27;');
}

// ── View Renderers ───────────────────────────────────────────────────────────

const OverviewView = {
    render(container, data, panel) {
        const { channels, instances, feed } = data;
        if (!channels.length && !instances.length) {
            container.innerHTML = '<div class="mesh-empty">No active channels</div>';
            return;
        }
        const presence = instances.length
            ? instances.map(i => `<span class="mesh-instance">${escapeHtml(i.instanceId)}</span>`).join('')
            : '<span class="mesh-dim">No agents online</span>';

        const channelItems = channels.length
            ? channels.map(ch => `
                <div class="mesh-channel-item" data-channel="${escapeHtml(ch.name)}">
                    <span class="mesh-channel-name">#${escapeHtml(ch.name)}</span>
                    <span class="mesh-channel-count">${escapeHtml(ch.messageCount)}</span>
                </div>`).join('')
            : '<div class="mesh-dim">No channels</div>';

        const recentMsgs = (feed || []).slice(0, 5).map(m =>
            `<div class="mesh-msg">
                <span class="mesh-sender">${escapeHtml(m.sender || m.agent_id || '?')}</span>
                <span class="mesh-content">${escapeHtml(String(m.content || '').substring(0, 60))}</span>
            </div>`
        ).join('');

        container.innerHTML = `
            <div class="mesh-section">
                <div class="mesh-label">ONLINE</div>
                <div class="mesh-presence">${presence}</div>
            </div>
            <div class="mesh-section">
                <div class="mesh-label">CHANNELS</div>
                ${channelItems}
            </div>
            ${recentMsgs ? `<div class="mesh-section"><div class="mesh-label">RECENT</div>${recentMsgs}</div>` : ''}
        `;
        container.querySelectorAll('.mesh-channel-item[data-channel]').forEach(el => {
            el.addEventListener('click', () => {
                panel.selectChannel(el.dataset.channel);
                panel.switchView('channel');
            });
        });
    }
};

const ChannelView = {
    _selected: null,
    render(container, data, panel) {
        const { channels, feed } = data;
        if (!channels.length) {
            container.innerHTML = '<div class="mesh-empty">No active channels</div>';
            return;
        }
        if (!this._selected || !channels.find(c => c.name === this._selected)) {
            this._selected = channels[0].name;
        }
        meshPanel.selectChannel(this._selected);
        const opts = channels.map(ch =>
            `<option value="${escapeHtml(ch.name)}" ${ch.name === this._selected ? 'selected' : ''}>#${escapeHtml(ch.name)}</option>`
        ).join('');
        const msgs = (feed || [])
            .filter(m => m.channel === this._selected)
            .map(m => `<div class="mesh-msg">
                <span class="mesh-sender">${escapeHtml(m.sender || m.agent_id || '?')}</span>
                <span class="mesh-content">${escapeHtml(String(m.content || '').substring(0, 80))}</span>
            </div>`).join('') || '<div class="mesh-dim">No messages</div>';
        container.innerHTML = `
            <select class="mesh-channel-select"
                onchange="ChannelView._selected=this.value; meshPanel.selectChannel(this.value); meshPanel._renderActiveView()">
                ${opts}
            </select>
            <div class="mesh-timeline">${msgs}</div>
        `;
    }
};

const FeedView = {
    render(container, data, panel) {
        const { feed, instances } = data;
        if (!feed || !feed.length) {
            container.innerHTML = '<div class="mesh-empty">No recent activity</div>';
            return;
        }
        const items = feed.slice(0, 50).map(m => `
            <div class="mesh-feed-item">
                <span class="mesh-dim">${escapeHtml(String(m.created_at || '').substring(11, 19))}</span>
                <span class="mesh-channel-tag" style="cursor:pointer" data-channel="${escapeHtml(m.channel || '')}">
                    #${escapeHtml(m.channel || '?')}
                </span>
                <span class="mesh-sender">${escapeHtml(m.sender || m.agent_id || '?')}</span>
                <span class="mesh-content">${escapeHtml(String(m.content || '').substring(0, 55))}</span>
            </div>`
        ).join('');
        const presenceFooter = instances?.length
            ? `<div class="mesh-presence-footer">${instances.map(i =>
                `<span class="mesh-instance">&#9679; ${escapeHtml(i.instanceId)}</span>`).join('')}</div>`
            : '';
        container.innerHTML = `<div class="mesh-feed">${items}</div>${presenceFooter}`;
        container.querySelectorAll('.mesh-channel-tag[data-channel]').forEach(el => {
            el.addEventListener('click', () => panel.selectChannel(el.dataset.channel));
        });
    }
};

// ── Initialise ───────────────────────────────────────────────────────────────
const meshPanel = new MeshPanel();
meshPanel.init();
