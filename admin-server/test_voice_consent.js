'use strict';

// Stage 1 tests for the AI Voice recipient-consent + safety-gate layer.
// No server, no Twilio, no Gemini required — this only proves the
// consent rules themselves are correct.
//
// Run:  node test_voice_consent.js

const fs = require('fs');
const path = require('path');
const assert = require('assert');

// Throwaway data dir so this never touches real admin data.
const TMP_DIR = path.join(__dirname, 'data-test-voice-consent');
fs.rmSync(TMP_DIR, { recursive: true, force: true });
process.env.DATA_DIR = TMP_DIR;

const consent = require('./src/voiceConsent');

let pass = 0;
let fail = 0;
function check(label, fn) {
  try {
    fn();
    pass++;
    console.log(`PASS: ${label}`);
  } catch (err) {
    fail++;
    console.log(`FAIL: ${label}  (${err.message})`);
  }
}

const NUMBER = '+91 98765 43210';
const OTHER = '+919876500000';

check('unknown number defaults to NOT_CONSENTED, not blocked', () => {
  const r = consent.getRecipient(NUMBER);
  assert.strictEqual(r.consentStatus, 'NOT_CONSENTED');
  assert.strictEqual(r.doNotCall, false);
});

check('gate blocks a never-consented number', () => {
  const result = consent.checkSafetyGate({
    phoneNumber: NUMBER, scheduleEnabled: true,
    providerConfigured: true, aiConfigured: true, rateLimitAvailable: true, actor: 'test',
  });
  assert.strictEqual(result.allowed, false);
  assert.strictEqual(result.reason, 'CONSENT_NOT_CONSENTED');
});

check('setConsent CONSENTED then gate allows it', () => {
  consent.setConsent({
    phoneNumber: NUMBER, status: 'CONSENTED',
    source: 'signup_form', purpose: 'appointment_reminders', actor: 'test',
  });
  const result = consent.checkSafetyGate({
    phoneNumber: NUMBER, scheduleEnabled: true,
    providerConfigured: true, aiConfigured: true, rateLimitAvailable: true, actor: 'test',
  });
  assert.strictEqual(result.allowed, true);
  assert.strictEqual(result.reason, null);
});

check('a differently formatted same number matches the same consent record', () => {
  const r = consent.getRecipient('+91-98765-43210');
  assert.strictEqual(r.phoneKey, '+919876543210');
  assert.strictEqual(r.consentStatus, 'CONSENTED');
});

check('revoking consent blocks the gate again', () => {
  consent.setConsent({ phoneNumber: NUMBER, status: 'REVOKED', source: 'recipient_request', actor: 'test' });
  const result = consent.checkSafetyGate({
    phoneNumber: NUMBER, scheduleEnabled: true,
    providerConfigured: true, aiConfigured: true, rateLimitAvailable: true, actor: 'test',
  });
  assert.strictEqual(result.allowed, false);
  assert.strictEqual(result.reason, 'CONSENT_REVOKED');
});

check('opt-out blocks even a freshly-consented number', () => {
  consent.setConsent({ phoneNumber: OTHER, status: 'CONSENTED', source: 'signup_form', actor: 'test' });
  consent.recordOptOut({ phoneNumber: OTHER, source: 'recipient_said_stop', actor: 'test' });
  const result = consent.checkSafetyGate({
    phoneNumber: OTHER, scheduleEnabled: true,
    providerConfigured: true, aiConfigured: true, rateLimitAvailable: true, actor: 'test',
  });
  assert.strictEqual(result.allowed, false);
  assert.strictEqual(result.reason, 'DO_NOT_CALL');
});

check('restoreRecipient clears the block but does NOT restore consent on its own', () => {
  consent.setConsent({ phoneNumber: OTHER, status: 'REVOKED', source: 'x', actor: 'test' });
  consent.restoreRecipient({ phoneNumber: OTHER, actor: 'test' });
  const r = consent.getRecipient(OTHER);
  assert.strictEqual(r.doNotCall, false);
  assert.strictEqual(r.consentStatus, 'REVOKED'); // still not CONSENTED — no silent bypass
});

check('disabled schedule blocks before consent is even checked', () => {
  consent.setConsent({ phoneNumber: NUMBER, status: 'CONSENTED', source: 'x', actor: 'test' });
  const result = consent.checkSafetyGate({
    phoneNumber: NUMBER, scheduleEnabled: false,
    providerConfigured: true, aiConfigured: true, rateLimitAvailable: true, actor: 'test',
  });
  assert.strictEqual(result.allowed, false);
  assert.strictEqual(result.reason, 'SCHEDULE_DISABLED');
});

check('provider not configured blocks even a consented, opted-in number', () => {
  const result = consent.checkSafetyGate({
    phoneNumber: NUMBER, scheduleEnabled: true,
    providerConfigured: false, aiConfigured: true, rateLimitAvailable: true, actor: 'test',
  });
  assert.strictEqual(result.allowed, false);
  assert.strictEqual(result.reason, 'PROVIDER_NOT_CONFIGURED');
});

check('rate limit exhausted blocks last, after everything else passes', () => {
  const result = consent.checkSafetyGate({
    phoneNumber: NUMBER, scheduleEnabled: true,
    providerConfigured: true, aiConfigured: true, rateLimitAvailable: false, actor: 'test',
  });
  assert.strictEqual(result.allowed, false);
  assert.strictEqual(result.reason, 'RATE_LIMIT_EXCEEDED');
});

check('number without a country code is rejected, not silently guessed', () => {
  const result = consent.setConsent({ phoneNumber: '9876543210', status: 'CONSENTED', source: 'x', actor: 'test' });
  assert.strictEqual(result.ok, false);
});

console.log(`\n${pass} passed, ${fail} failed`);
fs.rmSync(TMP_DIR, { recursive: true, force: true });
process.exit(fail > 0 ? 1 : 0);
