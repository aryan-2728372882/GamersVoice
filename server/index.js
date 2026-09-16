const fs = require('fs');
const path = require('path');
const http = require('http');
const { WebSocketServer, WebSocket } = require('ws');
const crypto = require('crypto');
const { randomUUID } = crypto;

const nodemailer = require('nodemailer');

// Automatically load local .env if present (strictly gitignored and kept on your PC only)
const envPaths = [path.join(__dirname, '.env'), path.join(__dirname, '..', '.env')];
for (const envPath of envPaths) {
  if (fs.existsSync(envPath)) {
    try {
      const rawEnv = fs.readFileSync(envPath, 'utf8');
      rawEnv.split('\n').forEach(line => {
        const trimmed = line.trim();
        if (trimmed && !trimmed.startsWith('#')) {
          const eqIdx = trimmed.indexOf('=');
          if (eqIdx > 0) {
            const k = trimmed.substring(0, eqIdx).trim();
            const v = trimmed.substring(eqIdx + 1).trim().replace(/^['"]|['"]$/g, '');
            if (process.env[k] === undefined) {
              process.env[k] = v;
            }
          }
        }
      });
      console.log(`[Env Loader] Loaded local configuration from ${path.basename(envPath)}`);
      break;
    } catch (_) {}
  }
}

const PORT = process.env.PORT || 3000;

// Configuration strictly from environment variables (No hardcoded secrets)
const SMTP_USER = process.env.SMTP_USER || '';
const SMTP_PASS = (process.env.SMTP_PASS || '').replace(/\s+/g, '');
const SMTP_HOST = process.env.SMTP_HOST || 'smtp.gmail.com';
const SMTP_PORT = parseInt(process.env.SMTP_PORT || '465', 10);
const TELEGRAM_BOT_TOKEN = process.env.TELEGRAM_BOT_TOKEN || '';
const TELEGRAM_CHAT_ID = process.env.TELEGRAM_CHAT_ID || '';
const RAZORPAY_KEY_ID = process.env.RAZORPAY_KEY_ID || '';
const RAZORPAY_KEY_SECRET = process.env.RAZORPAY_KEY_SECRET || '';

// If ADMIN_SECRET_KEY is not set in Render environment, auto-generate an ephemeral 256-bit key at boot
const ADMIN_SECRET_KEY = process.env.ADMIN_SECRET_KEY || crypto.randomBytes(32).toString('hex');
if (!process.env.ADMIN_SECRET_KEY) {
  console.warn('[Security Notice] ADMIN_SECRET_KEY env var not set. Auto-generated ephemeral admin secret for this session:');
  console.warn(`[Security Notice] >>> ${ADMIN_SECRET_KEY} <<<`);
}

const ADMIN_ALLOWED_EMAILS = (process.env.ADMIN_ALLOWED_EMAILS || 'prabhakararyan2007@gmail.com,supportgamersvoice@gmail.com')
  .toLowerCase()
  .split(',')
  .map(e => e.trim());

// Constant-time string comparison to prevent timing attacks
function timingSafeEqualStr(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const bufA = Buffer.from(a, 'utf8');
  const bufB = Buffer.from(b, 'utf8');
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

// Firebase Admin SDK Initialization (Loaded strictly from env or gitignored local file)
let adminApp = null;
let adminDb = null;
let adminAuth = null;
let adminMessaging = null;

try {
  let serviceAccount = null;
  if (process.env.FIREBASE_SERVICE_ACCOUNT) {
    try {
      const rawEnv = process.env.FIREBASE_SERVICE_ACCOUNT.trim();
      if (rawEnv.startsWith('{')) {
        serviceAccount = JSON.parse(rawEnv);
      } else {
        const decoded = Buffer.from(rawEnv, 'base64').toString('utf8');
        serviceAccount = JSON.parse(decoded);
      }
    } catch (e) {
      console.error('[Firebase Admin] Failed parsing FIREBASE_SERVICE_ACCOUNT env var:', e.message);
    }
  }

  // Local file or Render Secret File fallback (strictly gitignored)
  if (!serviceAccount) {
    const candidatePaths = [
      path.join(__dirname, 'service-account.json'),
      path.join(__dirname, '..', 'gamersvoice-ea413-firebase-adminsdk-fbsvc-d941789a8b.json'),
      path.join('/etc/secrets', 'service-account.json'),
      path.join('/etc/secrets', 'FIREBASE_SERVICE_ACCOUNT')
    ];
    for (const saPath of candidatePaths) {
      if (fs.existsSync(saPath)) {
        try {
          serviceAccount = JSON.parse(fs.readFileSync(saPath, 'utf8'));
          console.log(`[Firebase Admin] Loaded service account from ${saPath}`);
          break;
        } catch (e) {
          console.error(`[Firebase Admin] Failed parsing ${saPath}:`, e.message);
        }
      }
    }
  }

  if (serviceAccount) {
    const admin = require('firebase-admin');
    const { getFirestore } = require('firebase-admin/firestore');
    const { getAuth } = require('firebase-admin/auth');
    const { getMessaging } = require('firebase-admin/messaging');

    adminApp = admin.initializeApp({
      credential: admin.cert(serviceAccount)
    }, 'GamerVoiceAdminApp');

    adminDb = getFirestore(adminApp);
    adminAuth = getAuth(adminApp);
    adminMessaging = getMessaging(adminApp);
    console.log('[Firebase Admin] Initialized Firestore, Auth, and Cloud Messaging for:', serviceAccount.project_id);
  } else {
    console.warn('[Firebase Admin] No service account credentials found. Set FIREBASE_SERVICE_ACCOUNT env var in Render.');
  }
} catch (err) {
  console.error('[Firebase Admin Init Error]', err.message);
}

// Active admin sessions: token -> { email, type, expireAt }
const adminSessions = new Map();

async function isAuthorizedAdmin(req) {
  const authHeader = req.headers['x-admin-key'] || req.headers['authorization'];
  if (!authHeader) return false;

  const key = authHeader.replace(/^Bearer\s+/i, '').trim();

  // 1. Direct master key match (timing-safe)
  if (ADMIN_SECRET_KEY && timingSafeEqualStr(key, ADMIN_SECRET_KEY)) {
    return { type: 'master', user: 'master_admin' };
  }

  // 2. Active admin session check (expires after 24 hours)
  if (adminSessions.has(key)) {
    const sess = adminSessions.get(key);
    if (Date.now() < sess.expireAt) {
      return { type: sess.type || 'firebase', user: sess.email || 'master_admin' };
    } else {
      adminSessions.delete(key);
    }
  }

  // 3. Direct Firebase ID token verification
  if (adminAuth) {
    try {
      const decoded = await adminAuth.verifyIdToken(key);
      if (decoded && decoded.email && ADMIN_ALLOWED_EMAILS.includes(decoded.email.toLowerCase())) {
        return { type: 'firebase', user: decoded.email };
      }
    } catch (_) {}
  }

  return false;
}

// Authenticates Firebase user tokens from Authorization Bearer header
async function verifyUserAuth(req) {
  const authHeader = req.headers['authorization'] || '';
  const token = authHeader.replace(/^Bearer\s+/i, '').trim();
  if (!token) return null;

  if (adminAuth) {
    try {
      const decoded = await adminAuth.verifyIdToken(token);
      if (decoded && decoded.uid) {
        return { uid: decoded.uid, email: decoded.email || '' };
      }
    } catch (e) {
      console.warn('[User Auth] verifyIdToken failed:', e.message);
    }
  }
  return null;
}

// Replay attack prevention store (dual-layer: memory cache + Firestore persistence)
const USED_PAYMENTS_FILE = path.join(__dirname, 'used_payments.json');
let usedPaymentIds = new Set();
try {
  if (fs.existsSync(USED_PAYMENTS_FILE)) {
    const raw = fs.readFileSync(USED_PAYMENTS_FILE, 'utf8');
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) {
      usedPaymentIds = new Set(parsed);
    }
  }
} catch (err) {
  console.error('[Storage Error] Failed to read used payments file:', err.message);
}

async function recordUsedPayment(paymentId) {
  usedPaymentIds.add(paymentId);
  try {
    fs.writeFileSync(USED_PAYMENTS_FILE, JSON.stringify(Array.from(usedPaymentIds), null, 2), 'utf8');
  } catch (_) {}
  if (adminDb) {
    try {
      await adminDb.collection('system_processed_payments').doc(paymentId).set({
        paymentId,
        redeemedAt: new Date().toISOString(),
        timestamp: Date.now()
      });
    } catch (e) {
      console.warn('[Firestore] Could not sync used payment to Firestore:', e.message);
    }
  }
}

async function isPaymentUsed(paymentId) {
  if (usedPaymentIds.has(paymentId)) return true;
  if (adminDb) {
    try {
      const snap = await adminDb.collection('system_processed_payments').doc(paymentId).get();
      if (snap.exists) {
        usedPaymentIds.add(paymentId);
        return true;
      }
    } catch (_) {}
  }
  return false;
}

// Welcome email deduplication store (dual-layer: memory cache + Firestore persistence)
const SENT_EMAILS_FILE = path.join(__dirname, 'sent_welcome_emails.json');
let sentWelcomeEmails = new Set();
try {
  if (fs.existsSync(SENT_EMAILS_FILE)) {
    const raw = fs.readFileSync(SENT_EMAILS_FILE, 'utf8');
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) {
      sentWelcomeEmails = new Set(parsed.map(e => String(e).toLowerCase().trim()));
    }
  }
} catch (err) {
  console.error('[Storage Error] Failed to read sent welcome emails file:', err.message);
}

async function recordSentWelcomeEmail(email) {
  const cleanEmail = email.trim().toLowerCase();
  sentWelcomeEmails.add(cleanEmail);
  try {
    fs.writeFileSync(SENT_EMAILS_FILE, JSON.stringify(Array.from(sentWelcomeEmails), null, 2), 'utf8');
  } catch (_) {}
  if (adminDb) {
    try {
      await adminDb.collection('system_welcome_emails').doc(cleanEmail).set({
        email: cleanEmail,
        sentAt: new Date().toISOString(),
        timestamp: Date.now()
      });
    } catch (e) {
      console.warn('[Firestore] Could not sync sent email to Firestore:', e.message);
    }
  }
}

async function isWelcomeEmailSent(email) {
  const cleanEmail = email.trim().toLowerCase();
  if (sentWelcomeEmails.has(cleanEmail)) return true;
  if (adminDb) {
    try {
      const snap = await adminDb.collection('system_welcome_emails').doc(cleanEmail).get();
      if (snap.exists) {
        sentWelcomeEmails.add(cleanEmail);
        return true;
      }
    } catch (_) {}
  }
  return false;
}

// In-memory data structures
const rooms = new Map();
const clients = new Map();

// Zero-Firestore-Burn Referral Leaderboard Cache (15-Minute TTL)
let leaderboardCache = {
  data: [],
  lastFetched: 0
};
const LEADERBOARD_CACHE_TTL = 15 * 60 * 1000;

function renderJoinRoomHtml(roomCode, activePeerCount, isRoomActive) {
  const statusBadge = isRoomActive
    ? `<span style="display:inline-block;padding:6px 14px;background:rgba(0,230,118,0.15);border:1px solid #00e676;border-radius:20px;color:#00e676;font-weight:700;font-size:0.85rem">🟢 Room Active (${activePeerCount}/5 Connected)</span>`
    : `<span style="display:inline-block;padding:6px 14px;background:rgba(255,215,0,0.15);border:1px solid #ffd700;border-radius:20px;color:#ffd700;font-weight:700;font-size:0.85rem">🟡 Room Ready to Connect</span>`;

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Join Squad Room ${roomCode} — GamerVoice</title>
  <link rel="icon" type="image/png" href="/logo.png">
  <meta property="og:title" content="Join Squad Room ${roomCode} on GamerVoice">
  <meta property="og:description" content="Connect with your squad with 0 lag and studio noise suppression.">
  <link rel="stylesheet" href="/css/core.css">
  <style>
    body { min-height: 100vh; display: flex; flex-direction: column; justify-content: center; align-items: center; background: radial-gradient(circle at 50% 20%, rgba(0, 240, 255, 0.12), transparent 70%), #070a12; color: #fff; font-family: var(--font-body); padding: 20px; text-align: center; }
    .join-card { background: rgba(13, 18, 31, 0.95); border: 1px solid var(--line-cyan); border-radius: 16px; padding: 36px 24px; max-width: 440px; width: 100%; box-shadow: 0 10px 40px rgba(0,0,0,0.6); }
    .room-badge { font-family: var(--font-mono); font-size: 2.8rem; font-weight: 900; letter-spacing: 0.15em; color: var(--neon-cyan); background: rgba(0, 240, 255, 0.08); border: 2px dashed var(--neon-cyan); border-radius: 12px; padding: 12px 20px; margin: 20px 0; user-select: all; }
    .btn-action { display: block; width: 100%; padding: 14px 20px; border-radius: 10px; font-weight: 800; font-size: 1.05rem; text-decoration: none; margin: 10px 0; cursor: pointer; transition: transform 0.15s ease; border: none; }
    .btn-action:hover { transform: scale(1.02); }
    .btn-open { background: linear-gradient(135deg, #00f0ff 0%, #00e676 100%); color: #070a12; }
    .btn-download { background: rgba(255,255,255,0.06); border: 1px solid var(--line-cyan); color: #fff; }
    .btn-copy { background: transparent; color: #8e9bae; font-size: 0.9rem; text-decoration: underline; margin-top: 8px; border: none; cursor: pointer; }
  </style>
</head>
<body>
  <div class="join-card">
    <div style="display:flex;align-items:center;justify-content:center;gap:10px;margin-bottom:14px">
      <img src="/logo.png" alt="GamerVoice" style="width:36px;height:36px;border-radius:8px">
      <span style="font-weight:900;font-size:1.3rem;letter-spacing:0.04em">GAMERVOICE</span>
    </div>
    <div style="margin-bottom:12px">${statusBadge}</div>
    <h1 style="font-size:1.4rem;font-weight:800;margin:0 0 6px">Squad Voice Room</h1>
    <p style="color:#8e9bae;font-size:0.95rem;margin:0">You're invited to join voice comms with 0 lag.</p>
    <div class="room-badge">${roomCode}</div>
    <a href="gamervoice://join/${roomCode}" class="btn-action btn-open">🚀 Open in GamerVoice App</a>
    <button class="btn-action btn-download" onclick="window.location.href='/download-apk'">📲 Download GamerVoice APK</button>
    <button class="btn-copy" onclick="navigator.clipboard.writeText('${roomCode}');alert('Room Code ${roomCode} copied!')">📋 Copy Room Code Only</button>
  </div>
  <script>
    if (/Android/i.test(navigator.userAgent)) {
      setTimeout(() => {
        window.location.href = "gamervoice://join/${roomCode}";
      }, 350);
    }
  </script>
</body>
</html>`;
}

// Rate limiting in-memory storage (ip -> { count, resetTime })
const ipRateLimits = new Map();
const roomAttemptLimits = new Map();

function isRateLimited(map, key, maxCount, windowMs) {
  const now = Date.now();
  const entry = map.get(key);
  if (!entry || now > entry.resetTime) {
    map.set(key, { count: 1, resetTime: now + windowMs });
    return false;
  }
  entry.count++;
  return entry.count > maxCount;
}

// Clean up stale rate limit entries every 5 minutes
setInterval(() => {
  const now = Date.now();
  for (const [ip, entry] of ipRateLimits.entries()) {
    if (now > entry.resetTime) ipRateLimits.delete(ip);
  }
  for (const [ip, entry] of roomAttemptLimits.entries()) {
    if (now > entry.resetTime) roomAttemptLimits.delete(ip);
  }
}, 300000);

// Helper: parse JSON request body
function parseJsonBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (chunk) => {
      body += chunk;
      if (body.length > 100000) {
        req.destroy();
        reject(new Error('Body too large'));
      }
    });
    req.on('end', () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch (err) {
        reject(err);
      }
    });
    req.on('error', reject);
  });
}

// Helper: parse raw request body for webhook signature verification
function parseRawBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (chunk) => {
      body += chunk;
      if (body.length > 500000) {
        req.destroy();
        reject(new Error('Body too large'));
      }
    });
    req.on('end', () => {
      resolve(body);
    });
    req.on('error', reject);
  });
}

function sendResponse(res, statusCode, data) {
  res.writeHead(statusCode, {
    'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization'
  });
  res.end(JSON.stringify(data));
}

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.apk': 'application/vnd.android.package-archive'
};

const PUBLIC_DIR = path.resolve(__dirname, 'public');

function serveStaticFile(res, filePath, defaultMime = 'application/octet-stream', downloadName = null) {
  try {
    const resolvedPath = path.resolve(filePath);
    const rel = path.relative(PUBLIC_DIR, resolvedPath);

    // Strict path boundary validation: Target MUST reside inside PUBLIC_DIR
    if (rel.startsWith('..') || path.isAbsolute(rel)) {
      return false;
    }

    const filename = path.basename(resolvedPath).toLowerCase();

    // Defense-in-depth: completely reject any sensitive files or credential patterns
    if (
      filename.includes('service-account') ||
      filename.includes('adminsdk') ||
      filename.includes('vault') ||
      filename.endsWith('.key') ||
      filename.endsWith('.pem') ||
      filename.endsWith('.env') ||
      (filename.endsWith('.json') && (filename.includes('firebase') || filename.includes('payment') || filename.includes('email')))
    ) {
      return false;
    }

    if (fs.existsSync(resolvedPath) && fs.statSync(resolvedPath).isFile()) {
      const ext = path.extname(resolvedPath).toLowerCase();
      const contentType = MIME_TYPES[ext] || defaultMime;
      const headers = {
        'Content-Type': contentType,
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Private-Network': 'true'
      };
      if (downloadName) {
        headers['Content-Disposition'] = `attachment; filename="${downloadName}"`;
      }
      res.writeHead(200, headers);
      fs.createReadStream(resolvedPath).pipe(res);
      return true;
    }
  } catch (err) {
    console.error('[Static File Error]', err.message);
  }
  return false;
}

// HTTP Server for APIs, Webhooks, and Health Checks
const server = http.createServer(async (req, res) => {
  try {
    const clientIp = req.headers['x-forwarded-for']?.split(',')[0]?.trim() || req.socket.remoteAddress || 'unknown';

    // Handle CORS preflight
    if (req.method === 'OPTIONS') {
      res.writeHead(204, {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type, Authorization',
        'Access-Control-Allow-Private-Network': 'true'
      });
      res.end();
      return;
    }

    // Rate Limiting on API endpoints (60 req/min)
    if (req.url.startsWith('/api/') && isRateLimited(ipRateLimits, clientIp, 60, 60000)) {
      sendResponse(res, 429, { error: 'Rate limit exceeded. Please wait a moment.' });
      return;
    }

    // Serve Brand Logo
    if (req.url === '/logo.png') {
      const logoPath = path.join(PUBLIC_DIR, 'logo.png');
      if (serveStaticFile(res, logoPath, 'image/png')) {
        return;
      } else {
        sendResponse(res, 404, { error: 'Logo not found' });
        return;
      }
    }

    // Direct APK Download
    if (req.url === '/download-apk' || req.url === '/gamervoice.apk') {
      const apkPath = path.join(PUBLIC_DIR, 'gamervoice-release.apk');
      if (serveStaticFile(res, apkPath, 'application/vnd.android.package-archive', 'GamerVoice-v1.0.0.apk')) {
        console.log(`[APK Download] Triggered from ${clientIp}`);
        return;
      } else {
        sendResponse(res, 404, { error: 'APK release build not found on server' });
        return;
      }
    }

    // Health Check
    if (req.url === '/health' || req.url === '/api/health') {
      sendResponse(res, 200, {
        status: 'ok',
        service: 'GamerVoice WebRTC Signaling & Security Relay',
        activeRooms: rooms.size,
        connectedClients: clients.size,
        timestamp: new Date().toISOString()
      });
      return;
    }

    // Android App Links verification — MUST return application/json with correct CORS headers
    if (req.method === 'GET' && (req.url === '/.well-known/assetlinks.json' || req.url === '/.well-known/assetlinks.json?')) {
      const assetlinksPath = path.join(PUBLIC_DIR, '.well-known', 'assetlinks.json');
      try {
        const content = fs.readFileSync(assetlinksPath, 'utf8').replace(/^\uFEFF/, '');
        res.writeHead(200, {
          'Content-Type': 'application/json; charset=utf-8',
          'Access-Control-Allow-Origin': '*',
          'Cache-Control': 'public, max-age=3600'
        });
        res.end(content);
      } catch (e) {
        res.writeHead(404, { 'Content-Type': 'text/plain' });
        res.end('Not found');
      }
      return;
    }

    // Web Landing & Deep Link Preview for Squad Rooms (/join/CODE)
    if (req.method === 'GET' && req.url.startsWith('/join/')) {
      const codePart = req.url.split('?')[0].replace('/join/', '').trim().toUpperCase();
      if (/^[A-Z0-9]{3,8}$/.test(codePart)) {
        const room = rooms.get(codePart);
        const peerCount = room && room.peers ? room.peers.size : 0;
        const isRoomActive = Boolean(room);
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(renderJoinRoomHtml(codePart, peerCount, isRoomActive));
        return;
      }
    }

    // Static Web Assets from server/public/
    if (req.method === 'GET' && !req.url.startsWith('/api/')) {
      const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
      let pathname = parsedUrl.pathname;

      const PAGE_ROUTES = {
        '/features': '/pages/features.html',
        '/vip': '/pages/vip.html',
        '/compare': '/pages/compare.html',
        '/download': '/pages/download.html',
        '/lab': '/pages/lab.html',
        '/referrals': '/pages/referrals.html',
        '/faq': '/pages/faq.html',
        '/support': '/pages/support.html',
        '/privacy': '/pages/privacy.html',
        '/terms': '/pages/terms.html',
        '/refund': '/pages/refund.html',
        '/profile': '/pages/profile.html'
      };
      if (PAGE_ROUTES[pathname]) {
        pathname = PAGE_ROUTES[pathname];
      }

      if (pathname === '/admin' || pathname === '/admin/') {
        pathname = '/admin.html';
      }

      if (pathname === '/') pathname = '/index.html';

      const targetStaticPath = path.join(PUBLIC_DIR, pathname);
      if (serveStaticFile(res, targetStaticPath)) {
        return;
      }
    }

  // API 1: Server-Side Welcome Email Relay
  if (req.method === 'POST' && req.url === '/api/send-welcome-email') {
    try {
      // Stricter rate limit on welcome email dispatch (10 requests per 10 minutes per IP)
      if (isRateLimited(ipRateLimits, 'email_' + clientIp, 10, 600000)) {
        sendResponse(res, 429, { error: 'Too many welcome email requests. Please slow down.' });
        return;
      }

      const { email, name } = await parseJsonBody(req);
      const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
      if (!email || !emailRegex.test(email.trim())) {
        sendResponse(res, 400, { error: 'Invalid recipient email address format' });
        return;
      }

      const displayName = name && name.trim() ? name.trim() : 'Gamer';
      const cleanEmail = email.trim().toLowerCase();

      // Deduplication: Check if welcome email was already dispatched (memory + Firestore persistence)
      if (await isWelcomeEmailSent(cleanEmail)) {
        console.log(`[Email Deduplication] Welcome email already sent to ${cleanEmail}. Bypassing duplicate dispatch.`);
        sendResponse(res, 200, {
          success: true,
          alreadySent: true,
          message: 'Welcome email was already dispatched to this address.'
        });
        return;
      }

      if (!SMTP_PASS || !SMTP_USER) {
        console.log(`[SMTP Notice] Server SMTP credentials not set in environment. Mocking dispatch to: ${cleanEmail}`);
        await recordSentWelcomeEmail(cleanEmail);
        sendResponse(res, 200, {
          success: true,
          message: 'Email request received (SMTP pending server environment variable)'
        });
        return;
      }

      const transporter = nodemailer.createTransport({
        host: SMTP_HOST,
        port: SMTP_PORT,
        secure: SMTP_PORT === 465,
        auth: {
          user: SMTP_USER,
          pass: SMTP_PASS
        },
        tls: { rejectUnauthorized: false }
      });

      const mailOptions = {
        from: `"GamerVoice Core Team" <${SMTP_USER}>`,
        to: cleanEmail,
        subject: "hey, welcome — glad you're here 🎧",
        text: buildWelcomePlainText(displayName),
        html: buildWelcomeHtml(displayName, cleanEmail)
      };

      await transporter.sendMail(mailOptions);
      recordSentWelcomeEmail(cleanEmail);
      console.log(`[Email Dispatched] Heartfelt welcome email sent to ${cleanEmail}`);
      sendResponse(res, 200, { success: true, message: 'Welcome email successfully dispatched' });
    } catch (err) {
      console.error('[Email Error]', err.message);
      sendResponse(res, 500, { error: 'Failed to dispatch email: ' + err.message });
    }
    return;
  }

  // API 2: Server-Side Support Ticket Relay
  if (req.method === 'POST' && req.url === '/api/submit-support-ticket') {
    try {
      const { category, subject, description, userEmail, userId, appVersion, isVip } = await parseJsonBody(req);
      if (!description || !subject) {
        sendResponse(res, 400, { error: 'Subject and description are required' });
        return;
      }

      const ticketId = 'GV-' + Date.now().toString(36).toUpperCase();
      const vipHeader = isVip ? `👑 *[VIP HIGH PRIORITY TICKET]* 👑\n\n` : '';
      const messageText = `${vipHeader}🎮 *NEW GAMERVOICE SUPPORT TICKET*\n\n` +
        `🆔 *Ticket ID:* \`${ticketId}\`\n` +
        `💎 *VIP Status:* ${isVip ? '👑 VIP Member (Priority 1)' : 'Standard / Free'}\n` +
        `📂 *Category:* ${category || 'General Issue'}\n` +
        `✉️ *User Email:* \`${userEmail || 'Anonymous'}\`\n` +
        `👤 *User ID:* \`${userId || 'N/A'}\`\n` +
        `📱 *App Version:* \`${appVersion || '1.0.0'}\`\n\n` +
        `📝 *Subject:* ${subject}\n\n` +
        `💬 *Description:*\n${description}\n\n` +
        `🕒 *Timestamp:* ${new Date().toISOString()}`;

      if (TELEGRAM_BOT_TOKEN && TELEGRAM_CHAT_ID) {
        try {
          const tgUrl = `https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage`;
          await fetch(tgUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              chat_id: TELEGRAM_CHAT_ID,
              text: messageText,
              parse_mode: 'Markdown'
            })
          });
          console.log(`[Support Ticket] Forwarded to Telegram: ${ticketId} (VIP: ${!!isVip})`);
        } catch (tgErr) {
          console.error('[Telegram Forward Error]', tgErr.message);
        }
      } else {
        console.log(`[Support Ticket Received] ${ticketId} - Logged to server console:\n${messageText}`);
      }

      sendResponse(res, 200, {
        success: true,
        ticketId,
        message: 'Support ticket logged successfully'
      });
    } catch (err) {
      console.error('[Support Ticket Error]', err.message);
      sendResponse(res, 500, { error: 'Failed to process ticket: ' + err.message });
    }
    return;
  }

  function uidToCode(uid) {
  if (!uid) return "GV-ALPHA01";
  const words = ["TITAN","GHOST","VIPER","BLADE","STORM","RAVEN","ALPHA","SIGMA","NEXUS","CYBER"];
  let hash = 0;
  for (let i = 0; i < uid.length; i++) hash = ((hash << 5) - hash) + uid.charCodeAt(i);
  const word = words[Math.abs(hash) % words.length];
  const num = String(Math.abs(hash >> 4) % 100).padStart(2, '0');
  return "GV-" + word + num;
}

  // API 2B: Register Referral Code Owner
  if (req.method === 'POST' && req.url === '/api/referral/register-code') {
    try {
      const authUser = await verifyUserAuth(req);
      if (!authUser) {
        sendResponse(res, 401, { error: 'Authentication required' });
        return;
      }
      const { referralCode } = await parseJsonBody(req);
      if (!referralCode || referralCode.length < 5) {
        sendResponse(res, 400, { error: 'Valid referral code required (e.g. GV-XXXX)' });
        return;
      }
      const cleanCode = referralCode.trim().toUpperCase();
      if (adminDb) {
        const refDocRef = adminDb.collection('referrals').doc(cleanCode);
        const snap = await refDocRef.get();
        if (snap.exists && snap.data().ownerUid !== authUser.uid) {
          sendResponse(res, 409, { error: 'Referral code is already claimed by another squad member' });
          return;
        }
        await refDocRef.set({
          code: cleanCode,
          ownerUid: authUser.uid,
          ownerEmail: authUser.email,
          createdAt: snap.exists ? snap.data().createdAt : new Date().toISOString()
        }, { merge: true });

        await adminDb.collection('users').doc(authUser.uid).set({
          referralCode: cleanCode
        }, { merge: true });
      }
      sendResponse(res, 200, { success: true, code: cleanCode });
    } catch (err) {
      console.error('[Referral Register Error]', err.message);
      sendResponse(res, 500, { error: 'Failed registering referral code: ' + err.message });
    }
    return;
  }

  // API 2C: Viral Squad Referral Code Redemption (+3 Days VIP for BOTH players)
  if (req.method === 'POST' && req.url === '/api/referral/redeem') {
    try {
      const authUser = await verifyUserAuth(req);
      if (!authUser) {
        sendResponse(res, 401, { error: 'Authentication required. Please sign in to redeem referral codes.' });
        return;
      }
      const refereeUid = authUser.uid;

      const { referralCode } = await parseJsonBody(req);
      if (!referralCode || referralCode.trim().length < 5) {
        sendResponse(res, 400, { error: 'Valid referral code required (e.g. GV-XXXX)' });
        return;
      }

      const cleanCode = referralCode.trim().toUpperCase();
      console.log(`[Referral Engine] Authenticated redemption of code ${cleanCode} by referee ${refereeUid}`);

      if (!adminDb) {
        sendResponse(res, 503, { error: 'Database service temporarily unavailable. Please try again later.' });
        return;
      }

      // Pre-check & Pre-fetch referral document before transaction
      const refDocRef = adminDb.collection('referrals').doc(cleanCode);
      const refSnap = await refDocRef.get();
      let refData = null;
      let referrerUid = null;

      if (refSnap.exists) {
        refData = refSnap.data();
        referrerUid = refData.ownerUid;
      } else {
        // Fallback: check users collection by referralCode
        const userQuery = await adminDb.collection('users').where('referralCode', '==', cleanCode).limit(1).get();
        if (!userQuery.empty) {
          const uDoc = userQuery.docs[0];
          referrerUid = uDoc.id;
          refData = {
            code: cleanCode,
            ownerUid: referrerUid,
            ownerEmail: uDoc.data().email || '',
            createdAt: new Date().toISOString()
          };
          await refDocRef.set(refData, { merge: true });
        } else {
          sendResponse(res, 404, { error: 'Invalid referral code. Please check with your squadmate.' });
          return;
        }
      }

      // Pre-transaction Self-Redemption Check (Multi-vector)
      const refereeDeterministicCode = uidToCode(refereeUid);
      const isSelfByUid = (referrerUid && referrerUid === refereeUid);
      const isSelfByEmail = (refData.ownerEmail && authUser.email && refData.ownerEmail.toLowerCase() === authUser.email.toLowerCase());
      const isSelfByCode = (cleanCode === refereeDeterministicCode);

      if (isSelfByUid || isSelfByEmail || isSelfByCode) {
        sendResponse(res, 400, { error: 'You cannot redeem your own referral code!' });
        return;
      }

      // Execute redemption inside an atomic Firestore transaction
      // Strict rule: ALL reads must be executed before ANY writes!
      await adminDb.runTransaction(async (transaction) => {
        const redemptionDocRef = adminDb.collection('referral_redemptions').doc(refereeUid);
        const refereeDocRef = adminDb.collection('users').doc(refereeUid);
        const referrerDocRef = adminDb.collection('users').doc(referrerUid);

        // 1. ALL READS FIRST
        const refDocSnap = await transaction.get(refDocRef);
        const redemptionSnap = await transaction.get(redemptionDocRef);
        const refereeSnap = await transaction.get(refereeDocRef);
        const referrerSnap = await transaction.get(referrerDocRef);

        // 2. VALIDATION
        if (redemptionSnap.exists || (refereeSnap.exists && refereeSnap.data().hasRedeemedReferral)) {
          const err = new Error('You have already redeemed a welcome referral code on this account.');
          err.statusCode = 400;
          throw err;
        }

        if (refereeSnap.exists) {
          const refUserDoc = refereeSnap.data();
          if (refUserDoc.referralCode && refUserDoc.referralCode.toUpperCase() === cleanCode) {
            const err = new Error('You cannot redeem your own referral code!');
            err.statusCode = 400;
            throw err;
          }
        }

        // 3. CALCULATIONS
        const now = Date.now();
        const threeDaysMs = 3 * 24 * 60 * 60 * 1000;

        let refereeBaseTs = now;
        if (refereeSnap.exists) {
          const d = refereeSnap.data();
          if (d.expiryTimestamp && Number(d.expiryTimestamp) > now) {
            refereeBaseTs = Number(d.expiryTimestamp);
          }
        }
        const newRefereeExpTs = refereeBaseTs + threeDaysMs;
        const refereeExpDate = new Date(newRefereeExpTs).toLocaleDateString('en-US', { month: 'short', day: '2-digit', year: 'numeric' });

        let referrerBaseTs = now;
        let currentRefCount = 0;
        if (referrerSnap.exists) {
          const rd = referrerSnap.data();
          if (rd.expiryTimestamp && Number(rd.expiryTimestamp) > now) {
            referrerBaseTs = Number(rd.expiryTimestamp);
          }
          currentRefCount = Number(rd.referralCount) || 0;
        }
        const newReferrerExpTs = referrerBaseTs + threeDaysMs;
        const referrerExpDate = new Date(newReferrerExpTs).toLocaleDateString('en-US', { month: 'short', day: '2-digit', year: 'numeric' });

        // 4. ALL WRITES LAST
        transaction.set(refereeDocRef, {
          isVip: true,
          planType: 'WEEKLY',
          expiryTimestamp: newRefereeExpTs,
          expiresAt: refereeExpDate,
          hasRedeemedReferral: true,
          redeemedReferralCode: cleanCode,
          paymentId: `ref_welcome_${cleanCode}`,
          updatedAt: new Date().toISOString()
        }, { merge: true });

        transaction.set(referrerDocRef, {
          isVip: true,
          planType: 'WEEKLY',
          expiryTimestamp: newReferrerExpTs,
          expiresAt: referrerExpDate,
          referralCount: currentRefCount + 1,
          paymentId: `ref_bonus_${cleanCode}`,
          updatedAt: new Date().toISOString()
        }, { merge: true });

        transaction.set(redemptionDocRef, {
          refereeUid,
          refereeEmail: authUser.email,
          referrerUid,
          code: cleanCode,
          redeemedAt: new Date().toISOString(),
          timestamp: now
        });

        const currentRedeemedCount = (refDocSnap.exists && refDocSnap.data().redeemedCount) || 0;
        transaction.set(refDocRef, {
          redeemedCount: currentRedeemedCount + 1,
          lastRedeemedBy: refereeUid,
          lastRedeemedAt: new Date().toISOString()
        }, { merge: true });
      });

      // Invalidate leaderboard cache so the new recruiter count reflects promptly
      leaderboardCache.lastFetched = 0;

      sendResponse(res, 200, {
        success: true,
        message: '🎉 Referral code verified! 3 Days of VIP Pass unlocked for BOTH you and your squad mate!'
      });
    } catch (err) {
      console.error('[Referral Error]', err.message);
      const statusCode = err.statusCode || 500;
      sendResponse(res, statusCode, { error: err.message || 'Failed to process referral' });
    }
    return;
  }

  // API 2D: Viral Referral Leaderboard (RAM Cached with Zero Firestore Burn)
  if (req.method === 'GET' && req.url === '/api/referrals/leaderboard') {
    try {
      const now = Date.now();
      if (leaderboardCache.data.length > 0 && (now - leaderboardCache.lastFetched) < LEADERBOARD_CACHE_TTL) {
        sendResponse(res, 200, {
          success: true,
          cached: true,
          updatedAt: new Date(leaderboardCache.lastFetched).toISOString(),
          leaderboard: leaderboardCache.data
        });
        return;
      }

      if (!adminDb) {
        sendResponse(res, 200, {
          success: true,
          cached: false,
          leaderboard: leaderboardCache.data.length > 0 ? leaderboardCache.data : []
        });
        return;
      }

      let snapshot;
      try {
        snapshot = await adminDb.collection('users')
          .where('referralCount', '>', 0)
          .orderBy('referralCount', 'desc')
          .limit(10)
          .get();
      } catch (_idxErr) {
        snapshot = await adminDb.collection('users')
          .orderBy('referralCount', 'desc')
          .limit(10)
          .get();
      }

      const rawItems = [];
      snapshot.forEach(doc => {
        const d = doc.data() || {};
        const count = Number(d.referralCount) || 0;
        if (count > 0) {
          rawItems.push({ id: doc.id, d, count });
        }
      });

      const list = [];
      for (let i = 0; i < rawItems.length; i++) {
        const { id, d, count } = rawItems[i];
        let name = d.displayName || d.name || '';

        // If name is missing or placeholder, resolve from Firebase Auth and heal Firestore
        if ((!name || name.startsWith('SquadLeader_')) && adminAuth) {
          try {
            const authRecord = await adminAuth.getUser(id);
            if (authRecord.displayName) {
              name = authRecord.displayName;
            } else if (authRecord.email) {
              name = authRecord.email.split('@')[0];
            }
            if (name) {
              adminDb.collection('users').doc(id).set({
                displayName: name,
                name: name,
                email: authRecord.email || d.email || ''
              }, { merge: true }).catch(() => {});
            }
          } catch (_authErr) {}
        }

        if (!name && d.email) {
          name = d.email.split('@')[0];
        }
        if (!name) {
          name = 'SquadLeader_' + id.slice(-4);
        }

        // Hide ONLY the last 2 digits of the referral code (keep name completely visible)
        const rawCode = (d.referralCode || '').trim();
        let maskedCode = rawCode;
        if (rawCode.length > 2) {
          maskedCode = rawCode.slice(0, -2) + '**';
        } else if (rawCode.length > 0) {
          maskedCode = '**';
        }

        const rank = i + 1;
        let monthlyPrize = null;
        if (rank === 1) monthlyPrize = '14 Days VIP';
        else if (rank === 2) monthlyPrize = '7 Days VIP';
        else if (rank === 3) monthlyPrize = '3 Days VIP';

        list.push({
          rank,
          name,
          referralCode: maskedCode,
          referralCount: count,
          isVip: Boolean(d.isVip),
          monthlyPrize
        });
      }

      leaderboardCache = {
        data: list,
        lastFetched: now
      };

      sendResponse(res, 200, {
        success: true,
        cached: false,
        updatedAt: new Date(now).toISOString(),
        monthlyCompetition: {
          title: "Monthly Squad Leader Championship",
          prizes: {
            first: "14 Days VIP",
            second: "7 Days VIP",
            third: "3 Days VIP"
          }
        },
        leaderboard: list
      });
    } catch (err) {
      console.warn('[Leaderboard Warning]', err.message);
      sendResponse(res, 200, {
        success: true,
        cached: true,
        leaderboard: leaderboardCache.data
      });
    }
    return;
  }

  // API 2E: Season Glory Reward Check (Top 3 Crate Opening)
  if (req.method === 'GET' && req.url.startsWith('/api/season/user-reward')) {
    try {
      const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
      const uid = parsedUrl.searchParams.get('uid');
      if (!uid || !adminDb) {
        sendResponse(res, 200, { hasReward: false });
        return;
      }

      const userDoc = await adminDb.collection('users').doc(uid).get();
      if (!userDoc.exists) {
        sendResponse(res, 200, { hasReward: false });
        return;
      }

      const d = userDoc.data();
      const reward = d.pendingSeasonReward;
      if (reward && !reward.claimed) {
        sendResponse(res, 200, {
          hasReward: true,
          rank: Number(reward.rank) || 1,
          vipDays: Number(reward.vipDays) || (reward.rank === 1 ? 14 : (reward.rank === 2 ? 7 : 3)),
          seasonId: reward.seasonId || 'season_current',
          recruits: Number(d.referralCount) || 0
        });
        return;
      }

      sendResponse(res, 200, { hasReward: false });
    } catch (err) {
      console.warn('[Season Reward Error]', err.message);
      sendResponse(res, 200, { hasReward: false });
    }
    return;
  }

  // API 2F: Claim Season Glory Reward
  if (req.method === 'POST' && req.url === '/api/season/claim-reward') {
    try {
      const { uid } = await parseJsonBody(req);
      if (!uid || !adminDb) {
        sendResponse(res, 400, { success: false, error: 'Missing uid' });
        return;
      }

      const userRef = adminDb.collection('users').doc(uid);
      const userDoc = await userRef.get();
      if (!userDoc.exists) {
        sendResponse(res, 404, { success: false, error: 'User not found' });
        return;
      }

      const d = userDoc.data();
      const reward = d.pendingSeasonReward;
      if (!reward || reward.claimed) {
        sendResponse(res, 200, { success: true, message: 'Reward already claimed' });
        return;
      }

      const vipDays = Number(reward.vipDays) || (reward.rank === 1 ? 14 : (reward.rank === 2 ? 7 : 3));
      const now = Date.now();
      const rewardDurationMs = vipDays * 24 * 60 * 60 * 1000;
      let baseTs = now;
      if (d.expiryTimestamp && Number(d.expiryTimestamp) > now) {
        baseTs = Number(d.expiryTimestamp);
      }
      const newExpTs = baseTs + rewardDurationMs;
      const newExpDate = new Date(newExpTs).toLocaleDateString('en-US', { month: 'short', day: '2-digit', year: 'numeric' });

      await userRef.set({
        isVip: true,
        expiryTimestamp: newExpTs,
        expiresAt: newExpDate,
        pendingSeasonReward: {
          ...reward,
          claimed: true,
          claimedAt: new Date().toISOString()
        }
      }, { merge: true });

      sendResponse(res, 200, {
        success: true,
        message: `🎉 Successfully claimed ${vipDays} Days VIP!`,
        newExpiryTimestamp: newExpTs,
        expiresAt: newExpDate
      });
    } catch (err) {
      sendResponse(res, 500, { success: false, error: err.message });
    }
    return;
  }

  // API 2G: Admin / Test Trigger Season Reset
  if (req.method === 'POST' && req.url === '/api/season/trigger-monthly-reset') {
    try {
      if (!adminDb) {
        sendResponse(res, 500, { error: 'Database not ready' });
        return;
      }

      const snapshot = await adminDb.collection('users')
        .where('referralCount', '>', 0)
        .orderBy('referralCount', 'desc')
        .limit(3)
        .get();

      const top3 = [];
      const seasonId = new Date().toISOString().slice(0, 7); // e.g. "2026-09"
      let rank = 1;

      for (const doc of snapshot.docs) {
        const d = doc.data();
        const vipDays = rank === 1 ? 14 : (rank === 2 ? 7 : 3);
        const rewardData = {
          seasonId,
          rank,
          vipDays,
          claimed: false,
          grantedAt: new Date().toISOString()
        };

        await doc.ref.set({
          pendingSeasonReward: rewardData
        }, { merge: true });

        top3.push({
          uid: doc.id,
          name: d.displayName || d.name || 'Champion',
          rank,
          vipDays
        });
        rank++;
      }

      sendResponse(res, 200, {
        success: true,
        message: `Monthly reset executed for season ${seasonId}`,
        top3
      });
    } catch (err) {
      sendResponse(res, 500, { error: err.message });
    }
    return;
  }

  // API 3: Cryptographic Razorpay Payment Verification
  if (req.method === 'POST' && req.url === '/api/verify-payment') {
    try {
      const { paymentId, planTier, userId, userEmail } = await parseJsonBody(req);
      if (!paymentId || !paymentId.startsWith('pay_')) {
        sendResponse(res, 400, { verified: false, error: 'Invalid payment ID format' });
        return;
      }

      // Replay Attack Protection: Check if paymentId was already redeemed (memory + Firestore)
      if (await isPaymentUsed(paymentId)) {
        console.warn(`[Payment Replay Attack] Blocked reused payment ID: ${paymentId} by ${userId}`);
        sendResponse(res, 400, {
          verified: false,
          error: 'This payment ID has already been redeemed for VIP membership.'
        });
        return;
      }

      const expectedAmounts = {
        DAY_PASS: 900,
        WEEKLY: 2900,
        MONTHLY: 8900,
        LIFETIME: 24900
      };

      const expectedAmount = expectedAmounts[planTier];

      if (!RAZORPAY_KEY_SECRET) {
        console.error('[Payment Error] RAZORPAY_KEY_SECRET is not configured on server.');
        sendResponse(res, 503, {
          verified: false,
          error: 'Server payment verification gateway is unconfigured. Please configure RAZORPAY_KEY_SECRET in Render dashboard.'
        });
        return;
      }

      const authHeader = 'Basic ' + Buffer.from(`${RAZORPAY_KEY_ID}:${RAZORPAY_KEY_SECRET}`).toString('base64');
      const rzpResponse = await fetch(`https://api.razorpay.com/v1/payments/${paymentId}`, {
        headers: { Authorization: authHeader }
      });

      if (!rzpResponse.ok) {
        sendResponse(res, 400, { verified: false, error: 'Payment lookup failed on Razorpay' });
        return;
      }

      const paymentData = await rzpResponse.json();
      if (paymentData.status !== 'captured') {
        sendResponse(res, 400, { verified: false, error: `Payment not captured. Status: ${paymentData.status}` });
        return;
      }

      if (expectedAmount && paymentData.amount !== expectedAmount) {
        sendResponse(res, 400, {
          verified: false,
          error: `Payment amount mismatch: expected ₹${expectedAmount / 100}, got ₹${paymentData.amount / 100}`
        });
        return;
      }

      // Commit to persistent used payment IDs store to prevent replay attacks (memory + Firestore)
      await recordUsedPayment(paymentId);

      console.log(`[Payment Verified] Razorpay payment ${paymentId} verified for ${userId} (${planTier})`);
      sendResponse(res, 200, {
        verified: true,
        paymentId,
        planTier,
        amountPaid: paymentData.amount / 100,
        currency: paymentData.currency
      });
    } catch (err) {
      console.error('[Payment Verification Error]', err.message);
      sendResponse(res, 500, { verified: false, error: err.message });
    }
    return;
  }

  // API: Razorpay Webhook (Automated server-to-server payment fulfillment)
  if (req.method === 'POST' && req.url === '/api/webhooks/razorpay') {
    try {
      const rawBody = await parseRawBody(req);
      const signature = req.headers['x-razorpay-signature'] || '';
      const webhookSecret = process.env.RAZORPAY_WEBHOOK_SECRET || RAZORPAY_KEY_SECRET;

      if (webhookSecret) {
        const expectedSignature = crypto.createHmac('sha256', webhookSecret).update(rawBody).digest('hex');
        if (!signature || !timingSafeEqualStr(signature, expectedSignature)) {
          console.warn('[Razorpay Webhook] Invalid signature rejected');
          sendResponse(res, 400, { error: 'Invalid webhook signature' });
          return;
        }
      }

      const eventData = rawBody ? JSON.parse(rawBody) : {};
      console.log(`[Razorpay Webhook] Received event: ${eventData.event}`);

      if (eventData.event === 'payment.captured' || eventData.event === 'order.paid') {
        const payment = eventData.payload?.payment?.entity || {};
        const paymentId = payment.id;
        const notes = payment.notes || {};
        const userId = notes.userId || notes.userUid || notes.uid || '';
        const planTier = notes.planTier || notes.planType || 'MONTHLY';
        const userEmail = payment.email || notes.email || notes.userEmail || '';

        if (paymentId && !(await isPaymentUsed(paymentId))) {
          await recordUsedPayment(paymentId);

          let targetUid = userId;
          if (!targetUid && userEmail && adminDb) {
            const snap = await adminDb.collection('users').where('email', '==', userEmail.toLowerCase().trim()).limit(1).get();
            if (!snap.empty) {
              targetUid = snap.docs[0].id;
            }
          }

          const days = planTier === 'DAY_PASS' ? 1 : planTier === 'WEEKLY' ? 7 : planTier === 'LIFETIME' ? -1 : 30;
          const isLifetime = planTier === 'LIFETIME' || days === -1;
          const now = Date.now();
          const expiryTimestamp = isLifetime ? -1 : (now + (days * 24 * 60 * 60 * 1000));
          const expiresAt = isLifetime ? 'N/A (Permanent)' : new Date(expiryTimestamp).toLocaleDateString('en-US', {
            month: 'short',
            day: 'numeric',
            year: 'numeric'
          });

          if (adminDb && targetUid) {
            const updates = {
              isVip: true,
              planType: isLifetime ? 'LIFETIME' : planTier,
              expiresAt,
              expiryTimestamp,
              purchasedAt: new Date().toISOString(),
              paymentId: paymentId
            };
            await adminDb.collection('users').doc(targetUid).set(updates, { merge: true });
            console.log(`[Razorpay Webhook] Successfully credited VIP to user ${targetUid} via payment ${paymentId}`);
          }
        }
      }

      sendResponse(res, 200, { status: 'ok' });
    } catch (err) {
      console.error('[Razorpay Webhook Error]', err.message);
      sendResponse(res, 500, { error: err.message });
    }
    return;
  }

  // API: Latest App Version Check (For in-app auto-update notifications)
  if (req.method === 'GET' && req.url === '/api/app-version') {
    sendResponse(res, 200, {
      latestVersionCode: 9,
      latestVersionName: '1.0.9',
      downloadUrl: '/gamervoice-release.apk',
      mandatory: false,
      changelog: '• Free Fire AAA 3D Glory Crate Season Rewards\n• In-app auto updates & instant downloads\n• Peer network connection quality indicators\n• Multi-account Google sign-in fixes\n• Hindi localization support'
    });
    return;
  }

  // API: Self-Service User Account Deletion
  if (req.method === 'POST' && req.url === '/api/account/delete') {
    try {
      const authUser = await verifyUserAuth(req);
      if (!authUser || !authUser.uid) {
        sendResponse(res, 401, { error: 'Authentication required to delete account.' });
        return;
      }

      const uid = authUser.uid;
      console.log(`[Account Deletion] Initiated self-service account deletion for user: ${uid}`);

      if (adminDb) {
        // 1. Delete user profile document
        await adminDb.collection('users').doc(uid).delete();

        // 2. Delete user's registered referral code if any
        try {
          const refSnap = await adminDb.collection('referralCodes').where('uid', '==', uid).get();
          if (!refSnap.empty) {
            const batch = adminDb.batch();
            refSnap.forEach(doc => batch.delete(doc.ref));
            await batch.commit();
          }
        } catch (refErr) {
          console.warn('[Account Deletion] Error clearing referral codes:', refErr.message);
        }
      }

      // 3. Delete Firebase Auth user
      if (adminAuth) {
        try {
          await adminAuth.deleteUser(uid);
          console.log(`[Account Deletion] Deleted Auth record for ${uid}`);
        } catch (authErr) {
          console.warn('[Account Deletion] Warning deleting Auth record:', authErr.message);
        }
      }

      sendResponse(res, 200, { success: true, message: 'Account and associated data deleted successfully.' });
    } catch (err) {
      console.error('[Account Deletion Error]', err.message);
      sendResponse(res, 500, { error: err.message });
    }
    return;
  }

  // Admin API: Authentication (Supports Master Passcode OR Firebase ID Token)
  if (req.method === 'POST' && (req.url === '/api/admin/auth' || req.url === '/api/admin/verify-token')) {
    try {
      const { key, idToken } = await parseJsonBody(req);

      // Path A: Firebase Auth ID Token verification
      if (idToken) {
        let decodedEmail = null;
        let decodedUid = null;

        if (adminAuth) {
          try {
            const decoded = await adminAuth.verifyIdToken(idToken);
            decodedEmail = (decoded.email || '').toLowerCase().trim();
            decodedUid = decoded.uid;
          } catch (verifyErr) {
            console.warn('[Admin Auth] adminAuth.verifyIdToken warning:', verifyErr.message);
          }
        }

        // Fallback: Verify token with Google's public OAuth2 tokeninfo endpoint
        if (!decodedEmail) {
          try {
            const tokenInfoRes = await fetch(`https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(idToken)}`);
            if (tokenInfoRes.ok) {
              const info = await tokenInfoRes.json();
              if (info && info.email) {
                decodedEmail = (info.email || '').toLowerCase().trim();
                decodedUid = info.sub;
              }
            }
          } catch (fetchErr) {
            console.warn('[Admin Auth] Google tokeninfo fallback warning:', fetchErr.message);
          }
        }

        if (!decodedEmail) {
          sendResponse(res, 401, { authenticated: false, error: 'Invalid or expired Firebase ID token.' });
          return;
        }

        if (!ADMIN_ALLOWED_EMAILS.includes(decodedEmail)) {
          console.warn(`[Admin Auth] Blocked unauthorized email login attempt: ${decodedEmail}`);
          sendResponse(res, 403, {
            authenticated: false,
            error: `Access Denied: ${decodedEmail} is not listed in authorized administrator emails.`
          });
          return;
        }

        const sessionToken = 'gv_adm_' + randomUUID();
        adminSessions.set(sessionToken, {
          email: decodedEmail,
          uid: decodedUid,
          expireAt: Date.now() + 24 * 60 * 60 * 1000 // 24-hour admin session
        });

        console.log(`[Admin Auth] Admin ${decodedEmail} authenticated successfully via Firebase.`);
        sendResponse(res, 200, {
          authenticated: true,
          token: sessionToken,
          email: decodedEmail,
          adminType: 'firebase',
          timestamp: Date.now()
        });
        return;
      }

      // Path B: Master Security Key verification (timing-safe check & random 24h session token)
      if (key && ADMIN_SECRET_KEY && timingSafeEqualStr(key.trim(), ADMIN_SECRET_KEY)) {
        const masterSessionToken = 'gv_adm_master_' + randomUUID();
        adminSessions.set(masterSessionToken, {
          email: 'admin@gamersvoice.internal',
          type: 'master',
          expireAt: Date.now() + 24 * 60 * 60 * 1000 // 24-hour expiration
        });
        sendResponse(res, 200, {
          authenticated: true,
          token: masterSessionToken,
          email: 'admin@gamersvoice.internal',
          adminType: 'master',
          timestamp: Date.now()
        });
        return;
      }

      sendResponse(res, 401, { authenticated: false, error: 'Invalid master passcode or missing credentials.' });
    } catch (err) {
      sendResponse(res, 400, { error: err.message });
    }
    return;
  }

  // Admin API: Server Stats & Telemetry
  if (req.method === 'GET' && req.url === '/api/admin/server-stats') {
    const authAdmin = await isAuthorizedAdmin(req);
    if (!authAdmin) {
      sendResponse(res, 401, { error: 'Unauthorized administrative access' });
      return;
    }

    const activeRoomsList = [];
    for (const [code, r] of rooms.entries()) {
      const peerList = [];
      for (const [pId, pInfo] of r.peers.entries()) {
        peerList.push({
          peerId: pId,
          name: pInfo.name || 'Gamer',
          avatar: pInfo.avatar || 'avatar_1'
        });
      }
      activeRoomsList.push({
        roomCode: code,
        peerCount: r.peers.size,
        peers: peerList
      });
    }

    const mem = process.memoryUsage();
    sendResponse(res, 200, {
      uptimeSeconds: Math.floor(process.uptime()),
      totalRooms: rooms.size,
      totalClients: clients.size,
      usedPaymentsCount: usedPaymentIds.size,
      adminType: authAdmin.type,
      adminUser: authAdmin.user,
      memoryUsage: {
        heapUsedMb: Math.round((mem.heapUsed / (1024 * 1024)) * 100) / 100,
        heapTotalMb: Math.round((mem.heapTotal / (1024 * 1024)) * 100) / 100,
        rssMb: Math.round((mem.rss / (1024 * 1024)) * 100) / 100
      },
      activeRooms: activeRoomsList
    });
    return;
  }

  // Admin API: Terminate Squad Room
  if (req.method === 'POST' && req.url === '/api/admin/close-room') {
    const authAdmin = await isAuthorizedAdmin(req);
    if (!authAdmin) {
      sendResponse(res, 401, { error: 'Unauthorized' });
      return;
    }

    try {
      const { roomCode } = await parseJsonBody(req);
      const code = String(roomCode || '').trim().toUpperCase();
      const room = rooms.get(code);
      if (!room) {
        sendResponse(res, 404, { error: `Room ${code} not found or already closed` });
        return;
      }

      // Notify and disconnect all peers in room
      for (const [peerId, peerInfo] of room.peers.entries()) {
        try {
          sendJson(peerInfo.ws, {
            type: 'error',
            message: 'This squad room was closed by the administrator.'
          });
          clients.delete(peerInfo.ws);
        } catch (_) {}
      }
      rooms.delete(code);
      console.log(`[Admin] Room ${code} was terminated by administrator (${authAdmin.user}).`);
      sendResponse(res, 200, { success: true, message: `Room ${code} successfully terminated` });
    } catch (err) {
      sendResponse(res, 500, { error: err.message });
    }
    return;
  }

  // Admin API: Zomato-Style Push Notification Broadcast
  if (req.method === 'POST' && req.url === '/api/admin/broadcast-push') {
    const authAdmin = await isAuthorizedAdmin(req);
    if (!authAdmin) {
      sendResponse(res, 401, { error: 'Unauthorized administrative access' });
      return;
    }

    try {
      if (!adminMessaging) {
        sendResponse(res, 503, {
          error: 'Firebase Cloud Messaging is not active on this server. Ensure service account is configured.'
        });
        return;
      }

      const { title, body, roomCode, imageUrl, actionLabel } = await parseJsonBody(req);
      if (!title || !body) {
        sendResponse(res, 400, { error: 'Both Title and Message Body are required for broadcast.' });
        return;
      }

      const cleanTitle = String(title).trim();
      const cleanBody = String(body).trim();
      const cleanRoom = roomCode ? String(roomCode).trim().toUpperCase() : '';
      const cleanImage = imageUrl ? String(imageUrl).trim() : '';
      const cleanAction = actionLabel ? String(actionLabel).trim() : 'JOIN SQUAD 🎮';

      const messagePayload = {
        topic: 'all_gamers',
        notification: {
          title: cleanTitle,
          body: cleanBody,
          ...(cleanImage ? { imageUrl: cleanImage } : {})
        },
        data: {
          title: cleanTitle,
          body: cleanBody,
          roomCode: cleanRoom,
          actionLabel: cleanAction,
          ...(cleanImage ? { imageUrl: cleanImage } : {}),
          timestamp: String(Date.now())
        },
        android: {
          priority: 'high',
          notification: {
            channelId: 'squad_broadcast_alerts',
            sound: 'default',
            priority: 'high',
            defaultVibrateTimings: true,
            defaultSound: true,
            icon: 'ic_notification',
            color: '#00FF88',
            ...(cleanImage ? { imageUrl: cleanImage } : {})
          }
        }
      };

      const fcmResponse = await adminMessaging.send(messagePayload);
      console.log(`[FCM Broadcast] Push dispatched to topic 'all_gamers' by ${authAdmin.user}: ${fcmResponse}`);

      if (adminDb) {
        try {
          await adminDb.collection('admin_push_history').add({
            title: cleanTitle,
            body: cleanBody,
            roomCode: cleanRoom,
            imageUrl: cleanImage,
            actionLabel: cleanAction,
            sentBy: authAdmin.user,
            fcmMessageId: fcmResponse,
            sentAt: new Date().toISOString(),
            timestamp: Date.now()
          });
        } catch (_) {}
      }

      sendResponse(res, 200, {
        success: true,
        messageId: fcmResponse,
        message: '🚀 Push notification dispatched to all gamers successfully!'
      });
    } catch (err) {
      console.error('[FCM Broadcast Error]', err.message);
      sendResponse(res, 500, { error: 'Failed to broadcast push notification: ' + err.message });
    }
    return;
  }

  // Admin API: Fetch Recent Push Broadcast History
  if (req.method === 'GET' && req.url === '/api/admin/push-history') {
    const authAdmin = await isAuthorizedAdmin(req);
    if (!authAdmin) {
      sendResponse(res, 401, { error: 'Unauthorized administrative access' });
      return;
    }

    try {
      if (!adminDb) {
        sendResponse(res, 200, { history: [] });
        return;
      }
      const snapshot = await adminDb.collection('admin_push_history')
        .orderBy('timestamp', 'desc')
        .limit(10)
        .get();

      const history = [];
      snapshot.forEach(doc => {
        history.push({ id: doc.id, ...doc.data() });
      });
      sendResponse(res, 200, { history });
    } catch (err) {
      sendResponse(res, 500, { error: err.message });
    }
    return;
  }

  // Admin API: Fetch all Firestore Users via Admin SDK
  if (req.method === 'GET' && req.url === '/api/admin/users') {
    const authAdmin = await isAuthorizedAdmin(req);
    if (!authAdmin) {
      sendResponse(res, 401, { error: 'Unauthorized administrative access' });
      return;
    }

    if (!adminDb) {
      sendResponse(res, 500, { error: 'Firebase Admin Database not initialized on server.' });
      return;
    }

    try {
      const snapshot = await adminDb.collection('users').get();
      const userList = [];
      snapshot.forEach(doc => {
        userList.push({ id: doc.id, ...doc.data() });
      });

      sendResponse(res, 200, {
        success: true,
        count: userList.length,
        users: userList
      });
    } catch (err) {
      console.error('[Admin API Users Error]', err.message);
      sendResponse(res, 500, { error: 'Failed to query users: ' + err.message });
    }
    return;
  }

  // Admin API: Grant VIP Days / Lifetime via Admin SDK
  if (req.method === 'POST' && req.url === '/api/admin/user/grant') {
    const authAdmin = await isAuthorizedAdmin(req);
    if (!authAdmin) {
      sendResponse(res, 401, { error: 'Unauthorized' });
      return;
    }

    if (!adminDb) {
      sendResponse(res, 500, { error: 'Admin database not initialized' });
      return;
    }

    try {
      const { userId, days, planType, paymentId } = await parseJsonBody(req);
      if (!userId) {
        sendResponse(res, 400, { error: 'Missing userId' });
        return;
      }

      const isLifetime = planType === 'LIFETIME';
      const now = Date.now();
      const expiryTimestamp = isLifetime ? -1 : (now + (Number(days || 30) * 24 * 60 * 60 * 1000));
      const expiresAt = isLifetime ? 'N/A (Permanent)' : new Date(expiryTimestamp).toLocaleDateString('en-US', {
        month: 'short',
        day: 'numeric',
        year: 'numeric'
      });

      const updates = {
        isVip: true,
        planType: isLifetime ? 'LIFETIME' : (planType || 'MONTHLY'),
        expiresAt,
        expiryTimestamp,
        purchasedAt: new Date().toISOString(),
        paymentId: (paymentId && String(paymentId).trim()) ? String(paymentId).trim() : `pay_admin_grant_${Date.now()}`
      };

      await adminDb.collection('users').doc(userId).set(updates, { merge: true });
      console.log(`[Admin VIP Grant] User ${userId} granted ${isLifetime ? 'LIFETIME' : days + ' days'} by ${authAdmin.user}`);
      sendResponse(res, 200, {
        success: true,
        message: `Successfully granted VIP to user ${userId}`,
        updates
      });
    } catch (err) {
      console.error('[Admin VIP Grant Error]', err.message);
      sendResponse(res, 500, { error: err.message });
    }
    return;
  }

  // Admin API: Revoke VIP Status
  if (req.method === 'POST' && req.url === '/api/admin/user/revoke') {
    const authAdmin = await isAuthorizedAdmin(req);
    if (!authAdmin) {
      sendResponse(res, 401, { error: 'Unauthorized' });
      return;
    }

    if (!adminDb) {
      sendResponse(res, 500, { error: 'Admin database not initialized' });
      return;
    }

    try {
      const { userId } = await parseJsonBody(req);
      if (!userId) {
        sendResponse(res, 400, { error: 'Missing userId' });
        return;
      }

      await adminDb.collection('users').doc(userId).set({
        isVip: false,
        planType: 'FREE',
        expiresAt: 'Revoked by Administrator',
        expiryTimestamp: 0
      }, { merge: true });

      console.log(`[Admin VIP Revoke] User ${userId} VIP revoked by ${authAdmin.user}`);
      sendResponse(res, 200, { success: true, message: `VIP revoked for user ${userId}` });
    } catch (err) {
      console.error('[Admin VIP Revoke Error]', err.message);
      sendResponse(res, 500, { error: err.message });
    }
    return;
  }

  // Admin API: Delete User Document
  if (req.method === 'POST' && req.url === '/api/admin/user/delete') {
    const authAdmin = await isAuthorizedAdmin(req);
    if (!authAdmin) {
      sendResponse(res, 401, { error: 'Unauthorized' });
      return;
    }

    if (!adminDb) {
      sendResponse(res, 500, { error: 'Admin database not initialized' });
      return;
    }

    try {
      const { userId } = await parseJsonBody(req);
      if (!userId) {
        sendResponse(res, 400, { error: 'Missing userId' });
        return;
      }

      await adminDb.collection('users').doc(userId).delete();
      console.log(`[Admin User Delete] User ${userId} deleted by ${authAdmin.user}`);
      sendResponse(res, 200, { success: true, message: `User document ${userId} deleted.` });
    } catch (err) {
      console.error('[Admin User Delete Error]', err.message);
      sendResponse(res, 500, { error: err.message });
    }
    return;
  }

  // Admin API: Provision or Register New User with VIP
  if (req.method === 'POST' && req.url === '/api/admin/user/provision') {
    const authAdmin = await isAuthorizedAdmin(req);
    if (!authAdmin) {
      sendResponse(res, 401, { error: 'Unauthorized' });
      return;
    }

    if (!adminDb) {
      sendResponse(res, 500, { error: 'Admin database not initialized' });
      return;
    }

    try {
      const { email, name, tier, days, paymentId } = await parseJsonBody(req);
      const cleanEmail = String(email || '').trim().toLowerCase();
      if (!cleanEmail || !cleanEmail.includes('@')) {
        sendResponse(res, 400, { error: 'Valid email address is required' });
        return;
      }

      const planTier = tier || 'MONTHLY';
      const isLifetime = planTier === 'LIFETIME';
      const now = Date.now();
      const expiryTimestamp = isLifetime ? -1 : (now + (Number(days || 30) * 24 * 60 * 60 * 1000));
      const expiresAt = isLifetime ? 'N/A (Permanent)' : new Date(expiryTimestamp).toLocaleDateString('en-US', {
        month: 'short',
        day: 'numeric',
        year: 'numeric'
      });

      // Find if user doc already exists
      const snap = await adminDb.collection('users').where('userEmail', '==', cleanEmail).limit(1).get();
      const docRef = !snap.empty ? snap.docs[0].ref : adminDb.collection('users').doc();

      const userDoc = {
        userEmail: cleanEmail,
        displayName: (name && name.trim()) || 'Gamer',
        isVip: planTier !== 'FREE',
        planType: planTier,
        paymentId: (paymentId && String(paymentId).trim()) || `manual_adm_${Date.now()}`,
        purchasedAt: new Date().toISOString(),
        expiresAt,
        expiryTimestamp
      };

      await docRef.set(userDoc, { merge: true });
      console.log(`[Admin Provision] User ${cleanEmail} provisioned as ${planTier} by ${authAdmin.user}`);
      sendResponse(res, 200, {
        success: true,
        message: `User ${cleanEmail} successfully provisioned.`,
        userId: docRef.id,
        user: userDoc
      });
    } catch (err) {
      console.error('[Admin Provision Error]', err.message);
      sendResponse(res, 500, { error: err.message });
    }
    return;
  }

  // Admin API: Update Payment Transaction ID
  if (req.method === 'POST' && req.url === '/api/admin/user/update-payment') {
    const authAdmin = await isAuthorizedAdmin(req);
    if (!authAdmin) {
      sendResponse(res, 401, { error: 'Unauthorized' });
      return;
    }

    if (!adminDb) {
      sendResponse(res, 500, { error: 'Admin database not initialized' });
      return;
    }

    try {
      const { userId, paymentId } = await parseJsonBody(req);
      if (!userId) {
        sendResponse(res, 400, { error: 'Missing userId' });
        return;
      }

      await adminDb.collection('users').doc(userId).set({
        paymentId: (paymentId && String(paymentId).trim()) || null
      }, { merge: true });

      sendResponse(res, 200, { success: true, message: 'Transaction ID updated.' });
    } catch (err) {
      sendResponse(res, 500, { error: err.message });
    }
    return;
  }

  // Not Found
  sendResponse(res, 404, { error: 'Route not found' });
  } catch (fatalErr) {
    console.error('[HTTP Fatal Error]', fatalErr);
    if (!res.headersSent) {
      sendResponse(res, 500, { error: 'Internal server error' });
    }
  }
});

