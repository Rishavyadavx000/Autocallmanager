'use strict';

// Recipient consent + do-not-call tracking for AI Voice Call, and the
// pre-execution safety gate that every AI Voice execution must pass
// through. This module has no dependency on any telephony/AI provider —
// it only answers one question, independent of the Android app or the
// admin UI: "is it currently OK to place an AI Voice call to this number?"
//
// AI_VOICE_COMPLIANCE_MODE is fixed at STRICT. There is no other mode
// implemented, and nothing in this file skips the consent check —
// per spec, no mode may silently bypass consent.

const { db } = require('./db');

const CONSENT_STATUSES = ['CONSENTED', 'NOT_CONSENTED', 'REVOKED', 'UNKNOWN'];
const DEFAULT_CONSENT_STATUS = 'NOT_CONSENTED';
const AI_VOICE_COMPLIANCE_MODE = 'STRICT';

const BLOCK_REASONS = {
  SCHEDULE_DISABLED: 'SCHEDULE_DISABLED',
  INVALID_NUMBER: 'INVALID_NUMBER',
  CONSENT_NOT_CONSENTED: 'CONSENT_NOT_CONSENTED',
  CONSENT_REVOKED: 'CONSENT_REVOKED',
  CONSENT_UNKNOWN: 'CONSENT_UNKNOWN',
  DO_NOT_CALL: 'DO_NOT_CALL',
  PROVIDER_NOT_CONFIGURED: 'PROVIDER_NOT_CONFIGURED',
  AI_NOT_CONFIGURED: 'AI_NOT_CONFIGURED',
  RATE_LIMIT_EXCEEDED: 'RATE_LIMIT_EXCEEDED',
};

// --- Phone normalization ----------------------------------------------
// Deliberately conservative: if we can't tell the country code, we refuse
// rather than guess — guessing would mean silently treating two different
// numbers as the same recipient, or vice versa.

function normalizePhone(input) {
  const trimmed = String(input || '').trim();
  if (!trimmed) return { ok: false, error: 'Phone number is required' };
  const hasPlus = trimmed.startsWith('+');
  const digits = trimmed.replace(/[^0-9]/g, '');
  if (!hasPlus || !digits) {
    return { ok: false, error: 'Phone number must include a country code, e.g. +91XXXXXXXXXX' };
  }
  if (digits.length < 8 || digits.length > 15) {
    return { ok: false, error: 'Phone number must have 8-15 digits after the country code' };
  }
  return { ok: true, key: `+${digits}` };
}

// --- Audit ---------------------------------------------------------------

function voiceAudit(actor, phoneKey, action, detail) {
  try {
    db.prepare(
      `INSERT INTO voice_consent_audit (ts, actor, phone_key, action, detail)
       VALUES (?, ?, ?, ?, ?)`
    ).run(Date.now(), actor || 'unknown', phoneKey || '', action, detail || '');
  } catch (_) {
    /* auditing must never break the caller */
  }
}

// --- Recipient read/write --------------------------------------------------

function rowToShape(row) {
  return {
    phoneKey: row.phone_key,
    rawPhone: row.raw_phone,
    consentStatus: row.consent_status,
    consentSource: row.consent_source,
    consentTimestamp: row.consent_timestamp,
    consentPurpose: row.consent_purpose,
    consentEvidenceId: row.consent_evidence_id,
    doNotCall: !!row.do_not_call,
    optOutTimestamp: row.opt_out_timestamp,
    optOutSource: row.opt_out_source,
    known: true,
  };
}

/** Always returns a shape, even for a number never seen before (defaults apply). */
function getRecipient(phoneNumber) {
  const norm = normalizePhone(phoneNumber);
  if (!norm.ok) return null;
  const row = db.prepare('SELECT * FROM voice_recipients WHERE phone_key = ?').get(norm.key);
  if (!row) {
    return {
      phoneKey: norm.key,
      rawPhone: phoneNumber,
      consentStatus: DEFAULT_CONSENT_STATUS,
      consentSource: null,
      consentTimestamp: null,
      consentPurpose: null,
      consentEvidenceId: null,
      doNotCall: false,
      optOutTimestamp: null,
      optOutSource: null,
      known: false,
    };
  }
  return rowToShape(row);
}

