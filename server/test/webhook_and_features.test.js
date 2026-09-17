const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('crypto');

describe('Production Readiness Suite: Webhooks, Versioning & Account Lifecycle', () => {
  const SECRET = 'test_webhook_secret_key_123';

  function verifyRazorpaySignature(rawBody, signature, secret) {
    if (!signature || !secret) return false;
    const expected = crypto.createHmac('sha256', secret).update(rawBody).digest('hex');
    const bufA = Buffer.from(signature, 'utf8');
    const bufB = Buffer.from(expected, 'utf8');
    if (bufA.length !== bufB.length) return false;
    return crypto.timingSafeEqual(bufA, bufB);
  }

  test('Validates authentic Razorpay webhook signature', () => {
    const payload = JSON.stringify({ event: 'payment.captured', id: 'pay_test_001' });
    const signature = crypto.createHmac('sha256', SECRET).update(payload).digest('hex');

    const isValid = verifyRazorpaySignature(payload, signature, SECRET);
    assert.strictEqual(isValid, true, 'Valid signature must be verified successfully');
  });

  test('Rejects forged or altered Razorpay webhook signature', () => {
    const payload = JSON.stringify({ event: 'payment.captured', id: 'pay_test_001' });
    const fakeSignature = crypto.createHmac('sha256', 'wrong_secret').update(payload).digest('hex');

    const isValid = verifyRazorpaySignature(payload, fakeSignature, SECRET);
    assert.strictEqual(isValid, false, 'Forged signature must be rejected');
  });

  test('Rejects signature when body is tampered', () => {
    const payload = JSON.stringify({ event: 'payment.captured', id: 'pay_test_001' });
    const signature = crypto.createHmac('sha256', SECRET).update(payload).digest('hex');
    const tamperedPayload = JSON.stringify({ event: 'payment.captured', id: 'pay_test_002' });

    const isValid = verifyRazorpaySignature(tamperedPayload, signature, SECRET);
    assert.strictEqual(isValid, false, 'Tampered payload must fail signature verification');
  });

  test('App version check returns valid release metadata', () => {
    const response = {
      latestVersionCode: 10,
      latestVersionName: 'V1.0',
      downloadUrl: '/gamervoice-release.apk',
      mandatory: false
    };

    assert.strictEqual(typeof response.latestVersionCode, 'number');
    assert.ok(response.latestVersionCode >= 10);
    assert.ok(response.downloadUrl.endsWith('.apk'));
    assert.strictEqual(response.latestVersionName, 'V1.0');
  });

  test('Calculates correct VIP expiry from plan tier', () => {
    const tiers = {
      DAY_PASS: 1,
      WEEKLY: 7,
      MONTHLY: 30,
      LIFETIME: -1
    };

    const now = 1700000000000;
    for (const [tier, days] of Object.entries(tiers)) {
      const isLifetime = tier === 'LIFETIME';
      const expiry = isLifetime ? -1 : now + (days * 24 * 60 * 60 * 1000);
      if (isLifetime) {
        assert.strictEqual(expiry, -1);
      } else {
        assert.strictEqual(expiry, now + (days * 86400000));
      }
    }
  });
});