// WebSocket Server attached to HTTP server
const wss = new WebSocketServer({ server });

wss.on('connection', (ws, req) => {
  ws.isAlive = true;
  ws.clientIp = req?.headers['x-forwarded-for']?.split(',')[0]?.trim() || req?.socket?.remoteAddress || 'unknown';

  ws.on('pong', () => {
    ws.isAlive = true;
  });

  ws.on('message', (message) => {
    let data;
    try {
      data = JSON.parse(message.toString());
    } catch (err) {
      sendError(ws, 'Invalid JSON payload');
      return;
    }

    if (!data || !data.type) {
      sendError(ws, 'Missing "type" in payload');
      return;
    }

    switch (data.type) {
      case 'create-room':
        handleCreateRoom(ws, data);
        break;

      case 'join-room':
        handleJoinRoom(ws, data);
        break;

      case 'offer':
      case 'answer':
      case 'ice-candidate':
        handleRelaySignal(ws, data);
        break;

      case 'leave-room':
        handleLeaveRoom(ws);
        break;

      case 'ping':
        // Cloud signaling heartbeat with echoed client timestamp for live latency measurement
        sendJson(ws, { 
          type: 'pong',
          timestamp: data.timestamp || Date.now()
        });
        break;

      case 'tactical-callout':
        handleBroadcastRoom(ws, data);
        break;

      case 'kick-peer':
        handleKickPeer(ws, data);
        break;

      default:
        sendError(ws, `Unknown message type: ${data.type}`);
        break;
    }
  });

  ws.on('close', () => {
    handleLeaveRoom(ws);
  });

  ws.on('error', (err) => {
    console.error('WebSocket client error:', err.message);
    handleLeaveRoom(ws);
  });
});

