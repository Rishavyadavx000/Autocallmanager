'use strict';

// Centralized role -> capability map for the Admin Control Center.
//
// Add new capability strings here as new admin features ship (App Control,
// etc.) and reference them from server.js route declarations, rather than
// hardcoding role checks inside individual handlers. This keeps "who can do
// what" auditable in one place, per the FINAL RULE in the admin spec:
// authorization is enforced by the backend, not the frontend.

const ROLES = ['OWNER', 'ADMIN', 'EDITOR', 'VIEWER'];

// '*' means "every capability, including ones added later." Only OWNER
// gets this — user/role management is intentionally OWNER-only and never
// granted via '*' lookups on other roles.
const CAPABILITIES = {
  OWNER: ['*'],
  ADMIN: [
    'dashboard.read',
    'api.read', 'api.write', 'api.test',
    'prompts.read', 'prompts.write', 'prompts.publish', 'prompts.rollback', 'prompts.test',
    'app_control.read', 'app_control.write',
    'audit.read',
    'voice_consent.read', 'voice_consent.write',
    'voice_calls.read', 'voice_calls.write', 'voice_calls.test',
  ],
  EDITOR: [
    'dashboard.read',
    'api.read', // can see configured providers to pick one when testing a prompt; cannot add/edit/delete
    'prompts.read', 'prompts.write', 'prompts.publish', 'prompts.rollback', 'prompts.test',
  ],
  VIEWER: [
    'dashboard.read',
    'api.read',
    'prompts.read',
    'app_control.read',
    'audit.read',
  ],
};

function isValidRole(role) {
  return ROLES.includes(role);
}

function hasPermission(role, capability) {
  const caps = CAPABILITIES[role];
  if (!caps) return false;
  return caps.includes('*') || caps.includes(capability);
}

module.exports = { ROLES, CAPABILITIES, isValidRole, hasPermission };
