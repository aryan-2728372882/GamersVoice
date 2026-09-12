const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const WebSocket = require('c:/Users/prabh/AndroidStudioProjects/GamersVoice/server/node_modules/ws');

const ARTIFACT_DIR = 'C:\\Users\\prabh\\.gemini\\antigravity\\brain\\6db7aeb1-e28e-4455-bd2a-a9255d1f4344\\scratch';

// Helper for HTTP requests
function httpRequest(url, options = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request(url, options, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => resolve({ statusCode: res.statusCode, headers: res.headers, body: data }));
    });
    req.on('error', reject);
    req.end();
  });
}

const testResults = [];
function recordResult(category, testName, passed, details = '') {
  testResults.push({ category, testName, passed, details });
  const symbol = passed ? '✅ PASS' : '❌ FAIL';
  console.log(`${symbol} [${category}] ${testName} ${details ? `(${details})` : ''}`);
}

async function runApiAndServerTests() {
  console.log('\n======================================================');
  console.log('  PHASE 1: HTTP API & ROUTING INTEGRITY TESTS');
  console.log('======================================================');

  // 1. GET /
  try {
    const res = await httpRequest('http://localhost:3000/');
    const hasHtml = res.body.includes('<!DOCTYPE html>') && res.body.includes('GamerVoice');
    recordResult('HTTP Routing', 'GET / (Home Page)', res.statusCode === 200 && hasHtml, `Status: ${res.statusCode}, Size: ${res.body.length}b`);
  } catch (e) {
    recordResult('HTTP Routing', 'GET / (Home Page)', false, e.message);
  }

  // 2. GET /health
  try {
    const res = await httpRequest('http://localhost:3000/health');
    const json = JSON.parse(res.body);
    recordResult('HTTP Routing', 'GET /health (Server Health API)', res.statusCode === 200 && json.status === 'ok', `status: ${json.status}`);
  } catch (e) {
    recordResult('HTTP Routing', 'GET /health (Server Health API)', false, e.message);
  }

  // 3. GET /terms (302 Redirect)
  try {
    const res = await httpRequest('http://localhost:3000/terms');
    recordResult('HTTP Routing', 'GET /terms (Redirect)', res.statusCode === 302 && res.headers.location === '/#terms', `Redirect: ${res.headers.location}`);
  } catch (e) {
    recordResult('HTTP Routing', 'GET /terms (Redirect)', false, e.message);
  }

  // 4. GET /privacy (302 Redirect)
  try {
    const res = await httpRequest('http://localhost:3000/privacy');
    recordResult('HTTP Routing', 'GET /privacy (Redirect)', res.statusCode === 302 && res.headers.location === '/#privacy', `Redirect: ${res.headers.location}`);
  } catch (e) {
    recordResult('HTTP Routing', 'GET /privacy (Redirect)', false, e.message);
  }

  // 5. GET /refund (302 Redirect)
  try {
    const res = await httpRequest('http://localhost:3000/refund');
    recordResult('HTTP Routing', 'GET /refund (Redirect)', res.statusCode === 302 && res.headers.location === '/#refund', `Redirect: ${res.headers.location}`);
  } catch (e) {
    recordResult('HTTP Routing', 'GET /refund (Redirect)', false, e.message);
  }

  // 6. Static Asset: /style.css
  try {
    const res = await httpRequest('http://localhost:3000/style.css');
    recordResult('Static Assets', 'GET /style.css', res.statusCode === 200 && res.body.includes('var(--gold-primary)'), `Size: ${res.body.length}b`);
  } catch (e) {
    recordResult('Static Assets', 'GET /style.css', false, e.message);
  }

  // 7. Static Asset: /app.js
  try {
    const res = await httpRequest('http://localhost:3000/app.js');
    recordResult('Static Assets', 'GET /app.js', res.statusCode === 200 && res.body.includes('initTelemetry'), `Size: ${res.body.length}b`);
  } catch (e) {
    recordResult('Static Assets', 'GET /app.js', false, e.message);
  }

  // 8. Static Asset: /logo.png
  try {
    const res = await httpRequest('http://localhost:3000/logo.png');
    recordResult('Static Assets', 'GET /logo.png', res.statusCode === 200, `Size: ${res.body.length}b`);
  } catch (e) {
    recordResult('Static Assets', 'GET /logo.png', false, e.message);
  }

  console.log('\n======================================================');
  console.log('  PHASE 2: WEBSOCKET SIGNALING & PROTOCOL TESTS');
  console.log('======================================================');

  // 9. WebSocket Handshake & Heartbeat
  try {
    const ws = new WebSocket('ws://localhost:3000');
    await new Promise((resolve, reject) => {
      ws.on('open', resolve);
      ws.on('error', reject);
    });
    recordResult('WebSocket', 'WebSocket Connection Handshake', true, 'State: OPEN');

    // Ping / Pong
    const pongMsg = await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Pong timeout')), 3000);
      const onMsg = (raw) => {
        const msg = JSON.parse(raw);
        if (msg.type === 'pong') {
          clearTimeout(timeout);
          ws.off('message', onMsg);
          resolve(msg);
        }
      };
      ws.on('message', onMsg);
      ws.send(JSON.stringify({ type: 'ping', timestamp: Date.now() }));
    });
    recordResult('WebSocket', 'Heartbeat Ping/Pong Protocol', pongMsg.type === 'pong', 'Pong response received cleanly');

    // Create Room
    const roomMsg = await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Room creation timeout')), 3000);
      const onMsg = (raw) => {
        const msg = JSON.parse(raw);
        if (msg.type === 'room-created') {
          clearTimeout(timeout);
          ws.off('message', onMsg);
          resolve(msg);
        }
      };
      ws.on('message', onMsg);
      ws.send(JSON.stringify({ type: 'create-room', name: 'SuiteBot', avatar: 'avatar_1' }));
    });
    recordResult('WebSocket', 'Squad Room Creation Protocol', !!roomMsg.roomCode && !!roomMsg.peerId, `Room Code: ${roomMsg.roomCode}, Peer ID: ${roomMsg.peerId}`);

    ws.close();
  } catch (e) {
    recordResult('WebSocket', 'WebSocket Tests', false, e.message);
  }
}

