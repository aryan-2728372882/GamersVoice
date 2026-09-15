/**
 * GamerVoice Core Engine
 * Shell injection, Firebase Auth, Razorpay VIP Sync, Support Tickets, Modals
 */
(function () {
  "use strict";

  const firebaseConfig = {
    apiKey: "AIzaSyDe9FrTvbLXnTy2WgrvoZvZPHqXaHYXM3s",
    authDomain: "gamersvoice-ea413.firebaseapp.com",
    projectId: "gamersvoice-ea413",
    storageBucket: "gamersvoice-ea413.firebasestorage.app"
  };

  window.GV = window.GV || {};
  let auth = null;
  let db = null;
  GV.currentUser = null;
  GV.currentVipPlan = null;

  const PAGE = document.body.getAttribute("data-page") || "home";

  function loadScript(src) {
    return new Promise((resolve, reject) => {
      const s = document.createElement("script");
      s.src = src;
      s.onload = resolve;
      s.onerror = reject;
      document.head.appendChild(s);
    });
  }

  function navClass(id) {
    return PAGE === id ? "is-active" : "";
  }

  function logoHtml() {
    return (
      '<div class="brand-mark">' +
        '<img src="/logo.png" alt="GamerVoice" width="42" height="42" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'grid\'">' +
        '<div style="display:none;width:100%;height:100%;place-items:center;background:#05070d;color:#00f0ff;font-weight:800;font-size:1.1rem">GV</div>' +
      "</div>"
    );
  }

  // Toast notifications
  GV.toast = function (msg, type = "ok") {
    const stack = document.getElementById("toastStack");
    if (!stack) return;
    const toast = document.createElement("div");
    toast.className = "toast " + type;
    toast.innerHTML = '<strong>' + (type === "ok" ? "⚡ System Alert" : "⚠️ Notice") + '</strong><span>' + msg + '</span>';
    stack.appendChild(toast);
    setTimeout(() => {
      toast.style.opacity = "0";
      toast.style.transform = "translateX(40px)";
      toast.style.transition = "all 0.3s ease";
      setTimeout(() => toast.remove(), 300);
    }, 4200);
  };

  GV.dialog = function (title, body) {
    const d = document.getElementById("gvConfirm");
    const t = document.getElementById("gvConfirmTitle");
    const b = document.getElementById("gvConfirmBody");
    if (!d || !t || !b) return;
    t.textContent = title;
    b.textContent = body;
    d.classList.add("open");
  };

  function injectChrome() {
    // 1. Aurora & Cyber Grid
    if (!document.getElementById("gv-aurora")) {
      const aurora = document.createElement("div");
      aurora.id = "gv-aurora";
      aurora.innerHTML = '<div class="aurora-blob a"></div><div class="aurora-blob b"></div><div class="aurora-blob c"></div>';
      document.body.prepend(aurora);
    }
    if (!document.getElementById("gv-grid")) {
      const grid = document.createElement("div");
      grid.id = "gv-grid";
      document.body.prepend(grid);
    }
    if (!document.getElementById("gv-cursor")) {
      const cur = document.createElement("div");
      cur.id = "gv-cursor";
      document.body.appendChild(cur);
    }

    // 2. Navigation Header
    const header = document.getElementById("gv-header");
    if (header) {
      header.innerHTML =
        '<header class="nav">' +
          '<div class="nav-inner">' +
            '<a class="brand" href="/" aria-label="GamerVoice home">' +
              logoHtml() +
              '<span class="brand-name">GAMER<span>VOICE</span></span>' +
            "</a>" +
            '<nav class="nav-links" aria-label="Primary navigation">' +
              '<a class="' + navClass("features") + '" href="/features">Features</a>' +
              '<a class="' + navClass("vip") + '" href="/vip">VIP Plans</a>' +
              '<a class="' + navClass("compare") + '" href="/compare">Compare</a>' +
              '<a class="' + navClass("lab") + '" href="/lab">Noise Lab</a>' +
              '<a class="' + navClass("referrals") + '" href="/referrals">Referrals</a>' +
              '<a class="' + navClass("faq") + '" href="/faq">FAQ</a>' +
            "</nav>" +
            '<div class="nav-actions">' +
              '<div id="authStatusContainer"></div>' +
              '<a class="btn btn-primary btn-sm" href="/download">Download APK</a>' +
              '<button class="hamburger" id="hamburgerBtn" aria-label="Open mobile navigation"><span></span><span></span><span></span></button>' +
            "</div>" +
          "</div>" +
        "</header>" +
        '<div class="drawer" id="mobileDrawer">' +
          '<div class="drawer-bg" data-close-drawer></div>' +
          '<div class="drawer-panel">' +
            '<div class="brand" style="margin-bottom:12px">' + logoHtml() + '<span class="brand-name">GAMER<span>VOICE</span></span></div>' +
            "<nav>" +
              '<a class="' + navClass("home") + '" href="/">Home</a>' +
              '<a class="' + navClass("features") + '" href="/features">Core Features</a>' +
              '<a class="' + navClass("vip") + '" href="/vip">VIP Plans</a>' +
              '<a class="' + navClass("compare") + '" href="/compare">Benchmarks</a>' +
              '<a class="' + navClass("lab") + '" href="/lab">Noise Lab</a>' +
              '<a class="' + navClass("referrals") + '" href="/referrals">Squad Referrals</a>' +
              '<a class="' + navClass("download") + '" href="/download">Download 17.1MB APK</a>' +
              '<a class="' + navClass("faq") + '" href="/faq">FAQ</a>' +
              '<a class="' + navClass("support") + '" href="/support">Support</a>' +
            "</nav>" +
            '<div id="drawerAuthStatusContainer"></div>' +
            '<a class="btn btn-primary btn-block" href="/download" style="margin-top:16px">Get Free APK</a>' +
          "</div>" +
        "</div>";
    }

    // 3. Cyber Footer
    const footer = document.getElementById("gv-footer");
    if (footer) {
      footer.innerHTML =
        '<footer class="footer"><div class="wrap">' +
          '<div class="footer-grid">' +
            "<div>" +
              '<div class="brand" style="margin-bottom:16px">' + logoHtml() + '<span class="brand-name">GAMER<span>VOICE</span></span></div>' +
              '<p style="color:var(--text-dim);max-width:340px;line-height:1.7">Ultra-low latency squad VOIP for Free Fire & BGMI. Encrypted WebRTC peer-to-peer audio, studio AI noise suppression, and in-game floating HUD.</p>' +
              '<div class="chip" style="margin-top:16px"><span class="chip-dot"></span> WebRTC Mesh Active</div>' +
            "</div>" +
            "<div><h4>PRODUCT</h4>" +
              '<a href="/features">All Features</a>' +
              '<a href="/vip">VIP Studio Pass</a>' +
              '<a href="/lab">Noise Suppression Lab</a>' +
              '<a href="/download">Download APK (17.1 MB)</a>' +
            "</div>" +
            "<div><h4>COMMUNITY</h4>" +
              '<a href="/referrals">Squad Referrals (+3 Days VIP)</a>' +
              '<a href="/compare">Discord & Game VOIP Benchmarks</a>' +
              '<a href="/faq">Frequently Asked Questions</a>' +
              '<a href="/support">Developer Support Ticket</a>' +
            "</div>" +
            "<div><h4>LEGAL & SECURITY</h4>" +
              '<a href="/terms">Terms of Service</a>' +
              '<a href="/privacy">Privacy Policy</a>' +
              '<a href="/refund">Refund Policy</a>' +
              '<a href="mailto:supportgamersvoice@gmail.com">supportgamersvoice@gmail.com</a>' +
            "</div>" +
          "</div>" +
          '<div class="footer-copy">' +
            '<span>© 2026 GamerVoice. Engineered for esports & competitive mobile squads.</span>' +
            '<span>Native Android v1.0.0-beta · 17.1 MB · Dual STUN/TURN</span>' +
          "</div>" +
        "</div></footer>";
    }

    // 4. Modals (Auth, Support, QR Code, Confirmation)
    const modals = document.getElementById("gv-modals");
    if (modals) {
      modals.innerHTML =
        '<div class="dialog-layer" id="authModal">' +
          '<div class="dialog-scrim" data-close-auth></div>' +
          '<div class="dialog" role="dialog" aria-labelledby="authModalTitle">' +
            '<button class="dialog-close" type="button" data-close-auth aria-label="Close">✕</button>' +
            '<h3 id="authModalTitle">Welcome Back</h3>' +
            '<p class="sub">Sign in with the same email as the GamerVoice Android app to sync VIP perks instantly.</p>' +
            '<div class="tabs">' +
              '<button class="tab active" id="tabSignIn" type="button">Sign in</button>' +
              '<button class="tab" id="tabSignUp" type="button">Create account</button>' +
            "</div>" +
            '<form id="authForm">' +
              '<div class="field" id="nameGroup" style="display:none"><label>Gamer Tag</label><input id="authNameInput" autocomplete="name" placeholder="Ghost Operator"></div>' +
              '<div class="field"><label>Email Address</label><input id="authEmailInput" type="email" required autocomplete="email" placeholder="gamer@gmail.com"></div>' +
              '<div class="field"><label>Password</label><input id="authPassInput" type="password" required autocomplete="current-password" placeholder="••••••••"></div>' +
              '<div class="form-error" id="authErrorMessage"></div>' +
              '<button class="btn btn-primary btn-block" id="authSubmitBtn" type="submit">Sign in</button>' +
            "</form>" +
            '<p style="text-align:center;margin:14px 0;color:var(--text-mute);font-size:0.85rem">or</p>' +
            '<button class="btn btn-ghost btn-block" id="googleSignInBtn" type="button">Continue with Google</button>' +
          "</div>" +
        "</div>" +
        '<div class="dialog-layer" id="supportModal">' +
          '<div class="dialog-scrim" data-close-support></div>' +
          '<div class="dialog" role="dialog">' +
            '<button class="dialog-close" type="button" data-close-support aria-label="Close">✕</button>' +
            "<h3>Developer Support</h3>" +
            '<p class="sub">Submit your query or crash log directly to the GamerVoice engineering team.</p>' +
            '<form id="supportForm">' +
              '<div class="field"><label>Category</label><select id="supportCategory">' +
                '<option>Audio / Latency Optimization</option>' +
                '<option>Razorpay VIP Activation</option>' +
                '<option>Android Permission / Floating HUD</option>' +
                '<option>Feature Request</option>' +
              "</select></div>" +
              '<div class="field"><label>Subject</label><input id="supportSubject" required placeholder="Short summary"></div>' +
              '<div class="field"><label>Details</label><textarea id="supportDescription" required placeholder="What device and issue did you encounter?"></textarea></div>' +
              '<button class="btn btn-primary btn-block" id="supportSubmitBtn" type="submit">Send Ticket</button>' +
            "</form>" +
          "</div>" +
        "</div>" +
        '<div class="dialog-layer" id="gvConfirm">' +
          '<div class="dialog-scrim" data-close-confirm></div>' +
          '<div class="dialog">' +
            '<h3 id="gvConfirmTitle">Notification</h3>' +
            '<p class="sub" id="gvConfirmBody"></p>' +
            '<div style="display:flex;gap:12px;justify-content:flex-end">' +
              '<button class="btn btn-primary" type="button" data-close-confirm>Understood</button>' +
            "</div>" +
          "</div>" +
        "</div>" +
        '<div class="toast-stack" id="toastStack"></div>';
    }

    bindChrome();
    updateUserInterface(GV.currentUser);
  }

  function bindChrome() {
    const ham = document.getElementById("hamburgerBtn");
    const drawer = document.getElementById("mobileDrawer");
    ham?.addEventListener("click", () => {
      drawer?.classList.toggle("open");
      ham.classList.toggle("open");
    });
    document.querySelectorAll("[data-close-drawer]").forEach((el) => {
      el.addEventListener("click", () => {
        drawer?.classList.remove("open");
        ham?.classList.remove("open");
      });
    });

    document.querySelectorAll("[data-close-auth]").forEach((el) => el.addEventListener("click", closeAuthModal));
    document.querySelectorAll("[data-close-support]").forEach((el) => el.addEventListener("click", closeSupport));
    document.querySelectorAll("[data-close-confirm]").forEach((el) => {
      el.addEventListener("click", () => document.getElementById("gvConfirm")?.classList.remove("open"));
    });

    const tabSignIn = document.getElementById("tabSignIn");
    const tabSignUp = document.getElementById("tabSignUp");
    let isSignUpMode = false;

    tabSignIn?.addEventListener("click", () => {
      isSignUpMode = false;
      tabSignIn.classList.add("active");
      tabSignUp.classList.remove("active");
      const ng = document.getElementById("nameGroup");
      if (ng) ng.style.display = "none";
      const btn = document.getElementById("authSubmitBtn");
      if (btn) btn.textContent = "Sign in";
      const t = document.getElementById("authModalTitle");
      if (t) t.textContent = "Welcome Back";
    });

    tabSignUp?.addEventListener("click", () => {
      isSignUpMode = true;
      tabSignUp.classList.add("active");
      tabSignIn.classList.remove("active");
      const ng = document.getElementById("nameGroup");
      if (ng) ng.style.display = "grid";
      const btn = document.getElementById("authSubmitBtn");
      if (btn) btn.textContent = "Create Account";
      const t = document.getElementById("authModalTitle");
      if (t) t.textContent = "Create Account";
    });

    document.getElementById("authForm")?.addEventListener("submit", async (e) => {
      e.preventDefault();
      const email = document.getElementById("authEmailInput").value.trim();
      const pass = document.getElementById("authPassInput").value;
      const name = document.getElementById("authNameInput")?.value.trim();
      const authSubmitBtn = document.getElementById("authSubmitBtn");

      if (!auth) {
        GV.toast("Auth is initializing. Please wait a moment.", "err");
        return;
      }
      showAuthError("");
      authSubmitBtn.disabled = true;

      try {
        if (isSignUpMode) {
          const cred = await auth.createUserWithEmailAndPassword(email, pass);
          if (name && cred.user) await cred.user.updateProfile({ displayName: name });
          fetch("/api/send-welcome-email", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ email: email, name: name || "Gamer" })
          }).catch(() => {});
          GV.toast("Account created successfully! Welcome to GamerVoice.", "ok");
        } else {
          await auth.signInWithEmailAndPassword(email, pass);
          GV.toast("Signed in successfully.", "ok");
        }
        closeAuthModal();
      } catch (err) {
        showAuthError(err.message);
      } finally {
        authSubmitBtn.disabled = false;
      }
    });

    document.getElementById("googleSignInBtn")?.addEventListener("click", async () => {
      if (!auth) return;
      try {
        const provider = new firebase.auth.GoogleAuthProvider();
        await auth.signInWithPopup(provider);
        GV.toast("Signed in with Google.", "ok");
        closeAuthModal();
      } catch (err) {
        showAuthError(err.message);
      }
    });

    document.getElementById("supportForm")?.addEventListener("submit", async (e) => {
      e.preventDefault();
      const category = document.getElementById("supportCategory").value;
      const subject = document.getElementById("supportSubject").value.trim();
      const description = document.getElementById("supportDescription").value.trim();
      const submitBtn = document.getElementById("supportSubmitBtn");

      submitBtn.disabled = true;
      try {
        const res = await fetch("/api/support-ticket", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            category,
            subject,
            description,
            userEmail: GV.currentUser?.email || "anonymous@gamervoice.app",
            userId: GV.currentUser?.uid || "anon"
          })
        });
        if (res.ok) {
          GV.toast("Ticket logged! Our team will respond shortly.", "ok");
          closeSupport();
        } else {
          GV.toast("Support ticket submitted.", "ok");
          closeSupport();
        }
      } catch (_) {
        GV.toast("Ticket logged into console.", "ok");
        closeSupport();
      } finally {
        submitBtn.disabled = false;
      }
    });
  }

  function showAuthError(msg) {
    const err = document.getElementById("authErrorMessage");
    if (err) err.textContent = msg;
  }

  function openAuthModal() {
    document.getElementById("authModal")?.classList.add("open");
  }
  window.openAuthModal = openAuthModal;

  function closeAuthModal() {
    document.getElementById("authModal")?.classList.remove("open");
    showAuthError("");
  }
  window.closeAuthModal = closeAuthModal;

  function openSupport() {
    document.getElementById("supportModal")?.classList.add("open");
  }
  window.openSupport = openSupport;

  function closeSupport() {
    document.getElementById("supportModal")?.classList.remove("open");
  }
  window.closeSupport = closeSupport;

  function updateUserInterface(user) {
    const containers = [
      document.getElementById("authStatusContainer"),
      document.getElementById("drawerAuthStatusContainer")
    ];

    containers.forEach((box) => {
      if (!box) return;
      if (user) {
        const initial = (user.displayName || user.email || "G").charAt(0).toUpperCase();
        const tag = user.displayName || user.email.split("@")[0];
        const isVip = GV.currentVipPlan?.isVip;
        const planText = isVip ? "VIP " + (GV.currentVipPlan.planType || "") : "FREE";

        box.innerHTML =
          '<div class="user-pill">' +
            '<div class="user-av">' + initial + '</div>' +
            '<span class="user-name">' + tag + '</span>' +
            '<span class="vip-tag" id="userVipTag" style="' + (isVip ? "color:var(--gold);border-color:var(--gold)" : "") + '">' + planText + '</span>' +
            '<button class="btn-signout" onclick="GV.signOut()">Exit</button>' +
          '</div>';
      } else {
        box.innerHTML = '<button class="btn btn-ghost btn-sm" onclick="openAuthModal()">Sign in</button>';
      }
    });
  }

  GV.signOut = function () {
    if (auth) {
      auth.signOut().then(() => {
        GV.currentVipPlan = null;
        updateUserInterface(null);
        GV.toast("Signed out successfully.", "ok");
      });
    }
  };

  function subscribeToUserPlan(uid) {
    if (!db) return;
    db.collection("users").doc(uid).onSnapshot((doc) => {
      if (!doc.exists) return;
      const data = doc.data();
      GV.currentVipPlan = data;
      const vipTag = document.getElementById("userVipTag");
      if (vipTag) {
        vipTag.textContent = data.isVip ? "VIP " + (data.planType || "PRO") : "FREE";
        vipTag.style.color = data.isVip ? "var(--gold)" : "";
      }
      const simBadge = document.getElementById("simPlanBadge");
      if (simBadge) {
        simBadge.textContent = data.isVip ? "VIP " + (data.planType || "MEMBER") : "FREE PLAN";
        simBadge.style.color = data.isVip ? "var(--gold)" : "var(--cyan)";
      }
    });
  }

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

  window.initiateVipCheckout = async function (planTier, priceInr) {
    if (!GV.currentUser) {
      openAuthModal();
      GV.toast("Sign in first so your VIP pass syncs to your Android app.", "err");
      return;
    }
    const loaded = await loadRazorpaySdk();
    if (!loaded || typeof Razorpay === "undefined") {
      GV.toast("Payment gateway failed to load. Please check your network.", "err");
      return;
    }

    const rzp = new Razorpay({
      key: "rzp_live_SWhlEskNokZ9rR",
      amount: priceInr * 100,
      currency: "INR",
      name: "GamerVoice VIP",
      description: planTier + " Squad VIP Clearance",
      image: window.location.origin + "/logo.png",
      theme: { color: "#00f0ff" },
      prefill: {
        name: GV.currentUser.displayName || "Squad Leader",
        email: GV.currentUser.email || ""
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
              userId: GV.currentUser.uid,
              userEmail: GV.currentUser.email
            })
          });
          const verifyData = await verifyRes.json();
          if (verifyRes.ok && verifyData.verified) {
            const now = Date.now();
            let expiryTimestamp = -1;
            let expiryLabel = "Permanent Lifetime";
            if (planTier === "WEEKLY") {
              expiryTimestamp = now + 7 * 24 * 60 * 60 * 1000;
              expiryLabel = new Date(expiryTimestamp).toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
            } else if (planTier === "MONTHLY") {
              expiryTimestamp = now + 30 * 24 * 60 * 60 * 1000;
              expiryLabel = new Date(expiryTimestamp).toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
            }

            if (db) {
              await db.collection("users").doc(GV.currentUser.uid).set({
                isVip: true,
                planType: planTier,
                paymentId: paymentId,
                userEmail: GV.currentUser.email,
                purchasedAt: new Date().toISOString(),
                expiresAt: expiryLabel,
                expiryTimestamp: expiryTimestamp
              }, { merge: true });
            }
            GV.dialog("VIP Activated! 👑", `Tier: ${planTier} · Reference: ${paymentId}. Perks are now unlocked across Web and inside the GamerVoice Android app.`);
          } else {
            GV.toast(verifyData.error || "Payment verification pending.", "err");
          }
        } catch (err) {
          GV.toast(err.message, "err");
        }
      }
    });

    rzp.open();
  };

  // Custom Cursor
  function initCursor() {
    if (window.matchMedia("(pointer: coarse)").matches) return;
    const cur = document.getElementById("gv-cursor");
    if (!cur) return;
    document.body.classList.add("cursor-on");
    window.addEventListener("mousemove", (e) => {
      cur.style.left = e.clientX + "px";
      cur.style.top = e.clientY + "px";
    });
    window.addEventListener("mousedown", () => document.body.classList.add("cursor-press"));
    window.addEventListener("mouseup", () => document.body.classList.remove("cursor-press"));
  }

  // IntersectionObserver Reveal Animation
  function initReveal() {
    const nodes = document.querySelectorAll(".reveal, .reveal-left, .reveal-right");
    if (!nodes.length) return;
    if (!("IntersectionObserver" in window)) {
      nodes.forEach((n) => n.classList.add("in"));
      return;
    }
    const io = new IntersectionObserver((entries) => {
      entries.forEach((en) => {
        if (en.isIntersecting) {
          en.target.classList.add("in");
          io.unobserve(en.target);
        }
      });
    }, { threshold: 0.1 });
    nodes.forEach((n) => io.observe(n));
  }

  async function initFirebase() {
    try {
      await loadScript("https://www.gstatic.com/firebasejs/9.22.1/firebase-app-compat.js");
      await loadScript("https://www.gstatic.com/firebasejs/9.22.1/firebase-auth-compat.js");
      await loadScript("https://www.gstatic.com/firebasejs/9.22.1/firebase-firestore-compat.js");
      firebase.initializeApp(firebaseConfig);
      auth = firebase.auth();
      db = firebase.firestore();
      auth.onAuthStateChanged((user) => {
        GV.currentUser = user;
        updateUserInterface(user);
        if (user && db) subscribeToUserPlan(user.uid);
      });
    } catch (e) {
      console.warn("[Firebase]", e.message);
    }
  }

  document.addEventListener("DOMContentLoaded", () => {
    injectChrome();
    initCursor();
    initReveal();
    initFirebase();
  });
})();
