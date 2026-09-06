'use strict';

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');

const { db, bootstrap, audit, auditEvent } = require('./db');
const auth = require('./auth');
const permissions = require('./permissions');
const promptsService = require('./prompts');
const voiceConsent = require('./voiceConsent');
const voiceCallService = require('./voiceCallService');

bootstrap({ hashPassword: auth.hashPassword });

const PORT = Number(process.env.PORT) || 8787;
const PUBLIC_APP_KEY = process.env.PUBLIC_APP_KEY || ''; // optional, weak abuse-limiter only — see README
const MAX_BODY_BYTES = 1024 * 1024; // 1MB
const ADMIN_DIR = path.join(__dirname, '..', 'public', 'admin');

// ---------------------------------------------------------------------------
// Small dependency-free router
// ---------------------------------------------------------------------------

const routes = []; // { method, matcher(pathname) -> params|null, handler }

function route(method, pattern, handler) {
  const paramNames = [];
  const regexStr = pattern
    .split('/')
    .map((seg) => {
      if (seg.startsWith(':')) {
        paramNames.push(seg.slice(1));
        return '([^/]+)';
      }
      return seg.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    })
    .join('/');
  const regex = new RegExp(`^${regexStr}$`);
  routes.push({
    method,
    matcher(pathname) {
      const m = regex.exec(pathname);
      if (!m) return null;
      const params = {};
      paramNames.forEach((name, i) => (params[name] = decodeURIComponent(m[i + 1])));
      return params;
    },
    handler,
  });
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(Object.assign(new Error('Request body too large'), { statusCode: 413 }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      if (!raw) return resolve({});
      try {
        resolve(JSON.parse(raw));
      } catch (_) {
        reject(Object.assign(new Error('Invalid JSON body'), { statusCode: 400 }));
      }
    });
    req.on('error', reject);
  });
}

function readFormBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(Object.assign(new Error('Request body too large'), { statusCode: 413 }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      const out = {};
      for (const part of raw.split('&')) {
        if (!part) continue;
        const i = part.indexOf('=');
        const k = decodeURIComponent((i >= 0 ? part.slice(0, i) : part).replace(/\+/g, ' '));
        const v = decodeURIComponent((i >= 0 ? part.slice(i + 1) : '').replace(/\+/g, ' '));
        out[k] = v;
      }
      resolve(out);
    });
    req.on('error', reject);
  });
}

function send(res, statusCode, body, extraHeaders) {
  const payload = typeof body === 'string' ? body : JSON.stringify(body);
  res.writeHead(statusCode, {
    'Content-Type': typeof body === 'string' ? 'text/plain; charset=utf-8' : 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
    'X-Content-Type-Options': 'nosniff',
    ...extraHeaders,
  });
  res.end(payload);
}

function clientIp(req) {
  const fwd = req.headers['x-forwarded-for'];
  if (fwd) return String(fwd).split(',')[0].trim();
  return req.socket.remoteAddress || 'unknown';
}

// --- Auth middleware ---------------------------------------------------

/**
 * Resolves the session's admin, then (if `permission` is given) checks it
 * against that admin's role. Sends the appropriate 401/403 and returns null
 * on failure so callers can `if (!admin) return;`. Every route below that
 * touches privileged data passes a permission string — see permissions.js
 * for the role -> capability map.
 */
function requireAdmin(req, res, permission) {
  const token = auth.bearerToken(req);
  const admin = auth.sessionAdmin(token);
  if (!admin) {
    send(res, 401, { ok: false, error: 'Missing or expired admin session. Log in again.' });
    return null;
  }
  if (permission && !permissions.hasPermission(admin.role, permission)) {
    auditEvent({
      username: admin.username,
      role: admin.role,
      action: 'forbidden',
      target: permission,
      result: 'denied',
      detail: `path=${req.url}`,
    });
    send(res, 403, { ok: false, error: `Your role (${admin.role}) does not have permission to do this.` });
    return null;
  }
  return admin;
}

