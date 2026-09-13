const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

describe('Payment Verification Security & Logic Suite', () => {
  let testUsedPayments = new Set();

  function testRecordUsedPayment(paymentId) {
    testUsedPayments.add(paymentId);
  }

  function testIsPaymentUsed(paymentId) {
    return testUsedPayments.has(paymentId);
  }

  const EXPECTED_AMOUNTS = {
    WEEKLY: 2900,
    MONTHLY: 8900,
    LIFETIME: 24900
  };

  function validatePaymentVerification({ paymentId, planTier, paymentData }) {
    if (!paymentId || !paymentId.startsWith('pay_')) {
      return { valid: false, status: 400, error: 'Invalid payment ID format' };
    }

    if (testIsPaymentUsed(paymentId)) {
      return { valid: false, status: 400, error: 'This payment ID has already been redeemed for VIP membership.' };
    }

    if (!EXPECTED_AMOUNTS[planTier]) {
      return { valid: false, status: 400, error: 'Invalid or unknown VIP tier' };
    }

    if (!paymentData) {
      return { valid: false, status: 400, error: 'Payment lookup failed on Razorpay' };
    }

    if (paymentData.status !== 'captured') {
      return { valid: false, status: 400, error: `Payment not captured. Status: ${paymentData.status}` };
    }

    const expectedAmount = EXPECTED_AMOUNTS[planTier];
    if (paymentData.amount !== expectedAmount) {
      return {
        valid: false,
        status: 400,
        error: `Payment amount mismatch: expected ₹${expectedAmount / 100}, got ₹${paymentData.amount / 100}`
      };
    }

    testRecordUsedPayment(paymentId);
    return {
      valid: true,
      status: 200,
      paymentId,
      planTier,
      amountPaid: paymentData.amount / 100,
      currency: paymentData.currency || 'INR'
    };
  }

  beforeEach(() => {
    testUsedPayments.clear();
  });

  test('Rejects invalid payment ID prefix or format', () => {
    const invalidIds = ['', '12345', 'invalid_id', 'rzp_test_123', null, undefined];
    for (const badId of invalidIds) {
      const res = validatePaymentVerification({
        paymentId: badId,
        planTier: 'WEEKLY',
        paymentData: { status: 'captured', amount: 2900 }
      });
      assert.equal(res.valid, false);
      assert.equal(res.status, 400);
      assert.equal(res.error, 'Invalid payment ID format');
    }
  });

  test('Validates legitimate Razorpay payment for each VIP tier', () => {
    const tiers = [
      { tier: 'WEEKLY', amount: 2900 },
      { tier: 'MONTHLY', amount: 8900 },
      { tier: 'LIFETIME', amount: 24900 }
    ];

    for (const { tier, amount } of tiers) {
      const pId = `pay_test_${tier}_${Date.now()}`;
      const res = validatePaymentVerification({
        paymentId: pId,
        planTier: tier,
        paymentData: { status: 'captured', amount, currency: 'INR' }
      });
      assert.equal(res.valid, true);
      assert.equal(res.status, 200);
      assert.equal(res.amountPaid, amount / 100);
    }
  });

  test('Prevents replay attacks by rejecting already used payment IDs', () => {
    const paymentId = 'pay_replay_test_999';
    const firstAttempt = validatePaymentVerification({
      paymentId,
      planTier: 'WEEKLY',
      paymentData: { status: 'captured', amount: 2900 }
    });
    assert.equal(firstAttempt.valid, true);
    assert.equal(testIsPaymentUsed(paymentId), true);

    // Duplicate attempt with identical payment ID
    const secondAttempt = validatePaymentVerification({
      paymentId,
      planTier: 'WEEKLY',
      paymentData: { status: 'captured', amount: 2900 }
    });
    assert.equal(secondAttempt.valid, false);
    assert.equal(secondAttempt.status, 400);
    assert.match(secondAttempt.error, /already been redeemed/i);
  });

  test('Blocks amount tampering attempts (e.g. paying ₹1 for a ₹249 Lifetime pass)', () => {
    const res = validatePaymentVerification({
      paymentId: 'pay_tampered_123',
      planTier: 'LIFETIME',
      paymentData: { status: 'captured', amount: 100 } // 100 paise = ₹1 instead of ₹249
    });
    assert.equal(res.valid, false);
    assert.equal(res.status, 400);
    assert.match(res.error, /amount mismatch/i);
  });

  test('Rejects uncaptured or failed Razorpay payments', () => {
    const uncapturedStatuses = ['created', 'authorized', 'failed', 'refunded'];
    for (const status of uncapturedStatuses) {
      const res = validatePaymentVerification({
        paymentId: `pay_status_${status}`,
        planTier: 'WEEKLY',
        paymentData: { status, amount: 2900 }
      });
      assert.equal(res.valid, false);
      assert.equal(res.status, 400);
      assert.match(res.error, /Payment not captured/i);
    }
  });
});
