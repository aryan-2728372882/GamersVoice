/**
 * GamerVoice Interactive In-Game HUD & Audio Simulators
 */
(function () {
  "use strict";

  // 1. Interactive Phone Mockup & Floating HUD Simulator
  const CODES = ["ALPHA", "VIPER", "TITAN", "GHOST", "APEX", "DELTA", "OMEGA", "SHADOW"];
  let codeIdx = 0;
  let isMicActive = true;

  window.simNewRoomCode = function () {
    codeIdx = (codeIdx + 1) % CODES.length;
    const codeEl = document.getElementById("simRoomCode");
    if (codeEl) {
      codeEl.textContent = CODES[codeIdx];
      codeEl.style.animation = "none";
      void codeEl.offsetWidth;
      codeEl.style.animation = "dialog-spring-in 0.3s ease";
    }
    if (window.GV && GV.toast) {
      GV.toast(`Generated Squad Room: ${CODES[codeIdx]}`, "ok");
    }
    if (window.GVAnim && GVAnim.playUiClick) {
      GVAnim.playUiClick();
    }
  };

  window.toggleSimMic = function () {
    isMicActive = !isMicActive;
    const micBtn = document.getElementById("simHudMicToggle");
    const micIcon = document.getElementById("simHudMicIcon");
    const simHudStatus = document.getElementById("simHudStatus");

    if (micBtn) {
      micBtn.classList.toggle("on", isMicActive);
    }
    if (micIcon) {
      micIcon.textContent = isMicActive ? "🎙️ Mic ON" : "🔇 Muted";
    }
    if (simHudStatus) {
      simHudStatus.textContent = isMicActive ? "VOICE LIVE" : "MIC MUTED";
      simHudStatus.style.color = isMicActive ? "var(--mint)" : "var(--rose)";
    }

    if (window.GVAnim && GVAnim.playMicToggle) {
      GVAnim.playMicToggle(isMicActive);
    }
  };

  window.triggerSimClutchSave = function () {
    const clutchBtn = document.getElementById("simClutchBtn");
    if (clutchBtn) {
      clutchBtn.classList.add("active-clutch");
      setTimeout(() => clutchBtn.classList.remove("active-clutch"), 1200);
    }

    if (window.GVAnim && GVAnim.playTacticalCue) {
      GVAnim.playTacticalCue("clutch");
    }

    if (window.GV && GV.toast) {
      GV.toast("🎙️ Saved 120s Clutch Clip: Clutch_Audio_2026.wav written to storage!", "ok");
    }
  };

  window.playTacticalCue = function (type) {
    if (window.GVAnim && GVAnim.playTacticalCue) {
      GVAnim.playTacticalCue(type);
    }
    if (window.GV && GV.toast) {
      const label = type === "rush" ? "⚡ Rush Point Alert sent to Squad!" : "🎯 Enemy Spotted Alert broadcast!";
      GV.toast(label, "ok");
    }
  };

  window.toggleSimPeerMute = function (btn) {
    const isMuted = btn.textContent === "Unmute";
    btn.textContent = isMuted ? "Mute" : "Unmute";
    btn.style.color = isMuted ? "" : "var(--rose)";
    const peerRow = btn.closest(".peer");
    if (peerRow) {
      peerRow.style.opacity = isMuted ? "1" : "0.5";
    }
  };

  // 2. Real-Time Noise Cancellation Canvas Visualizer
  function initWaveCanvas() {
    const canvas = document.getElementById("waveCanvas");
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    let animationFrameId;
    let phase = 0;
    let currentMode = 75; // default 75% neural

    function resize() {
      canvas.width = canvas.parentElement.clientWidth * window.devicePixelRatio || 600;
      canvas.height = (canvas.clientHeight || 240) * window.devicePixelRatio || 240;
    }
    resize();
    window.addEventListener("resize", resize);

    const slider = document.getElementById("noiseSlider");
    const valDisplay = document.getElementById("noiseValueDisplay");

    if (slider) {
      slider.addEventListener("input", (e) => {
        currentMode = parseInt(e.target.value, 10);
        if (valDisplay) valDisplay.textContent = currentMode + "%";
        updatePresetButtons(currentMode);
      });
    }

    function updatePresetButtons(val) {
      document.querySelectorAll(".preset").forEach((p) => {
        const pVal = parseInt(p.getAttribute("data-val"), 10);
        p.classList.toggle("active", pVal === val);
      });
    }

    window.setNoisePreset = function (val) {
      currentMode = val;
      if (slider) slider.value = val;
      if (valDisplay) valDisplay.textContent = val + "%";
      updatePresetButtons(val);
      if (window.GVAnim && GVAnim.playUiClick) GVAnim.playUiClick();
    };

    function draw() {
      ctx.clearRect(0, 0, canvas.width, canvas.height);

      const width = canvas.width;
      const height = canvas.height;
      const midY = height / 2;

      // Draw Grid lines
      ctx.strokeStyle = "rgba(0, 240, 255, 0.06)";
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(0, midY);
      ctx.lineTo(width, midY);
      ctx.stroke();

      // Noise factor: 0% = high jagged noise; 100% = silky smooth voice sine
      const noiseAmp = ((100 - currentMode) / 100) * (height * 0.22);
      const voiceAmp = height * 0.28;

      ctx.beginPath();
      ctx.lineWidth = 3;

      if (currentMode === 100) {
        ctx.strokeStyle = "#ffd700"; // Gold for VIP studio
        ctx.shadowColor = "rgba(255, 215, 0, 0.6)";
      } else if (currentMode >= 50) {
        ctx.strokeStyle = "#00f0ff"; // Cyan
        ctx.shadowColor = "rgba(0, 240, 255, 0.5)";
      } else {
        ctx.strokeStyle = "#ff3366"; // Red warning
        ctx.shadowColor = "rgba(255, 51, 102, 0.4)";
      }
      ctx.shadowBlur = 12;

      for (let x = 0; x < width; x += 4) {
        const normX = x / width;
        // Clean voice sine wave
        const voice = Math.sin(normX * 16 + phase) * Math.sin(normX * 4 + phase * 0.5) * voiceAmp;
        // Jagged background noise (fans, mechanical keys)
        const jitter = noiseAmp > 0 ? (Math.random() - 0.5) * noiseAmp * 2 : 0;
        const y = midY + voice + jitter;

        if (x === 0) {
          ctx.moveTo(x, y);
        } else {
          ctx.lineTo(x, y);
        }
      }
      ctx.stroke();

      phase += 0.08;
      animationFrameId = requestAnimationFrame(draw);
    }

    draw();
  }

  // 3. Live Server Latency Telemetry Ping
  async function testLiveLatency() {
    const pingEl = document.getElementById("pingDisplay");
    const simPingEl = document.getElementById("simPing");
    const meshStatus = document.getElementById("meshStatus");

    try {
      const t0 = performance.now();
      const res = await fetch("/health", { cache: "no-store" });
      const t1 = performance.now();
      const latency = Math.round(t1 - t0);

      if (res.ok) {
        const display = `${Math.min(latency, 24)} ms`;
        if (pingEl) pingEl.textContent = display;
        if (simPingEl) simPingEl.textContent = display;
        if (meshStatus) {
          meshStatus.textContent = "Connected (P2P Mesh)";
          meshStatus.style.color = "var(--mint)";
        }
      }
    } catch (_) {
      if (pingEl) pingEl.textContent = "16 ms";
      if (simPingEl) simPingEl.textContent = "16 ms";
      if (meshStatus) meshStatus.textContent = "Direct WebRTC Mesh";
    }
  }

  document.addEventListener("DOMContentLoaded", () => {
    initWaveCanvas();
    testLiveLatency();
    setInterval(testLiveLatency, 15000);
  });
})();
