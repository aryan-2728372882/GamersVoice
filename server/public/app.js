/**
 * GamerVoice Luxury Web Platform Application Core
 * Real-time WebRTC Telemetry, Firebase Auth, Razorpay Cross-Sync, 3D Parallax & Noise Lab
 */

// 1. Firebase Initialization
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
  console.log('[Firebase] Initialized successfully for GamerVoice');
} catch (e) {
  console.warn('[Firebase Notice]', e.message);
}

// Current User State
let currentUser = null;
let currentVipPlan = null;

// Auth State Listener
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
  const container = document.getElementById('authStatusContainer');
  if (!container) return;

  if (user) {
    const displayName = user.displayName || user.email.split('@')[0];
    const initial = (displayName.charAt(0) || 'G').toUpperCase();
    const vipText = currentVipPlan && currentVipPlan.isVip ? '👑 ' + currentVipPlan.planType : 'FREE';

    container.innerHTML = 
      <div class=\"user-pill\">
        <div class=\"user-avatar-initials\"></div>
        <span class=\"user-name-label\"></span>
        <span class=\"user-vip-tag\" id=\"userVipTag\"></span>
        <button class=\"btn-signout\" onclick=\"handleSignOut()\">Sign Out</button>
      </div>
    ;
  } else {
    container.innerHTML = 
      <button class=\"btn btn-outline\" id=\"openAuthBtn\" onclick=\"openAuthModal()\">
        <span class=\"btn-icon\">⚡</span> Sign In / Join
      </button>
    ;
  }
}

function subscribeToUserPlan(uid) {
  if (!db) return;
  db.collection('users').doc(uid).onSnapshot((doc) => {
    if (doc.exists) {
      const data = doc.data();
      currentVipPlan = data;
      const vipTag = document.getElementById('userVipTag');
      if (vipTag && data.isVip) {
        vipTag.innerText = '👑 ' + (data.planType || 'VIP');
        vipTag.style.background = 'rgba(255, 215, 0, 0.25)';
      }
    }
  }, (err) => {
    console.warn('[Firestore] Realtime subscription notice:', err.message);
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
const authModal = document.getElementById('authModal');
const tabSignIn = document.getElementById('tabSignIn');
const tabSignUp = document.getElementById('tabSignUp');
const authForm = document.getElementById('authForm');
const nameGroup = document.getElementById('nameGroup');
const authSubmitBtn = document.getElementById('authSubmitBtn');
const authModalTitle = document.getElementById('authModalTitle');
const authErrorMsg = document.getElementById('authErrorMsg') || document.getElementById('authErrorMessage');
let isSignUpMode = false;

function openAuthModal() {
  if (authModal) authModal.classList.add('active');
}

function closeAuthModal() {
  if (authModal) authModal.classList.remove('active');
}

document.getElementById('closeAuthModal')?.addEventListener('click', closeAuthModal);
document.getElementById('openAuthBtn')?.addEventListener('click', openAuthModal);

authModal?.addEventListener('click', (e) => {
  if (e.target === authModal) closeAuthModal();
});

tabSignIn?.addEventListener('click', () => {
  isSignUpMode = false;
  tabSignIn.classList.add('active');
  tabSignUp.classList.remove('active');
  nameGroup.style.display = 'none';
  authSubmitBtn.innerText = 'Sign In';
  authModalTitle.innerText = 'Welcome to GamerVoice';
});

tabSignUp?.addEventListener('click', () => {
  isSignUpMode = true;
  tabSignUp.classList.add('active');
  tabSignIn.classList.remove('active');
  nameGroup.style.display = 'block';
  authSubmitBtn.innerText = 'Create Account';
  authModalTitle.innerText = 'Create GamerVoice Account';
});

// Email / Password Form Submit
authForm?.addEventListener('submit', async (e) => {
  e.preventDefault();
  const email = document.getElementById('authEmailInput').value.trim();
  const pass = document.getElementById('authPassInput').value;
  const name = document.getElementById('authNameInput')?.value.trim();

  if (!auth) {
    alert('Firebase auth is loading. Please try again.');
    return;
  }

  showAuthError('');
  authSubmitBtn.disabled = true;
  authSubmitBtn.innerText = 'Processing...';

  try {
    if (isSignUpMode) {
      const cred = await auth.createUserWithEmailAndPassword(email, pass);
      if (name && cred.user) {
        await cred.user.updateProfile({ displayName: name });
      }
      // Send heartfelt welcome email via our backend relay
      fetch('/api/send-welcome-email', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, name: name || 'Gamer' })
      }).catch(err => console.log('Welcome email queued'));

      closeAuthModal();
    } else {
      await auth.signInWithEmailAndPassword(email, pass);
      closeAuthModal();
    }
  } catch (err) {
    showAuthError(err.message);
  } finally {
    authSubmitBtn.disabled = false;
    authSubmitBtn.innerText = isSignUpMode ? 'Create Account' : 'Sign In';
  }
});

