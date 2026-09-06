'use strict';

// Storage layer for the AutoCallManager Admin Panel (V1.0.5).
//
// Uses Node's built-in `node:sqlite` module so the server runs with zero
// third-party dependencies and zero native compilation steps. `node:sqlite`
// is marked experimental by Node (stable API, but the maintainers reserve
// the right to change it between major versions). If you'd rather depend on
// a long-term-stable driver, swap this file for `better-sqlite3` — the
// `.prepare(sql).run(...)/.get(...)/.all(...)` shape used below is close to
// a drop-in match.
//
// Requires Node >= 22.5. Tested against Node 22.x.

const fs = require('fs');
const path = require('path');
const { DatabaseSync } = require('node:sqlite');

const DATA_DIR = process.env.DATA_DIR
  ? path.resolve(process.env.DATA_DIR)
  : path.join(__dirname, '..', 'data');

if (!fs.existsSync(DATA_DIR)) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
}

const DB_PATH = path.join(DATA_DIR, 'admin.sqlite');
const db = new DatabaseSync(DB_PATH);

db.exec('PRAGMA journal_mode = WAL;');
db.exec('PRAGMA foreign_keys = ON;');

db.exec(`
  CREATE TABLE IF NOT EXISTS admin_users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'OWNER',
    created_at INTEGER NOT NULL
  );

  CREATE TABLE IF NOT EXISTS sessions (
    token TEXT PRIMARY KEY,
    admin_id INTEGER NOT NULL REFERENCES admin_users(id),
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
  );

  CREATE TABLE IF NOT EXISTS prompts (
    id TEXT PRIMARY KEY,
    type TEXT NOT NULL,
    name TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    output_format TEXT NOT NULL DEFAULT 'plain_text',
    max_words INTEGER NOT NULL DEFAULT 60,
    variables_json TEXT NOT NULL DEFAULT '[]',
    current_version INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
  );

  CREATE TABLE IF NOT EXISTS prompt_versions (
    prompt_id TEXT NOT NULL REFERENCES prompts(id),
    version INTEGER NOT NULL,
    template TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    created_by TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (prompt_id, version)
  );



  CREATE TABLE IF NOT EXISTS voice_recipients (
    phone_key TEXT PRIMARY KEY,
    raw_phone TEXT NOT NULL,
    consent_status TEXT NOT NULL DEFAULT 'NOT_CONSENTED',
    consent_source TEXT,
    consent_timestamp INTEGER,
    consent_purpose TEXT,
    consent_evidence_id TEXT,
    do_not_call INTEGER NOT NULL DEFAULT 0,
    opt_out_timestamp INTEGER,
    opt_out_source TEXT,
    updated_at INTEGER NOT NULL
  );

  CREATE TABLE IF NOT EXISTS voice_calls (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    execution_id TEXT UNIQUE NOT NULL,
    schedule_id TEXT,
    phone_key TEXT NOT NULL,
    provider TEXT NOT NULL,
    provider_call_id TEXT,
    state TEXT NOT NULL,
    ai_state TEXT NOT NULL,
    prompt_id TEXT,
    prompt_version INTEGER,
    instruction TEXT NOT NULL DEFAULT '',
    max_duration_seconds INTEGER NOT NULL DEFAULT 120,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    error_code TEXT
  );

  CREATE TABLE IF NOT EXISTS voice_consent_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts INTEGER NOT NULL,
    actor TEXT NOT NULL,
    phone_key TEXT NOT NULL,
    action TEXT NOT NULL,
    detail TEXT
  );

  CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts INTEGER NOT NULL,
    admin_username TEXT NOT NULL,
    action TEXT NOT NULL,
    prompt_id TEXT,
    detail TEXT,
    role TEXT,
    target TEXT,
    result TEXT,
    request_id TEXT
  );
`);

// --- Migrations for databases created before roles/RBAC existed ------------
// CREATE TABLE IF NOT EXISTS above only applies to brand-new databases; an
// existing admin.sqlite from before this version needs these columns added
// explicitly. Existing admin accounts become OWNER, which preserves the full
// access they already had (there was no role concept to have limited it).
function columnExists(table, column) {
  return db.prepare(`PRAGMA table_info(${table})`).all().some((c) => c.name === column);
}

