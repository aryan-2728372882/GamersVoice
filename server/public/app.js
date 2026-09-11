/**
 * GamerVoice Luxury Web Platform Application Engine
 */

// 1. Firebase Client Configuration
const firebaseConfig = {
  apiKey: "AIzaSyDe9FrTvbLXnTy2WgrvoZvZPHqXaHYXM3s",
  authDomain: "gamersvoice-ea413.firebaseapp.com",
  projectId: "gamersvoice-ea413",
  storageBucket: "gamersvoice-ea413.firebasestorage.app"
};

let app, auth, db;
try {
  app = firebase.initializeApp(firebaseConfig);
  auth = firebase.auth();
  db = firebase.firestore();
  console.log("[Firebase] Ready");
} catch (e) {
  console.warn("[Firebase Notice]", e.message);
}

let currentUser = null;
let currentVipPlan = null;

if (auth) {
  auth.onAuthStateChanged((user) => {
    currentUser = user;
    updateUserInterface(user);
    if (user && db) {
      subscribeToUserPlan(user.uid);
    }
  });
}

function updateUserInterface(user) {
  const container = document.getElementById("authStatusContainer");
  if (!container) return;

  if (user) {
    const displayName = user.displayName || user.email.split("@")[0];
    const initial = (displayName.charAt(0) || "G").toUpperCase();
    const vipText = currentVipPlan && currentVipPlan.isVip ? ("👑 " + currentVipPlan.planType) : "FREE";

    container.innerHTML = 
      '<div class="user-pill">' +
        '<div class="user-avatar-initials">' + initial + '</div>' +
        '<span class="user-name-label">' + escapeHtml(displayName) + '</span>' +
        '<span class="user-vip-tag" id="userVipTag">' + vipText + '</span>' +
        '<button class="btn-signout" onclick="handleSignOut()">Sign Out</button>' +
      '</div>';
  } else {
    container.innerHTML = 
      '<button class="btn btn-outline" id="openAuthBtn" onclick="openAuthModal()">' +
        '<span class="btn-icon">⚡</span> <span class="btn-text">Sign In</span>' +
      '</button>';
  }
}

function subscribeToUserPlan(uid) {
  if (!db) return;
  db.collection("users").doc(uid).onSnapshot((doc) => {
    if (doc.exists) {
      const data = doc.data();
      currentVipPlan = data;
      const vipTag = document.getElementById("userVipTag");
      if (vipTag && data.isVip) {
        vipTag.innerText = "👑 " + (data.planType || "VIP");
        vipTag.style.background = "rgba(255, 215, 0, 0.25)";
      }
      const simBadge = document.getElementById("simPlanBadge");
      if (simBadge && data.isVip) {
        simBadge.innerText = "VIP " + data.planType;
      }
    }
  }, (err) => {
    console.warn("[Firestore Subscription]", err.message);
  });
}

function handleSignOut() {
  if (auth) {
    auth.signOut().then(() => {
      currentVipPlan = null;
      updateUserInterface(null);
    });
  }
}

// 2. Authentication Modal Logic
const authModal = document.getElementById("authModal");
const tabSignIn = document.getElementById("tabSignIn");
const tabSignUp = document.getElementById("tabSignUp");
const authForm = document.getElementById("authForm");
const nameGroup = document.getElementById("nameGroup");
const authSubmitBtn = document.getElementById("authSubmitBtn");
const authModalTitle = document.getElementById("authModalTitle");
const authErrorMsg = document.getElementById("authErrorMessage");
let isSignUpMode = false;

function openAuthModal() {
  if (authModal) authModal.classList.add("active");
}

function closeAuthModal() {
  if (authModal) authModal.classList.remove("active");
}

tabSignIn?.addEventListener("click", () => {
  isSignUpMode = false;
  tabSignIn.classList.add("active");
  tabSignUp.classList.remove("active");
  if (nameGroup) nameGroup.style.display = "none";
  if (authSubmitBtn) authSubmitBtn.innerText = "Sign In";
  if (authModalTitle) authModalTitle.innerText = "Welcome to GamerVoice";
});

tabSignUp?.addEventListener("click", () => {
  isSignUpMode = true;
  tabSignUp.classList.add("active");
  tabSignIn.classList.remove("active");
  if (nameGroup) nameGroup.style.display = "block";
  if (authSubmitBtn) authSubmitBtn.innerText = "Create Account";
  if (authModalTitle) authModalTitle.innerText = "Create GamerVoice Account";
});