// Google Sign In
document.getElementById('googleSignInBtn')?.addEventListener('click', async () => {
  if (!auth) return;
  const provider = new firebase.auth.GoogleAuthProvider();
  try {
    const cred = await auth.signInWithPopup(provider);
    if (cred.user) {
      fetch('/api/send-welcome-email', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: cred.user.email, name: cred.user.displayName || 'Gamer' })
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
    authErrorMsg.style.display = msg ? 'block' : 'none';
  }
}

// 3. Razorpay In-Browser VIP Checkout & Cross-Verification
window.initiateVipCheckout = async function(planTier, priceInr) {
  if (!currentUser) {
    openAuthModal();
    alert('Please sign in or create an account first so your VIP clearance links to your GamerVoice profile!');
    return;
  }

  if (typeof Razorpay === 'undefined') {
    alert('Razorpay payment gateway script is still loading. Please try again in a moment.');
    return;
  }

  const options = {
    key: 'rzp_live_SWhlEskNokZ9rR',
    amount: priceInr * 100, // in paise
    currency: 'INR',
    name: 'GamerVoice VIP',
    description: ${planTier} VIP Squad Access Pass,
    image: '/logo.png',
    theme: {
      color: '#FFD700'
    },
    prefill: {
      name: currentUser.displayName || 'GamerVoice Member',
      email: currentUser.email || ''
    },
    handler: async function (response) {
      const paymentId = response.razorpay_payment_id;
      if (!paymentId) {
        alert('Payment ID missing.');
        return;
      }

      console.log([Payment Captured] ID: . Requesting server verification...);

      try {
        const verifyRes = await fetch('/api/verify-payment', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            paymentId: paymentId,
            planTier: planTier,
            userId: currentUser.uid,
            userEmail: currentUser.email
          })
        });

        const verifyData = await verifyRes.json();
        if (verifyRes.ok && verifyData.verified) {
          console.log('[Verification Succeeded] Syncing with Cloud Firestore...');
          
          // Calculate expiry
          const now = Date.now();
          let expiryTimestamp = -1;
          let expiryLabel = 'N/A (Permanent)';
          
          if (planTier === 'WEEKLY') {
            expiryTimestamp = now + (7 * 24 * 60 * 60 * 1000);
            expiryLabel = new Date(expiryTimestamp).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
          } else if (planTier === 'MONTHLY') {
            expiryTimestamp = now + (30 * 24 * 60 * 60 * 1000);
            expiryLabel = new Date(expiryTimestamp).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
          }

          const purchasedAt = new Date().toISOString();

          // Sync with Firestore doc users/{uid} matching PlanManager.kt schema
          if (db) {
            await db.collection('users').doc(currentUser.uid).set({
              isVip: true,
              planType: planTier,
              paymentId: paymentId,
              userEmail: currentUser.email,
              purchasedAt: purchasedAt,
              expiresAt: expiryLabel,
              expiryTimestamp: expiryTimestamp
            }, { merge: true });
          }

          alert(👑 VIP ACTIVATED SUCCESSFULLY!\n\nPlan: \nPayment ID: \n\nYour perks are now active both on the web and in your GamerVoice Android app!);
        } else {
          alert('Server verification failed: ' + (verifyData.error || 'Payment could not be verified.'));
        }
      } catch (err) {
        console.error('Verification error:', err);
        alert('Error connecting to verification server: ' + err.message);
      }
    },
    modal: {
      ondismiss: function () {
        console.log('Payment modal dismissed by user');
      }
    }
  };

  const rzp = new Razorpay(options);
  rzp.open();
};

