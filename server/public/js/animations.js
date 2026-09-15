/**
 * GamerVoice Advanced Animation & Audio Synthesizer Engine
 * 3D Card Parallax, Web Audio Tactical Cues, Canvas Particles, Dynamic Scroll
 */
(function () {
  "use strict";

  window.GVAnim = window.GVAnim || {};

  // 1. Web Audio API Sound Synthesizer (Zero External Audio Files Needed)
  let audioCtx = null;
  let soundEnabled = localStorage.getItem("gv_sound_enabled") !== "false";

  function getAudioContext() {
    if (!audioCtx && (window.AudioContext || window.webkitAudioContext)) {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      audioCtx = new AudioContextClass();
    }
    if (audioCtx && audioCtx.state === "suspended") {
      audioCtx.resume();
    }
    return audioCtx;
  }

  GVAnim.isSoundEnabled = () => soundEnabled;
  GVAnim.toggleSound = () => {
    soundEnabled = !soundEnabled;
    localStorage.setItem("gv_sound_enabled", soundEnabled ? "true" : "false");
    return soundEnabled;
  };

  GVAnim.playTone = function (freqStart, freqEnd, duration, type = "sine", gainLevel = 0.15) {
    if (!soundEnabled) return;
    try {
      const ctx = getAudioContext();
      if (!ctx) return;
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      const now = ctx.currentTime;

      osc.type = type;
      osc.frequency.setValueAtTime(freqStart, now);
      if (freqEnd !== freqStart) {
        osc.frequency.exponentialRampToValueAtTime(Math.max(10, freqEnd), now + duration);
      }

      gain.gain.setValueAtTime(gainLevel, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + duration);

      osc.connect(gain);
      gain.connect(ctx.destination);

      osc.start(now);
      osc.stop(now + duration);
    } catch (_) {}
  };

  GVAnim.playUiClick = function () {
    GVAnim.playTone(600, 150, 0.08, "triangle", 0.12);
  };

  GVAnim.playMicToggle = function (isOn) {
    if (isOn) {
      GVAnim.playTone(320, 640, 0.12, "sine", 0.18);
    } else {
      GVAnim.playTone(640, 240, 0.14, "sine", 0.18);
    }
  };

  GVAnim.playTacticalCue = function (type) {
    if (!soundEnabled) return;
    try {
      const ctx = getAudioContext();
      if (!ctx) return;
      const now = ctx.currentTime;

      if (type === "rush") {
        // Double energetic chirp
        GVAnim.playTone(520, 880, 0.1, "sine", 0.2);
        setTimeout(() => GVAnim.playTone(660, 1100, 0.15, "triangle", 0.22), 110);
      } else if (type === "enemy") {
        // High alert ping
        GVAnim.playTone(900, 450, 0.18, "sawtooth", 0.16);
      } else if (type === "clutch") {
        // Major chord harmonic triumph
        [440, 554.37, 659.25, 880].forEach((freq, idx) => {
          setTimeout(() => {
            GVAnim.playTone(freq, freq * 1.02, 0.35, "sine", 0.14);
          }, idx * 60);
        });
      } else {
        GVAnim.playTone(480, 720, 0.1, "sine", 0.15);
      }
    } catch (_) {}
  };

  // 2. 3D Card Parallax Tilt on Mouse Move
  function initCardParallax() {
    if (window.matchMedia("(pointer: coarse)").matches) return; // Skip touch devices

    const cards = document.querySelectorAll(".card, .phone, .download-hero-card");
    cards.forEach((card) => {
      card.addEventListener("mousemove", (e) => {
        const rect = card.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const y = e.clientY - rect.top;
        const centerX = rect.width / 2;
        const centerY = rect.height / 2;

        const rotateX = ((y - centerY) / centerY) * -6; // max 6 deg
        const rotateY = ((x - centerX) / centerX) * 6;

        card.style.transform = `perspective(1000px) rotateX(${rotateX.toFixed(2)}deg) rotateY(${rotateY.toFixed(2)}deg) translateY(-4px)`;
      });

      card.addEventListener("mouseleave", () => {
        card.style.transform = "";
      });
    });
  }

  // 3. Navbar Elevation on Scroll
  function initNavScroll() {
    const nav = document.querySelector(".nav");
    if (!nav) return;
    const onScroll = () => {
      if (window.scrollY > 20) {
        nav.classList.add("scrolled");
      } else {
        nav.classList.remove("scrolled");
      }
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    onScroll();
  }

  // 4. Attach Sound Cues to Clickable Elements
  function initButtonSounds() {
    document.addEventListener("click", (e) => {
      const target = e.target.closest("button, .btn, .tab, .faq-q");
      if (target && !target.classList.contains("no-sound")) {
        GVAnim.playUiClick();
      }
    });
  }

  document.addEventListener("DOMContentLoaded", () => {
    initCardParallax();
    initNavScroll();
    initButtonSounds();
  });
})();
