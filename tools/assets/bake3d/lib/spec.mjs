// Validation of the bake config and the primitive model spec. Both are plain JSON files; the
// tool never fills in hidden defaults for camera, light or scale.
import { DIRECTIONS } from './camera.mjs';

export class BakeError extends Error {
  constructor(code, message) {
    super(`${code}: ${message}`);
    this.code = code;
  }
}

const SHAPES = new Set(['box', 'cylinder', 'cone', 'sphere']);
const COLOR = /^#[0-9a-f]{6}$/;

function fail(code, msg) {
  throw new BakeError(code, msg);
}

function isObj(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

function finite(v, path, { min = -Infinity, max = Infinity } = {}) {
  if (typeof v !== 'number' || !Number.isFinite(v) || v < min || v > max) {
    fail('INVALID_SPEC', `${path} must be a finite number in [${min}, ${max}]`);
  }
  return v;
}

function int(v, path, min, max) {
  if (!Number.isInteger(v) || v < min || v > max) fail('INVALID_SPEC', `${path} must be an integer in [${min}, ${max}]`);
  return v;
}

function vec3(v, path, opts) {
  if (!Array.isArray(v) || v.length !== 3) fail('INVALID_SPEC', `${path} must be [x, y, z]`);
  v.forEach((c, i) => finite(c, `${path}[${i}]`, opts));
  return v;
}

export function validateConfig(c) {
  if (!isObj(c) || c.schema !== 'opensamguk-bake3d-config/0') fail('INVALID_SPEC', 'config.schema must be opensamguk-bake3d-config/0');
  if (c.status === 'APPROVED') {
    if (typeof c.approvalRef !== 'string' || !c.approvalRef.trim()) fail('INVALID_SPEC', 'APPROVED config needs approvalRef');
  } else if (c.status !== 'PROVISIONAL') {
    fail('INVALID_SPEC', 'config.status must be PROVISIONAL or APPROVED');
  }
  if (!isObj(c.cell)) fail('INVALID_SPEC', 'config.cell missing');
  int(c.cell.width, 'cell.width', 1, 2048);
  int(c.cell.height, 'cell.height', 1, 2048);
  if (!isObj(c.anchor)) fail('INVALID_SPEC', 'config.anchor missing');
  int(c.anchor.x, 'anchor.x', 1, c.cell.width - 1);
  int(c.anchor.y, 'anchor.y', 1, c.cell.height - 1);
  if (!isObj(c.camera) || c.camera.projection !== 'orthographic') fail('INVALID_SPEC', 'camera.projection must be orthographic');
  finite(c.camera.elevationDeg, 'camera.elevationDeg', { min: 1, max: 89 });
  finite(c.camera.yawDeg, 'camera.yawDeg', { min: -180, max: 180 });
  finite(c.camera.tileDiamondWidthPx, 'camera.tileDiamondWidthPx', { min: 1, max: 1024 });
  if (!isObj(c.light)) fail('INVALID_SPEC', 'config.light missing');
  vec3(c.light.directionCamera, 'light.directionCamera');
  if (Math.hypot(...c.light.directionCamera) === 0) fail('INVALID_SPEC', 'light.directionCamera must not be zero');
  finite(c.light.directionalIntensity, 'light.directionalIntensity', { min: 0, max: 100 });
  finite(c.light.ambientIntensity, 'light.ambientIntensity', { min: 0, max: 100 });
  if (!Array.isArray(c.directions) || !c.directions.length) fail('INVALID_SPEC', 'config.directions must be a non-empty list');
  const seen = new Set();
  for (const d of c.directions) {
    if (!DIRECTIONS.includes(d) || seen.has(d)) fail('INVALID_SPEC', `bad or duplicate direction ${d}`);
    seen.add(d);
  }
  // Palette/material ramps are not approved (art-consistency guide). The hook stays NOT_RUN and
  // any ramp input is refused rather than silently applied.
  if (c.ramp !== null) fail('RAMP_NOT_APPROVED', 'config.ramp must be null until a palette/ramp policy is approved');
  return c;
}

export function validateModel(m) {
  if (!isObj(m) || m.schema !== 'opensamguk-bake3d-model/0') fail('INVALID_SPEC', 'model.schema must be opensamguk-bake3d-model/0');
  if (typeof m.id !== 'string' || !/^[a-z0-9][a-z0-9-]{0,63}$/.test(m.id)) fail('INVALID_SPEC', 'model.id must be a lowercase slug');
  if (m.kind !== 'test-fixture' && m.kind !== 'model') fail('INVALID_SPEC', 'model.kind must be test-fixture or model');
  if (!isObj(m.materials)) fail('INVALID_SPEC', 'model.materials missing');
  const names = Object.keys(m.materials).sort();
  if (names.length > 254) fail('INVALID_SPEC', 'at most 254 materials');
  for (const n of names) {
    const mat = m.materials[n];
    if (!/^[a-z0-9][a-z0-9-]{0,31}$/.test(n)) fail('INVALID_SPEC', `material name ${n}`);
    if (!isObj(mat) || typeof mat.color !== 'string' || !COLOR.test(mat.color)) fail('INVALID_SPEC', `material ${n}.color must be #rrggbb`);
    if (mat.team !== undefined && typeof mat.team !== 'boolean') fail('INVALID_SPEC', `material ${n}.team must be boolean`);
  }
  if (!Array.isArray(m.parts)) fail('INVALID_SPEC', 'model.parts must be a list');
  m.parts.forEach((p, i) => {
    const at = `parts[${i}]`;
    if (!isObj(p) || !SHAPES.has(p.shape)) fail('INVALID_SPEC', `${at}.shape must be one of ${[...SHAPES].join(', ')}`);
    if (!(p.material in m.materials)) fail('INVALID_SPEC', `${at}.material is not declared`);
    vec3(p.position, `${at}.position`);
    vec3(p.rotationDeg ?? [0, 0, 0], `${at}.rotationDeg`);
    if (p.shape === 'box') vec3(p.size, `${at}.size`, { min: 1e-6 });
    if (p.shape === 'cylinder' || p.shape === 'cone') {
      finite(p.radius, `${at}.radius`, { min: 1e-6 });
      finite(p.height, `${at}.height`, { min: 1e-6 });
      int(p.segments, `${at}.segments`, 3, 128);
    }
    if (p.shape === 'sphere') {
      finite(p.radius, `${at}.radius`, { min: 1e-6 });
      int(p.segments, `${at}.segments`, 3, 128);
    }
  });
  return m;
}

// Material table in stable (sorted-name) order; the id pass writes index + 1.
export function materialTable(model) {
  return Object.keys(model.materials)
    .sort()
    .map((name, index) => ({ name, id: index + 1, color: model.materials[name].color, team: model.materials[name].team === true }));
}

// JSON with recursively sorted keys, two-space indent and a trailing newline.
export function canonicalJson(value) {
  const sort = (v) => {
    if (Array.isArray(v)) return v.map(sort);
    if (isObj(v)) return Object.fromEntries(Object.keys(v).sort().map((k) => [k, sort(v[k])]));
    return v;
  };
  return `${JSON.stringify(sort(value), null, 2)}\n`;
}