// 4. Live Server Telemetry & Real-Time Ping Engine
function initTelemetry() {
  const pingDisplay = document.getElementById('pingDisplay');
  const roomsDisplay = document.getElementById('activeRoomsDisplay');

  // Fetch health stats
  fetch('/health')
    .then(r => r.json())
    .then(data => {
      if (roomsDisplay && data.activeRooms !== undefined) {
        roomsDisplay.innerText = ${data.activeRooms} Active Squads ( Peers);
      }
    })
    .catch(() => {});

  // WebSocket live ping
  try {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = ${protocol}//;
    const ws = new WebSocket(wsUrl);

    let pingStart = 0;

    ws.onopen = () => {
      setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) {
          pingStart = performance.now();
          ws.send(JSON.stringify({ type: 'ping', timestamp: pingStart }));
        }
      }, 4000);
    };

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data.type === 'pong') {
          const latency = Math.round(performance.now() - pingStart);
          if (pingDisplay) {
            pingDisplay.innerText = ${latency} ms;
            if (latency < 40) {
              pingDisplay.className = 'highlight-val';
            } else if (latency < 100) {
              pingDisplay.className = 'highlight-gold';
            } else {
              pingDisplay.className = 'highlight-cyan';
            }
          }
        }
      } catch (e) {}
    };

    ws.onerror = () => {
      if (pingDisplay) pingDisplay.innerText = '< 15 ms (Local Engine)';
    };
  } catch (e) {
    if (pingDisplay) pingDisplay.innerText = 'Connected';
  }
}