function requirePublicAppKey(req, res) {
  if (!PUBLIC_APP_KEY) {
    send(res, 503, { ok: false, error: 'Public app key is not configured on the server.' });
    return false;
  }
  return checkPublicAppKey(req, res);
}

function checkPublicAppKey(req, res) {
  if (!PUBLIC_APP_KEY) return true; // open by default — see README for tradeoffs
  const provided = req.headers['x-app-key'];
  if (provided === PUBLIC_APP_KEY) return true;
  send(res, 401, { ok: false, error: 'Missing or invalid X-App-Key.' });
  return false;
}

// ---------------------------------------------------------------------------
// Public, unauthenticated endpoints (consumed by the Android app)
// ---------------------------------------------------------------------------

route('GET', '/v1/health', (req, res) => {
  send(res, 200, { ok: true, service: 'autocallmanager-admin', time: Date.now() });
});

route('GET', '/v1/prompts', (req, res) => {
  if (!checkPublicAppKey(req, res)) return;
  res.setHeader('Access-Control-Allow-Origin', '*');
  send(res, 200, promptsService.publicCatalog());
});

route('GET', '/v1/prompts/:id', (req, res, params) => {
  if (!checkPublicAppKey(req, res)) return;
  const prompt = promptsService.publicOne(params.id);
  res.setHeader('Access-Control-Allow-Origin', '*');
  if (!prompt) return send(res, 404, { ok: false, error: `prompt "${params.id}" not found or disabled` });
  send(res, 200, prompt);
});

// ---------------------------------------------------------------------------
// AI Voice call endpoints
// ---------------------------------------------------------------------------

route('POST', '/v1/voice/calls', async (req, res) => {
  if (!requirePublicAppKey(req, res)) return;
  const body = await readBody(req);
  const result = voiceConsent.checkSafetyGate({
    phoneNumber: body.phoneNumber,
    scheduleEnabled: body.scheduleEnabled !== false,
    providerConfigured: voiceCallService.configured(),
    aiConfigured: voiceCallService.aiConfigured(),
    rateLimitAvailable: true,
    actor: 'android_app',
  });
  if (!result.allowed) return send(res, 403, { ok: false, error: result.reason, reason: result.reason });

  const call = await voiceCallService.createOutboundCall({
    phoneNumber: body.phoneNumber,
    scheduleId: body.scheduleId || null,
    promptId: body.promptId || null,
    promptVersion: body.promptVersion || null,
    instruction: body.instruction || '',
    maxDurationSeconds: body.maxDurationSeconds,
  });
  send(res, call.ok ? 201 : (call.status || 500), call);
});

route('GET', '/v1/voice/calls/:executionId', (req, res, params) => {
  if (!requirePublicAppKey(req, res)) return;
  const call = voiceCallService.getCall(params.executionId);
  if (!call) return send(res, 404, { ok: false, error: 'Voice call not found' });
  send(res, 200, { ok: true, call });
});

route('POST', '/v1/voice/calls/:executionId/cancel', (req, res, params) => {
  const admin = requireAdmin(req, res, 'voice_calls.write');
  if (!admin) return;
  const ok = voiceCallService.updateCall(params.executionId, { state: 'CANCELLED', aiState: 'ENDED', errorCode: null });
  send(res, ok ? 200 : 404, ok ? { ok: true } : { ok: false, error: 'Voice call not found' });
});

route('GET', '/v1/admin/voice/calls', (req, res) => {
  const admin = requireAdmin(req, res, 'voice_calls.read');
  if (!admin) return;
  const rows = db.prepare('SELECT * FROM voice_calls ORDER BY id DESC LIMIT 200').all();
  send(res, 200, { ok: true, calls: rows });
});

route('GET', '/v1/voice/providers/status', (req, res) => {
  const admin = requireAdmin(req, res, 'voice_calls.read');
  if (!admin) return;
  send(res, 200, {
    ok: true,
    provider: voiceCallService.PROVIDER,
    configured: voiceCallService.configured(),
    aiConfigured: voiceCallService.aiConfigured(),
  });
});

