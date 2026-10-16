// Pure tests (no browser): camera math, PNG codec, pixel helpers, spec validation.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { DIRECTIONS, directionYawDeg, facingWorld, lightWorld, projectToPixel, screenVector } from '../lib/camera.mjs';
import { alphaBBox, crop, diffStats, teamMask, touchesCellEdge } from '../lib/image.mjs';
import { decodePng, encodePng } from '../lib/png.mjs';
import { BakeError, canonicalJson, materialTable, validateConfig, validateModel } from '../lib/spec.mjs';
import { EXPECTED_SIGNS } from './expect.mjs';

const CONFIG = JSON.parse(readFileSync(new URL('../config.provisional.json', import.meta.url)));
const BUILDING = JSON.parse(readFileSync(new URL('../fixtures/primitive-building.json', import.meta.url)));

const clone = (v) => JSON.parse(JSON.stringify(v));

test('shipped config is PROVISIONAL with no ramp and validates', () => {
  validateConfig(CONFIG);
  assert.equal(CONFIG.status, 'PROVISIONAL');
  assert.equal(CONFIG.ramp, null);
});

test('world origin projects exactly onto the anchor pixel', () => {
  assert.deepEqual(projectToPixel(CONFIG, [0, 0, 0]), [CONFIG.anchor.x, CONFIG.anchor.y]);
});

test('elevation 30 gives a 2:1 ground diamond of the configured width', () => {
  const pts = [[0.5, 0, 0.5], [-0.5, 0, 0.5], [-0.5, 0, -0.5], [0.5, 0, -0.5]].map((p) => projectToPixel(CONFIG, p));
  const xs = pts.map((p) => p[0]);
  const ys = pts.map((p) => p[1]);
  const w = Math.max(...xs) - Math.min(...xs);
  const h = Math.max(...ys) - Math.min(...ys);
  assert.ok(Math.abs(w - CONFIG.camera.tileDiamondWidthPx) < 1e-9, `width ${w}`);
  assert.ok(Math.abs(w / h - 2) < 1e-9, `ratio ${w / h}`);
});

test('each named direction faces the expected way on screen', () => {
  for (const d of DIRECTIONS) {
    const [x, y] = screenVector(CONFIG, facingWorld(CONFIG.camera, d));
    const [sx, sy] = EXPECTED_SIGNS[d];
    for (const [v, s, axis] of [[x, sx, 'x'], [y, sy, 'y']]) {
      if (s === 0) assert.ok(Math.abs(v) < 1e-9, `${d} ${axis}=${v}`);
      else assert.equal(Math.sign(v), s, `${d} ${axis}=${v}`);
    }
  }
  assert.equal(new Set(DIRECTIONS.map((d) => directionYawDeg(CONFIG.camera, d))).size, 8);
});

test('light vector is fixed by config and independent of direction', () => {
  const L = lightWorld(CONFIG);
  const [x, y] = screenVector(CONFIG, L);
  const [cx, cy] = CONFIG.light.directionCamera;
  const n = Math.hypot(...CONFIG.light.directionCamera);
  assert.ok(Math.abs(x - cx / n) < 1e-9 && Math.abs(y + cy / n) < 1e-9);
});

test('PNG encode is byte-deterministic and round-trips', () => {
  const rgba = new Uint8Array(5 * 3 * 4).map((_, i) => (i * 37) & 0xff);
  const a = encodePng(5, 3, rgba);
  const b = encodePng(5, 3, rgba.slice());
  assert.ok(a.equals(b));
  const d = decodePng(a);
  assert.equal(d.width, 5);
  assert.equal(d.height, 3);
  assert.deepEqual(d.rgba, rgba);
  const broken = Buffer.from(a);
  broken[broken.length - 20] ^= 0xff;
  assert.throws(() => decodePng(broken), /CRC|PNG|incorrect|invalid/);
});

