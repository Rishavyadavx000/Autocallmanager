'use strict';

const crypto = require('crypto');
const { db, audit, auditEvent } = require('./db');
const permissions = require('./permissions');

const SCRYPT_KEYLEN = 64;
const SESSION_TTL_MS = (Number(process.env.SESSION_TTL_HOURS) || 12) * 60 * 60 * 1000;

// --- Passwords -------------------------------------------------------------

function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(String(password), salt, SCRYPT_KEYLEN).toString('hex');
  return `${salt}:${hash}`;
}

function verifyPassword(password, stored) {
  const parts = String(stored || '').split(':');
  if (parts.length !== 2) return false;
  const [salt, hashHex] = parts;
  const expected = Buffer.from(hashHex, 'hex');
  const actual = crypto.scryptSync(String(password), salt, SCRYPT_KEYLEN);
  if (actual.length !== expected.length) return false;
  return crypto.timingSafeEqual(actual, expected);
}

// --- Sessions ----------------------------------------------------------

function createSession(adminId) {
  const token = crypto.randomBytes(32).toString('hex');
  const now = Date.now();
  db.prepare(
    'INSERT INTO sessions (token, admin_id, created_at, expires_at) VALUES (?, ?, ?, ?)'
  ).run(token, adminId, now, now + SESSION_TTL_MS);
  return { token, expiresAt: now + SESSION_TTL_MS };
}

function destroySession(token) {
  db.prepare('DELETE FROM sessions WHERE token = ?').run(token);
}

/** Returns the admin_users row for a valid, unexpired token, or null. */
function sessionAdmin(token) {
  if (!token) return null;
  const row = db.prepare(
    `SELECT a.id, a.username, a.role FROM sessions s
     JOIN admin_users a ON a.id = s.admin_id
     WHERE s.token = ? AND s.expires_at > ?`
  ).get(token, Date.now());
  return row || null;
}

function bearerToken(req) {
  const header = req.headers['authorization'] || '';
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  return match ? match[1].trim() : null;
}

// --- Login rate limiting (in-memory; resets on restart) --------------------
// Fine for a single small admin team on one process. For multi-instance
// deployments, move this to a shared store (DB table or Redis).

const MAX_ATTEMPTS = 5;
const WINDOW_MS = 15 * 60 * 1000;
const attempts = new Map(); // key -> { count, windowStart }

function loginKey(username, ip) {
  return `${String(username || '').toLowerCase()}::${ip || 'unknown'}`;
}

function isLocked(username, ip) {
  const entry = attempts.get(loginKey(username, ip));
  if (!entry) return false;
  if (Date.now() - entry.windowStart > WINDOW_MS) {
    attempts.delete(loginKey(username, ip));
    return false;
  }
  return entry.count >= MAX_ATTEMPTS;
}

function recordFailedLogin(username, ip) {
  const key = loginKey(username, ip);
  const entry = attempts.get(key);
  if (!entry || Date.now() - entry.windowStart > WINDOW_MS) {
    attempts.set(key, { count: 1, windowStart: Date.now() });
  } else {
    entry.count += 1;
  }
}

function clearFailedLogins(username, ip) {
  attempts.delete(loginKey(username, ip));
}

// --- Login/logout handlers ---------------------------------------------

function login(username, password, ip) {
  if (isLocked(username, ip)) {
    return { ok: false, status: 429, error: 'Too many failed attempts. Try again in a few minutes.' };
  }
  const user = db.prepare('SELECT * FROM admin_users WHERE username = ?').get(String(username || '').trim());
  if (!user || !verifyPassword(password, user.password_hash)) {
    recordFailedLogin(username, ip);
    audit(username || 'unknown', 'login_failed', null, `ip=${ip}`);
    return { ok: false, status: 401, error: 'Invalid username or password.' };
  }
  clearFailedLogins(username, ip);
  const session = createSession(user.id);
  audit(user.username, 'login_success', null, `ip=${ip}`);
  return { ok: true, token: session.token, expiresAt: session.expiresAt, username: user.username, role: user.role };
}

// --- Admin user / role management (OWNER only — enforced by the caller via
// requireAdmin(req, res, 'users.write') / 'users.read' in server.js) --------

function listAdmins() {
  return db.prepare('SELECT id, username, role, created_at FROM admin_users ORDER BY id ASC').all();
}

function createAdmin({ username, password, role }, createdBy) {
  username = String(username || '').trim();
  if (!username) return { ok: false, status: 400, error: 'Username is required.' };
  if (String(password || '').length < 10) {
    return { ok: false, status: 400, error: 'Password must be at least 10 characters.' };
  }
  if (!permissions.isValidRole(role)) {
    return { ok: false, status: 400, error: `Role must be one of: ${permissions.ROLES.join(', ')}.` };
  }
  const existing = db.prepare('SELECT id FROM admin_users WHERE username = ?').get(username);
  if (existing) return { ok: false, status: 409, error: 'That username already exists.' };
  db.prepare(
    'INSERT INTO admin_users (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)'
  ).run(username, hashPassword(password), role, Date.now());
  auditEvent({ username: createdBy, role: 'OWNER', action: 'admin_user_created', target: username, result: 'success', detail: `role=${role}` });
  return { ok: true };
}

function updateAdminRole(targetUsername, newRole, updatedBy) {
  if (!permissions.isValidRole(newRole)) {
    return { ok: false, status: 400, error: `Role must be one of: ${permissions.ROLES.join(', ')}.` };
  }
  const target = db.prepare('SELECT * FROM admin_users WHERE username = ?').get(targetUsername);
  if (!target) return { ok: false, status: 404, error: 'Admin user not found.' };
  if (target.username === updatedBy && newRole !== 'OWNER') {
    return { ok: false, status: 400, error: 'You cannot change your own role away from OWNER.' };
  }
  db.prepare('UPDATE admin_users SET role = ? WHERE id = ?').run(newRole, target.id);
  auditEvent({ username: updatedBy, role: 'OWNER', action: 'admin_role_changed', target: targetUsername, result: 'success', detail: `newRole=${newRole}` });
  return { ok: true };
}

function changePassword(adminId, username, currentPassword, newPassword) {
  const user = db.prepare('SELECT * FROM admin_users WHERE id = ?').get(adminId);
  if (!user || !verifyPassword(currentPassword, user.password_hash)) {
    return { ok: false, status: 401, error: 'Current password is incorrect.' };
  }
  if (String(newPassword || '').length < 10) {
    return { ok: false, status: 400, error: 'New password must be at least 10 characters.' };
  }
  db.prepare('UPDATE admin_users SET password_hash = ? WHERE id = ?').run(hashPassword(newPassword), adminId);
  db.prepare('DELETE FROM sessions WHERE admin_id = ?').run(adminId);
  audit(username, 'password_changed', null, '');
  return { ok: true };
}

module.exports = {
  hashPassword,
  verifyPassword,
  createSession,
  destroySession,
  sessionAdmin,
  bearerToken,
  login,
  changePassword,
  listAdmins,
  createAdmin,
  updateAdminRole,
};