route('POST', '/v1/voice/webhooks/answer', async (req, res, params) => {
  const call = voiceCallService.getCall(params.executionId);
  if (!call) return send(res, 404, 'not found');
  const form = await readFormBody(req);
  const parsed = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  if (!voiceCallService.verifyTwilioSignature(req, parsed, form)) return send(res, 403, 'forbidden');
  voiceCallService.updateCall(call.executionId, { state: 'ANSWERED', aiState: 'AI_READY' });
  res.writeHead(200, { 'Content-Type': 'text/xml; charset=utf-8' });
  res.end(voiceCallService.answerTwiML(call));
});

route('POST', '/v1/voice/webhooks/respond', async (req, res, params) => {
  const call = voiceCallService.getCall(params.executionId);
  if (!call) return send(res, 404, 'not found');
  const form = await readFormBody(req);
  const parsed = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  if (!voiceCallService.verifyTwilioSignature(req, parsed, form)) return send(res, 403, 'forbidden');
  res.writeHead(200, { 'Content-Type': 'text/xml; charset=utf-8' });
  res.end(await voiceCallService.respondTwiML(call, form.SpeechResult || ''));
});

route('POST', '/v1/voice/webhooks/status', async (req, res, params) => {
  const call = voiceCallService.getCall(params.executionId);
  if (!call) return send(res, 404, 'not found');
  const form = await readFormBody(req);
  const parsed = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  if (!voiceCallService.verifyTwilioSignature(req, parsed, form)) return send(res, 403, 'forbidden');
  const status = String(form.CallStatus || '').toLowerCase();
  const map = { queued: 'OUTBOUND_REQUESTED', ringing: 'RINGING', 'in-progress': 'CONNECTED', completed: 'COMPLETED', busy: 'FAILED', failed: 'FAILED', 'no-answer': 'NO_ANSWER', canceled: 'CANCELLED' };
  voiceCallService.updateCall(call.executionId, { state: map[status] || call.state, aiState: status === 'completed' ? 'ENDED' : call.aiState, providerCallId: form.CallSid || call.providerCallId, errorCode: ['busy','failed','no-answer','canceled'].includes(status) ? `PROVIDER_${status.toUpperCase().replace('-', '_')}` : null });
  send(res, 200, 'ok');
});

// ---------------------------------------------------------------------------
// Admin auth endpoints
// ---------------------------------------------------------------------------

route('POST', '/v1/admin/login', async (req, res) => {
  const body = await readBody(req);
  const result = auth.login(body.username, body.password, clientIp(req));
  if (!result.ok) return send(res, result.status, { ok: false, error: result.error });
  send(res, 200, { ok: true, token: result.token, expiresAt: result.expiresAt, username: result.username, role: result.role });
});

route('POST', '/v1/admin/logout', (req, res) => {
  const token = auth.bearerToken(req);
  if (token) auth.destroySession(token);
  send(res, 200, { ok: true });
});

route('POST', '/v1/admin/change-password', async (req, res) => {
  const admin = requireAdmin(req, res);
  if (!admin) return;
  const body = await readBody(req);
  const result = auth.changePassword(admin.id, admin.username, body.currentPassword, body.newPassword);
  if (!result.ok) return send(res, result.status, result);
  send(res, 200, { ok: true, message: 'Password changed. Please log in again.' });
});

route('GET', '/v1/admin/audit', (req, res) => {
  const admin = requireAdmin(req, res, 'audit.read');
  if (!admin) return;
  const rows = db.prepare('SELECT * FROM audit_log ORDER BY id DESC LIMIT 200').all();
  send(res, 200, { ok: true, entries: rows });
});

// ---------------------------------------------------------------------------
// Admin user & role management (OWNER only)
// ---------------------------------------------------------------------------

route('GET', '/v1/admin/users', (req, res) => {
  const admin = requireAdmin(req, res, 'users.read');
  if (!admin) return;
  send(res, 200, { ok: true, users: auth.listAdmins(), roles: permissions.ROLES });
});

route('POST', '/v1/admin/users', async (req, res) => {
  const admin = requireAdmin(req, res, 'users.write');
  if (!admin) return;
  const body = await readBody(req);
  const result = auth.createAdmin({ username: body.username, password: body.password, role: body.role }, admin.username);
  send(res, result.ok ? 201 : result.status, result);
});