// Heartbeat interval to maintain WebSocket connection on free tier hosting
const interval = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) {
      return ws.terminate();
    }
    ws.isAlive = false;
    ws.ping();
  });
}, 30000);

wss.on('close', () => {
  clearInterval(interval);
});

// --- Message Handlers ---

function generateRoomCode() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let code = '';
  do {
    code = '';
    for (let i = 0; i < 5; i++) {
      code += chars.charAt(Math.floor(Math.random() * chars.length));
    }
  } while (rooms.has(code));
  return code;
}

function handleCreateRoom(ws, data = {}) {
  // If client is already in a room, leave first
  handleLeaveRoom(ws);

  const roomCode = generateRoomCode();
  const peerId = randomUUID();
  const name = data.name || 'Gamer';
  const avatar = data.avatar || 'avatar_1';

  const room = {
    code: roomCode,
    hostPeerId: peerId,
    peers: new Map()
  };

  room.peers.set(peerId, { ws, peerId, name, avatar, isHost: true });
  rooms.set(roomCode, room);
  clients.set(ws, { peerId, roomCode, name, avatar, isHost: true });

  sendJson(ws, {
    type: 'room-created',
    roomCode,
    peerId,
    name,
    avatar,
    isHost: true
  });

  console.log(`[Create] Room ${roomCode} created by ${name} (${peerId})`);
}

