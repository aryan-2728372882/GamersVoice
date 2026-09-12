const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const WebSocket = require('c:/Users/prabh/AndroidStudioProjects/GamersVoice/server/node_modules/ws');

const ARTIFACT_DIR = 'C:\\Users\\prabh\\.gemini\\antigravity\\brain\\6db7aeb1-e28e-4455-bd2a-a9255d1f4344\\scratch';
const ADMIN_KEY = 'gv-admin-master-2026';

function httpRequest(url, options = {}, postData = null) {
  return new Promise((resolve, reject) => {
    const req = http.request(url, options, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => resolve({ statusCode: res.statusCode, headers: res.headers, body: data }));
    });
    req.on('error', reject);
    if (postData) req.write(typeof postData === 'string' ? postData : JSON.stringify(postData));
    req.end();
  });
}

const results = [];
function record(testName, passed, details = '') {
  results.push({ testName, passed, details });
  const symbol = passed ? '✅ PASS' : '❌ FAIL';
  console.log(`${symbol} ${testName} ${details ? `(${details})` : ''}`);
}

async function runAdminTests() {
  console.log('\n======================================================');
  console.log('  GAMERVOICE ADMIN COMMAND CENTER AUTOMATED SUITE');
  console.log('======================================================\n');

  // 1. GET /admin
  try {
    const res = await httpRequest('http://localhost:3000/admin');
    const hasAdminUi = res.body.includes('GAMERVOICE ADMIN') && res.body.includes('authLockOverlay');
    record('GET /admin Route Delivery', res.statusCode === 200 && hasAdminUi, `Status: ${res.statusCode}, Size: ${res.body.length}b`);
  } catch (e) {
    record('GET /admin Route Delivery', false, e.message);
  }

  // 2. Static Assets
  try {
    const resCss = await httpRequest('http://localhost:3000/admin.css');
    const resJs = await httpRequest('http://localhost:3000/admin.js');
    record('Admin Static Assets Delivery', resCss.statusCode === 200 && resJs.statusCode === 200, `CSS: ${resCss.body.length}b, JS: ${resJs.body.length}b`);
  } catch (e) {
    record('Admin Static Assets Delivery', false, e.message);
  }

  // 3. Auth with Invalid Passcode (Should be rejected 401)
  try {
    const res = await httpRequest('http://localhost:3000/api/admin/auth', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' }
    }, { key: 'wrong-passcode' });
    const json = JSON.parse(res.body);
    record('Auth Rejection on Bad Key', res.statusCode === 401 && json.authenticated === false, `Status: ${res.statusCode}`);
  } catch (e) {
    record('Auth Rejection on Bad Key', false, e.message);
  }

  // 4. Auth with Valid Master Key (Should succeed 200)
  let adminToken = '';
  try {
    const res = await httpRequest('http://localhost:3000/api/admin/auth', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' }
    }, { key: ADMIN_KEY });
    const json = JSON.parse(res.body);
    adminToken = json.token;
    record('Auth Success on Master Key', res.statusCode === 200 && json.authenticated === true && !!adminToken, `Token generated`);
  } catch (e) {
    record('Auth Success on Master Key', false, e.message);
  }

  // 5. Server Stats without Authorization (Should be 401)
  try {
    const res = await httpRequest('http://localhost:3000/api/admin/server-stats');
    record('Server Stats Protected', res.statusCode === 401, `Status: ${res.statusCode}`);
  } catch (e) {
    record('Server Stats Protected', false, e.message);
  }

  // 6. Server Stats with Authorization Header
  try {
    const res = await httpRequest('http://localhost:3000/api/admin/server-stats', {
      headers: { 'x-admin-key': ADMIN_KEY }
    });
    const json = JSON.parse(res.body);
    record('Server Stats Authorized Query', res.statusCode === 200 && json.uptimeSeconds >= 0 && typeof json.totalRooms === 'number', `Uptime: ${json.uptimeSeconds}s, Heap: ${json.memoryUsage?.heapUsedMb}MB, Rooms: ${json.totalRooms}`);
  } catch (e) {
    record('Server Stats Authorized Query', false, e.message);
  }

  // 7. Live Room Lifecycle & Admin Force Close Test
  try {
    const ws = new WebSocket('ws://localhost:3000');
    await new Promise(r => ws.on('open', r));

    const roomData = await new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error('Room create timeout')), 3000);
      ws.on('message', raw => {
        const msg = JSON.parse(raw);
        if (msg.type === 'room-created') {
          clearTimeout(t);
          resolve(msg);
        }
      });
      ws.send(JSON.stringify({ type: 'create-room', name: 'MockPlayer' }));
    });

    // Check stats now includes room
    const statsRes = await httpRequest('http://localhost:3000/api/admin/server-stats', {
      headers: { 'x-admin-key': ADMIN_KEY }
    });
    const stats = JSON.parse(statsRes.body);
    const roomExistsInStats = stats.activeRooms.some(r => r.roomCode === roomData.roomCode);
    record('Real-time Active Room in Telemetry', roomExistsInStats, `Room: ${roomData.roomCode}`);

    // Admin force-closes room
    const closeRes = await httpRequest('http://localhost:3000/api/admin/close-room', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-admin-key': ADMIN_KEY
      }
    }, { roomCode: roomData.roomCode });
    const closeJson = JSON.parse(closeRes.body);
    record('Admin Force Close Room API', closeRes.statusCode === 200 && closeJson.success === true, `Closed ${roomData.roomCode}`);

    ws.close();
  } catch (e) {
    record('Live Room Admin Control', false, e.message);
  }

  // 8. Headless Chrome Browser CDP End-to-End Test
  console.log('\n--- Launching Headless Chrome Browser E2E Test ---');
  const chrome = spawn('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', [
    '--headless=new',
    '--remote-debugging-port=9230',
    '--window-size=1400,950',
    '--disable-gpu',
    'http://localhost:3000/admin'
  ]);

  await new Promise(r => setTimeout(r, 2500));

  const listRes = await new Promise((res, rej) => {
    http.get('http://localhost:9230/json', r => {
      let d = ''; r.on('data', c => d += c); r.on('end', () => res(JSON.parse(d)));
    }).on('error', rej);
  });

  const page = listRes.find(p => p.type === 'page');
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise(r => ws.on('open', r));

  let reqId = 1;
  const sendCdp = (method, params = {}) => new Promise(resolve => {
    const id = reqId++;
    const handler = (data) => {
      const parsed = JSON.parse(data);
      if (parsed.id === id) {
        ws.off('message', handler);
        resolve(parsed.result);
      }
    };
    ws.on('message', handler);
    ws.send(JSON.stringify({ id, method, params }));
  });

  await sendCdp('Runtime.enable');
  await sendCdp('Page.enable');
  await new Promise(r => setTimeout(r, 1000));

  // Check Lock Screen is visible
  const lockVisible = await sendCdp('Runtime.evaluate', {
    expression: `
      (() => {
        const overlay = document.getElementById('authLockOverlay');
        return overlay && getComputedStyle(overlay).display !== 'none';
      })()
    `,
    returnByValue: true
  });
  record('Security Gate Lock Screen Active on Arrival', lockVisible.result.value, 'Protected by PIN overlay');

  // Submit master passcode
  await sendCdp('Runtime.evaluate', {
    expression: `
      document.getElementById('adminPasscodeInput').value = '${ADMIN_KEY}';
      submitAdminPasscode();
    `
  });
  await new Promise(r => setTimeout(r, 1500));

  // Verify dashboard unlocked
  const unlocked = await sendCdp('Runtime.evaluate', {
    expression: `
      (() => {
        const overlay = document.getElementById('authLockOverlay');
        const totalUsers = document.getElementById('statTotalUsers');
        return overlay && overlay.style.display === 'none' && !!totalUsers;
      })()
    `,
    returnByValue: true
  });
  record('Passcode Authentication & Dashboard Unlock', unlocked.result.value, 'Command Center unlocked');

  // Test Tab Switching to Provision Tab
  await sendCdp('Runtime.evaluate', {
    expression: `switchAdminTab('provision');`
  });
  await new Promise(r => setTimeout(r, 400));
  const provActive = await sendCdp('Runtime.evaluate', {
    expression: `document.getElementById('tab-provision')?.classList.contains('active');`,
    returnByValue: true
  });
  record('Tab Navigation to Provision Panel', provActive.result.value, 'Tab switched');

  // Switch back to Users Tab
  await sendCdp('Runtime.evaluate', {
    expression: `switchAdminTab('users');`
  });
  await new Promise(r => setTimeout(r, 400));

  // Capture High-Resolution Verification Screenshot
  const snap = await sendCdp('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(ARTIFACT_DIR, 'admin_unlocked.png'), Buffer.from(snap.data, 'base64'));
  record('Admin Visual Verification Snapshot', true, 'Saved admin_unlocked.png');

  ws.close();
  chrome.kill();

  console.log('\n======================================================');
  console.log('  ADMIN TEST SUITE SUMMARY');
  console.log('======================================================');
  const passedCount = results.filter(r => r.passed).length;
  const totalCount = results.length;
  console.log(`TOTAL TESTS: ${totalCount}`);
  console.log(`PASSED:      ${passedCount}`);
  console.log(`FAILED:      ${totalCount - passedCount}`);
  console.log(`SUCCESS:     ${Math.round((passedCount / totalCount) * 100)}%\n`);

  if (passedCount === totalCount) {
    console.log('🎉 ALL ADMIN COMMAND CENTER SECURITY, TELEMETRY & CONTROLS PASSED 100%!');
  } else {
    process.exit(1);
  }
}

runAdminTests().catch(err => {
  console.error('Admin Test Suite Error:', err);
  process.exit(1);
});
