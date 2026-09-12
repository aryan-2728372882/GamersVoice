/**
 * GamerVoice Exclusive Admin Command Center Application Engine
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
  app = firebase.initializeApp(firebaseConfig, "AdminApp");
  auth = firebase.auth(app);
  db = firebase.firestore(app);
} catch (e) {
  try {
    app = firebase.app("AdminApp");
    auth = firebase.auth(app);
    db = firebase.firestore(app);
  } catch (_) {}
}

let adminToken = sessionStorage.getItem("gv_admin_token") || null;
let adminEmail = sessionStorage.getItem("gv_admin_email") || null;
let cachedUsers = [];
let activeEditingUser = null;
let telemetryTimer = null;

// Switch between Firebase Login and Master Passcode tabs
window.switchAuthTab = function(tab) {
  const btnFb = document.getElementById("btnAuthTabFirebase");
  const btnMaster = document.getElementById("btnAuthTabMaster");
  const panelFb = document.getElementById("authPanelFirebase");
  const panelMaster = document.getElementById("authPanelMaster");

  if (tab === "firebase") {
    btnFb?.classList.add("active");
    btnMaster?.classList.remove("active");
    panelFb?.classList.add("active");
    panelMaster?.classList.remove("active");
  } else {
    btnMaster?.classList.add("active");
    btnFb?.classList.remove("active");
    panelMaster?.classList.add("active");
    panelFb?.classList.remove("active");
  }
};

// 2. Authentication: Firebase Admin Login
window.submitAdminFirebaseLogin = async function(e) {
  if (e) e.preventDefault();
  const emailInput = document.getElementById("adminEmailInput");
  const passwordInput = document.getElementById("adminPasswordInput");
  const errorMsg = document.getElementById("firebaseAuthErrorMsg");
  const submitBtn = document.getElementById("btnFirebaseLoginSubmit");

  const email = emailInput ? emailInput.value.trim().toLowerCase() : "";
  const password = passwordInput ? passwordInput.value : "";

  if (!email || !password) {
    if (errorMsg) errorMsg.innerText = "Please enter both administrator email and password.";
    return;
  }

  if (!auth) {
    if (errorMsg) errorMsg.innerText = "Firebase Client Auth not ready. Please refresh the page.";
    return;
  }

  if (submitBtn) {
    submitBtn.disabled = true;
    submitBtn.innerText = "Verifying Credentials...";
  }
  if (errorMsg) errorMsg.innerText = "";

  try {
    // 1. Authenticate with Firebase Client Auth
    const userCredential = await auth.signInWithEmailAndPassword(email, password);
    const user = userCredential.user;
    const idToken = await user.getIdToken(true);

    // 2. Validate token and admin IAM privileges on server
    const res = await fetch("/api/admin/verify-token", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idToken })
    });
    const data = await res.json();

    if (res.ok && data.authenticated) {
      adminToken = data.token;
      adminEmail = data.email || email;
      sessionStorage.setItem("gv_admin_token", adminToken);
      sessionStorage.setItem("gv_admin_email", adminEmail);

      updateAdminSessionBadge(adminEmail);
      document.getElementById("authLockOverlay").style.display = "none";
      initAdminDashboard();
    } else {
      if (errorMsg) errorMsg.innerText = data.error || "Authentication denied: Not authorized as administrator.";
      try { await auth.signOut(); } catch (_) {}
    }
  } catch (err) {
    console.error("Firebase Login Error:", err);
    let friendly = err.message;
    if (err.code === "auth/user-not-found" || err.code === "auth/wrong-password" || err.code === "auth/invalid-credential") {
      friendly = "Invalid admin email or password.";
    } else if (err.code === "auth/too-many-requests") {
      friendly = "Too many failed attempts. Please wait a moment.";
    }
    if (errorMsg) errorMsg.innerText = friendly;
  } finally {
    if (submitBtn) {
      submitBtn.disabled = false;
      submitBtn.innerText = "Sign In to Admin Panel";
    }
  }
};

// Authentication: Master Passcode Unlock
window.submitAdminPasscode = async function(e) {
  if (e) e.preventDefault();
  const input = document.getElementById("adminPasscodeInput");
  const errorMsg = document.getElementById("lockErrorMsg");
  const key = input ? input.value.trim() : "";

  if (!key) {
    if (errorMsg) errorMsg.innerText = "Please enter the admin master passcode.";
    return;
  }

  try {
    const res = await fetch("/api/admin/auth", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ key })
    });
    const data = await res.json();

    if (res.ok && data.authenticated) {
      adminToken = data.token;
      adminEmail = "Root Master Key";
      sessionStorage.setItem("gv_admin_token", adminToken);
      sessionStorage.setItem("gv_admin_email", adminEmail);

      updateAdminSessionBadge(adminEmail);
      document.getElementById("authLockOverlay").style.display = "none";
      initAdminDashboard();
    } else {
      if (errorMsg) errorMsg.innerText = data.error || "Authentication denied: Invalid passcode.";
    }
  } catch (err) {
    if (errorMsg) errorMsg.innerText = "Server connection error: " + err.message;
  }
};

function updateAdminSessionBadge(userLabel) {
  const badge = document.getElementById("adminSessionUserBadge");
  if (badge) {
    badge.innerText = "🛡️ " + userLabel;
    badge.style.display = "inline-block";
  }
}

window.lockAdminSession = function() {
  adminToken = null;
  adminEmail = null;
  sessionStorage.removeItem("gv_admin_token");
  sessionStorage.removeItem("gv_admin_email");
  if (telemetryTimer) clearInterval(telemetryTimer);
  document.getElementById("authLockOverlay").style.display = "flex";
  const passInput = document.getElementById("adminPasscodeInput");
  if (passInput) passInput.value = "";
  const emailInput = document.getElementById("adminEmailInput");
  if (emailInput) emailInput.value = "";
  const pwdInput = document.getElementById("adminPasswordInput");
  if (pwdInput) pwdInput.value = "";
  const badge = document.getElementById("adminSessionUserBadge");
  if (badge) badge.style.display = "none";
  try { if (auth) auth.signOut(); } catch (_) {}
};

// Check if already authenticated on initial load
document.addEventListener("DOMContentLoaded", () => {
  if (adminToken) {
    if (adminEmail) updateAdminSessionBadge(adminEmail);
    const overlay = document.getElementById("authLockOverlay");
    if (overlay) overlay.style.display = "none";
    initAdminDashboard();
  }
});

// 3. Initialize Dashboard & Real-Time Sync
async function initAdminDashboard() {
  await loadServerStats();
  await loadAllFirestoreUsers();

  // Periodic telemetry refresh every 4 seconds
  if (telemetryTimer) clearInterval(telemetryTimer);
  telemetryTimer = setInterval(loadServerStats, 4000);
}

// 4. Server Diagnostics & Active Rooms Telemetry
async function loadServerStats() {
  if (!adminToken) return;

  try {
    const res = await fetch("/api/admin/server-stats", {
      headers: { "x-admin-key": adminToken }
    });
    if (!res.ok) {
      if (res.status === 401) lockAdminSession();
      return;
    }
    const data = await res.json();

    // Update Ribbon Metrics
    document.getElementById("statActiveRooms").innerText = data.totalRooms || 0;
    document.getElementById("statConnectedPeers").innerText = data.totalClients || 0;
    document.getElementById("statHeapUsage").innerText = (data.memoryUsage?.heapUsedMb || 0) + " MB";

    // Format uptime
    const uptime = data.uptimeSeconds || 0;
    const hours = Math.floor(uptime / 3600);
    const mins = Math.floor((uptime % 3600) / 60);
    document.getElementById("statUptime").innerText = `${hours}h ${mins}m`;

    renderActiveRoomsList(data.activeRooms || []);
  } catch (err) {
    console.warn("Telemetry fetch error:", err);
  }
}

function renderActiveRoomsList(rooms) {
  const container = document.getElementById("activeRoomsContainer");
  if (!container) return;

  if (rooms.length === 0) {
    container.innerHTML = `
      <div style="grid-column: 1 / -1; text-align: center; padding: 40px; color: var(--text-muted); background: var(--bg-card); border-radius: var(--radius-md); border: 1px dashed var(--border-glass);">
        <div style="font-size: 2rem; margin-bottom: 8px;">🎮</div>
        <p style="font-weight: 700;">No squad rooms are currently open in server memory.</p>
        <p style="font-size: 0.8rem; margin-top: 4px;">Rooms appear in real-time when squads create or join voice channels.</p>
      </div>
    `;
    return;
  }

  container.innerHTML = rooms.map(room => {
    const peerItems = room.peers.map(p => `
      <div class="room-peer-item">
        <span>👤 <strong>${escapeHtml(p.name)}</strong> (${p.peerId.slice(0, 8)}...)</span>
        <span style="color: var(--emerald-primary); font-size: 0.72rem;">● Active</span>
      </div>
    `).join("");

    return `
      <div class="room-card">
        <div class="room-card-header">
          <div>
            <div class="room-code-badge">${escapeHtml(room.roomCode)}</div>
            <div style="font-size: 0.75rem; color: var(--text-muted);">${room.peerCount} / 5 Squad Members</div>
          </div>
          <button class="btn-admin btn-danger" onclick="closeSquadRoom('${escapeHtml(room.roomCode)}')">
            ✕ Force Close
          </button>
        </div>
        <div class="room-peers-list">
          ${peerItems}
        </div>
      </div>
    `;
  }).join("");
}

window.closeSquadRoom = async function(roomCode) {
  if (!confirm(`Are you sure you want to terminate squad room "${roomCode}"? All connected players will be disconnected.`)) {
    return;
  }

  try {
    const res = await fetch("/api/admin/close-room", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-admin-key": adminToken
      },
      body: JSON.stringify({ roomCode })
    });
    const data = await res.json();
    if (res.ok) {
      alert(`Room ${roomCode} successfully terminated.`);
      loadServerStats();
    } else {
      alert("Error closing room: " + (data.error || "Unknown"));
    }
  } catch (err) {
    alert("Request error: " + err.message);
  }
};

// 5. Firestore Users Synchronization (via Secure Server Admin SDK)
async function loadAllFirestoreUsers() {
  if (!adminToken) return;

  try {
    const res = await fetch("/api/admin/users", {
      headers: { "x-admin-key": adminToken }
    });
    if (res.ok) {
      const data = await res.json();
      if (data && Array.isArray(data.users)) {
        cachedUsers = data.users;
        calculateAndRenderMetrics();
        filterAndRenderUsers();
        renderTransactionsLedger();
        return;
      }
    }
  } catch (err) {
    console.warn("Server admin users API failed, checking client SDK fallback:", err);
  }

  // Fallback to client SDK if available
  if (db) {
    try {
      const snapshot = await db.collection("users").get();
      cachedUsers = [];
      snapshot.forEach(doc => {
        cachedUsers.push({ id: doc.id, ...doc.data() });
      });

      calculateAndRenderMetrics();
      filterAndRenderUsers();
      renderTransactionsLedger();
    } catch (err) {
      console.error("Error loading Firestore users:", err);
    }
  }
}

function calculateAndRenderMetrics() {
  const totalUsers = cachedUsers.length;
  let activeVips = 0;
  let totalRevenue = 0;

  const now = Date.now();
  cachedUsers.forEach(u => {
    const isVip = u.isVip === true;
    const expiry = u.expiryTimestamp || 0;
    const isPermanent = u.planType === "LIFETIME" || expiry === -1;
    const isNotExpired = isPermanent || (expiry > now);

    if (isVip && isNotExpired) {
      activeVips++;
    }

    if (u.planType === "WEEKLY") totalRevenue += 29;
    else if (u.planType === "MONTHLY") totalRevenue += 89;
    else if (u.planType === "LIFETIME") totalRevenue += 249;
  });

  document.getElementById("statTotalUsers").innerText = totalUsers;
  document.getElementById("statActiveVips").innerText = activeVips;
  document.getElementById("statTotalRevenue").innerText = `₹${totalRevenue.toLocaleString()}`;
}

function filterAndRenderUsers() {
  const query = (document.getElementById("userSearchInput")?.value || "").toLowerCase().trim();
  const filterTier = document.getElementById("userTierFilter")?.value || "ALL";
  const tbody = document.getElementById("usersTableBody");
  if (!tbody) return;

  const now = Date.now();

  const filtered = cachedUsers.filter(u => {
    const email = (u.userEmail || u.email || "").toLowerCase();
    const id = (u.id || "").toLowerCase();
    const name = (u.displayName || u.name || "").toLowerCase();

    const matchesQuery = !query || email.includes(query) || id.includes(query) || name.includes(query);
    if (!matchesQuery) return false;

    if (filterTier === "ALL") return true;
    if (filterTier === "VIP") return u.isVip === true && (u.planType === "LIFETIME" || (u.expiryTimestamp || 0) > now || u.expiryTimestamp === -1);
    if (filterTier === "FREE") return !u.isVip;
    if (filterTier === "EXPIRED") return u.isVip && u.planType !== "LIFETIME" && (u.expiryTimestamp || 0) <= now && u.expiryTimestamp !== -1;
    return true;
  });

  if (filtered.length === 0) {
    tbody.innerHTML = `
      <tr>
        <td colspan="6" style="text-align: center; padding: 30px; color: var(--text-muted);">
          No users match the search criteria.
        </td>
      </tr>
    `;
    return;
  }

  tbody.innerHTML = filtered.map(u => {
    const isVip = u.isVip === true;
    const plan = u.planType || "FREE";
    const expiry = u.expiryTimestamp || 0;
    const isPermanent = plan === "LIFETIME" || expiry === -1;
    const isExpired = !isPermanent && expiry > 0 && expiry <= now;

    let badgeClass = "badge-free";
    let statusText = "Free";
    if (isVip && !isExpired) {
      badgeClass = "badge-vip badge-active";
      statusText = `👑 ${plan}`;
    } else if (isExpired) {
      badgeClass = "badge-expired";
      statusText = `Expired (${plan})`;
    }

    const expiryDisplay = isPermanent ? "Permanent (N/A)" : (u.expiresAt || "N/A");
    const emailDisplay = u.userEmail || u.email || "No Email";
    const paymentDisplay = u.paymentId ? `<code style="color: var(--cyan-primary);">${escapeHtml(u.paymentId)}</code>` : "<span style='color: var(--text-muted);'>—</span>";

    return `
      <tr>
        <td>
          <div style="font-weight: 700;">${escapeHtml(emailDisplay)}</div>
          <div style="font-size: 0.72rem; color: var(--text-muted); font-family: monospace;">UID: ${u.id.slice(0, 10)}...</div>
        </td>
        <td><span class="${badgeClass}">${statusText}</span></td>
        <td>${expiryDisplay}</td>
        <td>${paymentDisplay}</td>
        <td style="font-size: 0.78rem; color: var(--text-muted);">
          ${u.purchasedAt ? new Date(u.purchasedAt).toLocaleDateString() : "—"}
        </td>
        <td style="text-align: right;">
          <button class="btn-admin btn-cyan" onclick="openEditUserModal('${u.id}')">
            ⚙️ Manage
          </button>
        </td>
      </tr>
    `;
  }).join("");
}

function renderTransactionsLedger() {
  const tbody = document.getElementById("transactionsTableBody");
  if (!tbody) return;

  const paidUsers = cachedUsers.filter(u => u.paymentId || (u.isVip && u.planType));
  if (paidUsers.length === 0) {
    tbody.innerHTML = `
      <tr>
        <td colspan="5" style="text-align: center; padding: 30px; color: var(--text-muted);">
          No payment transactions recorded yet.
        </td>
      </tr>
    `;
    return;
  }

  tbody.innerHTML = paidUsers.map(u => {
    const amount = u.planType === "WEEKLY" ? "₹29" : (u.planType === "MONTHLY" ? "₹89" : (u.planType === "LIFETIME" ? "₹249" : "₹0"));
    const paymentId = u.paymentId || "manual_grant";
    const dateStr = u.purchasedAt ? new Date(u.purchasedAt).toLocaleString() : "N/A";

    return `
      <tr>
        <td><code style="color: var(--cyan-primary); font-weight: 700;">${escapeHtml(paymentId)}</code></td>
        <td>${escapeHtml(u.userEmail || u.email || "Gamer")}</td>
        <td><span class="badge-vip">👑 ${escapeHtml(u.planType || "VIP")}</span></td>
        <td style="font-weight: 800; color: var(--gold-primary);">${amount}</td>
        <td style="font-size: 0.78rem; color: var(--text-muted);">${dateStr}</td>
      </tr>
    `;
  }).join("");
}

// 6. User Management Modal Controls
window.openEditUserModal = function(uid) {
  const user = cachedUsers.find(u => u.id === uid);
  if (!user) return;

  activeEditingUser = user;
  document.getElementById("modalUserUid").value = user.id;
  document.getElementById("modalUserEmail").innerText = user.userEmail || user.email || "User: " + user.id;
  document.getElementById("modalCurrentPlan").innerText = (user.isVip ? "👑 " : "") + (user.planType || "FREE");
  document.getElementById("modalCurrentExpiry").innerText = user.expiresAt || "N/A";
  document.getElementById("modalPaymentIdInput").value = user.paymentId || "";

  document.getElementById("userActionModal").classList.add("active");
};

window.closeEditUserModal = function() {
  document.getElementById("userActionModal").classList.remove("active");
  activeEditingUser = null;
};

window.quickGrantDays = async function(days, planTier = "MONTHLY") {
  if (!activeEditingUser) return;
  const pid = document.getElementById("modalPaymentIdInput")?.value?.trim() || activeEditingUser.paymentId || "";

  try {
    const res = await fetch("/api/admin/user/grant", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-admin-key": adminToken
      },
      body: JSON.stringify({
        userId: activeEditingUser.id,
        days: days,
        planType: planTier,
        paymentId: pid
      })
    });
    const data = await res.json();
    if (res.ok) {
      alert(`✅ Successfully granted +${days} days (${planTier}) to ${activeEditingUser.userEmail || activeEditingUser.id}!`);
      closeEditUserModal();
      loadAllFirestoreUsers();
    } else {
      alert("Error updating user: " + (data.error || "Unknown error"));
    }
  } catch (err) {
    alert("Request error: " + err.message);
  }
};

window.quickGrantLifetime = async function() {
  if (!activeEditingUser) return;
  const pid = document.getElementById("modalPaymentIdInput")?.value?.trim() || activeEditingUser.paymentId || "";

  try {
    const res = await fetch("/api/admin/user/grant", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-admin-key": adminToken
      },
      body: JSON.stringify({
        userId: activeEditingUser.id,
        days: 0,
        planType: "LIFETIME",
        paymentId: pid
      })
    });
    const data = await res.json();
    if (res.ok) {
      alert(`👑 Successfully granted LIFETIME VIP to ${activeEditingUser.userEmail || activeEditingUser.id}!`);
      closeEditUserModal();
      loadAllFirestoreUsers();
    } else {
      alert("Error updating user: " + (data.error || "Unknown error"));
    }
  } catch (err) {
    alert("Request error: " + err.message);
  }
};

window.applyCustomGrant = async function() {
  const days = parseInt(document.getElementById("customDaysInput").value);
  const tier = document.getElementById("customPlanTier").value;
  if (isNaN(days) || days <= 0) {
    alert("Please enter a valid number of days (> 0).");
    return;
  }
  await quickGrantDays(days, tier);
};

window.savePaymentIdOverride = async function() {
  if (!activeEditingUser) return;
  const pid = document.getElementById("modalPaymentIdInput")?.value?.trim();

  try {
    const res = await fetch("/api/admin/user/update-payment", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-admin-key": adminToken
      },
      body: JSON.stringify({
        userId: activeEditingUser.id,
        paymentId: pid
      })
    });
    const data = await res.json();
    if (res.ok) {
      alert("Transaction ID updated.");
      loadAllFirestoreUsers();
    } else {
      alert("Error updating payment ID: " + (data.error || "Unknown error"));
    }
  } catch (err) {
    alert("Request error: " + err.message);
  }
};

window.revokeActiveVip = async function() {
  if (!activeEditingUser) return;

  if (!confirm(`Are you sure you want to REVOKE VIP status for ${activeEditingUser.userEmail || activeEditingUser.id}? They will be reverted to Free immediately.`)) {
    return;
  }

  try {
    const res = await fetch("/api/admin/user/revoke", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-admin-key": adminToken
      },
      body: JSON.stringify({ userId: activeEditingUser.id })
    });
    const data = await res.json();
    if (res.ok) {
      alert("VIP status revoked.");
      closeEditUserModal();
      loadAllFirestoreUsers();
    } else {
      alert("Error revoking VIP: " + (data.error || "Unknown error"));
    }
  } catch (err) {
    alert("Request error: " + err.message);
  }
};

window.deleteUserDocument = async function() {
  if (!activeEditingUser) return;

  if (!confirm(`⚠️ PERMANENT ACTION: Are you sure you want to DELETE user document ${activeEditingUser.id}? This cannot be undone.`)) {
    return;
  }

  try {
    const res = await fetch("/api/admin/user/delete", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-admin-key": adminToken
      },
      body: JSON.stringify({ userId: activeEditingUser.id })
    });
    const data = await res.json();
    if (res.ok) {
      alert("User deleted from Firestore.");
      closeEditUserModal();
      loadAllFirestoreUsers();
    } else {
      alert("Error deleting user: " + (data.error || "Unknown error"));
    }
  } catch (err) {
    alert("Request error: " + err.message);
  }
};

// 7. Manual User Provisioning Form
window.handleManualProvision = async function(e) {
  e.preventDefault();

  const email = document.getElementById("provEmail").value.trim().toLowerCase();
  const name = document.getElementById("provName").value.trim() || "Gamer";
  const tier = document.getElementById("provTier").value;
  const days = parseInt(document.getElementById("provDays").value) || 30;
  const paymentId = document.getElementById("provPaymentId").value.trim() || ("manual_adm_" + Date.now());

  if (!email || !email.includes("@")) {
    alert("Please enter a valid email address.");
    return;
  }

  const submitBtn = document.getElementById("btnProvSubmit");
  if (submitBtn) {
    submitBtn.disabled = true;
    submitBtn.innerText = "Provisioning...";
  }

  try {
    const res = await fetch("/api/admin/user/provision", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-admin-key": adminToken
      },
      body: JSON.stringify({ email, name, tier, days, paymentId })
    });
    const data = await res.json();

    if (res.ok) {
      alert(`✅ VIP pass successfully created for ${email}!`);
      document.getElementById("manualProvisionForm").reset();
      switchAdminTab("users");
      loadAllFirestoreUsers();
    } else {
      alert("Error provisioning user: " + (data.error || "Unknown error"));
    }
  } catch (err) {
    alert("Error provisioning user: " + err.message);
  } finally {
    if (submitBtn) {
      submitBtn.disabled = false;
      submitBtn.innerText = "⚡ Provision & Grant Access";
    }
  }
};

// 8. Tab Navigation
window.switchAdminTab = function(tabName) {
  document.querySelectorAll(".admin-tab-btn").forEach(btn => {
    btn.classList.toggle("active", btn.dataset.tab === tabName);
  });
  document.querySelectorAll(".tab-pane").forEach(pane => {
    pane.classList.toggle("active", pane.id === `tab-${tabName}`);
  });
};

// 9. Data Exports
window.exportUsersCsv = function() {
  if (cachedUsers.length === 0) {
    alert("No users to export.");
    return;
  }

  const headers = ["User ID", "Email", "Display Name", "VIP Active", "Plan Tier", "Transaction ID", "Purchased Date", "Expires Date"];
  const rows = cachedUsers.map(u => [
    `"${u.id}"`,
    `"${u.userEmail || u.email || ''}"`,
    `"${u.displayName || u.name || ''}"`,
    u.isVip ? "TRUE" : "FALSE",
    `"${u.planType || 'FREE'}"`,
    `"${u.paymentId || ''}"`,
    `"${u.purchasedAt || ''}"`,
    `"${u.expiresAt || ''}"`
  ]);

  const csvContent = "data:text/csv;charset=utf-8," + [headers.join(","), ...rows.map(e => e.join(","))].join("\n");
  const encodedUri = encodeURI(csvContent);
  const link = document.createElement("a");
  link.setAttribute("href", encodedUri);
  link.setAttribute("download", `gamervoice_users_${Date.now()}.csv`);
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
};

window.exportTransactionsJson = function() {
  const paidUsers = cachedUsers.filter(u => u.paymentId || u.isVip);
  const dataStr = "data:text/json;charset=utf-8," + encodeURIComponent(JSON.stringify(paidUsers, null, 2));
  const link = document.createElement("a");
  link.setAttribute("href", dataStr);
  link.setAttribute("download", `gamervoice_transactions_${Date.now()}.json`);
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
};

function escapeHtml(str) {
  if (!str) return "";
  return String(str).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

// 10. Startup Lifecycle
document.addEventListener("DOMContentLoaded", () => {
  const overlay = document.getElementById("authLockOverlay");
  if (adminToken) {
    if (overlay) overlay.style.display = "none";
    initAdminDashboard();
  } else {
    if (overlay) overlay.style.display = "flex";
  }

  // Setup live search & filter listeners
  document.getElementById("userSearchInput")?.addEventListener("input", filterAndRenderUsers);
  document.getElementById("userTierFilter")?.addEventListener("change", filterAndRenderUsers);
});
