'use strict';

const { db, audit } = require('./db');

const ID_PATTERN = /^[a-z0-9]+(?:[._-][a-z0-9]+)*$/i;
const SCHEMA_VERSION = 1;

function currentTemplate(row) {
  const found = db
    .prepare('SELECT template FROM prompt_versions WHERE prompt_id = ? AND version = ?')
    .get(row.id, row.current_version);
  return found ? found.template : '';
}

// This is the exact shape the Android app parses (PromptStore.parse), matching the
// bundled ai_prompts_v1.json asset field-for-field. `template` MUST be included —
// the client renders {{variables}} on-device, it never asks the server to render.
function rowToClientShape(row) {
  return {
    id: row.id,
    version: row.current_version,
    type: row.type,
    name: row.name,
    enabled: !!row.enabled,
    outputFormat: row.output_format,
    maxWords: row.max_words,
    variables: JSON.parse(row.variables_json || '[]'),
    template: currentTemplate(row),
  };
}

function rowToAdminShape(row) {
  return {
    ...rowToClientShape(row),
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

/** Public catalog: enabled prompts only, in the exact shape the Android client already parses. */
function publicCatalog() {
  const rows = db.prepare('SELECT * FROM prompts WHERE enabled = 1 ORDER BY id').all();
  return { schemaVersion: SCHEMA_VERSION, prompts: rows.map(rowToClientShape) };
}

function publicOne(id) {
  const row = db.prepare('SELECT * FROM prompts WHERE id = ? AND enabled = 1').get(id);
  return row ? rowToClientShape(row) : null;
}

/** Admin catalog: every prompt regardless of enabled state, with template + timestamps. */
function adminList() {
  const rows = db.prepare('SELECT * FROM prompts ORDER BY id').all();
  return rows.map(rowToAdminShape);
}

function adminOne(id) {
  const row = db.prepare('SELECT * FROM prompts WHERE id = ?').get(id);
  return row ? rowToAdminShape(row) : null;
}

function versionHistory(id) {
  return db
    .prepare('SELECT version, template, created_at, created_by FROM prompt_versions WHERE prompt_id = ? ORDER BY version DESC')
    .all(id)
    .map((r) => ({ version: r.version, template: r.template, createdAt: r.created_at, createdBy: r.created_by }));
}

function validateNewPrompt(body) {
  const id = String(body.id || '').trim();
  const template = String(body.template || '').trim();
  if (!id || !ID_PATTERN.test(id)) return 'id must be alphanumeric with . _ - separators, e.g. message.custom.v1';
  if (!body.type || !String(body.type).trim()) return 'type is required';
  if (!body.name || !String(body.name).trim()) return 'name is required';
  if (!template) return 'template is required';
  if (db.prepare('SELECT 1 FROM prompts WHERE id = ?').get(id)) return `prompt id "${id}" already exists`;
  return null;
}

function createPrompt(body, adminUsername) {
  const error = validateNewPrompt(body);
  if (error) return { ok: false, status: 400, error };

  const now = Date.now();
  const id = String(body.id).trim();
  const maxWords = Number.isFinite(Number(body.maxWords)) ? Math.min(300, Math.max(10, Number(body.maxWords))) : 60;
  const variables = Array.isArray(body.variables) ? body.variables.map(String) : [];

  db.prepare(`
    INSERT INTO prompts (id, type, name, enabled, output_format, max_words, variables_json, current_version, created_at, updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
  `).run(
    id,
    String(body.type).trim(),
    String(body.name).trim(),
    body.enabled === false ? 0 : 1,
    String(body.outputFormat || 'plain_text'),
    maxWords,
    JSON.stringify(variables),
    now,
    now
  );
  db.prepare(
    'INSERT INTO prompt_versions (prompt_id, version, template, created_at, created_by) VALUES (?, 1, ?, ?, ?)'
  ).run(id, String(body.template).trim(), now, adminUsername);

  audit(adminUsername, 'create_prompt', id, `type=${body.type}`);
  return { ok: true, prompt: adminOne(id) };
}

/** Update metadata only (name, enabled, maxWords, variables, outputFormat, type). Template is versioned separately. */
function updatePromptMeta(id, body, adminUsername) {
  const row = db.prepare('SELECT * FROM prompts WHERE id = ?').get(id);
  if (!row) return { ok: false, status: 404, error: `prompt "${id}" not found` };

  const name = body.name !== undefined ? String(body.name).trim() : row.name;
  const type = body.type !== undefined ? String(body.type).trim() : row.type;
  const outputFormat = body.outputFormat !== undefined ? String(body.outputFormat) : row.output_format;
  const maxWords = body.maxWords !== undefined
    ? Math.min(300, Math.max(10, Number(body.maxWords) || row.max_words))
    : row.max_words;
  const variables = body.variables !== undefined
    ? JSON.stringify(Array.isArray(body.variables) ? body.variables.map(String) : [])
    : row.variables_json;
  const enabled = body.enabled !== undefined ? (body.enabled ? 1 : 0) : row.enabled;

  if (!name) return { ok: false, status: 400, error: 'name cannot be empty' };

  db.prepare(`
    UPDATE prompts SET name = ?, type = ?, output_format = ?, max_words = ?, variables_json = ?, enabled = ?, updated_at = ?
    WHERE id = ?
  `).run(name, type, outputFormat, maxWords, variables, enabled, Date.now(), id);

  audit(adminUsername, 'update_prompt_meta', id, JSON.stringify(body));
  return { ok: true, prompt: adminOne(id) };
}

/** Create a new template version and make it current. Old versions remain in history for rollback. */
function createVersion(id, body, adminUsername) {
  const row = db.prepare('SELECT * FROM prompts WHERE id = ?').get(id);
  if (!row) return { ok: false, status: 404, error: `prompt "${id}" not found` };
  const template = String(body.template || '').trim();
  if (!template) return { ok: false, status: 400, error: 'template is required' };

  const nextVersion = row.current_version + 1;
  const now = Date.now();
  db.prepare(
    'INSERT INTO prompt_versions (prompt_id, version, template, created_at, created_by) VALUES (?, ?, ?, ?, ?)'
  ).run(id, nextVersion, template, now, adminUsername);
  db.prepare('UPDATE prompts SET current_version = ?, updated_at = ? WHERE id = ?').run(nextVersion, now, id);

  audit(adminUsername, 'create_version', id, `version=${nextVersion}`);
  return { ok: true, prompt: adminOne(id) };
}

function rollback(id, targetVersion, adminUsername) {
  const row = db.prepare('SELECT * FROM prompts WHERE id = ?').get(id);
  if (!row) return { ok: false, status: 404, error: `prompt "${id}" not found` };
  const version = Number(targetVersion);
  const exists = db
    .prepare('SELECT 1 FROM prompt_versions WHERE prompt_id = ? AND version = ?')
    .get(id, version);
  if (!exists) return { ok: false, status: 404, error: `version ${targetVersion} not found for "${id}"` };

  db.prepare('UPDATE prompts SET current_version = ?, updated_at = ? WHERE id = ?').run(version, Date.now(), id);
  audit(adminUsername, 'rollback', id, `to_version=${version}`);
  return { ok: true, prompt: adminOne(id) };
}

/** Render-only preview: substitutes {{variables}} without calling any AI provider. Always available. */
function renderTemplate(template, maxWords, variables) {
  let out = String(template || '');
  const merged = { maxWords: String(maxWords || 60), ...variables };
  for (const [key, value] of Object.entries(merged)) {
    out = out.split(`{{${key}}}`).join(String(value));
  }
  return out;
}

async function testPrompt(id, variables, adminUsername) {
  const row = db.prepare('SELECT * FROM prompts WHERE id = ?').get(id);
  if (!row) return { ok: false, status: 404, error: `prompt "${id}" not found` };
  const versionRow = db
    .prepare('SELECT template FROM prompt_versions WHERE prompt_id = ? AND version = ?')
    .get(id, row.current_version);
  const rendered = renderTemplate(versionRow.template, row.max_words, variables || {});

  audit(adminUsername, 'test_prompt', id, 'render_only');

  const geminiKey = process.env.GEMINI_API_KEY;
  if (!geminiKey) {
    return { ok: true, mode: 'render_only', rendered, note: 'Set GEMINI_API_KEY on the server to enable live test calls.' };
  }

  try {
    const model = process.env.GEMINI_TEST_MODEL || 'gemini-2.5-flash-lite';
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`;
    const resp = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-goog-api-key': geminiKey },
      body: JSON.stringify({
        contents: [{ parts: [{ text: rendered }] }],
        generationConfig: { temperature: 0.7, maxOutputTokens: Math.max(32, row.max_words * 4) },
      }),
    });
    const data = await resp.json();
    if (!resp.ok) {
      return { ok: true, mode: 'live_failed', rendered, error: data?.error?.message || `HTTP ${resp.status}` };
    }
    const text = (data.candidates?.[0]?.content?.parts || []).map((p) => p.text || '').join('').trim();
    return { ok: true, mode: 'live', rendered, output: text };
  } catch (err) {
    return { ok: true, mode: 'live_failed', rendered, error: err.message || 'Live test call failed' };
  }
}

module.exports = {
  publicCatalog,
  publicOne,
  adminList,
  adminOne,
  versionHistory,
  createPrompt,
  updatePromptMeta,
  createVersion,
  rollback,
  testPrompt,
};
