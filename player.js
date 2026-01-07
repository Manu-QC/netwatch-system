/* ===== player.js =====
   Crea una "card" de stream y maneja reproducción HLS por instancia.
   OPTIMIZADO PARA BAJA LATENCIA
   ==================== */
(function () {
  // sanitiza para id
  function safeId(name) {
    return 'stream-' + String(name).replace(/[^a-zA-Z0-9_-]/g, '_');
  }

  // crea el DOM de una card de stream (video + meta)
  function makeCard(name) {
    const card = document.createElement('div');
    card.className = 'stream-card glass-card';
    card.dataset.stream = name;

    const title = document.createElement('span');
    title.className = 'stream-title';
    title.textContent = name;
    card.appendChild(title);

    const meta = document.createElement('div');
    meta.className = 'stream-meta';
    meta.innerHTML = `<span class="meta-state">--</span><span class="meta-fps">FPS: --</span>`;
    card.appendChild(meta);

    const videoWrap = document.createElement('div');
    videoWrap.style.width = '100%';
    videoWrap.style.position = 'relative';

    const video = document.createElement('video');
    video.id = safeId(name);
    video.className = 'video-player';
    video.setAttribute('playsinline', '');
    video.setAttribute('muted', '');
    video.setAttribute('controls', '');
    videoWrap.appendChild(video);

    const overlay = document.createElement('div');
    overlay.className = 'video-overlay';
    overlay.innerHTML = `<h3 class="overlay-title">Sin señal</h3><p class="overlay-text">Esperando conexión de la cámara...</p>`;
    videoWrap.appendChild(overlay);

    card.appendChild(videoWrap);

    return { card, video, overlay, meta };
  }

  // start player for a given card when state is 'live'
  function bindCard(name, cardObj) {
    const { card, video, overlay, meta } = cardObj;
    const fpsEl = meta.querySelector('.meta-fps');
    const stateEl = meta.querySelector('.meta-state');
    let hls = null;
    let statsInterval = null;

    function startPlayback() {
      if (hls) return;
      const url = `${location.origin}/hls/${encodeURIComponent(name)}.m3u8`;

      // Prefer hls.js
      if (window.Hls && Hls.isSupported()) {
        // CONFIGURACIÓN DE BAJA LATENCIA
        hls = new Hls({
          lowLatencyMode: true,
          backBufferLength: 90,
          maxBufferLength: 2,         // Reducido de 30 a 2 segundos
          liveSyncDurationCount: 3,   // Mantiene la sincronización cerca del directo
          enableWorker: true,
          maxMaxBufferLength: 4
        });
        
        hls.attachMedia(video);
        hls.on(Hls.Events.MEDIA_ATTACHED, () => {
          hls.loadSource(url);
        });
        hls.on(Hls.Events.MANIFEST_PARSED, () => {
          video.muted = true;
          video.play().catch(() => {});
          startStats();
        });
        hls.on(Hls.Events.ERROR, (event, data) => {
          if (data && data.fatal) {
            try { hls.destroy(); } catch (e) {}
            hls = null;
            stopStats();
          }
        });
      } else if (video.canPlayType && video.canPlayType('application/vnd.apple.mpegurl')) {
        // Safari nativo
        video.src = url;
        video.muted = true;
        video.play().catch(() => {});
        startStats();
      } else {
        // no HLS support
        stateEl.textContent = 'NO SOPORTADO';
      }

      overlay.classList.add('hidden');
    }

    function stopPlayback() {
      if (hls) {
        try { hls.destroy(); } catch (e) {}
        hls = null;
      }
      try {
        video.pause();
        if (video.src) video.removeAttribute('src');
        if (video.load) video.load();
      } catch (e) {}
      overlay.classList.remove('hidden');
      stopStats();
    }

    function startStats() {
      stopStats();
      statsInterval = setInterval(() => {
        try {
          if (!video) return;
          if (video.getVideoPlaybackQuality) {
            const q = video.getVideoPlaybackQuality();
            const fps = Math.round(q.totalVideoFrames / Math.max(1, video.currentTime || 1));
            fpsEl.textContent = `FPS: ${(isFinite(fps) && fps > 0 && fps < 1000) ? fps : '--'}`;
          }
        } catch (e) {}
      }, 1000);
    }

    function stopStats() {
      if (statsInterval) { clearInterval(statsInterval); statsInterval = null; }
      fpsEl.textContent = 'FPS: --';
    }

    // Subscribe to state changes for this stream
    if (window.streamingManager && typeof window.streamingManager.subscribe === 'function') {
      window.streamingManager.subscribe(name, function (state) {
        stateEl.textContent = String(state).toUpperCase();
        if (state === window.streamingManager.State.LIVE) {
          startPlayback();
        } else {
          stopPlayback();
        }
      });
    }

    return {
      startPlayback,
      stopPlayback,
      destroy: () => {
        stopPlayback();
        if (card && card.parentNode) card.parentNode.removeChild(card);
      }
    };
  }

  // Public API
  const ui = {
    createPlayer: function (name) {
      const grid = document.getElementById('streamsGrid');
      if (!grid) {
        console.warn('streamsGrid not found in DOM');
        return null;
      }
      const cardObj = makeCard(name);
      grid.appendChild(cardObj.card);
      const controller = bindCard(name, cardObj);
      return { el: cardObj.card, controller };
    },

    createAll: function (names) {
      if (!Array.isArray(names)) return [];
      return names.map(n => ui.createPlayer(n));
    }
  };

  window.streamingUI = ui;
})();