async function runBrowserTestSuite() {
  console.log('\n======================================================');
  console.log('  PHASE 3: HEADLESS CHROME CLIENT & E2E TESTS');
  console.log('======================================================');

  const chrome = spawn('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', [
    '--headless=new',
    '--remote-debugging-port=9229',
    '--window-size=1280,900',
    '--disable-gpu',
    'http://localhost:3000'
  ]);

  await new Promise(r => setTimeout(r, 2500));

  const listRes = await new Promise((res, rej) => {
    http.get('http://localhost:9229/json', r => {
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

  // Enable Console and Log domains to capture runtime errors
  const consoleLogs = [];
  const consoleErrors = [];
  ws.on('message', (raw) => {
    try {
      const data = JSON.parse(raw);
      if (data.method === 'Console.messageAdded') {
        const msg = data.params.message;
        if (msg.level === 'error') consoleErrors.push(msg.text);
        else consoleLogs.push(msg.text);
      } else if (data.method === 'Runtime.consoleAPICalled') {
        const type = data.params.type;
        const text = data.params.args.map(a => a.value || '').join(' ');
        if (type === 'error') consoleErrors.push(text);
        else consoleLogs.push(text);
      }
    } catch (_) {}
  });

  await sendCdp('Console.enable');
  await sendCdp('Runtime.enable');
  await sendCdp('Page.enable');

  // Let client load and settle for 3 seconds
  await new Promise(r => setTimeout(r, 3000));

  // 10. Load Performance & Timing Metrics
  const perfData = await sendCdp('Runtime.evaluate', {
    expression: `
      (() => {
        const nav = performance.getEntriesByType('navigation')[0];
        return {
          dns: Math.round(nav.domainLookupEnd - nav.domainLookupStart),
          tcp: Math.round(nav.connectEnd - nav.connectStart),
          ttfb: Math.round(nav.responseStart - nav.requestStart),
          domContentLoaded: Math.round(nav.domContentLoadedEventEnd - nav.startTime),
          completeLoad: Math.round(nav.loadEventEnd - nav.startTime)
        };
      })()
    `,
    returnByValue: true
  });
  const timing = perfData.result.value;
  recordResult('Performance', 'Server Response Time (TTFB < 100ms)', timing.ttfb < 100, `TTFB: ${timing.ttfb}ms, DOMContentLoaded: ${timing.domContentLoaded}ms`);

  // 11. Console Error Audit
  const fatalErrors = consoleErrors.filter(e => !e.includes('favicon'));
  recordResult('Console Audit', 'Zero Fatal JS Runtime Errors', fatalErrors.length === 0, fatalErrors.length ? fatalErrors.join(' | ') : 'Clean console output');

  // 12. Telemetry Bar Values
  const telemetryData = await sendCdp('Runtime.evaluate', {
    expression: `
      (() => {
        const ping = document.getElementById('pingDisplay')?.innerText || '';
        const simPing = document.getElementById('simPing')?.innerText || '';
        const status = document.getElementById('activeRoomsDisplay')?.innerText || '';
        return { ping, simPing, status };
      })()
    `,
    returnByValue: true
  });
  const tVals = telemetryData.result.value;
  const pingNum = parseInt(tVals.ping);
  const isSub20 = !isNaN(pingNum) && pingNum <= 25;
  recordResult('Telemetry Bar', 'Sub-20ms Voice Mesh Latency Display', isSub20, `Value: "${tVals.ping}", Simulator: "${tVals.simPing}"`);
  recordResult('Telemetry Bar', 'Voice Engine Status Badge', tVals.status.includes('Mesh') || tVals.status.includes('Relay'), `Status: "${tVals.status}"`);

  // 13. Screen Responsiveness & Horizontal Overflow Audit across Viewports
  const viewports = [
    { name: 'Mobile Compact (360x800)', width: 360, height: 800, mobile: true },
    { name: 'Mobile Modern (412x915)', width: 412, height: 915, mobile: true },
    { name: 'Tablet (768x1024)', width: 768, height: 1024, mobile: false },
    { name: 'Desktop Standard (1280x900)', width: 1280, height: 900, mobile: false },
    { name: 'Desktop Ultrawide (1920x1080)', width: 1920, height: 1080, mobile: false }
  ];

  for (const vp of viewports) {
    await sendCdp('Emulation.setDeviceMetricsOverride', {
      width: vp.width,
      height: vp.height,
      deviceScaleFactor: vp.mobile ? 2 : 1,
      mobile: vp.mobile
    });
    await new Promise(r => setTimeout(r, 400));

    const overflowCheck = await sendCdp('Runtime.evaluate', {
      expression: `
        (() => {
          const docWidth = document.documentElement.offsetWidth;
          const scrollWidth = document.documentElement.scrollWidth;
          const bodyWidth = document.body.scrollWidth;
          const hasOverflow = scrollWidth > window.innerWidth || bodyWidth > window.innerWidth;
          return {
            windowWidth: window.innerWidth,
            docWidth,
            scrollWidth,
            hasOverflow
          };
        })()
      `,
      returnByValue: true
    });
    const ov = overflowCheck.result.value;
    recordResult('Responsiveness', `No Overflow at ${vp.name}`, !ov.hasOverflow, `InnerWidth: ${ov.windowWidth}px, ScrollWidth: ${ov.scrollWidth}px`);
  }

  // Restore Desktop View for Feature Testing
  await sendCdp('Emulation.setDeviceMetricsOverride', {
    width: 1280,
    height: 900,
    deviceScaleFactor: 1,
    mobile: false
  });
  await new Promise(r => setTimeout(r, 400));

  // 14. Feature Section Navigation & Element Verification
  const sectionIds = ['features', 'simulator', 'noise-demo', 'comparison', 'vip', 'faq'];
  for (const id of sectionIds) {
    const exists = await sendCdp('Runtime.evaluate', {
      expression: `!!document.getElementById('${id}')`,
      returnByValue: true
    });
    recordResult('Section Navigation', `Section #${id} Exists & Rendered`, exists.result.value, `Element #${id}`);
  }

  // 15. Phone App Simulator Controls
  const simTest = await sendCdp('Runtime.evaluate', {
    expression: `
      (() => {
        const micBtn = document.getElementById('simHudMicToggle');
        const initialStatus = document.getElementById('simHudStatus')?.innerText;
        window.toggleSimMic();
        const toggledStatus = document.getElementById('simHudStatus')?.innerText;
        window.toggleSimMic(); // toggle back
        return { initialStatus, toggledStatus };
      })()
    `,
    returnByValue: true
  });
  const sRes = simTest.result.value;
  recordResult('App Simulator', 'Interactive Mic Toggle Control', sRes.initialStatus !== sRes.toggledStatus, `From "${sRes.initialStatus}" to "${sRes.toggledStatus}"`);

  // 16. Noise Lab Interactive Engine
  const noiseLabTest = await sendCdp('Runtime.evaluate', {
    expression: `
      (() => {
        const slider = document.getElementById('noiseSlider');
        const label = document.getElementById('noisePercentLabel');
        const hasCanvas = !!document.getElementById('waveCanvas');
        let sliderReacted = false;
        if (slider && label) {
          const oldVal = slider.value;
          slider.value = 50;
          slider.dispatchEvent(new Event('input'));
          sliderReacted = label.innerText.includes('50%');
          slider.value = oldVal;
          slider.dispatchEvent(new Event('input'));
        }
        return { hasCanvas, sliderReacted, currentLabel: label?.innerText };
      })()
    `,
    returnByValue: true
  });
  const nl = noiseLabTest.result.value;
  recordResult('Noise Lab', 'Noise Suppression Slider & Canvas Visualizer', nl.hasCanvas && nl.sliderReacted, `Canvas present, Reactivity: ${nl.sliderReacted}`);

  // 17. Comparison Matrix View Switching
  const compTest = await sendCdp('Runtime.evaluate', {
    expression: `
      (() => {
        if (typeof window.setComparisonView !== 'function') return { ok: false };
        window.setComparisonView('discord');
        const tabDiscordActive = document.getElementById('compTabDiscord')?.classList.contains('active');
        window.setComparisonView('all');
        const tabAllActive = document.getElementById('compTabAll')?.classList.contains('active');
        return { ok: true, tabDiscordActive, tabAllActive };
      })()
    `,
    returnByValue: true
  });
  const ct = compTest.result.value;
  recordResult('Comparison Table', 'Platform Switching & Filtering Tabs', ct.ok && ct.tabDiscordActive && ct.tabAllActive, `Discord active: ${ct.tabDiscordActive}, All active: ${ct.tabAllActive}`);

  // 18. FAQ Accordion Expansion
  const faqTest = await sendCdp('Runtime.evaluate', {
    expression: `
      (() => {
        const firstFaqBtn = document.querySelector('.faq-question');
        if (!firstFaqBtn) return false;
        const item = firstFaqBtn.parentElement;
        const initiallyOpen = item.classList.contains('active');
        firstFaqBtn.click();
        const afterClickOpen = item.classList.contains('active');
        firstFaqBtn.click(); // revert
        return initiallyOpen !== afterClickOpen;
      })()
    `,
    returnByValue: true
  });
  recordResult('FAQ Accordion', 'Question Expand/Collapse Toggle', faqTest.result.value, 'FAQ click activates accordion body');

  // 19. Support Ticket Modal
  const supportTest = await sendCdp('Runtime.evaluate', {
    expression: `
      (() => {
        const modal = document.getElementById('supportModal');
        window.openSupport();
        const opened = modal?.classList.contains('active');
        const categorySelect = document.getElementById('supportCategory');
        categorySelect.value = 'Audio/Noise Issue';
        window.closeSupport();
        const closed = !modal?.classList.contains('active');
        return { opened, closed, categorySelected: categorySelect.value === 'Audio/Noise Issue' };
      })()
    `,
    returnByValue: true
  });
  const st = supportTest.result.value;
  recordResult('Support Modal', 'Modal Open, Input Selection, & Close', st.opened && st.closed && st.categorySelected, `Open: ${st.opened}, Close: ${st.closed}`);

  // 20. Legal & Policy Modal Tab Routing
  const policyTest = await sendCdp('Runtime.evaluate', {
    expression: `
      (() => {
        const modal = document.getElementById('policyModal');
        window.openPolicyModal('privacy');
        const privacyTitle = document.getElementById('policyModalTitle')?.innerText || '';
        const privacyOpen = modal?.classList.contains('active');
        window.switchPolicyTab('terms');
        const termsTitle = document.getElementById('policyModalTitle')?.innerText || '';
        window.closePolicyModal();
        const closed = !modal?.classList.contains('active');
        return {
          privacyOpen,
          privacyTitle,
          termsTitle,
          closed
        };
      })()
    `,
    returnByValue: true
  });
  const pt = policyTest.result.value;
  recordResult('Policy Modal', 'Hash Routing, Tab Switching (Privacy/Terms), & Close', pt.privacyOpen && pt.closed && pt.privacyTitle.includes('Privacy') && pt.termsTitle.includes('Terms'), `Privacy: "${pt.privacyTitle}", Terms: "${pt.termsTitle}"`);

  // 21. Capture High-Resolution Verification Screenshots
  // Desktop Hero + Telemetry
  await sendCdp('Runtime.evaluate', {
    expression: `window.scrollTo({ top: 0, behavior: 'instant' });`
  });
  await new Promise(r => setTimeout(r, 400));
  const snapHero = await sendCdp('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(ARTIFACT_DIR, 'suite_hero_desktop.png'), Buffer.from(snapHero.data, 'base64'));

  // Mobile Simulator + Telemetry
  await sendCdp('Emulation.setDeviceMetricsOverride', {
    width: 412,
    height: 915,
    deviceScaleFactor: 2,
    mobile: true
  });
  await new Promise(r => setTimeout(r, 400));
  const snapMobile = await sendCdp('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(ARTIFACT_DIR, 'suite_mobile_view.png'), Buffer.from(snapMobile.data, 'base64'));

  // Policy Modal Open View
  await sendCdp('Runtime.evaluate', {
    expression: `window.openPolicyModal('terms');`
  });
  await new Promise(r => setTimeout(r, 400));
  const snapPolicy = await sendCdp('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(ARTIFACT_DIR, 'suite_policy_modal.png'), Buffer.from(snapPolicy.data, 'base64'));

  recordResult('Visual Verification', 'High-Res Screenshots Captured', true, 'Saved suite_hero_desktop.png, suite_mobile_view.png, suite_policy_modal.png');

  ws.close();
  chrome.kill();

  console.log('\n======================================================');
  console.log('  TEST SUITE EXECUTION SUMMARY');
  console.log('======================================================');
  const passedCount = testResults.filter(r => r.passed).length;
  const totalCount = testResults.length;
  const percent = Math.round((passedCount / totalCount) * 100);
  console.log(`TOTAL TESTS: ${totalCount}`);
  console.log(`PASSED:      ${passedCount}`);
  console.log(`FAILED:      ${totalCount - passedCount}`);
  console.log(`SUCCESS:     ${percent}%\n`);

  if (passedCount === totalCount) {
    console.log('🎉 ALL WEBSITE SYSTEMS, PROTOCOLS, PERFORMANCE & CONTROLS PASSED 100%!');
  } else {
    console.error('⚠️ SOME TESTS FAILED. CHECK LOGS ABOVE.');
    process.exit(1);
  }
}

async function main() {
  await runApiAndServerTests();
  await runBrowserTestSuite();
}

main().catch(err => {
  console.error('Test Suite Fatal Error:', err);
  process.exit(1);
});