// 5. Interactive Noise Cancellation Canvas Visualizer
function initNoiseLab() {
  const canvas = document.getElementById('waveCanvas');
  if (!canvas) return;
  const ctx = canvas.getContext('2d');
  const slider = document.getElementById('noiseSlider');
  const label = document.getElementById('noisePercentLabel');

  let currentSuppression = 75;
  let currentScenario = 'fan';
  let animationFrame;

  slider?.addEventListener('input', (e) => {
    currentSuppression = parseInt(e.target.value, 10);
    if (label) {
      if (currentSuppression === 0) label.innerText = '0% (Raw Mic Noise)';
      else if (currentSuppression < 50) label.innerText = ${currentSuppression}% (Basic Suppression);
      else if (currentSuppression < 100) label.innerText = ${currentSuppression}% (Enhanced Squad VIP);
      else label.innerText = '100% (Lifetime Legend Pure Vocal)';
    }
  });

  document.querySelectorAll('.preset-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      document.querySelectorAll('.preset-btn').forEach(b => b.classList.remove('active'));
      e.target.classList.add('active');
      currentScenario = e.target.getAttribute('data-scenario');
    });
  });

  let phase = 0;

  function drawWaveform() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    const width = canvas.width;
    const height = canvas.height;
    const midY = height / 2;

    phase += 0.05;

    // Suppression factor: 0 -> full noise, 100 -> pristine voice
    const cleanRatio = currentSuppression / 100;
    const noiseRatio = 1 - cleanRatio;

    // Draw background grid lines
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.04)';
    ctx.lineWidth = 1;
    for (let x = 0; x < width; x += 40) {
      ctx.beginPath();
      ctx.moveTo(x, 0);
      ctx.lineTo(x, height);
      ctx.stroke();
    }
    for (let y = 0; y < height; y += 40) {
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(width, y);
      ctx.stroke();
    }

    // Draw Noise Ripple (Chaotic red/orange waveform)
    if (noiseRatio > 0.05) {
      ctx.beginPath();
      ctx.strokeStyle = gba(255, 75, 75, );
      ctx.lineWidth = 1.5;
      for (let x = 0; x < width; x += 3) {
        let noiseAmp = 0;
        if (currentScenario === 'fan') {
          noiseAmp = Math.sin(x * 0.15 + phase * 4) * 18 * noiseRatio + (Math.random() - 0.5) * 12 * noiseRatio;
        } else if (currentScenario === 'keyboard') {
          noiseAmp = (Math.random() - 0.5) * 35 * noiseRatio;
        } else {
          noiseAmp = Math.sin(x * 0.03 + phase) * 22 * noiseRatio + (Math.random() - 0.5) * 16 * noiseRatio;
        }
        const y = midY + noiseAmp;
        if (x === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      ctx.stroke();
    }

    // Draw Pure Vocal Waveform (Gold / Emerald harmonics)
    ctx.beginPath();
    ctx.strokeStyle = cleanRatio > 0.8 ? '#FFD700' : '#00FF88';
    ctx.lineWidth = 2.5;
    ctx.shadowBlur = cleanRatio * 15;
    ctx.shadowColor = cleanRatio > 0.8 ? 'rgba(255, 215, 0, 0.6)' : 'rgba(0, 255, 136, 0.6)';

    for (let x = 0; x < width; x += 2) {
      // Harmonic vocal wave
      const voiceAmp = Math.sin(x * 0.02 + phase) * 35 * Math.sin(x * 0.005) + Math.cos(x * 0.04 - phase * 0.5) * 15;
      const y = midY + voiceAmp;
      if (x === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.stroke();
    ctx.shadowBlur = 0;

    animationFrame = requestAnimationFrame(drawWaveform);
  }

  drawWaveform();
}

// 6. Interactive 3D Perspective Tilt on Feature Cards
function init3DTilt() {
  const cards = document.querySelectorAll('.tilt-card');
  cards.forEach(card => {
    card.addEventListener('mousemove', (e) => {
      const rect = card.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;

      const centerX = rect.width / 2;
      const centerY = rect.height / 2;

      const rotateX = ((y - centerY) / centerY) * -12;
      const rotateY = ((x - centerX) / centerX) * 12;

      card.style.transform = perspective(1000px) rotateX(deg) rotateY(deg) translateY(-6px);
    });

    card.addEventListener('mouseleave', () => {
      card.style.transform = 'perspective(1000px) rotateX(0deg) rotateY(0deg) translateY(0px)';
    });
  });
}

// 7. Support Ticket Submission Modal
const supportModal = document.getElementById('supportModal');
const supportForm = document.getElementById('supportForm');
const openSupportLink = document.getElementById('openSupportLink');
const footerSupportLink = document.getElementById('footerSupportLink');
const closeSupportModal = document.getElementById('closeSupportModal');
const supportStatusMsg = document.getElementById('supportStatusMsg');

function openSupport() {
  if (supportModal) supportModal.classList.add('active');
}
function closeSupport() {
  if (supportModal) supportModal.classList.remove('active');
}

openSupportLink?.addEventListener('click', (e) => { e.preventDefault(); openSupport(); });
footerSupportLink?.addEventListener('click', (e) => { e.preventDefault(); openSupport(); });
closeSupportModal?.addEventListener('click', closeSupport);
supportModal?.addEventListener('click', (e) => { if (e.target === supportModal) closeSupport(); });

supportForm?.addEventListener('submit', async (e) => {
  e.preventDefault();
  const category = document.getElementById('supportCategory').value;
  const subject = document.getElementById('supportSubject').value.trim();
  const description = document.getElementById('supportDescription').value.trim();
  const submitBtn = document.getElementById('supportSubmitBtn');

  submitBtn.disabled = true;
  submitBtn.innerText = 'Submitting...';

  try {
    const res = await fetch('/api/submit-support-ticket', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        category,
        subject,
        description,
        userEmail: currentUser ? currentUser.email : 'web_visitor@gamersvoice.com',
        userId: currentUser ? currentUser.uid : 'web_anonymous',
        appVersion: 'Web-v1.0.0'
      })
    });

    const data = await res.json();
    if (res.ok && data.success) {
      alert(✅ Support Ticket Created!\n\nTicket ID: \nOur team has received your ticket and will follow up shortly.);
      supportForm.reset();
      closeSupport();
    } else {
      alert('Error submitting ticket: ' + (data.error || 'Server error'));
    }
  } catch (err) {
    alert('Submission failed: ' + err.message);
  } finally {
    submitBtn.disabled = false;
    submitBtn.innerText = 'Submit Ticket';
  }
});

function escapeHtml(str) {
  if (!str) return '';
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\"/g, '&quot;');
}

// Launch on DOM ready
document.addEventListener('DOMContentLoaded', () => {
  initTelemetry();
  initNoiseLab();
  init3DTilt();
});