function handleJoinRoom(ws, data = {}) {
  const clientIp = ws.clientIp || 'unknown';

  // Room code brute-force protection: max 12 attempts per minute
  if (isRateLimited(roomAttemptLimits, clientIp, 12, 60000)) {
    console.warn(`[Brute-Force Protection] Join rate limit exceeded by ${clientIp}`);
    sendError(ws, 'Too many room join attempts. Please wait 1 minute before trying again.');
    return;
  }

  const requestedCode = typeof data === 'string' ? data : data.roomCode;
  if (!requestedCode || typeof requestedCode !== 'string') {
    sendError(ws, 'Invalid room code');
    return;
  }

  const roomCode = requestedCode.toUpperCase().trim();
  if (!/^[A-Z0-9]{5}$/.test(roomCode)) {
    sendError(ws, 'Invalid room code format (must be 5 alphanumeric characters)');
    return;
  }
  let room = rooms.get(roomCode);

  if (!room) {
    // If it's a valid 5-character alphanumeric room code (e.g. persistent room saved in Firestore),
    // re-create the room in memory on demand so returning squad members can rejoin anytime even with 0 members!
    if (/^[A-Z0-9]{5}$/.test(roomCode)) {
      room = {
        code: roomCode,
        peers: new Map(),
        cleanupTimer: null
      };
      rooms.set(roomCode, room);
      console.log(`[Rejoin] Persistent room ${roomCode} re-opened in memory`);
    } else {
      sendError(ws, 'Room not found');
      return;
    }
  } else if (room.cleanupTimer) {
    clearTimeout(room.cleanupTimer);
    room.cleanupTimer = null;
    console.log(`[Grace Period Cancelled] Peer joined room ${roomCode} — cancelling idle cleanup timer`);
  }

  if (room.peers.size >= 5) {
    sendError(ws, 'Room is full (max 5 members)');
    return;
  }

  // Leave any existing room first
  handleLeaveRoom(ws);

  const peerId = randomUUID();
  const name = data.name || 'Gamer';
  const avatar = data.avatar || 'avatar_1';

  const existingPeerIds = Array.from(room.peers.keys());
  const existingMembers = Array.from(room.peers.values()).map(p => ({
    peerId: p.peerId,
    name: p.name || 'Gamer',
    avatar: p.avatar || 'avatar_1'
  }));

  // Notify existing members about the new peer
  for (const [existingId, peerObj] of room.peers.entries()) {
    if (peerObj.ws.readyState === WebSocket.OPEN) {
      sendJson(peerObj.ws, {
        type: 'peer-joined',
        peerId,
        name,
        avatar
      });
    }
  }

  if (!room.hostPeerId) {
    room.hostPeerId = existingPeerIds[0] || peerId;
  }
  const isHost = room.hostPeerId === peerId;

  // Add new peer to room
  room.peers.set(peerId, { ws, peerId, name, avatar, isHost });
  clients.set(ws, { peerId, roomCode, name, avatar, isHost });

  // Send confirmation, peer IDs, and rich member info to joiner
  sendJson(ws, {
    type: 'room-joined',
    roomCode,
    peerId,
    hostPeerId: room.hostPeerId,
    isHost,
    peers: existingPeerIds,
    members: existingMembers
  });

  console.log(`[Join] ${name} (${peerId}) joined room ${roomCode} (${room.peers.size}/5 members)`);
}