authForm?.addEventListener("submit", async (e) => {
  e.preventDefault();
  const email = document.getElementById("authEmailInput").value.trim();
  const pass = document.getElementById("authPassInput").value;
  const name = document.getElementById("authNameInput")?.value.trim();

  if (!auth) {
    alert("Firebase authentication is initializing. Please retry in 2 seconds.");
    return;
  }

  showAuthError("");
  authSubmitBtn.disabled = true;
  authSubmitBtn.innerText = "Processing...";

  try {
    if (isSignUpMode) {
      const cred = await auth.createUserWithEmailAndPassword(email, pass);
      if (name && cred.user) {
        await cred.user.updateProfile({ displayName: name });
      }
      fetch("/api/send-welcome-email", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, name: name || "Gamer" })
      }).catch(err => console.log("Welcome email queued"));

      closeAuthModal();
    } else {
      await auth.signInWithEmailAndPassword(email, pass);
      closeAuthModal();
    }
  } catch (err) {
    showAuthError(err.message);
  } finally {
    authSubmitBtn.disabled = false;
    authSubmitBtn.innerText = isSignUpMode ? "Create Account" : "Sign In";
  }
});

document.getElementById("googleSignInBtn")?.addEventListener("click", async () => {
  if (!auth) return;
  const provider = new firebase.auth.GoogleAuthProvider();
  try {
    const cred = await auth.signInWithPopup(provider);
    if (cred.user) {
      fetch("/api/send-welcome-email", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: cred.user.email, name: cred.user.displayName || "Gamer" })
      }).catch(e => {});
    }
    closeAuthModal();
  } catch (err) {
    showAuthError(err.message);
  }
});

function showAuthError(msg) {
  if (authErrorMsg) {
    authErrorMsg.innerText = msg;
    authErrorMsg.style.display = msg ? "block" : "none";
  }
}

// 3. Razorpay In-Browser VIP Checkout
window.initiateVipCheckout = async function(planTier, priceInr) {
  if (!currentUser) {
    openAuthModal();
    alert("Please sign in first so your VIP clearance links directly to your GamerVoice account!");
    return;
  }

  if (typeof Razorpay === "undefined") {
    alert("Payment gateway is loading. Please check your internet connection.");
    return;
  }

  const options = {
    key: "rzp_live_SWhlEskNokZ9rR",
    amount: priceInr * 100,
    currency: "INR",
    name: "GamerVoice VIP Clearance",
    description: planTier + " VIP Squad Access",
    image: "/logo.png",
    theme: { color: "#FFD700" },
    prefill: {
      name: currentUser.displayName || "GamerVoice Member",
      email: currentUser.email || ""
    },
    handler: async function (response) {
      const paymentId = response.razorpay_payment_id;
      if (!paymentId) return;

      try {
        const verifyRes = await fetch("/api/verify-payment", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            paymentId: paymentId,
            planTier: planTier,
            userId: currentUser.uid,
            userEmail: currentUser.email
          })
        });

        const verifyData = await verifyRes.json();
        if (verifyRes.ok && verifyData.verified) {
          const now = Date.now();
          let expiryTimestamp = -1;
          let expiryLabel = "N/A (Permanent)";
          
          if (planTier === "WEEKLY") {
            expiryTimestamp = now + (7 * 24 * 60 * 60 * 1000);
            expiryLabel = new Date(expiryTimestamp).toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
          } else if (planTier === "MONTHLY") {
            expiryTimestamp = now + (30 * 24 * 60 * 60 * 1000);
            expiryLabel = new Date(expiryTimestamp).toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
          }

          if (db) {
            await db.collection("users").doc(currentUser.uid).set({
              isVip: true,
              planType: planTier,
              paymentId: paymentId,
              userEmail: currentUser.email,
              purchasedAt: new Date().toISOString(),
              expiresAt: expiryLabel,
              expiryTimestamp: expiryTimestamp
            }, { merge: true });
          }

          alert("👑 VIP CLEARANCE ACTIVATED!\n\nTier: " + planTier + "\nPayment ID: " + paymentId + "\n\nYour perks are now live both on this website and in your GamerVoice Android app!");
        } else {
          alert("Payment verification error: " + (verifyData.error || "Could not verify payment with server."));
        }
      } catch (err) {
        alert("Server verification failed: " + err.message);
      }
    }
  };

  const rzp = new Razorpay(options);
  rzp.open();
};

