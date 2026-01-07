/* ===== monitor.js =====
   Monitor por stream que hace HEAD sobre /hls/<stream>.m3u8 y actualiza
   window.streamingManager.setState(stream, state).
   Expone window.streamingMonitor:
     - start(streamName)
     - stop(streamName)
     - startAll(arrayOfNames)
   ==================== */
(function () {
  const handles = {}; // name -> { intervalId, lastSeen }
  const DEFAULT_CHECK_MS = window.__CHECK_INTERVAL_MS__ || 2000;
  const STALLED_GRACE_MS = 4000;

  function safeFetchHead(url) {
    return fetch(url, { method: 'HEAD', cache: 'no-store' })
      .then(r => r.ok)
      .catch(() => false);
  }

  function hlsUrlFor(name) {
    // Asume /hls/<name>.m3u8
    return `${location.origin}/hls/${encodeURIComponent(name)}.m3u8`;
  }

  function tick(name) {
    const url = hlsUrlFor(name);
    safeFetchHead(url).then(ok => {
      const now = Date.now();
      if (!handles[name]) { handles[name] = { lastSeen: 0 }; }
      if (ok) {
        handles[name].lastSeen = now;
        window.streamingManager.setState(name, window.streamingManager.State.LIVE);
        return;
      }
      // si no ok: si vimos stream hace poco => STALLED
      const last = handles[name].lastSeen || 0;
      if (last && (now - last) < STALLED_GRACE_MS) {
        window.streamingManager.setState(name, window.streamingManager.State.STALLED);
      } else {
        window.streamingManager.setState(name, window.streamingManager.State.WAITING);
      }
    }).catch(() => {
      window.streamingManager.setState(name, window.streamingManager.State.WAITING);
    });
  }

  function start(name, intervalMs) {
    if (!name) return;
    if (!handles[name]) handles[name] = {};
    // evita múltiples timers
    if (handles[name].intervalId) return;
    // init stream in manager
    if (window.streamingManager && typeof window.streamingManager.init === 'function') {
      window.streamingManager.init(name);
    }
    // tick now
    tick(name);
    const ms = intervalMs || DEFAULT_CHECK_MS;
    handles[name].intervalId = setInterval(() => tick(name), ms);
  }

  function stop(name) {
    if (!handles[name]) return;
    if (handles[name].intervalId) {
      clearInterval(handles[name].intervalId);
      handles[name].intervalId = null;
    }
    if (window.streamingManager && typeof window.streamingManager.setState === 'function') {
      window.streamingManager.setState(name, window.streamingManager.State.IDLE);
    }
  }

  function startAll(names, ms) {
    if (!Array.isArray(names)) return;
    names.forEach(n => start(n, ms));
  }

  function stopAll() {
    Object.keys(handles).forEach(k => stop(k));
  }

  window.streamingMonitor = {
    start,
    stop,
    startAll,
    stopAll,
    _internal: handles
  };
})();
