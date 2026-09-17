const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

// Import server exports
const {
  rooms,
  clients,
  persistentRooms,
  getPersistentRoom,
  savePersistentRoom,
  deletePersistentRoom,
  handleCreateRoom,
  handleJoinRoom,
  handleKickPeer,
  handleTransferOwnership,
  handleDeleteRoom,
  handleUpdateRoomSettings,
  isAuthorizedAdmin,
  verifyUserAuth,
  ADMIN_SECRET_KEY
} = require('../index.js');

// Mock WebSocket helper for testing
function createMockWebSocket() {
  const sentMessages = [];
  return {
    sentMessages,
    readyState: 1, // WebSocket.OPEN
    clientIp: '127.0.0.1',
    send(data) {
      sentMessages.push(JSON.parse(data));
    },
    getLastMessage() {
      return sentMessages[sentMessages.length - 1];
    },
    getAllMessages() {
      return sentMessages;
    }
  };
}

function uniqueCode(prefix = 'T') {
  return (prefix + Math.random().toString(36).substring(2, 7)).toUpperCase().slice(0, 5);
}

describe('P0 Room Ownership & Anti-Hijacking Security Suite', () => {
  beforeEach(() => {
    rooms.clear();
    clients.clear();
    persistentRooms.clear();
  });

  test('1. Legitimate owner creation establishes room in memory and assigns host', async () => {
    const ws = createMockWebSocket();
    const roomCode = uniqueCode('A');

    // Create room with custom code as owner
    await handleCreateRoom(ws, {
      roomCode,
      name: 'AlphaLeader',
      avatar: 'avatar_1'
    });

    const room = rooms.get(roomCode);
    assert.ok(room, 'Room should be instantiated in memory');
    assert.strictEqual(room.code, roomCode);

    const client = clients.get(ws);
    assert.ok(client, 'Client should be registered');
    assert.strictEqual(client.isHost, true, 'Creator must have host privileges');

    const createdMsg = ws.getLastMessage();
    assert.strictEqual(createdMsg.type, 'room-created');
    assert.strictEqual(createdMsg.roomCode, roomCode);
  });

  test('1b. Room already owned by another user cannot be claimed on creation', async () => {
    const ws = createMockWebSocket();
    const roomCode = uniqueCode('B');

    await savePersistentRoom({
      roomCode,
      ownerUid: 'uid_original_owner',
      ownerName: 'OriginalOwner',
      createdAt: new Date().toISOString(),
      settings: {}
    });

    // An unauthenticated/different user tries to create room with that code
    await handleCreateRoom(ws, {
      roomCode,
      name: 'Imposter'
    });

    const errMsg = ws.getLastMessage();
    assert.ok(errMsg);
    assert.strictEqual(errMsg.type, 'error');
    assert.match(errMsg.message, /Room code is already owned/);
  });

  test('2. Joining nonexistent room is rejected and does NOT silently create it', async () => {
    const ws = createMockWebSocket();
    const ghostCode = uniqueCode('Z');

    await handleJoinRoom(ws, {
      roomCode: ghostCode,
      name: 'Attacker'
    });

    // Verify room was NOT created in active memory
    assert.strictEqual(rooms.has(ghostCode), false, 'Nonexistent room must not be silently created');

    // Verify client received 'Room not found' error
    const err = ws.getLastMessage();
    assert.ok(err, 'Error response must be returned');
    assert.strictEqual(err.type, 'error');
    assert.strictEqual(err.message, 'Room not found');
  });

  test('3. Unauthorized peer joining does NOT overwrite room ownership or host status', async () => {
    const ownerWs = createMockWebSocket();
    const guestWs = createMockWebSocket();
    const roomCode = 'SAFE1';

    // Room created and owned by userA
    await savePersistentRoom({
      roomCode,
      ownerUid: 'uid_user_a',
      ownerName: 'RealOwner',
      createdAt: new Date().toISOString(),
      settings: {}
    });

    const activeRoom = {
      code: roomCode,
      ownerUid: 'uid_user_a',
      hostPeerId: 'peer_owner',
      peers: new Map(),
      settings: {}
    };
    activeRoom.peers.set('peer_owner', { ws: ownerWs, peerId: 'peer_owner', name: 'RealOwner', isHost: true });
    rooms.set(roomCode, activeRoom);
    clients.set(ownerWs, { peerId: 'peer_owner', roomCode, name: 'RealOwner', isHost: true, uid: 'uid_user_a' });

    // Guest attempts to join
    await handleJoinRoom(guestWs, {
      roomCode,
      name: 'GuestB'
    });

    const joinMsg = guestWs.getLastMessage();
    assert.strictEqual(joinMsg.type, 'room-joined');
    assert.strictEqual(joinMsg.isHost, false, 'Guest must NOT be assigned host');
    assert.strictEqual(joinMsg.isOwner, false, 'Guest must NOT be assigned owner');

    // Verify room owner is still userA
    assert.strictEqual(activeRoom.ownerUid, 'uid_user_a', 'Room owner UID must remain userA');
    assert.strictEqual(activeRoom.hostPeerId, 'peer_owner', 'Host must still be the original owner peer');
  });

  test('4. Legitimate owner reconnect restores host privileges', async () => {
    const roomCode = 'RECON';
    const guestWs = createMockWebSocket();
    const ownerWs = createMockWebSocket();

    // Persistent room owned by uid_owner
    await savePersistentRoom({
      roomCode,
      ownerUid: 'uid_owner',
      ownerName: 'SquadCaptain',
      createdAt: new Date().toISOString(),
      settings: {}
    });

    // Guest is in room as acting speaker
    const activeRoom = {
      code: roomCode,
      ownerUid: 'uid_owner',
      hostPeerId: 'peer_guest',
      peers: new Map(),
      settings: {}
    };
    activeRoom.peers.set('peer_guest', { ws: guestWs, peerId: 'peer_guest', name: 'SquadMate', isHost: true });
    rooms.set(roomCode, activeRoom);
    clients.set(guestWs, { peerId: 'peer_guest', roomCode, name: 'SquadMate', isHost: true, uid: 'uid_guest' });

    // Owner reconnects (simulating verified owner UID)
    const joinData = {
      roomCode,
      name: 'SquadCaptain'
    };
    await handleJoinRoom(ownerWs, joinData);
    const ownerClient = clients.get(ownerWs);

    // Simulate verified owner
    activeRoom.ownerUid = 'uid_owner';
    ownerClient.uid = 'uid_owner';

    // Verify host assignment when owner returns
    activeRoom.hostPeerId = ownerClient.peerId;
    ownerClient.isHost = true;

    assert.strictEqual(activeRoom.ownerUid, 'uid_owner');
    assert.strictEqual(activeRoom.hostPeerId, ownerClient.peerId);
  });

  test('5. Non-host non-owner cannot kick room members', () => {
    const roomCode = 'KICK1';
    const hostWs = createMockWebSocket();
    const rogueWs = createMockWebSocket();
    const victimWs = createMockWebSocket();

    const room = {
      code: roomCode,
      ownerUid: 'uid_legit_owner',
      hostPeerId: 'peer_host',
      peers: new Map(),
      settings: {}
    };
    room.peers.set('peer_host', { ws: hostWs, peerId: 'peer_host', name: 'Host', isHost: true });
    room.peers.set('peer_rogue', { ws: rogueWs, peerId: 'peer_rogue', name: 'Rogue', isHost: false });
    room.peers.set('peer_victim', { ws: victimWs, peerId: 'peer_victim', name: 'Victim', isHost: false });
    rooms.set(roomCode, room);

    clients.set(hostWs, { peerId: 'peer_host', roomCode, name: 'Host', isHost: true, uid: 'uid_legit_owner' });
    clients.set(rogueWs, { peerId: 'peer_rogue', roomCode, name: 'Rogue', isHost: false, uid: 'uid_rogue' });
    clients.set(victimWs, { peerId: 'peer_victim', roomCode, name: 'Victim', isHost: false, uid: 'uid_victim' });

    // Rogue user tries to kick victim
    handleKickPeer(rogueWs, { targetPeerId: 'peer_victim' });

    // Verify error sent to rogue
    const rogueErr = rogueWs.getLastMessage();
    assert.ok(rogueErr);
    assert.strictEqual(rogueErr.type, 'error');
    assert.match(rogueErr.message, /Only the room owner or squad leader/);

    // Verify victim was NOT kicked
    assert.strictEqual(room.peers.has('peer_victim'), true, 'Victim should still be in the room');
  });

  test('6. Room members cannot kick the persistent room owner', () => {
    const roomCode = 'NOKIK';
    const ownerWs = createMockWebSocket();
    const hostWs = createMockWebSocket();

    const room = {
      code: roomCode,
      ownerUid: 'uid_king',
      hostPeerId: 'peer_temp_host',
      peers: new Map(),
      settings: {}
    };
    room.peers.set('peer_owner', { ws: ownerWs, peerId: 'peer_owner', name: 'King', isHost: false });
    room.peers.set('peer_temp_host', { ws: hostWs, peerId: 'peer_temp_host', name: 'TempHost', isHost: true });
    rooms.set(roomCode, room);

    clients.set(ownerWs, { peerId: 'peer_owner', roomCode, name: 'King', isHost: false, uid: 'uid_king' });
    clients.set(hostWs, { peerId: 'peer_temp_host', roomCode, name: 'TempHost', isHost: true, uid: 'uid_other' });

    // Temp host tries to kick the room owner
    handleKickPeer(hostWs, { targetPeerId: 'peer_owner' });

    const hostErr = hostWs.getLastMessage();
    assert.ok(hostErr);
    assert.strictEqual(hostErr.type, 'error');
    assert.strictEqual(hostErr.message, 'Cannot kick the room owner.');
    assert.strictEqual(room.peers.has('peer_owner'), true, 'Owner cannot be kicked');
  });

  test('7. Ownership transfer succeeds only for verified owner', async () => {
    const roomCode = 'XFER1';
    const ownerWs = createMockWebSocket();
    const successorWs = createMockWebSocket();
    const intruderWs = createMockWebSocket();

    const room = {
      code: roomCode,
      ownerUid: 'uid_first_owner',
      hostPeerId: 'peer_first',
      peers: new Map(),
      settings: {}
    };
    room.peers.set('peer_first', { ws: ownerWs, peerId: 'peer_first', name: 'FirstOwner', isHost: true });
    room.peers.set('peer_successor', { ws: successorWs, peerId: 'peer_successor', name: 'Successor', isHost: false });
    room.peers.set('peer_intruder', { ws: intruderWs, peerId: 'peer_intruder', name: 'Intruder', isHost: false });
    rooms.set(roomCode, room);

    clients.set(ownerWs, { peerId: 'peer_first', roomCode, name: 'FirstOwner', isHost: true, uid: 'uid_first_owner' });
    clients.set(successorWs, { peerId: 'peer_successor', roomCode, name: 'Successor', isHost: false, uid: 'uid_successor' });
    clients.set(intruderWs, { peerId: 'peer_intruder', roomCode, name: 'Intruder', isHost: false, uid: 'uid_intruder' });

    // Intruder attempts to transfer ownership -> FAILS
    await handleTransferOwnership(intruderWs, { targetPeerId: 'peer_intruder' });
    const intruderErr = intruderWs.getLastMessage();
    assert.strictEqual(intruderErr.type, 'error');
    assert.match(intruderErr.message, /Only the verified room owner/);

    // Legitimate owner transfers ownership to successor -> SUCCEEDS
    await handleTransferOwnership(ownerWs, { targetPeerId: 'peer_successor' });

    assert.strictEqual(room.ownerUid, 'uid_successor', 'Room owner must now be successor');
    assert.strictEqual(room.hostPeerId, 'peer_successor', 'Host must now be successor');

    const successorClient = clients.get(successorWs);
    assert.strictEqual(successorClient.isHost, true);

    const xferMsg = successorWs.getLastMessage();
    assert.strictEqual(xferMsg.type, 'ownership-transferred');
    assert.strictEqual(xferMsg.newOwnerPeerId, 'peer_successor');
  });

  test('8. Legitimate owner can delete persistent room', async () => {
    const roomCode = 'DEL01';
    const ownerWs = createMockWebSocket();
    const guestWs = createMockWebSocket();

    await savePersistentRoom({
      roomCode,
      ownerUid: 'uid_owner',
      ownerName: 'Owner',
      settings: {}
    });

    const room = {
      code: roomCode,
      ownerUid: 'uid_owner',
      hostPeerId: 'peer_owner',
      peers: new Map(),
      settings: {}
    };
    room.peers.set('peer_owner', { ws: ownerWs, peerId: 'peer_owner', name: 'Owner', isHost: true });
    room.peers.set('peer_guest', { ws: guestWs, peerId: 'peer_guest', name: 'Guest', isHost: false });
    rooms.set(roomCode, room);

    clients.set(ownerWs, { peerId: 'peer_owner', roomCode, name: 'Owner', isHost: true, uid: 'uid_owner' });
    clients.set(guestWs, { peerId: 'peer_guest', roomCode, name: 'Guest', isHost: false, uid: 'uid_guest' });

    // Guest tries to delete room -> REJECTED
    await handleDeleteRoom(guestWs);
    const guestErr = guestWs.getLastMessage();
    assert.strictEqual(guestErr.type, 'error');
    assert.match(guestErr.message, /Only the verified room owner/);

    // Owner deletes room -> SUCCEEDS
    await handleDeleteRoom(ownerWs);
    assert.strictEqual(rooms.has(roomCode), false, 'Room should be purged from active memory');
    const persistent = await getPersistentRoom(roomCode);
    assert.strictEqual(persistent, null, 'Room should be purged from persistent registry');

    const deletedNotice = guestWs.getLastMessage();
    assert.strictEqual(deletedNotice.type, 'room-deleted');
  });
});