route('PUT', '/v1/admin/users/:username/role', async (req, res, params) => {
  const admin = requireAdmin(req, res, 'users.write');
  if (!admin) return;
  const body = await readBody(req);
  const result = auth.updateAdminRole(params.username, body.role, admin.username);
  send(res, result.ok ? 200 : result.status, result);
});

// ---------------------------------------------------------------------------
// AI Voice recipient consent / do-not-call (RBAC protected)
// ---------------------------------------------------------------------------

route('GET', '/v1/admin/voice/recipients/:phone', (req, res, params) => {
  const admin = requireAdmin(req, res, 'voice_consent.read');
  if (!admin) return;
  const recipient = voiceConsent.getRecipient(params.phone);
  if (!recipient) return send(res, 400, { ok: false, error: 'Invalid phone number. Use international format, e.g. +91XXXXXXXXXX.' });
  send(res, 200, { ok: true, recipient });
});

route('POST', '/v1/admin/voice/consent', async (req, res) => {
  const admin = requireAdmin(req, res, 'voice_consent.write');
  if (!admin) return;
  const body = await readBody(req);
  const result = voiceConsent.setConsent({
    phoneNumber: body.phoneNumber,
    status: body.status,
    source: body.source,
    purpose: body.purpose,
    evidenceId: body.evidenceId,
    actor: admin.username,
  });
  send(res, result.ok ? 200 : result.status || 400, result);
});

route('POST', '/v1/admin/voice/opt-out', async (req, res) => {
  const admin = requireAdmin(req, res, 'voice_consent.write');
  if (!admin) return;
  const body = await readBody(req);
  const result = voiceConsent.recordOptOut({
    phoneNumber: body.phoneNumber,
    source: body.source,
    actor: admin.username,
  });
  send(res, result.ok ? 200 : result.status || 400, result);
});

route('POST', '/v1/admin/voice/restore', async (req, res) => {
  const admin = requireAdmin(req, res, 'voice_consent.write');
  if (!admin) return;
  const body = await readBody(req);
  const result = voiceConsent.restoreRecipient({ phoneNumber: body.phoneNumber, actor: admin.username });
  send(res, result.ok ? 200 : result.status || 400, result);
});

route('POST', '/v1/admin/voice/check', async (req, res) => {
  const admin = requireAdmin(req, res, 'voice_consent.read');
  if (!admin) return;
  const body = await readBody(req);
  const result = voiceConsent.checkSafetyGate({
    phoneNumber: body.phoneNumber,
    scheduleEnabled: body.scheduleEnabled !== false,
    providerConfigured: !!body.providerConfigured,
    aiConfigured: !!body.aiConfigured,
    rateLimitAvailable: body.rateLimitAvailable !== false,
    actor: admin.username,
  });
  send(res, 200, { ok: true, ...result });
});

// ---------------------------------------------------------------------------
// Admin prompt management endpoints (require Authorization: Bearer <token>)
// ---------------------------------------------------------------------------

route('GET', '/v1/admin/prompts', (req, res) => {
  const admin = requireAdmin(req, res, 'prompts.read');
  if (!admin) return;
  send(res, 200, { ok: true, prompts: promptsService.adminList() });
});

route('GET', '/v1/admin/prompts/:id', (req, res, params) => {
  const admin = requireAdmin(req, res, 'prompts.read');
  if (!admin) return;
  const prompt = promptsService.adminOne(params.id);
  if (!prompt) return send(res, 404, { ok: false, error: `prompt "${params.id}" not found` });
  send(res, 200, { ok: true, prompt });
});

route('GET', '/v1/admin/prompts/:id/versions', (req, res, params) => {
  const admin = requireAdmin(req, res, 'prompts.read');
  if (!admin) return;
  send(res, 200, { ok: true, versions: promptsService.versionHistory(params.id) });
});

route('POST', '/v1/prompts', async (req, res) => {
  const admin = requireAdmin(req, res, 'prompts.write');
  if (!admin) return;
  const body = await readBody(req);
  const result = promptsService.createPrompt(body, admin.username);
  send(res, result.ok ? 201 : result.status, result);
});

