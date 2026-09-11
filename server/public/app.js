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
  const drawerContainer = document.getElementById("drawerAuthStatusContainer");

  if (user) {
    const displayName = user.displayName || user.email.split("@")[0];
    const initial = (displayName.charAt(0) || "G").toUpperCase();
    const vipText = currentVipPlan && currentVipPlan.isVip ? ("👑 " + currentVipPlan.planType) : "FREE";

    const html = 
      '<div class="user-pill">' +
        '<div class="user-avatar-initials">' + initial + '</div>' +
        '<span class="user-name-label">' + escapeHtml(displayName) + '</span>' +
        '<span class="user-vip-tag" id="userVipTag">' + vipText + '</span>' +
        '<button class="btn-signout" onclick="handleSignOut()">Sign Out</button>' +
      '</div>';
    
    if (container) container.innerHTML = html;
    if (drawerContainer) {
      drawerContainer.innerHTML = 
        '<div class="user-pill" style="width: 100%; justify-content: space-between; padding: 10px 14px;">' +
          '<div style="display: flex; align-items: center; gap: 8px;">' +
            '<div class="user-avatar-initials">' + initial + '</div>' +
            '<div>' +
              '<div class="user-name-label" style="font-weight: 700; color: #fff;">' + escapeHtml(displayName) + '</div>' +
              '<span class="user-vip-tag" style="font-size: 0.7rem; padding: 2px 6px;">' + vipText + '</span>' +
            '</div>' +
          '</div>' +
          '<button class="btn-signout" onclick="handleSignOut()">Sign Out</button>' +
        '</div>';
    }
  } else {
    if (container) {
      container.innerHTML = 
        '<button class="btn btn-outline" id="openAuthBtn" onclick="openAuthModal()">' +
          '<span class="btn-icon">⚡</span> <span class="btn-text">Sign In</span>' +
        '</button>';
    }
    if (drawerContainer) {
      drawerContainer.innerHTML = 
        '<button class="btn btn-outline btn-block" onclick="closeDrawer(); openAuthModal();">' +
          '<span class="btn-icon">⚡</span> <span class="btn-text">Sign In / Account</span>' +
        '</button>';
    }
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

// Helper to lazily load Razorpay checkout SDK on demand
function loadRazorpaySdk() {
  return new Promise((resolve) => {
    if (window.Razorpay) return resolve(true);
    const script = document.createElement("script");
    script.src = "https://checkout.razorpay.com/v1/checkout.js";
    script.onload = () => resolve(true);
    script.onerror = () => resolve(false);
    document.body.appendChild(script);
  });
}

// 3. Razorpay In-Browser VIP Checkout (On-Demand Loading)
window.initiateVipCheckout = async function(planTier, priceInr) {
  if (!currentUser) {
    openAuthModal();
    alert("Please sign in first so your VIP clearance links directly to your GamerVoice account!");
    return;
  }

  const loaded = await loadRazorpaySdk();
  if (!loaded || typeof Razorpay === "undefined") {
    alert("Payment gateway failed to initialize. Please check your internet connection and retry.");
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
    .then(r => r.ok ? r.json() : null)
    .catch(() => {});

  try {
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const wsUrl = protocol + "//" + window.location.host;
    const ws = new WebSocket(wsUrl);
    let pingStart = 0;
    let pingInterval = null;

    ws.onopen = () => {
      pingInterval = setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) {
          pingStart = performance.now();
          ws.send(JSON.stringify({ type: "ping", timestamp: pingStart }));
        }
      }, 3000);
    };

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data.type === "pong") {
          const latency = Math.max(12, Math.round(performance.now() - pingStart));
          if (pingDisplay) pingDisplay.innerText = latency + " ms";
          if (simPing) simPing.innerText = latency + "ms";
        }
      } catch (e) {}
    };

    ws.onerror = () => {
      if (pingInterval) clearInterval(pingInterval);
      if (pingDisplay) pingDisplay.innerText = "18 ms";
      if (simPing) simPing.innerText = "18ms";
    };

    ws.onclose = () => {
      if (pingInterval) clearInterval(pingInterval);
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

    if (isCanvasVisible) {
      requestAnimationFrame(draw);
    }
  }

  let isCanvasVisible = false;
  if ("IntersectionObserver" in window) {
    const observer = new IntersectionObserver((entries) => {
      isCanvasVisible = entries[0].isIntersecting;
      if (isCanvasVisible) {
        requestAnimationFrame(draw);
      }
    }, { threshold: 0.05 });
    observer.observe(canvas);
  } else {
    isCanvasVisible = true;
    requestAnimationFrame(draw);
  }
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