function handleRelaySignal(ws, data) {
  const clientInfo = clients.get(ws);
  if (!clientInfo) {
    sendError(ws, 'You are not in a room');
    return;
  }

  const { peerId: senderPeerId, roomCode } = clientInfo;
  const targetPeerId = data.targetPeerId || data.target;

  if (!targetPeerId) {
    sendError(ws, 'Missing targetPeerId for signaling message');
    return;
  }

  const room = rooms.get(roomCode);
  if (!room) {
    sendError(ws, 'Room no longer exists');
    return;
  }

  const targetPeer = room.peers.get(targetPeerId);
  if (!targetPeer) {
    sendError(ws, `Target peer ${targetPeerId} not found in room`);
    return;
  }

  if (targetPeer.ws.readyState === WebSocket.OPEN) {
    // Forward message as-is, ensuring senderPeerId is attached
    sendJson(targetPeer.ws, {
      ...data,
      senderPeerId
    });
  }
}

function handleBroadcastRoom(ws, data) {
  const clientInfo = clients.get(ws);
  if (!clientInfo) {
    sendError(ws, 'You are not in a room');
    return;
  }

  const { peerId: senderPeerId, roomCode, name: senderName } = clientInfo;
  const room = rooms.get(roomCode);
  if (!room) return;

  for (const [memberId, peerObj] of room.peers.entries()) {
    if (memberId !== senderPeerId && peerObj.ws.readyState === WebSocket.OPEN) {
      sendJson(peerObj.ws, {
        ...data,
        senderPeerId,
        senderName: senderName || 'Gamer'
      });
    }
  }
}

