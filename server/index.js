const http = require('http');
const { WebSocketServer, WebSocket } = require('ws');
const { randomUUID } = require('crypto');

const PORT = process.env.PORT || 8080;

// In-memory data structures
// rooms: Map<roomCode, { code: string, peers: Map<peerId, { ws: WebSocket, peerId: string }> }>
const rooms = new Map();

// clients: Map<WebSocket, { peerId: string, roomCode: string }>
const clients = new Map();

// Generate a random 5-character uppercase alphanumeric room code
function generateRoomCode() {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
  let code = '';
  for (let i = 0; i < 5; i++) {
    code += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  if (rooms.has(code)) {
    return generateRoomCode();
  }
  return code;
}

// HTTP Server for Health Checks (Render.com free tier requirement)
const server = http.createServer((req, res) => {
  if (req.url === '/' || req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      status: 'ok',
      service: 'GamerVoice WebRTC Signaling Server',
      activeRooms: rooms.size,
      connectedClients: clients.size
    }));
  } else {
    res.writeHead(404);
    res.end();
  }
});

// WebSocket Server attached to HTTP server
const wss = new WebSocketServer({ server });

wss.on('connection', (ws) => {
  ws.isAlive = true;

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
        handleCreateRoom(ws);
        break;

      case 'join-room':
        handleJoinRoom(ws, data.roomCode);
        break;

      case 'offer':
      case 'answer':
      case 'ice-candidate':
        handleRelaySignal(ws, data);
        break;

      case 'leave-room':
        handleLeaveRoom(ws);
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

function handleCreateRoom(ws) {
  // If client is already in a room, leave first
  handleLeaveRoom(ws);

  const roomCode = generateRoomCode();
  const peerId = randomUUID();

  const room = {
    code: roomCode,
    peers: new Map()
  };

  room.peers.set(peerId, { ws, peerId });
  rooms.set(roomCode, room);
  clients.set(ws, { peerId, roomCode });

  sendJson(ws, {
    type: 'room-created',
    roomCode,
    peerId
  });

  console.log(`[Create] Room ${roomCode} created by peer ${peerId}`);
}

function handleJoinRoom(ws, requestedCode) {
  if (!requestedCode || typeof requestedCode !== 'string') {
    sendError(ws, 'Invalid room code');
    return;
  }

  const roomCode = requestedCode.toUpperCase().trim();
  const room = rooms.get(roomCode);

  if (!room) {
    sendError(ws, 'Room not found');
    return;
  }

  if (room.peers.size >= 5) {
    sendError(ws, 'Room is full (max 5 members)');
    return;
  }

  // Leave any existing room first
  handleLeaveRoom(ws);

  const peerId = randomUUID();
  const existingPeerIds = Array.from(room.peers.keys());

  // Notify existing members about the new peer
  for (const [existingId, peerObj] of room.peers.entries()) {
    if (peerObj.ws.readyState === WebSocket.OPEN) {
      sendJson(peerObj.ws, {
        type: 'peer-joined',
        peerId
      });
    }
  }

  // Add new peer to room
  room.peers.set(peerId, { ws, peerId });
  clients.set(ws, { peerId, roomCode });

  // Send confirmation and list of existing peers to joiner
  sendJson(ws, {
    type: 'room-joined',
    roomCode,
    peerId,
    peers: existingPeerIds
  });

  console.log(`[Join] Peer ${peerId} joined room ${roomCode} (${room.peers.size}/5 members)`);
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

server.listen(PORT, () => {
  console.log(`GamerVoice signaling server listening on port ${PORT}`);
});
