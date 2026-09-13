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

    adminApp = admin.initializeApp({
      credential: admin.cert(serviceAccount)
    }, 'GamerVoiceAdminApp');

    adminDb = getFirestore(adminApp);
    adminAuth = getAuth(adminApp);
    console.log('[Firebase Admin] Initialized successfully for project:', serviceAccount.project_id);
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

    // Static Web Assets from server/public/
    if (req.method === 'GET' && !req.url.startsWith('/api/')) {
      const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
      let pathname = parsedUrl.pathname;

      // Policy & Legal direct route redirects
      if (pathname === '/privacy' || pathname === '/terms' || pathname === '/refund') {
        res.writeHead(302, { 'Location': '/#' + pathname.slice(1) });
        res.end();
        return;
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

  // API 2B: Viral Squad Referral Code Redemption (+3 Days VIP)
  if (req.method === 'POST' && req.url === '/api/referral/redeem') {
    try {
      const { referralCode, refereeUid, refereeEmail, refereeName } = await parseJsonBody(req);
      if (!referralCode || !refereeUid) {
        sendResponse(res, 400, { error: 'Referral code and referee ID are required' });
        return;
      }

      const cleanCode = referralCode.trim().toUpperCase();
      console.log(`[Referral Engine] Processing redemption of code ${cleanCode} for referee ${refereeUid}`);

      if (adminDb) {
        try {
          const userDocRef = adminDb.collection('users').doc(refereeUid);
          const userSnap = await userDocRef.get();
          const now = Date.now();
          let baseTs = now;
          if (userSnap.exists) {
            const data = userSnap.data();
            if (data.expiryTimestamp && Number(data.expiryTimestamp) > now) {
              baseTs = Number(data.expiryTimestamp);
            }
          }
          const threeDaysMs = 3 * 24 * 60 * 60 * 1000;
          const newExpTs = baseTs + threeDaysMs;
          const expDate = new Date(newExpTs).toLocaleDateString('en-US', { month: 'short', day: '2-digit', year: 'numeric' });

          await userDocRef.set({
            isVip: true,
            planType: 'WEEKLY',
            expiryTimestamp: newExpTs,
            expiresAt: expDate,
            paymentId: `ref_${cleanCode}`,
            updatedAt: new Date().toISOString()
          }, { merge: true });

          // Also record in referrals collection
          const refDoc = adminDb.collection('referrals').doc(cleanCode);
          await refDoc.set({
            code: cleanCode,
            lastRedeemedBy: refereeUid,
            lastRedeemedAt: new Date().toISOString()
          }, { merge: true });
        } catch (dbErr) {
          console.warn('[Referral DB Warning]', dbErr.message);
        }
      }

      sendResponse(res, 200, {
        success: true,
        message: '🎉 Referral code verified! 3 Days of VIP pass added to your squad profile.'
      });
    } catch (err) {
      console.error('[Referral Error]', err.message);
      sendResponse(res, 500, { error: 'Failed to process referral: ' + err.message });
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
    peers: new Map()
  };

  room.peers.set(peerId, { ws, peerId, name, avatar });
  rooms.set(roomCode, room);
  clients.set(ws, { peerId, roomCode, name, avatar });

  sendJson(ws, {
    type: 'room-created',
    roomCode,
    peerId,
    name,
    avatar
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

  // Add new peer to room
  room.peers.set(peerId, { ws, peerId, name, avatar });
  clients.set(ws, { peerId, roomCode, name, avatar });

  // Send confirmation, peer IDs, and rich member info to joiner
  sendJson(ws, {
    type: 'room-joined',
    roomCode,
    peerId,
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

server.listen(PORT, () => {
  console.log(`GamerVoice signaling & security server listening on port ${PORT}`);
});

// Process resilience: prevent uncaught async errors from terminating voice sessions
process.on('uncaughtException', (err) => {
  console.error('[FATAL PROCESS UNCAUGHT EXCEPTION]', err.stack || err);
});

process.on('unhandledRejection', (reason) => {
  console.error('[FATAL PROCESS UNHANDLED REJECTION]', reason);
});