if (!columnExists('admin_users', 'role')) {
  db.exec(`ALTER TABLE admin_users ADD COLUMN role TEXT NOT NULL DEFAULT 'OWNER'`);
}
for (const col of ['role', 'target', 'result', 'request_id']) {
  if (!columnExists('audit_log', col)) {
    db.exec(`ALTER TABLE audit_log ADD COLUMN ${col} TEXT`);
  }
}

/** Insert an audit trail row. Never throws — auditing must not break a request. */
function audit(adminUsername, action, promptId, detail) {
  try {
    db.prepare(
      `INSERT INTO audit_log (ts, admin_username, action, prompt_id, detail)
       VALUES (?, ?, ?, ?, ?)`
    ).run(Date.now(), adminUsername || 'unknown', action, promptId || null, detail || '');
  } catch (_) {
    /* ignore */
  }
}

/**
 * Structured audit entry for RBAC-aware events (role, generic target,
 * result, request id) — prefer this over the legacy `audit()` above for any
 * new admin/user/permission event. `audit()` remains as-is so existing
 * prompt-management call sites don't need to change. Never throws.
 */
function auditEvent({ username, role, action, target, result, requestId, detail }) {
  try {
    db.prepare(
      `INSERT INTO audit_log (ts, admin_username, action, prompt_id, detail, role, target, result, request_id)
       VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?)`
    ).run(
      Date.now(),
      username || 'unknown',
      action,
      detail || '',
      role || null,
      target || null,
      result || null,
      requestId || null
    );
  } catch (_) {
    /* ignore */
  }
}

/** One-time bootstrap: seed the default prompt catalog and the first admin user. */
function bootstrap({ hashPassword }) {
  const promptCount = db.prepare('SELECT COUNT(*) AS n FROM prompts').get().n;
  if (promptCount === 0) {
    const seedPath = path.join(__dirname, '..', 'seed', 'prompts.seed.json');
    const seed = JSON.parse(fs.readFileSync(seedPath, 'utf8'));
    const now = Date.now();
    const insertPrompt = db.prepare(`
      INSERT INTO prompts (id, type, name, enabled, output_format, max_words, variables_json, current_version, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `);
    const insertVersion = db.prepare(`
      INSERT INTO prompt_versions (prompt_id, version, template, created_at, created_by)
      VALUES (?, ?, ?, ?, ?)
    `);
    for (const p of seed.prompts) {
      insertPrompt.run(
        p.id,
        p.type,
        p.name,
        p.enabled ? 1 : 0,
        p.outputFormat || 'plain_text',
        p.maxWords || 60,
        JSON.stringify(p.variables || []),
        p.version || 1,
        now,
        now
      );
      insertVersion.run(p.id, p.version || 1, p.template, now, 'seed');
    }
    audit('system', 'seed_catalog', null, `Seeded ${seed.prompts.length} prompts`);
    console.log(`[db] Seeded prompt catalog with ${seed.prompts.length} prompts.`);
  }

  const adminCount = db.prepare('SELECT COUNT(*) AS n FROM admin_users').get().n;
  if (adminCount === 0) {
    const username = process.env.ADMIN_BOOTSTRAP_USER || 'admin';
    const crypto = require('crypto');
    const password = process.env.ADMIN_BOOTSTRAP_PASSWORD || crypto.randomBytes(9).toString('base64url');
    db.prepare(
      "INSERT INTO admin_users (username, password_hash, role, created_at) VALUES (?, ?, 'OWNER', ?)"
    ).run(username, hashPassword(password), Date.now());
    audit('system', 'bootstrap_admin', null, `Created admin user "${username}" with role OWNER`);

    console.log('');
    console.log('============================================================');
    console.log(' AutoCallManager Admin Panel — first-run admin account created');
    console.log(`   username: ${username}`);
    if (!process.env.ADMIN_BOOTSTRAP_PASSWORD) {
      console.log(`   password: ${password}   (generated — save this now)`);
      console.log('   Set ADMIN_BOOTSTRAP_PASSWORD to control this on next fresh install.');
    } else {
      console.log('   password: (from ADMIN_BOOTSTRAP_PASSWORD)');
    }
    console.log(' This message only appears once. Change the password after logging in.');
    console.log('============================================================');
    console.log('');
  }
}

module.exports = { db, audit, auditEvent, bootstrap, DATA_DIR, DB_PATH };