function handleKickPeer(ws, data = {}) {
  const clientInfo = clients.get(ws);
  if (!clientInfo) {
    sendError(ws, 'You are not in a room');
    return;
  }

  const { peerId: senderPeerId, roomCode, name: senderName } = clientInfo;
  const room = rooms.get(roomCode);
  if (!room) {
    sendError(ws, 'Room not found');
    return;
  }

  // Only the squad host who created the room can kick members
  if (room.hostPeerId && room.hostPeerId !== senderPeerId) {
    sendError(ws, 'Only the squad leader/host can kick participants from this room.');
    return;
  }

  const targetPeerId = data.targetPeerId;
  if (!targetPeerId || targetPeerId === senderPeerId) return;

  const targetPeer = room.peers.get(targetPeerId);
  if (targetPeer && targetPeer.ws) {
    console.log(`[Kick] ${targetPeer.name} (${targetPeerId}) kicked from room ${roomCode} by host ${senderName}`);
    sendJson(targetPeer.ws, {
      type: 'kicked-from-room',
      roomCode,
      reason: data.reason || 'You were removed from the room by the squad host.'
    });
    handleLeaveRoom(targetPeer.ws);
  }
}

function handleLeaveRoom(ws) {
  const clientInfo = clients.get(ws);
  if (!clientInfo) return;

  const { peerId, roomCode } = clientInfo;
  clients.delete(ws);

  const room = rooms.get(roomCode);
  if (room) {
    room.peers.delete(peerId);

    // Notify remaining members
    for (const [remainingId, peerObj] of room.peers.entries()) {
      if (peerObj.ws.readyState === WebSocket.OPEN) {
        sendJson(peerObj.ws, {
          type: 'peer-left',
          peerId
        });
      }
    }

    console.log(`[Leave] Peer ${peerId} left room ${roomCode} (${room.peers.size} remaining)`);

    // Clean up empty room with 3-minute grace period for network recovery and airplane mode toggles
    if (room.peers.size === 0) {
      if (room.cleanupTimer) clearTimeout(room.cleanupTimer);
      room.cleanupTimer = setTimeout(() => {
        if (room.peers.size === 0) {
          rooms.delete(roomCode);
          console.log(`[Clean] Room ${roomCode} deleted after 3-minute idle grace period`);
        }
      }, 180000); // 3 minutes = 180,000ms
      console.log(`[Grace Period] Room ${roomCode} empty — keeping alive for 3 minutes for player reconnect`);
    }
  }
}

