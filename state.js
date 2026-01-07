/* ===== state.js =====
   Manager de estados por stream (ID -> state)
   Expone window.streamingManager:
     - init(streamName)
     - subscribe(streamName, fn)
     - setState(streamName, state)
     - getState(streamName)
     - list()
   ==================== */
(function () {
  const State = {
    IDLE: 'idle',
    WAITING: 'waiting',
    LIVE: 'live',
    STALLED: 'stalled'
  };

  const streams = {}; // { name: { state, observers: [] } }

  function ensure(name) {
    if (!streams[name]) {
      streams[name] = { state: State.IDLE, observers: [] };
    }
    return streams[name];
  }

  function init(name) {
    ensure(name);
  }

  function subscribe(name, fn) {
    const s = ensure(name);
    if (typeof fn === 'function') {
      s.observers.push(fn);
      // enviar estado actual inmediatamente
      try { fn(s.state); } catch (e) { console.warn('observer error', e); }
    }
  }

  function setState(name, next) {
    const s = ensure(name);
    if (s.state === next) return;
    s.state = next;
    // notificar
    s.observers.forEach(fn => {
      try { fn(next); } catch (e) { console.warn('observer call failed', e); }
    });
  }

  function getState(name) {
    const s = ensure(name);
    return s.state;
  }

  function list() {
    return Object.keys(streams);
  }

  // Exponer API
  window.streamingManager = {
    State,
    init,
    subscribe,
    setState,
    getState,
    list
  };
})();