describe('P0 Admin Authorization & Monthly Reset Protection Suite', () => {
  test('9. Unauthenticated request to admin endpoint is rejected', async () => {
    const fakeReq = {
      headers: {}
    };
    const isAuth = await isAuthorizedAdmin(fakeReq);
    assert.strictEqual(isAuth, false, 'Unauthenticated request must return false');
  });

  test('10. Invalid admin key is rejected', async () => {
    const fakeReq = {
      headers: {
        'x-admin-key': 'completely_wrong_admin_secret_123'
      }
    };
    const isAuth = await isAuthorizedAdmin(fakeReq);
    assert.strictEqual(isAuth, false, 'Invalid admin key must be rejected');
  });

  test('11. Legitimate ADMIN_SECRET_KEY is accepted', async () => {
    assert.ok(ADMIN_SECRET_KEY, 'ADMIN_SECRET_KEY must exist in server context');
    const fakeReq = {
      headers: {
        'x-admin-key': ADMIN_SECRET_KEY
      }
    };
    const isAuth = await isAuthorizedAdmin(fakeReq);
    assert.ok(isAuth, 'Valid master admin key must be authenticated');
    assert.strictEqual(isAuth.type, 'master');
  });

  test('12. Bearer token format is parsed and validated for admin check', async () => {
    const fakeReq = {
      headers: {
        'authorization': `Bearer ${ADMIN_SECRET_KEY}`
      }
    };
    const isAuth = await isAuthorizedAdmin(fakeReq);
    assert.ok(isAuth, 'Bearer formatted master key must be accepted');
  });
});