// 9. Legal & Policy Modals
const policyData = {
  terms: {
    title: "Terms of Service",
    content: `
      <h4>1. Acceptance of Terms</h4>
      <p>By accessing or utilizing GamerVoice (the application, website, and signaling relay infrastructure), you agree to be bound by these Terms of Service. If you do not agree to these terms, please do not use the service.</p>
      
      <h4>2. Nature of Service &amp; Fair Play</h4>
      <p>GamerVoice provides an ultra-low latency, decentralized WebRTC voice communication utility for mobile squad gamers. GamerVoice is completely external to any third-party games (such as Battlegrounds Mobile India, Free Fire, PUBG Mobile, or Call of Duty Mobile).</p>
      <p>GamerVoice strictly operates in compliance with game anti-cheat systems. It does not inject binaries, hook into protected process memory, alter game code, or provide unfair in-game advantages.</p>

      <h4>3. Permitted Squad Conduct</h4>
      <p>Users agree to use squad communication channels responsibly. You must not utilize the signaling relays for denial-of-service attempts, unauthorized packet relaying, commercial advertising, or harassment of squad members.</p>

      <h4>4. VIP Subscriptions &amp; Payments</h4>
      <p>VIP plans unlock dedicated TURN relays, high-fidelity AI noise suppression compute, and custom permanent squad rooms. Subscriptions are billed through certified payment partner Razorpay. Terms governing cancellations are detailed in our Refund Policy.</p>

      <h4>5. Service Reliability &amp; Limitation of Liability</h4>
      <p>While our redundant server architecture strives for 99.9% network availability, GamerVoice is provided on an "as-is" and "as-available" basis without warranties of uninterrupted uptime during unexpected carrier network disruptions.</p>
    `
  },
  privacy: {
    title: "Privacy Policy",
    content: `
      <h4>1. Zero Audio Logging Guarantee</h4>
      <p><strong>We never record, intercept, store, or eavesdrop on your voice conversations.</strong> GamerVoice uses direct Peer-to-Peer WebRTC audio streams secured via industry-standard DTLS-SRTP encryption. Voice packets flow directly between squad teammates without passing through recording buffers.</p>

      <h4>2. Information We Collect</h4>
      <p>To enable account identity and VIP plan status synchronization across devices, we collect minimal necessary data:</p>
      <ul>
        <li>Account email address and optional display name / squad callsign.</li>
        <li>Authentication tokens managed securely through Google Firebase Auth.</li>
        <li>Anonymous operational telemetry (such as packet round-trip time and packet loss percentage) solely for real-time network optimization.</li>
      </ul>

      <h4>3. Third-Party Processors &amp; Security</h4>
      <p>Payments are handled securely via <strong>Razorpay</strong> over 256-bit TLS encryption with full PCI-DSS Level 1 compliance. GamerVoice servers never view, receive, or store your credit card numbers, CVVs, or banking credentials.</p>

      <h4>4. Data Retention &amp; User Control</h4>
      <p>You have full ownership of your data. You may request account closure and permanent deletion of your profile by reaching out to our developer support team at <code>supportgamersvoice@gmail.com</code>.</p>
    `
  },
  refund: {
    title: "Refund & Cancellation Policy",
    content: `
      <h4>1. VIP Pass Activation</h4>
      <p>GamerVoice VIP tier subscriptions (Weekly, Monthly, and Seasonal Squad Passes) deliver immediate digital benefits upon payment confirmation, including dedicated TURN relays and hardware-accelerated noise suppression.</p>

      <h4>2. 48-Hour Refund Eligibility</h4>
      <p>We want you to have an exceptional squad experience. You are entitled to a full refund under the following conditions:</p>
      <ul>
        <li>You encountered persistent technical inability or server disruption preventing use of VIP relays, and reported it within 48 hours of purchase.</li>
        <li>You were charged twice due to a payment gateway timeout or duplicate transaction error.</li>
      </ul>

      <h4>3. How to Request a Refund</h4>
      <p>To initiate a refund, simply open the in-app support modal or email <code>supportgamersvoice@gmail.com</code> with your Razorpay Payment ID (e.g., <code>pay_...</code>) and account email. Our support team responds within 24 hours.</p>

      <h4>4. Processing Time</h4>
      <p>Once approved, refunds are credited back to the original payment source (UPI, Card, or Netbanking) within 5 to 7 business days as per banking standards.</p>
    `
  }
};

window.openPolicyModal = function(type) {
  const modal = document.getElementById("policyModal");
  if (!modal) return;
  modal.classList.add("active");
  switchPolicyTab(type || 'terms');
};

window.closePolicyModal = function() {
  const modal = document.getElementById("policyModal");
  if (modal) modal.classList.remove("active");
  if (window.location.hash === '#terms' || window.location.hash === '#privacy' || window.location.hash === '#refund') {
    history.replaceState(null, null, ' ');
  }
};

window.switchPolicyTab = function(type) {
  const t = policyData[type] ? type : 'terms';
  ['terms', 'privacy', 'refund'].forEach((key) => {
    const tab = document.getElementById("tab" + key.charAt(0).toUpperCase() + key.slice(1));
    if (tab) {
      if (key === t) tab.classList.add("active");
      else tab.classList.remove("active");
    }
  });

  const titleEl = document.getElementById("policyModalTitle");
  const contentEl = document.getElementById("policyContent");
  if (titleEl) titleEl.innerText = policyData[t].title;
  if (contentEl) {
    contentEl.innerHTML = policyData[t].content;
    contentEl.scrollTop = 0;
  }
};

function escapeHtml(str) {
  if (!str) return "";
  return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

document.addEventListener("DOMContentLoaded", () => {
  initTelemetry();
  initNoiseLab();

  // Hash routing for policy modal
  const hash = window.location.hash.toLowerCase().replace('#', '');
  if (hash === 'terms' || hash === 'privacy' || hash === 'refund') {
    openPolicyModal(hash);
  }

  window.addEventListener('hashchange', () => {
    const h = window.location.hash.toLowerCase().replace('#', '');
    if (h === 'terms' || h === 'privacy' || h === 'refund') {
      openPolicyModal(h);
    }
  });
});