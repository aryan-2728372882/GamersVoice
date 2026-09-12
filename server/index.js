const fs = require('fs');
const path = require('path');
const http = require('http');
const { WebSocketServer, WebSocket } = require('ws');
const { randomUUID } = require('crypto');

const nodemailer = require('nodemailer');

const PORT = process.env.PORT || 3000;

// Configuration from environment variables
const SMTP_USER = process.env.SMTP_USER || 'supportgamersvoice@gmail.com';
const SMTP_PASS = (process.env.SMTP_PASS || 'ennawlvrlygkkefe').replace(/\s+/g, '');
const SMTP_HOST = process.env.SMTP_HOST || 'smtp.gmail.com';
const SMTP_PORT = parseInt(process.env.SMTP_PORT || '465', 10);
const TELEGRAM_BOT_TOKEN = process.env.TELEGRAM_BOT_TOKEN || '';
const TELEGRAM_CHAT_ID = process.env.TELEGRAM_CHAT_ID || '';
const RAZORPAY_KEY_ID = process.env.RAZORPAY_KEY_ID || 'rzp_live_SWhlEskNokZ9rR';
const RAZORPAY_KEY_SECRET = process.env.RAZORPAY_KEY_SECRET || '';

// Replay attack prevention store (persisted to disk)
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

function recordUsedPayment(paymentId) {
  usedPaymentIds.add(paymentId);
  try {
    fs.writeFileSync(USED_PAYMENTS_FILE, JSON.stringify(Array.from(usedPaymentIds), null, 2), 'utf8');
  } catch (err) {
    console.error('[Storage Error] Failed to write used payments file:', err.message);
  }
}

// Welcome email deduplication store (persisted to disk)
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

function recordSentWelcomeEmail(email) {
  const cleanEmail = email.trim().toLowerCase();
  sentWelcomeEmails.add(cleanEmail);
  try {
    fs.writeFileSync(SENT_EMAILS_FILE, JSON.stringify(Array.from(sentWelcomeEmails), null, 2), 'utf8');
  } catch (err) {
    console.error('[Storage Error] Failed to write sent welcome emails file:', err.message);
  }
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

const PUBLIC_DIR = path.join(__dirname, 'public');

function serveStaticFile(res, filePath, defaultMime = 'application/octet-stream', downloadName = null) {
  try {
    const safePath = path.normalize(filePath);
    if (!safePath.startsWith(PUBLIC_DIR) && !safePath.startsWith(path.dirname(PUBLIC_DIR))) {
      return false;
    }

    if (fs.existsSync(safePath) && fs.statSync(safePath).isFile()) {
      const ext = path.extname(safePath).toLowerCase();
      const contentType = MIME_TYPES[ext] || defaultMime;
      const headers = {
        'Content-Type': contentType,
        'Access-Control-Allow-Origin': '*'
      };
      if (downloadName) {
        headers['Content-Disposition'] = `attachment; filename="${downloadName}"`;
      }
      res.writeHead(200, headers);
      fs.createReadStream(safePath).pipe(res);
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
        'Access-Control-Allow-Headers': 'Content-Type, Authorization'
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
      const candidates = [
        path.join(PUBLIC_DIR, 'logo.png'),
        path.join(__dirname, 'logo.png'),
        path.join(__dirname, '../logo.png'),
        path.join(__dirname, '../app/src/main/res/drawable/app_logo.png')
      ];
      const logoPath = candidates.find(p => fs.existsSync(p));
      if (logoPath && serveStaticFile(res, logoPath, 'image/png')) {
        return;
      } else {
        sendResponse(res, 404, { error: 'Logo not found' });
        return;
      }
    }

    // Direct APK Download
    if (req.url === '/download-apk' || req.url === '/gamervoice.apk') {
      const apkCandidates = [
        path.join(PUBLIC_DIR, 'gamervoice-release.apk'),
        path.join(__dirname, '../app/build/outputs/apk/release/app-release.apk')
      ];
      const apkPath = apkCandidates.find(p => fs.existsSync(p));
      if (apkPath && serveStaticFile(res, apkPath, 'application/vnd.android.package-archive', 'GamerVoice-v1.0.0.apk')) {
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

      if (pathname === '/') pathname = '/index.html';

      const targetStaticPath = path.join(PUBLIC_DIR, pathname);
      if (serveStaticFile(res, targetStaticPath)) {
        return;
      }
    }

  // API 1: Server-Side Welcome Email Relay
  if (req.method === 'POST' && req.url === '/api/send-welcome-email') {
    try {
      const { email, name } = await parseJsonBody(req);
      if (!email || !email.includes('@')) {
        sendResponse(res, 400, { error: 'Invalid recipient email address' });
        return;
      }

      const displayName = name && name.trim() ? name.trim() : 'Gamer';
      const cleanEmail = email.trim().toLowerCase();

      // Deduplication: Check if welcome email was already dispatched to this email (from web or mobile app)
      if (sentWelcomeEmails.has(cleanEmail)) {
        console.log(`[Email Deduplication] Welcome email already sent to ${cleanEmail}. Bypassing duplicate dispatch.`);
        sendResponse(res, 200, {
          success: true,
          alreadySent: true,
          message: 'Welcome email was already dispatched to this address.'
        });
        return;
      }

      if (!SMTP_PASS) {
        console.log(`[SMTP Notice] Server SMTP_PASS not set in environment. Mocking dispatch to: ${cleanEmail}`);
        recordSentWelcomeEmail(cleanEmail);
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
      const { category, subject, description, userEmail, userId, appVersion } = await parseJsonBody(req);
      if (!description || !subject) {
        sendResponse(res, 400, { error: 'Subject and description are required' });
        return;
      }

      const ticketId = 'GV-' + Date.now().toString(36).toUpperCase();
      const messageText = `🎮 *NEW GAMERVOICE SUPPORT TICKET*\n\n` +
        `🆔 *Ticket ID:* \`${ticketId}\`\n` +
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
          console.log(`[Support Ticket] Forwarded to Telegram: ${ticketId}`);
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

  // API 3: Cryptographic Razorpay Payment Verification
  if (req.method === 'POST' && req.url === '/api/verify-payment') {
    try {
      const { paymentId, planTier, userId, userEmail } = await parseJsonBody(req);
      if (!paymentId || !paymentId.startsWith('pay_')) {
        sendResponse(res, 400, { verified: false, error: 'Invalid payment ID format' });
        return;
      }

      // Replay Attack Protection: Check if paymentId was already redeemed
      if (usedPaymentIds.has(paymentId)) {
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

      // Commit to persistent used payment IDs store to prevent replay attacks
      recordUsedPayment(paymentId);

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
        // Cloud signaling heartbeat. Do not echo client timestamp as voice latency
        // because cloud server WAN round-trip (e.g. India to US/EU, ~350ms) is not P2P voice mesh latency.
        sendJson(ws, { type: 'pong' });
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
        peers: new Map()
      };
      rooms.set(roomCode, room);
      console.log(`[Rejoin] Persistent room ${roomCode} re-opened in memory`);
    } else {
      sendError(ws, 'Room not found');
      return;
    }
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

    // Clean up empty room
    if (room.peers.size === 0) {
      rooms.delete(roomCode);
      console.log(`[Clean] Room ${roomCode} deleted (empty)`);
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
