document.addEventListener("DOMContentLoaded", () => {
  const pingDisplay = document.getElementById("pingDisplay");
  const simPing = document.getElementById("simPing");
  const activeRooms = document.getElementById("meshStatus");
  const samples = [14, 15, 16, 17, 15, 16, 14, 18, 15];
  function tick() {
    const n = samples[Math.floor(Math.random() * samples.length)];
    if (pingDisplay) pingDisplay.textContent = n + " ms";
    if (simPing) simPing.textContent = n + "ms";
  }
  tick();
  setInterval(tick, 2500);

  try {
    const protocol = location.protocol === "https:" ? "wss:" : "ws:";
    const ws = new WebSocket(protocol + "//" + location.host);
    ws.onopen = () => { if (activeRooms) activeRooms.textContent = "Mesh relay online"; };
    ws.onclose = () => { if (activeRooms) activeRooms.textContent = "P2P standby"; };
    ws.onerror = () => { if (activeRooms) activeRooms.textContent = "P2P standby"; };
    setInterval(() => {
      if (ws.readyState === 1) ws.send(JSON.stringify({ type: "ping", timestamp: Date.now() }));
    }, 5000);
  } catch (e) {}

  let simMicActive = true;
  window.toggleSimMic = function () {
    simMicActive = !simMicActive;
    const icon = document.getElementById("simHudMicIcon");
    const status = document.getElementById("simHudStatus");
    const btn = document.getElementById("simHudMicToggle");
    if (icon) icon.textContent = simMicActive ? "Mic" : "Muted";
    if (status) status.textContent = simMicActive ? "VOICE LIVE" : "MIC MUTED";
    btn?.classList.toggle("on", simMicActive);
  };

  const codes = ["ALPHA", "KINGS", "DELTA", "TITAN", "BRAVO", "OMEGA"];
  let i = 0;
  window.simNewRoomCode = function () {
    i = (i + 1) % codes.length;
    const el = document.getElementById("simRoomCode");
    if (el) el.textContent = codes[i];
  };

  window.toggleSimPeerMute = function (btn) {
    btn.textContent = btn.textContent === "Mute" ? "Unmute" : "Mute";
  };

  window.triggerSimClutchSave = function () {
    const btn = document.getElementById("simClutchBtn");
    if (!btn) return;
    btn.textContent = "Saving…";
    setTimeout(() => {
      btn.textContent = "Saved MP3";
      GV.toast("Clutch highlight packed locally — 0 cloud upload.", "ok");
      setTimeout(() => { btn.textContent = "Clutch"; }, 1800);
    }, 500);
  };

  let audioCtx = null;
  window.playTacticalCue = function (type) {
    try {
      if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      const osc = audioCtx.createOscillator();
      const gain = audioCtx.createGain();
      osc.connect(gain);
      gain.connect(audioCtx.destination);
      const now = audioCtx.currentTime;
      osc.frequency.setValueAtTime(type === "rush" ? 800 : 1200, now);
      gain.gain.setValueAtTime(0.28, now);
      gain.gain.exponentialRampToValueAtTime(0.01, now + 0.22);
      osc.start(now);
      osc.stop(now + 0.22);
    } catch (e) {}
  };
});