route('PUT', '/v1/prompts/:id', async (req, res, params) => {
  const admin = requireAdmin(req, res, 'prompts.write');
  if (!admin) return;
  const body = await readBody(req);
  const result = promptsService.updatePromptMeta(params.id, body, admin.username);
  send(res, result.ok ? 200 : result.status, result);
});

route('POST', '/v1/prompts/:id/versions', async (req, res, params) => {
  const admin = requireAdmin(req, res, 'prompts.write');
  if (!admin) return;
  const body = await readBody(req);
  const result = promptsService.createVersion(params.id, body, admin.username);
  send(res, result.ok ? 201 : result.status, result);
});

route('POST', '/v1/prompts/:id/rollback', async (req, res, params) => {
  const admin = requireAdmin(req, res, 'prompts.rollback');
  if (!admin) return;
  const body = await readBody(req);
  const result = promptsService.rollback(params.id, body.version, admin.username);
  send(res, result.ok ? 200 : result.status, result);
});

route('POST', '/v1/prompts/:id/test', async (req, res, params) => {
  const admin = requireAdmin(req, res, 'prompts.test');
  if (!admin) return;
  const body = await readBody(req);
  const result = await promptsService.testPrompt(params.id, body.variables || {}, admin.username);
  send(res, result.ok ? 200 : result.status || 400, result);
});

// ---------------------------------------------------------------------------
// Static admin panel UI
// ---------------------------------------------------------------------------

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml' };

function serveStatic(req, res, pathname) {
  let rel = pathname === '/admin' || pathname === '/admin/' ? '/index.html' : pathname.replace(/^\/admin/, '');
  const filePath = path.join(ADMIN_DIR, rel);
  if (!filePath.startsWith(ADMIN_DIR)) return send(res, 403, { ok: false, error: 'Forbidden' });
  fs.readFile(filePath, (err, data) => {
    if (err) return send(res, 404, { ok: false, error: 'Not found' });
    const ext = path.extname(filePath);
    res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream', 'Content-Length': data.length });
    res.end(data);
  });
}

// ---------------------------------------------------------------------------
// Request dispatch
// ---------------------------------------------------------------------------

async function handleRequest(req, res) {
  const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = parsedUrl.pathname;

  if (req.method === 'OPTIONS') {
    return send(res, 204, '', {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,POST,PUT,OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-App-Key',
    });
  }

  if (pathname.startsWith('/admin')) {
    return serveStatic(req, res, pathname);
  }

  for (const r of routes) {
    if (r.method !== req.method) continue;
    const params = r.matcher(pathname);
    if (!params) continue;
    try {
      await r.handler(req, res, params);
    } catch (err) {
      const status = err.statusCode || 500;
      audit('system', 'server_error', null, err.message);
      if (!res.headersSent) send(res, status, { ok: false, error: status === 500 ? 'Internal server error' : err.message });
    }
    return;
  }

  send(res, 404, { ok: false, error: `No route for ${req.method} ${pathname}` });
}

// ---------------------------------------------------------------------------
// Server startup — HTTPS if cert/key configured, otherwise plain HTTP.
// Always put this behind TLS in production (reverse proxy or the options below).
// ---------------------------------------------------------------------------

let server;
if (process.env.TLS_CERT_PATH && process.env.TLS_KEY_PATH) {
  server = https.createServer(
    { cert: fs.readFileSync(process.env.TLS_CERT_PATH), key: fs.readFileSync(process.env.TLS_KEY_PATH) },
    handleRequest
  );
} else {
  server = http.createServer(handleRequest);
}

server.listen(PORT, () => {
  console.log(`[server] AutoCallManager Admin API listening on port ${PORT} (${server instanceof https.Server ? 'https' : 'http'})`);
  console.log(`[server] Admin panel:   http://localhost:${PORT}/admin`);
  console.log(`[server] Public prompts: http://localhost:${PORT}/v1/prompts`);
});

module.exports = { server, handleRequest };