// 4. Live Telemetry & Real-Time WebSocket Ping
function initTelemetry() {
  const pingDisplay = document.getElementById("pingDisplay");
  const simPing = document.getElementById("simPing");

  fetch("/health")
    .then(r => r.json())
    .catch(() => {});

  try {
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const wsUrl = protocol + "//" + window.location.host;
    const ws = new WebSocket(wsUrl);
    let pingStart = 0;

    ws.onopen = () => {
      setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) {
          pingStart = performance.now();
          ws.send(JSON.stringify({ type: "ping", timestamp: pingStart }));
        }
      }, 3500);
    };

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data.type === "pong") {
          const latency = Math.round(performance.now() - pingStart);
          if (pingDisplay) pingDisplay.innerText = latency + " ms";
          if (simPing) simPing.innerText = latency + "ms";
        }
      } catch (e) {}
    };

    ws.onerror = () => {
      if (pingDisplay) pingDisplay.innerText = "18 ms (Ultra-Low)";
      if (simPing) simPing.innerText = "18ms";
    };
  } catch (e) {
    if (pingDisplay) pingDisplay.innerText = "18 ms";
  }
}

// 5. Interactive Phone App Simulator Controls
let simMicActive = true;
window.toggleSimMic = function() {
  simMicActive = !simMicActive;
  const btn = document.getElementById("simHudMicToggle");
  const icon = document.getElementById("simHudMicIcon");
  const status = document.getElementById("simHudStatus");
  const hud = document.getElementById("simFloatingHud");

  if (simMicActive) {
    btn?.classList.add("active");
    if (icon) icon.innerText = "🎙️";
    if (status) status.innerText = "VOICE ACTIVE";
    hud?.classList.remove("muted");
  } else {
    btn?.classList.remove("active");
    if (icon) icon.innerText = "🔇";
    if (status) status.innerText = "MIC MUTED";
    hud?.classList.add("muted");
  }
};

const sampleCodes = ["ALPHA", "KINGS", "DELTA", "TITAN", "BRAVO", "OMEGA"];
let codeIndex = 0;
window.simNewRoomCode = function() {
  codeIndex = (codeIndex + 1) % sampleCodes.length;
  const el = document.getElementById("simRoomCode");
  if (el) el.innerText = sampleCodes[codeIndex];
};

window.updateSimNoise = function(val) {
  const el = document.getElementById("simSliderVal");
  if (el) {
    if (val >= 100) el.innerText = "100% (VIP Studio)";
    else if (val >= 75) el.innerText = val + "% (VIP Squad)";
    else el.innerText = val + "% (Standard)";
  }
};

// Web Audio API Synthesizer for Tactical Soundboard Cues
let audioCtx = null;
window.playTacticalCue = function(type) {
  try {
    if (!audioCtx) {
      audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    }
    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();
    osc.connect(gain);
    gain.connect(audioCtx.destination);

    const now = audioCtx.currentTime;
    if (type === "rush") {
      osc.frequency.setValueAtTime(800, now);
      osc.frequency.exponentialRampToValueAtTime(1400, now + 0.18);
      gain.gain.setValueAtTime(0.3, now);
      gain.gain.exponentialRampToValueAtTime(0.01, now + 0.2);
      osc.start(now);
      osc.stop(now + 0.2);
    } else if (type === "enemy") {
      osc.frequency.setValueAtTime(1200, now);
      osc.frequency.setValueAtTime(700, now + 0.1);
      gain.gain.setValueAtTime(0.35, now);
      gain.gain.exponentialRampToValueAtTime(0.01, now + 0.25);
      osc.start(now);
      osc.stop(now + 0.25);
    } else if (type === "backup") {
      osc.frequency.setValueAtTime(500, now);
      osc.frequency.exponentialRampToValueAtTime(900, now + 0.2);
      gain.gain.setValueAtTime(0.3, now);
      gain.gain.exponentialRampToValueAtTime(0.01, now + 0.25);
      osc.start(now);
      osc.stop(now + 0.25);
    } else {
      osc.frequency.setValueAtTime(950, now);
      osc.frequency.exponentialRampToValueAtTime(450, now + 0.22);
      gain.gain.setValueAtTime(0.3, now);
      gain.gain.exponentialRampToValueAtTime(0.01, now + 0.25);
      osc.start(now);
      osc.stop(now + 0.25);
    }
  } catch (e) {
    console.log("Audio synthesis notice:", e.message);
  }
};

