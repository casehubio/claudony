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
})();