// Helper utilities
function sendJson(ws, payload) {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(payload));
  }
}

function sendError(ws, message) {
  sendJson(ws, {
    type: 'error',
    message
  });
}

function buildWelcomePlainText(name) {
  return `Hey ${name},
Welcome to GamerVoice. ❤️

Thanks for being here.
GamerVoice started with a pretty simple frustration: sometimes you just want to talk to your friends while you play — and somehow the mic cuts out, the audio glitches, or the app gets in the way.
So we decided to build something of our own.
We’re still growing, still learning, and still improving GamerVoice every day. It may not be perfect yet, but every person who joins genuinely matters to us. You’re part of the reason we’re building this in the first place.
You can use GamerVoice to talk with your squad, create rooms, and have a place to hang out while you play. It’s built with Free Fire squads in mind — but whether you’re playing something else or just looking for a place to hang out, you’re welcome here too.
A little bit of what’s under the hood: it’s light on your battery and RAM, your game audio stays clean while we run alongside it, and everything is peer-to-peer — we don’t record or store your voice, ever.
And if you ever want to go further, VIP unlocks a few extras like noise-cancelled comms and permanent private squad rooms. No rush though — only whenever you're ready.
And honestly, we’d love to hear from you.

If something doesn’t work properly, you have an idea, or there’s something you simply wish GamerVoice could do, tell us. You can reply directly to this email. There’s a real person on the other side, and we read it.
Thanks for giving GamerVoice a chance.
We hope it becomes a small part of a lot of great games, late-night conversations, ridiculous clutches, and memories with your friends.
Welcome to the community. 🎮
— The GamerVoice Team`;
}