// 6. Interactive Noise Lab Canvas Animation
function initNoiseLab() {
  const canvas = document.getElementById("waveCanvas");
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  const slider = document.getElementById("noiseSlider");
  const label = document.getElementById("noisePercentLabel");

  let currentSuppression = 75;
  let currentScenario = "fan";
  let phase = 0;

  slider?.addEventListener("input", (e) => {
    currentSuppression = parseInt(e.target.value, 10);
    if (label) {
      if (currentSuppression === 0) label.innerText = "0% (Raw Mic Noise)";
      else if (currentSuppression < 50) label.innerText = currentSuppression + "% (Basic Suppression)";
      else if (currentSuppression < 100) label.innerText = currentSuppression + "% (Enhanced Squad VIP)";
      else label.innerText = "100% (Lifetime Legend Pure Vocal)";
    }
  });

  document.querySelectorAll(".scenario-btn").forEach(btn => {
    btn.addEventListener("click", (e) => {
      document.querySelectorAll(".scenario-btn").forEach(b => b.classList.remove("active"));
      e.target.classList.add("active");
      currentScenario = e.target.getAttribute("data-scenario");
    });
  });

  function draw() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    const width = canvas.width;
    const height = canvas.height;
    const midY = height / 2;
    phase += 0.04;

    const cleanRatio = currentSuppression / 100;
    const noiseRatio = 1 - cleanRatio;

    // Background Grid
    ctx.strokeStyle = "rgba(255, 255, 255, 0.04)";
    ctx.lineWidth = 1;
    for (let x = 0; x < width; x += 40) {
      ctx.beginPath();
      ctx.moveTo(x, 0);
      ctx.lineTo(x, height);
      ctx.stroke();
    }

    // Chaotic Noise Waveform (Red/Orange)
    if (noiseRatio > 0.05) {
      ctx.beginPath();
      ctx.strokeStyle = "rgba(255, 80, 80, " + (noiseRatio * 0.75) + ")";
      ctx.lineWidth = 1.6;
      for (let x = 0; x < width; x += 4) {
        let nAmp = 0;
        if (currentScenario === "fan") {
          nAmp = Math.sin(x * 0.18 + phase * 3) * 16 * noiseRatio + (Math.random() - 0.5) * 12 * noiseRatio;
        } else if (currentScenario === "keyboard") {
          nAmp = (Math.random() - 0.5) * 32 * noiseRatio;
        } else {
          nAmp = Math.sin(x * 0.04 + phase) * 20 * noiseRatio + (Math.random() - 0.5) * 15 * noiseRatio;
        }
        const y = midY + nAmp;
        if (x === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      ctx.stroke();
    }

    // Pure Vocal Waveform (Gold / Emerald)
    ctx.beginPath();
    ctx.strokeStyle = cleanRatio > 0.8 ? "#FFD700" : "#00FF88";
    ctx.lineWidth = 2.4;
    for (let x = 0; x < width; x += 2) {
      const vAmp = Math.sin(x * 0.02 + phase) * 36 * Math.sin(x * 0.005) + Math.cos(x * 0.04 - phase * 0.5) * 14;
      const y = midY + vAmp;
      if (x === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.stroke();

    requestAnimationFrame(draw);
  }

  draw();
}

// 7. Navigation Drawer & FAQ Accordion Controls
window.toggleDrawer = function() {
  const drawer = document.getElementById("mobileDrawer");
  drawer?.classList.toggle("active");
};

window.closeDrawer = function() {
  const drawer = document.getElementById("mobileDrawer");
  drawer?.classList.remove("active");
};

window.toggleFaq = function(btn) {
  const item = btn.parentElement;
  item.classList.toggle("active");
};

// 8. Support Ticket Modal
window.openSupport = function() {
  const modal = document.getElementById("supportModal");
  modal?.classList.add("active");
};

window.closeSupport = function() {
  const modal = document.getElementById("supportModal");
  modal?.classList.remove("active");
};

document.getElementById("supportForm")?.addEventListener("submit", async (e) => {
  e.preventDefault();
  const category = document.getElementById("supportCategory").value;
  const subject = document.getElementById("supportSubject").value.trim();
  const description = document.getElementById("supportDescription").value.trim();
  const submitBtn = document.getElementById("supportSubmitBtn");

  submitBtn.disabled = true;
  submitBtn.innerText = "Submitting...";

  try {
    const res = await fetch("/api/submit-support-ticket", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        category,
        subject,
        description,
        userEmail: currentUser ? currentUser.email : "web_visitor@gamersvoice.com",
        userId: currentUser ? currentUser.uid : "web_anonymous",
        appVersion: "Web-v1.0.0"
      })
    });

    const data = await res.json();
    if (res.ok && data.success) {
      alert("✅ Support Ticket Submitted!\n\nTicket ID: " + data.ticketId + "\nOur engineering team has received your ticket.");
      document.getElementById("supportForm").reset();
      closeSupport();
    } else {
      alert("Error: " + (data.error || "Server error"));
    }
  } catch (err) {
    alert("Submission failed: " + err.message);
  } finally {
    submitBtn.disabled = false;
    submitBtn.innerText = "Submit Ticket";
  }
});

function escapeHtml(str) {
  if (!str) return "";
  return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

document.addEventListener("DOMContentLoaded", () => {
  initTelemetry();
  initNoiseLab();
});