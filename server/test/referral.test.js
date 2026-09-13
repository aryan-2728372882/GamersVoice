const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

describe('Viral Squad Referral Anti-Abuse & Reward Suite', () => {
  let mockReferrals = new Map();
  let mockRedemptions = new Set();
  let mockUsers = new Map();

  function testRedeemReferral({ authUser, referralCode }) {
    if (!authUser || !authUser.uid) {
      return { status: 401, error: 'Authentication required' };
    }
    const refereeUid = authUser.uid;

    if (!referralCode || typeof referralCode !== 'string' || referralCode.trim().length < 5) {
      return { status: 400, error: 'Valid referral code required (e.g. GV-XXXX)' };
    }

    const cleanCode = referralCode.trim().toUpperCase();

    // 1. Look up referral code
    const refData = mockReferrals.get(cleanCode);
    if (!refData) {
      return { status: 404, error: 'Invalid referral code. This code does not exist.' };
    }

    const referrerUid = refData.ownerUid;

    // 2. Prevent self-redemption
    if (referrerUid === refereeUid) {
      return { status: 400, error: 'You cannot redeem your own referral code!' };
    }

    // 3. Prevent duplicate redemptions by referee
    if (mockRedemptions.has(refereeUid)) {
      return { status: 400, error: 'You have already redeemed a welcome referral code on this account.' };
    }

    const refereeUser = mockUsers.get(refereeUid) || {};
    if (refereeUser.hasRedeemedReferral) {
      return { status: 400, error: 'You have already redeemed a welcome referral code on this account.' };
    }

    // 4. Calculations (+3 Days VIP)
    const now = Date.now();
    const threeDaysMs = 3 * 24 * 60 * 60 * 1000;

    let refereeBaseTs = now;
    if (refereeUser.expiryTimestamp && Number(refereeUser.expiryTimestamp) > now) {
      refereeBaseTs = Number(refereeUser.expiryTimestamp);
    }
    const newRefereeExpTs = refereeBaseTs + threeDaysMs;

    const referrerUser = mockUsers.get(referrerUid) || {};
    let referrerBaseTs = now;
    if (referrerUser.expiryTimestamp && Number(referrerUser.expiryTimestamp) > now) {
      referrerBaseTs = Number(referrerUser.expiryTimestamp);
    }
    const newReferrerExpTs = referrerBaseTs + threeDaysMs;

    // 5. Commit state (atomic)
    mockUsers.set(refereeUid, {
      ...refereeUser,
      isVip: true,
      planType: 'WEEKLY',
      expiryTimestamp: newRefereeExpTs,
      hasRedeemedReferral: true,
      redeemedReferralCode: cleanCode
    });

    mockUsers.set(referrerUid, {
      ...referrerUser,
      isVip: true,
      planType: 'WEEKLY',
      expiryTimestamp: newReferrerExpTs
    });

    mockRedemptions.add(refereeUid);
    refData.redeemedCount = (refData.redeemedCount || 0) + 1;

    return {
      status: 200,
      success: true,
      refereeExp: newRefereeExpTs,
      referrerExp: newReferrerExpTs
    };
  }

  beforeEach(() => {
    mockReferrals.clear();
    mockRedemptions.clear();
    mockUsers.clear();

    // Seed test referrer with code GV-PRO7
    mockReferrals.set('GV-PRO7', {
      code: 'GV-PRO7',
      ownerUid: 'user_host_1',
      ownerEmail: 'host@gamervoice.com',
      redeemedCount: 0
    });
    mockUsers.set('user_host_1', {
      uid: 'user_host_1',
      email: 'host@gamervoice.com',
      isVip: false,
      expiryTimestamp: 0
    });
  });

  test('Rejects unauthenticated requests', () => {
    const res = testRedeemReferral({ authUser: null, referralCode: 'GV-PRO7' });
    assert.equal(res.status, 401);
    assert.match(res.error, /Authentication required/i);
  });

  test('Rejects malformed or too short referral codes', () => {
    const badCodes = ['', 'GV', '1234', null, 12345];
    for (const code of badCodes) {
      const res = testRedeemReferral({ authUser: { uid: 'referee_1' }, referralCode: code });
      assert.equal(res.status, 400);
      assert.match(res.error, /Valid referral code required/i);
    }
  });

  test('Rejects non-existent referral codes with 404', () => {
    const res = testRedeemReferral({ authUser: { uid: 'referee_1' }, referralCode: 'GV-FAKEX' });
    assert.equal(res.status, 404);
    assert.match(res.error, /does not exist/i);
  });

  test('Blocks self-referral abuse when referrerUid equals refereeUid', () => {
    const res = testRedeemReferral({ authUser: { uid: 'user_host_1' }, referralCode: 'GV-PRO7' });
    assert.equal(res.status, 400);
    assert.match(res.error, /cannot redeem your own referral code/i);
  });

  test('Successfully redeems code and grants +3 Days VIP to both referee and referrer', () => {
    const now = Date.now();
    const threeDaysMs = 3 * 24 * 60 * 60 * 1000;

    const res = testRedeemReferral({
      authUser: { uid: 'referee_new_user' },
      referralCode: 'gv-pro7' // Lowercase test (should normalize)
    });

    assert.equal(res.status, 200);
    assert.equal(res.success, true);

    const referee = mockUsers.get('referee_new_user');
    const referrer = mockUsers.get('user_host_1');

    assert.equal(referee.isVip, true);
    assert.equal(referee.hasRedeemedReferral, true);
    assert.ok(referee.expiryTimestamp >= now + threeDaysMs - 1000);

    assert.equal(referrer.isVip, true);
    assert.ok(referrer.expiryTimestamp >= now + threeDaysMs - 1000);

    const refDoc = mockReferrals.get('GV-PRO7');
    assert.equal(refDoc.redeemedCount, 1);
  });

  test('Prevents repeated redemption by the same user', () => {
    // First redemption succeeds
    const firstRes = testRedeemReferral({
      authUser: { uid: 'repeat_user' },
      referralCode: 'GV-PRO7'
    });
    assert.equal(firstRes.status, 200);

    // Second redemption attempt
    const secondRes = testRedeemReferral({
      authUser: { uid: 'repeat_user' },
      referralCode: 'GV-PRO7'
    });
    assert.equal(secondRes.status, 400);
    assert.match(secondRes.error, /already redeemed/i);
  });

  test('Correctly stacks +3 days on top of existing remaining VIP duration', () => {
    const now = Date.now();
    const existingRemainingMs = 5 * 24 * 60 * 60 * 1000; // 5 days left
    const threeDaysMs = 3 * 24 * 60 * 60 * 1000;

    mockUsers.set('referee_with_vip', {
      uid: 'referee_with_vip',
      isVip: true,
      expiryTimestamp: now + existingRemainingMs
    });

    const res = testRedeemReferral({
      authUser: { uid: 'referee_with_vip' },
      referralCode: 'GV-PRO7'
    });

    assert.equal(res.status, 200);
    const updated = mockUsers.get('referee_with_vip');
    // Expected expiry should be now + 5 days + 3 days = 8 days
    const expected = now + existingRemainingMs + threeDaysMs;
    assert.ok(Math.abs(updated.expiryTimestamp - expected) < 2000);
  });
});