test('alpha bbox, empty image and cell-edge detection', () => {
  const W = 6;
  const H = 5;
  const img = new Uint8Array(W * H * 4);
  assert.equal(alphaBBox(W, H, img), null);
  img[(2 * W + 1) * 4 + 3] = 255;
  img[(3 * W + 3) * 4 + 3] = 1;
  const bbox = alphaBBox(W, H, img);
  assert.deepEqual(bbox, { x: 1, y: 2, w: 3, h: 2 });
  assert.equal(touchesCellEdge(W, H, bbox), false);
  assert.equal(touchesCellEdge(W, H, { x: 0, y: 2, w: 1, h: 1 }), true);
  assert.equal(touchesCellEdge(W, H, { x: 2, y: 2, w: 4, h: 1 }), true);
  assert.equal(touchesCellEdge(W, H, { x: 2, y: 2, w: 1, h: 3 }), true);
  const c = crop(W, img, bbox);
  assert.equal(c.length, 3 * 2 * 4);
  assert.equal(c[3], 255);
});

test('team mask is equal-channel grey exactly over team material pixels', () => {
  const color = new Uint8Array([200, 100, 50, 255, 10, 20, 30, 255, 0, 0, 0, 0]);
  const matid = new Uint8Array([2, 0, 0, 255, 1, 0, 0, 255, 0, 0, 0, 0]);
  const m = teamMask(color, matid, [2]);
  const g = Math.round(0.2126 * 200 + 0.7152 * 100 + 0.0722 * 50);
  assert.deepEqual([...m], [g, g, g, 255, 0, 0, 0, 0, 0, 0, 0, 0]);
});

test('diffStats reports pixel count and max channel difference', () => {
  const a = new Uint8Array([0, 0, 0, 255, 9, 9, 9, 255]);
  const b = new Uint8Array([0, 0, 0, 255, 9, 12, 9, 255]);
  assert.deepEqual(diffStats(a, b), { comparable: true, diffPixels: 1, maxAbsDiff: 3 });
  assert.deepEqual(diffStats(a, a), { comparable: true, diffPixels: 0, maxAbsDiff: 0 });
});

test('config validation refuses ramps, unapproved APPROVED and bad values', () => {
  const ramp = { ...clone(CONFIG), ramp: { wall: ['#000000'] } };
  assert.throws(() => validateConfig(ramp), (e) => e instanceof BakeError && e.code === 'RAMP_NOT_APPROVED');
  assert.throws(() => validateConfig({ ...clone(CONFIG), status: 'APPROVED' }), /approvalRef/);
  assert.throws(() => validateConfig({ ...clone(CONFIG), status: 'FINAL' }), /status/);
  const badDir = { ...clone(CONFIG), directions: ['s', 's'] };
  assert.throws(() => validateConfig(badDir), /direction/);
  const badLight = clone(CONFIG);
  badLight.light.directionCamera = [0, 0, 0];
  assert.throws(() => validateConfig(badLight), /light/);
  const nan = clone(CONFIG);
  nan.camera.elevationDeg = Number.NaN;
  assert.throws(() => validateConfig(nan), /elevationDeg/);
});

test('model validation refuses unknown shapes/materials and orders materials stably', () => {
  validateModel(BUILDING);
  const bad = clone(BUILDING);
  bad.parts[0].shape = 'torus';
  assert.throws(() => validateModel(bad), /shape/);
  const missing = clone(BUILDING);
  missing.parts[0].material = 'gold';
  assert.throws(() => validateModel(missing), /material/);
  const table = materialTable(BUILDING);
  assert.deepEqual(table.map((m) => m.name), ['banner', 'door', 'pole', 'roof', 'wall']);
  assert.deepEqual(table.filter((m) => m.team).map((m) => m.id), [1]);
});

test('canonical JSON sorts keys recursively', () => {
  assert.equal(canonicalJson({ b: 1, a: { d: 2, c: [3, { f: 1, e: 0 }] } }), '{\n  "a": {\n    "c": [\n      3,\n      {\n        "e": 0,\n        "f": 1\n      }\n    ],\n    "d": 2\n  },\n  "b": 1\n}\n');
});