function upsertRecipient(phoneKey, rawPhone, fields) {
  const existing = db.prepare('SELECT * FROM voice_recipients WHERE phone_key = ?').get(phoneKey);
  const now = Date.now();
  if (!existing) {
    db.prepare(`
      INSERT INTO voice_recipients (
        phone_key, raw_phone, consent_status, consent_source, consent_timestamp,
        consent_purpose, consent_evidence_id, do_not_call, opt_out_timestamp, opt_out_source, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      phoneKey, rawPhone,
      fields.consentStatus ?? DEFAULT_CONSENT_STATUS,
      fields.consentSource ?? null,
      fields.consentTimestamp ?? null,
      fields.consentPurpose ?? null,
      fields.consentEvidenceId ?? null,
      fields.doNotCall ? 1 : 0,
      fields.optOutTimestamp ?? null,
      fields.optOutSource ?? null,
      now
    );
    return;
  }
  db.prepare(`
    UPDATE voice_recipients SET
      raw_phone = ?, consent_status = ?, consent_source = ?, consent_timestamp = ?,
      consent_purpose = ?, consent_evidence_id = ?, do_not_call = ?, opt_out_timestamp = ?,
      opt_out_source = ?, updated_at = ?
    WHERE phone_key = ?
  `).run(
    rawPhone,
    fields.consentStatus ?? existing.consent_status,
    fields.consentSource !== undefined ? fields.consentSource : existing.consent_source,
    fields.consentTimestamp !== undefined ? fields.consentTimestamp : existing.consent_timestamp,
    fields.consentPurpose !== undefined ? fields.consentPurpose : existing.consent_purpose,
    fields.consentEvidenceId !== undefined ? fields.consentEvidenceId : existing.consent_evidence_id,
    fields.doNotCall !== undefined ? (fields.doNotCall ? 1 : 0) : existing.do_not_call,
    fields.optOutTimestamp !== undefined ? fields.optOutTimestamp : existing.opt_out_timestamp,
    fields.optOutSource !== undefined ? fields.optOutSource : existing.opt_out_source,
    now,
    phoneKey
  );
}

/** Record consent. This is the ONLY function that can set status to CONSENTED. */
function setConsent({ phoneNumber, status, source, purpose, evidenceId, actor }) {
  const norm = normalizePhone(phoneNumber);
  if (!norm.ok) return { ok: false, status: 400, error: norm.error };
  if (!CONSENT_STATUSES.includes(status)) {
    return { ok: false, status: 400, error: `status must be one of ${CONSENT_STATUSES.join(', ')}` };
  }
  if (status === 'CONSENTED' && !String(source || '').trim()) {
    return { ok: false, status: 400, error: 'consentSource is required to record CONSENTED' };
  }

  upsertRecipient(norm.key, phoneNumber, {
    consentStatus: status,
    consentSource: source || null,
    consentTimestamp: Date.now(),
    consentPurpose: purpose || null,
    consentEvidenceId: evidenceId || null,
  });

  voiceAudit(actor, norm.key, status === 'CONSENTED' ? 'CONSENT_GRANTED' : 'CONSENT_REVOKED', `status=${status} source=${source || ''}`);
  return { ok: true, recipient: getRecipient(phoneNumber) };
}

/** Recipient opts out. Independent of consentStatus — always wins. */
function recordOptOut({ phoneNumber, source, actor }) {
  const norm = normalizePhone(phoneNumber);
  if (!norm.ok) return { ok: false, status: 400, error: norm.error };

  upsertRecipient(norm.key, phoneNumber, {
    doNotCall: true,
    optOutTimestamp: Date.now(),
    optOutSource: source || 'unspecified',
  });

  voiceAudit(actor, norm.key, 'OPT_OUT', `source=${source || 'unspecified'}`);
  return { ok: true, recipient: getRecipient(phoneNumber) };
}

/**
 * Admin correction for a mistaken block. Clears doNotCall only — it does
 * NOT set consentStatus to CONSENTED. Real consent still has to come
 * through setConsent() separately, with its own source/evidence.
 */
function restoreRecipient({ phoneNumber, actor }) {
  const norm = normalizePhone(phoneNumber);
  if (!norm.ok) return { ok: false, status: 400, error: norm.error };

  upsertRecipient(norm.key, phoneNumber, {
    doNotCall: false,
    optOutTimestamp: null,
    optOutSource: null,
  });

  voiceAudit(actor, norm.key, 'RECIPIENT_RESTORED', 'doNotCall cleared; consentStatus unchanged');
  return { ok: true, recipient: getRecipient(phoneNumber) };
}

// --- The safety gate ---------------------------------------------------
//
// Call this immediately before creating the outbound call — never earlier,
// and never from a value cached at schedule-creation time. That's what
// makes a consent change after scheduling take effect: there's nothing to
// invalidate because nothing was ever assumed still true.

function checkSafetyGate({
  phoneNumber,
  scheduleEnabled,
  providerConfigured,
  aiConfigured,
  rateLimitAvailable,
  actor,
}) {
  const norm = normalizePhone(phoneNumber);
  const auditKey = norm.ok ? norm.key : String(phoneNumber || '');

  const record = (reason) => {
    voiceAudit(actor, auditKey, reason === null ? 'CALL_ALLOWED' : 'CALL_BLOCKED', reason || '');
    return { allowed: reason === null, reason };
  };

  if (!scheduleEnabled) return record(BLOCK_REASONS.SCHEDULE_DISABLED);
  if (!norm.ok) return record(BLOCK_REASONS.INVALID_NUMBER);

  const recipient = getRecipient(norm.key);

  if (recipient.consentStatus === 'NOT_CONSENTED') return record(BLOCK_REASONS.CONSENT_NOT_CONSENTED);
  if (recipient.consentStatus === 'REVOKED') return record(BLOCK_REASONS.CONSENT_REVOKED);
  if (recipient.consentStatus === 'UNKNOWN') return record(BLOCK_REASONS.CONSENT_UNKNOWN);
  // Only remaining status is CONSENTED — fall through.

  if (recipient.doNotCall) return record(BLOCK_REASONS.DO_NOT_CALL);

  if (!providerConfigured) return record(BLOCK_REASONS.PROVIDER_NOT_CONFIGURED);
  if (!aiConfigured) return record(BLOCK_REASONS.AI_NOT_CONFIGURED);
  if (!rateLimitAvailable) return record(BLOCK_REASONS.RATE_LIMIT_EXCEEDED);

  return record(null);
}

module.exports = {
  AI_VOICE_COMPLIANCE_MODE,
  CONSENT_STATUSES,
  BLOCK_REASONS,
  normalizePhone,
  getRecipient,
  setConsent,
  recordOptOut,
  restoreRecipient,
  checkSafetyGate,
};
