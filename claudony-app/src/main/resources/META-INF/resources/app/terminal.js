(function () {
    var params = new URLSearchParams(window.location.search);
    var sessionId = params.get('id');
    var sessionName = params.get('name') || 'Session';

    if (!sessionId) { window.location.href = '/app/'; return; }

    document.getElementById('session-name').textContent = sessionName;
    document.title = sessionName + ' \u2014 RemoteCC';

    // Show key bar on touch devices
    if ('ontouchstart' in window || navigator.maxTouchPoints > 0) {
        document.getElementById('key-bar').classList.remove('hidden');
    }

    var terminal = new Terminal({
        cursorBlink: true,
        fontSize: 14,
        fontFamily: 'Menlo, Monaco, "Courier New", monospace',
        scrollback: 5000,
        theme: {
            background: '#1e1e1e',
            foreground: '#d4d4d4',
            cursor: '#aeafad',
            selectionBackground: 'rgba(255,255,255,0.2)'
        }
    });

    var fitAddon = new FitAddon.FitAddon();
    terminal.loadAddon(fitAddon);
    terminal.open(document.getElementById('terminal-container'));
    fitAddon.fit();
    // Expose for E2E testing: allows tests to trigger onResize with known dimensions
    if (window.__CLAUDONY_TEST_MODE__) {
        window._xtermTerminal = terminal;
    }

    var resizeObserver = new ResizeObserver(function () { fitAddon.fit(); });
    resizeObserver.observe(document.getElementById('terminal-container'));

    terminal.onResize(function (size) {
        var proxyPeer = params.get('proxyPeer');
        var resizeUrl = proxyPeer
            ? '/api/peers/' + proxyPeer + '/sessions/' + sessionId
              + '/resize?cols=' + size.cols + '&rows=' + size.rows
            : '/api/sessions/' + sessionId + '/resize?cols=' + size.cols + '&rows=' + size.rows;
        fetch(resizeUrl, { method: 'POST' }).catch(function () {});
    });

    var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    var ws, attachAddon;
    var reconnectDelay = 1000;
    var reconnectTimer;

    function updateStatus(text, cssClass) {
        var badge = document.getElementById('status-badge');
        badge.textContent = text;
        badge.className = 'badge ' + cssClass;
    }

    function connect() {
        // Include current terminal dimensions in the URL so the server can
        // resize the tmux pane BEFORE capturing history, causing TUI apps to
        // redraw first so capture-pane gets the fresh state (not stale).
        var proxyPeer = params.get('proxyPeer');
        var wsUrl = proxyPeer
            ? proto + '//' + location.host + '/ws/proxy/' + proxyPeer
              + '/' + sessionId + '/' + terminal.cols + '/' + terminal.rows
            : proto + '//' + location.host + '/ws/' + sessionId
              + '/' + terminal.cols + '/' + terminal.rows;
        ws = new WebSocket(wsUrl);

        ws.onopen = function () {
            reconnectDelay = 1000;
            clearTimeout(reconnectTimer);
            if (attachAddon) attachAddon.dispose();
            attachAddon = new AttachAddon.AttachAddon(ws);
            terminal.loadAddon(attachAddon);
            updateStatus('connected', 'active');
            terminal.focus();
        };

        ws.onclose = function () {
            updateStatus('reconnecting\u2026', 'idle');
            reconnectTimer = setTimeout(function () {
                reconnectDelay = Math.min(reconnectDelay * 2, 10000);
                connect();
            }, reconnectDelay);
        };

        ws.onerror = function () { ws.close(); };
    }

    connect();

    document.querySelectorAll('#key-bar button[data-code]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(btn.dataset.code);
            }
            terminal.focus();
        });
    });

    // Compose overlay
    var overlay = document.getElementById('compose-overlay');
    var textarea = document.getElementById('compose-textarea');

    function openCompose() {
        overlay.classList.remove('hidden');
        textarea.focus();
        textarea.select();
    }

    function closeCompose() {
        overlay.classList.add('hidden');
        terminal.focus();
    }

    function sendCompose() {
        var text = textarea.value;
        if (!text) { closeCompose(); return; }
        textarea.value = '';
        closeCompose();
        terminal.paste(text);
    }

    document.getElementById('compose-btn').addEventListener('click', openCompose);
    document.getElementById('compose-key-btn').addEventListener('click', openCompose);
    document.getElementById('compose-send-btn').addEventListener('click', sendCompose);
    document.getElementById('compose-cancel-btn').addEventListener('click', closeCompose);

    textarea.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); sendCompose(); }
        if (e.key === 'Escape') { e.preventDefault(); closeCompose(); }
    });

    // Ctrl+G anywhere on the terminal page opens compose
    document.addEventListener('keydown', function (e) {
        if (e.ctrlKey && e.key === 'g' && overlay.classList.contains('hidden')) {
            e.preventDefault();
            openCompose();
        }
    });

    overlay.addEventListener('click', function (e) {
        if (e.target === overlay) closeCompose();
    });

    // ── Channel panel ─────────────────────────────────────────────────────────

    var chPanel      = document.getElementById('channel-panel');
    var chSelect     = document.getElementById('ch-select');
    var chFeed       = document.getElementById('ch-feed');
    var chInput      = document.getElementById('ch-input');
    var chTypeSelect = document.getElementById('ch-type-select');
    var chSendBtn    = document.getElementById('ch-send-btn');
    var chError      = document.getElementById('ch-error');
    var chToggleBtn  = document.getElementById('ch-toggle-btn');
    var chCloseBtn   = document.getElementById('ch-close-btn');

    var chSelectedName = null;
    var chLastId       = 0;
    var chPollTimer    = null;
    var POLL_MS        = 3000;

    // Normative type badge classes (Layer 1 — illocutionary act)
    var MSG_BADGE_LABELS = {
        query:    'QUERY',
        command:  'COMMAND',
        response: 'RESPONSE',
        status:   'STATUS',
        decline:  'DECLINE',
        handoff:  'HANDOFF',
        done:     'DONE',
        failure:  'FAILURE',
        event:    'EVENT'
    };

    // Terminal types (obligation discharged/cancelled — mark visually distinct)
    var TERMINAL_TYPES = { decline: 1, handoff: 1, done: 1, failure: 1 };

    function escHtml(s) {
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function formatTime(iso) {
        if (!iso) return '';
        try {
            var d = new Date(iso);
            return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
        } catch (e) { return ''; }
    }

    function renderMessage(entry) {
        var el = document.createElement('div');
        el.className = 'ch-msg';

        if (entry.type === 'EVENT') {
            el.classList.add('ch-msg-event');
            var agentId = escHtml(entry.agent_id || 'system');
            var toolName = entry.tool_name ? ' · ' + escHtml(entry.tool_name) : '';
            el.innerHTML =
                '<div class="ch-msg-meta">' +
                    '<span class="msg-badge msg-event">EVENT</span>' +
                    '<span class="ch-msg-sender ch-sender-agent">' + agentId + '</span>' +
                    '<span class="ch-msg-time">' + formatTime(entry.created_at) + '</span>' +
                '</div>' +
                '<div class="ch-msg-content">' + escHtml(entry.content || toolName || '') + '</div>';
        } else {
            var mtype = (entry.message_type || 'unknown').toLowerCase();
            var label = MSG_BADGE_LABELS[mtype] || mtype.toUpperCase();
            var sender = entry.sender || '';
            var isHuman = sender === 'human' || sender.indexOf('human') === 0;
            var isTerminal = TERMINAL_TYPES[mtype];

            if (isHuman)   el.classList.add('ch-msg-human');
            if (isTerminal) el.classList.add('ch-msg-terminal');

            el.innerHTML =
                '<div class="ch-msg-meta">' +
                    '<span class="msg-badge msg-' + escHtml(mtype) + '">' + label + '</span>' +
                    '<span class="ch-msg-sender ' + (isHuman ? 'ch-sender-human' : 'ch-sender-agent') + '">' +
                        escHtml(sender) +
                    '</span>' +
                    '<span class="ch-msg-time">' + formatTime(entry.created_at) + '</span>' +
                '</div>' +
                '<div class="ch-msg-content">' + escHtml(entry.content || '') + '</div>';
        }
        return el;
    }

    function appendMessages(entries) {
        var wasAtBottom = chFeed.scrollHeight - chFeed.scrollTop <= chFeed.clientHeight + 4;
        entries.forEach(function (entry) {
            chFeed.appendChild(renderMessage(entry));
            if (entry.id && entry.id > chLastId) chLastId = entry.id;
        });
        if (wasAtBottom) chFeed.scrollTop = chFeed.scrollHeight;
    }

    function pollChannel() {
        if (!chSelectedName) return;
        var url = '/api/mesh/channels/' + encodeURIComponent(chSelectedName) +
                  '/timeline?limit=50' + (chLastId ? '&after=' + chLastId : '');
        fetch(url).then(function (r) {
            if (!r.ok) return;
            return r.json();
        }).then(function (entries) {
            if (entries && entries.length) appendMessages(entries);
        }).catch(function () {});
        chPollTimer = setTimeout(pollChannel, POLL_MS);
    }

    function selectChannel(name) {
        clearTimeout(chPollTimer);
        chSelectedName = name || null;
        chLastId = 0;
        chFeed.innerHTML = '';
        chError.textContent = '';
        chSendBtn.disabled = !name;

        if (!name) return;

        // Load initial history then start polling
        var url = '/api/mesh/channels/' + encodeURIComponent(name) + '/timeline?limit=100';
        fetch(url).then(function (r) { return r.json(); }).then(function (entries) {
            if (entries && entries.length) {
                appendMessages(entries);
            } else {
                var empty = document.createElement('div');
                empty.className = 'ch-empty';
                empty.textContent = 'No messages yet.';
                chFeed.appendChild(empty);
            }
        }).catch(function () {}).finally(function () {
            chPollTimer = setTimeout(pollChannel, POLL_MS);
        });
    }

    function loadChannels() {
        fetch('/api/mesh/channels').then(function (r) { return r.json(); }).then(function (channels) {
            // Prefer channels matching the session name pattern
            channels.sort(function (a, b) { return a.name.localeCompare(b.name); });
            channels.forEach(function (ch) {
                var opt = document.createElement('option');
                opt.value = ch.name;
                opt.textContent = ch.name + (ch.message_count ? ' (' + ch.message_count + ')' : '');
                chSelect.appendChild(opt);
            });
            // Auto-select if channel name passed in URL
            var preselect = params.get('channel');
            if (preselect) {
                chSelect.value = preselect;
                selectChannel(preselect);
                openPanel();
            }
        }).catch(function () {});
    }

    function openPanel() {
        chPanel.classList.remove('collapsed');
        loadChannels();
    }

    function closePanel() {
        chPanel.classList.add('collapsed');
        clearTimeout(chPollTimer);
    }

    chToggleBtn.addEventListener('click', function () {
        if (chPanel.classList.contains('collapsed')) {
            openPanel();
        } else {
            closePanel();
        }
    });

    chCloseBtn.addEventListener('click', closePanel);

    chSelect.addEventListener('change', function () {
        selectChannel(chSelect.value || null);
    });

    chInput.addEventListener('input', function () {
        chSendBtn.disabled = !chSelectedName || !chInput.value.trim();
    });

    chInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
            e.preventDefault();
            chSendBtn.click();
        }
    });

    chSendBtn.addEventListener('click', function () {
        var content = chInput.value.trim();
        var type = chTypeSelect.value;
        if (!content || !chSelectedName) return;

        chSendBtn.disabled = true;
        chError.textContent = '';

        fetch('/api/mesh/channels/' + encodeURIComponent(chSelectedName) + '/messages', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ content: content, type: type })
        }).then(function (r) {
            if (!r.ok) return r.text().then(function (t) { throw new Error(t || r.status); });
            chInput.value = '';
            chSendBtn.disabled = true;
        }).catch(function (err) {
            chError.textContent = err.message || 'Send failed.';
            chSendBtn.disabled = false;
        });
    });

    // Ctrl+K toggles the channel panel
    document.addEventListener('keydown', function (e) {
        if (e.ctrlKey && e.key === 'k') {
            e.preventDefault();
            chToggleBtn.click();
        }
    });

    // ── Case worker panel ─────────────────────────────────────────────────────

    var casePanel        = document.getElementById('case-panel');
    var caseWorkerList   = document.getElementById('case-worker-list');
    var caseCloseBtn     = document.getElementById('case-close-btn');
    var workersToggleBtn = document.getElementById('workers-toggle-btn');
    var activeCaseId     = null;
    var casePoller;

    function openCasePanel() { casePanel.classList.remove('collapsed'); }
    function closeCasePanel() { casePanel.classList.add('collapsed'); }

    workersToggleBtn.addEventListener('click', function () {
        if (casePanel.classList.contains('collapsed')) openCasePanel();
        else closeCasePanel();
    });
    caseCloseBtn.addEventListener('click', closeCasePanel);

    function workerTimeAgo(iso) {
        var diff = Date.now() - new Date(iso).getTime();
        var m = Math.floor(diff / 60000);
        if (m < 1) return 'now';
        if (m < 60) return m + 'm';
        return Math.floor(m / 60) + 'h';
    }

    function workerDisplayName(w) {
        return w.roleName || w.name.replace(/^claudony-worker-/, '').replace(/^claudony-/, '');
    }

    function renderWorkers(workers) {
        caseWorkerList.innerHTML = '';
        if (!workers || workers.length === 0) {
            var ph = document.createElement('div');
            ph.className = 'case-panel-placeholder';
            ph.textContent = 'No workers found.';
            caseWorkerList.appendChild(ph);
            return;
        }
        workers.forEach(function (w) {
            var row = document.createElement('div');
            var status = (w.status || 'idle').toLowerCase();
            row.className = 'case-worker-row' + (w.id === sessionId ? ' active-worker' : '');
            row.innerHTML =
                '<span class="worker-status-dot ' + status + '"></span>' +
                '<span class="case-worker-name">' + workerDisplayName(w) + '</span>' +
                '<span class="case-worker-time">' + workerTimeAgo(w.createdAt) + '</span>';
            row.addEventListener('click', function () {
                if (w.id === sessionId) return;
                switchToWorker(w.id, workerDisplayName(w));
            });
            caseWorkerList.appendChild(row);
        });
    }

    function pollWorkers() {
        if (!activeCaseId) return;
        fetch('/api/sessions?caseId=' + encodeURIComponent(activeCaseId))
            .then(function (r) { return r.ok ? r.json() : []; })
            .then(renderWorkers)
            .catch(function () {});
    }

    function switchToWorker(newSessionId, newName) {
        sessionId = newSessionId;
        sessionName = newName;
        document.getElementById('session-name').textContent = newName;
        history.replaceState(null, '',
            '?id=' + newSessionId + '&name=' + encodeURIComponent(newName));
        clearTimeout(reconnectTimer);
        if (ws) { ws.close(); } else { connect(); }
    }

    function showCasePlaceholder(text) {
        var ph = document.createElement('div');
        ph.className = 'case-panel-placeholder';
        ph.textContent = text;
        caseWorkerList.appendChild(ph);
    }

    // Fetch current session to get caseId, then start panel
    fetch('/api/sessions/' + sessionId)
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (session) {
            if (session && session.caseId) {
                activeCaseId = session.caseId;
                openCasePanel();
                pollWorkers();
                casePoller = setInterval(pollWorkers, 3000);
            } else {
                showCasePlaceholder('No case assigned.');
            }
        })
        .catch(function () {
            showCasePlaceholder('No case assigned.');
        });
})();