function buildWelcomeHtml(name, email) {
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Welcome to GamerVoice</title>
  <style>
    body, table, td, a { -webkit-text-size-adjust: 100%; -ms-text-size-adjust: 100%; }
    table, td { mso-table-lspace: 0pt; mso-table-rspace: 0pt; }
    img { -ms-interpolation-mode: bicubic; border: 0; height: auto; line-height: 100%; outline: none; text-decoration: none; }
    table { border-collapse: collapse !important; }
    body { height: 100% !important; margin: 0 !important; padding: 0 !important; width: 100% !important; background-color: #060911; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; }
    @media screen and (max-width: 600px) {
      .email-container { width: 100% !important; padding: 12px !important; }
      .feature-col { display: block !important; width: 100% !important; margin-bottom: 12px !important; }
    }
  </style>
</head>
<body style="margin: 0; padding: 24px 12px; background-color: #060911; color: #E2E8F0;">
  <center>
    <table border="0" cellpadding="0" cellspacing="0" width="100%" style="max-width: 600px; background: #0B1120; border-radius: 20px; border: 1px solid #1E293B; overflow: hidden; box-shadow: 0 10px 30px rgba(0, 0, 0, 0.6);" class="email-container">
      <tr><td height="4" style="background: linear-gradient(90deg, #00FF88 0%, #00E5FF 50%, #7C3AED 100%);"></td></tr>
      <tr>
        <td style="padding: 32px 32px 20px 32px; text-align: left;">
          <table border="0" cellpadding="0" cellspacing="0" width="100%">
            <tr>
              <td width="48" valign="middle" style="padding-right: 14px;">
                <img src="https://raw.githubusercontent.com/aryan-2728372882/GamersVoice/main/logo.png" width="44" height="44" alt="GamerVoice Logo" style="display: block; border-radius: 12px; border: 1px solid #FFD700; box-shadow: 0 0 12px rgba(255, 215, 0, 0.25);" />
              </td>
              <td valign="middle">
                <span style="display: inline-block; font-size: 22px; font-weight: 900; letter-spacing: 2px; color: #00FF88; text-transform: uppercase;">GAMERVOICE</span>
                <div style="font-size: 11px; color: #64748B; font-weight: 700; letter-spacing: 1.5px; margin-top: 2px;">SQUAD AUDIO // ZERO-LAG VOIP</div>
              </td>
              <td align="right" valign="top">
                <span style="display: inline-block; padding: 6px 12px; background: rgba(0, 255, 136, 0.1); border: 1px solid rgba(0, 255, 136, 0.3); border-radius: 20px; font-size: 11px; font-weight: 700; color: #00FF88; letter-spacing: 0.5px;">
                  SQUAD CLEARANCE &#10003;
                </span>
              </td>
            </tr>
          </table>
        </td>
      </tr>
      <tr>
        <td style="padding: 0 32px 24px 32px;">
          <h1 style="margin: 0 0 8px 0; font-size: 26px; font-weight: 800; color: #F8FAFC; line-height: 1.3;">
            Hey <span style="color: #00E5FF;">${name}</span>,
          </h1>
          <div style="display: inline-block; font-size: 18px; font-weight: 700; color: #00FF88; margin-bottom: 16px;">
            Welcome to GamerVoice. ❤️
          </div>
          <p style="margin: 0; font-size: 15px; line-height: 1.7; color: #94A3B8;">Thanks for being here.</p>
        </td>
      </tr>
      <tr>
        <td style="padding: 0 32px 24px 32px;">
          <div style="background: rgba(15, 23, 42, 0.6); border: 1px solid rgba(51, 65, 85, 0.6); border-radius: 14px; padding: 20px; margin-bottom: 20px;">
            <p style="margin: 0 0 14px 0; font-size: 14px; line-height: 1.75; color: #CBD5E1;">
              GamerVoice started with a pretty simple frustration: sometimes you just want to talk to your friends while you play &mdash; and somehow the mic cuts out, the audio glitches, or the app gets in the way.
            </p>
            <p style="margin: 0 0 14px 0; font-size: 14px; line-height: 1.75; color: #CBD5E1;">
              So we decided to build something of our own.
            </p>
            <p style="margin: 0; font-size: 14px; line-height: 1.75; color: #CBD5E1;">
              We’re still growing, still learning, and still improving GamerVoice every day. It may not be perfect yet, but every person who joins genuinely matters to us. You’re part of the reason we’re building this in the first place.
            </p>
          </div>
          <p style="margin: 0 0 18px 0; font-size: 14px; line-height: 1.75; color: #CBD5E1;">
            You can use GamerVoice to talk with your squad, create rooms, and have a place to hang out while you play. It’s built with <strong style="color: #F8FAFC;">Free Fire</strong> squads in mind &mdash; but whether you’re playing something else or just looking for a place to hang out, you’re welcome here too.
          </p>
        </td>
      </tr>
      <tr>
        <td style="padding: 0 32px 24px 32px;">
          <div style="font-size: 12px; font-weight: 800; color: #64748B; letter-spacing: 1.2px; text-transform: uppercase; margin-bottom: 12px;">
            A LITTLE BIT OF WHAT’S UNDER THE HOOD
          </div>
          <table border="0" cellpadding="0" cellspacing="0" width="100%">
            <tr>
              <td width="48%" class="feature-col" valign="top" style="background: #0F172A; border: 1px solid #1E293B; border-radius: 12px; padding: 16px; margin-right: 4%;">
                <div style="font-size: 20px; margin-bottom: 6px;">⚡</div>
                <div style="font-size: 14px; font-weight: 700; color: #F1F5F9; margin-bottom: 4px;">Light on RAM &amp; Battery</div>
                <div style="font-size: 12px; color: #94A3B8; line-height: 1.5;">Clean game audio with zero FPS drops or in-game mic stutter.</div>
              </td>
              <td width="4%"></td>
              <td width="48%" class="feature-col" valign="top" style="background: #0F172A; border: 1px solid #1E293B; border-radius: 12px; padding: 16px;">
                <div style="font-size: 20px; margin-bottom: 6px;">🔒</div>
                <div style="font-size: 14px; font-weight: 700; color: #F1F5F9; margin-bottom: 4px;">100% Peer-to-Peer</div>
                <div style="font-size: 12px; color: #94A3B8; line-height: 1.5;">Direct encrypted streams. We don’t record or store your voice, ever.</div>
              </td>
            </tr>
          </table>
          <div style="margin-top: 14px; background: rgba(124, 58, 237, 0.08); border: 1px solid rgba(124, 58, 237, 0.25); border-radius: 12px; padding: 14px 16px;">
            <table border="0" cellpadding="0" cellspacing="0" width="100%">
              <tr>
                <td width="28" valign="top" style="font-size: 18px; line-height: 1;">👑</td>
                <td style="font-size: 13px; line-height: 1.6; color: #C4B5FD;">
                  <strong style="color: #DDD6FE;">VIP Squad Access:</strong> Unlocks extras like noise-cancelled comms and permanent private squad rooms. No rush though &mdash; only whenever you’re ready.
                </td>
              </tr>
            </table>
          </div>
        </td>
      </tr>
      <tr>
        <td style="padding: 0 32px 28px 32px;">
          <div style="background: linear-gradient(135deg, rgba(0, 229, 255, 0.08) 0%, rgba(0, 255, 136, 0.08) 100%); border: 1px solid rgba(0, 229, 255, 0.25); border-radius: 14px; padding: 20px;">
            <div style="font-size: 15px; font-weight: 700; color: #F8FAFC; margin-bottom: 8px;">
              And honestly, we’d love to hear from you. 💬
            </div>
            <p style="margin: 0; font-size: 13px; line-height: 1.7; color: #CBD5E1;">
              If something doesn’t work properly, you have an idea, or there’s something you simply wish GamerVoice could do, tell us. <strong>You can reply directly to this email.</strong> There’s a real person on the other side, and we read it.
            </p>
          </div>
        </td>
      </tr>
      <tr>
        <td style="padding: 0 32px 32px 32px;">
          <p style="margin: 0 0 16px 0; font-size: 14px; line-height: 1.75; color: #CBD5E1;">
            Thanks for giving GamerVoice a chance.<br>
            We hope it becomes a small part of a lot of great games, late-night conversations, ridiculous clutches, and memories with your friends.
          </p>
          <div style="font-size: 16px; font-weight: 800; color: #00FF88; margin-bottom: 6px;">Welcome to the community. 🎮</div>
          <div style="font-size: 15px; font-weight: 700; color: #F8FAFC;">&mdash; The GamerVoice Team</div>
        </td>
      </tr>
      <tr>
        <td style="background: #080D1A; border-top: 1px solid #1E293B; padding: 24px 32px; text-align: center;">
          <p style="margin: 0 0 8px 0; font-size: 11px; color: #64748B;">
            Sent with ❤️ to <strong style="color: #94A3B8;">${email}</strong> &bull; GamerVoice Squad Network
          </p>
          <p style="margin: 0; font-size: 11px; color: #475569; line-height: 1.5;">
            You received this email because you signed up for GamerVoice.<br>
            Need technical help? Reply directly to this email or visit our in-app Contact Support.
          </p>
        </td>
      </tr>
    </table>
  </center>
</body>
</html>`;
}

if (require.main === module) {
  server.listen(PORT, () => {
    console.log(`GamerVoice signaling & security server listening on port ${PORT}`);

    // Render Free-Tier Keep-Alive Engine: Prevents container from sleeping after 15 min idle
    const PING_INTERVAL_MS = 10 * 60 * 1000; // 10 minutes
    const HOST_URL = process.env.RENDER_EXTERNAL_URL || 'https://gamersvoice.onrender.com';

    if (process.env.ENABLE_KEEP_ALIVE !== 'false') {
      setInterval(() => {
        try {
          const pingUrl = `${HOST_URL}/health`;
          const client = pingUrl.startsWith('https') ? require('https') : require('http');
          client.get(pingUrl, (res) => {
            res.on('data', () => {}); // Consume stream
          }).on('error', (err) => {
            console.log('[Keep-Alive Self-Ping]', err.message);
          });
        } catch (e) {
          console.warn('[Keep-Alive Ping]', e.message);
        }
      }, PING_INTERVAL_MS);
      console.log(`[Keep-Alive Engine] Active: self-pinging every 10 min at ${HOST_URL}/health`);
    }
  });
}

// Process resilience: crash logging, client auto-reconnect broadcast, and graceful restart
let isShuttingDown = false;

async function handleFatalProcessError(type, err) {
  console.error(`[FATAL PROCESS ${type}]`, err?.stack || err);

  if (adminDb) {
    try {
      await adminDb.collection('server_errors').add({
        type,
        message: err?.message || String(err),
        stack: err?.stack || null,
        timestamp: Date.now(),
        iso: new Date().toISOString()
      });
    } catch (_) {}
  }

  if (process.env.NODE_ENV === 'test') {
    return;
  }

  if (isShuttingDown) return;
  isShuttingDown = true;

  // Broadcast graceful restart notice to connected WebSockets so clients auto-reconnect cleanly
  try {
    wss.clients.forEach((client) => {
      if (client.readyState === WebSocket.OPEN) {
        sendJson(client, {
          type: 'server-restarting',
          message: 'Signaling server cycling for health maintenance. Auto-reconnecting...'
        });
      }
    });
  } catch (_) {}

  // 1.5-second grace period to flush pending sockets and logs, then clean fail-fast exit
  setTimeout(() => {
    try { server.close(); } catch (_) {}
    process.exit(1);
  }, 1500);
}

process.on('uncaughtException', (err) => {
  handleFatalProcessError('UNCAUGHT_EXCEPTION', err);
});

process.on('unhandledRejection', (reason) => {
  handleFatalProcessError('UNHANDLED_REJECTION', reason);
});

module.exports = {
  server,
  rooms,
  clients,
  isPaymentUsed,
  recordUsedPayment
};
