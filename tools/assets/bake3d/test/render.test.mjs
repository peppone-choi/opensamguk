// Real headless-Chromium bakes of the primitive fixtures. Skips (with the reason) when three or
// Playwright Chromium cannot be resolved, unless BAKE3D_REQUIRE_RENDER=1, where that is a failure.
// BAKE3D_EVIDENCE_DIR keeps the outputs, contact sheets and determinism statistics.
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { after, before, test } from 'node:test';
import { createHash } from 'node:crypto';
import { bake } from '../bake.mjs';
import { DIRECTIONS } from '../lib/camera.mjs';
import { diffStats } from '../lib/image.mjs';
import { decodePng, encodePng } from '../lib/png.mjs';
import { openRenderer } from '../lib/render.mjs';
import { contactSheet } from '../lib/sheet.mjs';
import { materialTable } from '../lib/spec.mjs';
import { EXPECTED_SIGNS } from './expect.mjs';

const TOOL = new URL('..', import.meta.url).pathname;
const REQUIRE = process.env.BAKE3D_REQUIRE_RENDER === '1';
const CHROMIUM = process.env.BAKE3D_CHROMIUM_PATH || undefined;
const readJson = (rel) => {
  const bytes = readFileSync(path.join(TOOL, rel));
  return { bytes, json: JSON.parse(bytes) };
};
const CONFIG = readJson('config.provisional.json');
const sha256 = (b) => createHash('sha256').update(b).digest('hex');

let renderer = null;
let skipReason = null;
let evidence = null;
const stats = {};

before(async () => {
  evidence = process.env.BAKE3D_EVIDENCE_DIR || (await mkdtemp(path.join(os.tmpdir(), 'bake3d-')));
  await mkdir(evidence, { recursive: true });
  try {
    renderer = await openRenderer({ executablePath: CHROMIUM });
  } catch (e) {
    if (REQUIRE) throw e;
    skipReason = `render environment unavailable: ${e.message.split('\n')[0]}`;
  }
});

after(async () => {
  await renderer?.close();
  if (evidence) {
    stats.env = renderer?.env ?? null;
    stats.skipReason = skipReason;
    await writeFile(path.join(evidence, 'render-evidence.json'), `${JSON.stringify(stats, null, 2)}\n`);
  }
});

function guard(t) {
  if (skipReason) {
    t.skip(skipReason);
    return true;
  }
  return false;
}

function bakeFixture(name, outName, extra = {}) {
  const model = readJson(`fixtures/${name}.json`);
  return bake({
    config: CONFIG.json,
    configBytes: CONFIG.bytes,
    model: model.json,
    modelBytes: model.bytes,
    outDir: path.join(evidence, outName),
    renderer: extra.ownBrowser ? undefined : renderer,
    executablePath: CHROMIUM,
    debug: extra.debug,
  });
}

// Pixel centroid (cell coordinates) of matid pixels whose id is in `ids`, or of all opaque
// pixels when `ids` is null; optionally weighted to the brightest colour pixels.
function centroid(raw, ids, { brightestFraction } = {}) {
  const { width } = CONFIG.json.cell;
  const lum = [];
  for (let i = 0; i < raw.matid.length; i += 4) {
    if (raw.matid[i + 3] === 0 || (ids && !ids.includes(raw.matid[i]))) continue;
    lum.push([i / 4, 0.2126 * raw.color[i] + 0.7152 * raw.color[i + 1] + 0.0722 * raw.color[i + 2]]);
  }
  let pts = lum;
  if (brightestFraction) {
    const sorted = [...lum].sort((a, b) => b[1] - a[1] || a[0] - b[0]);
    pts = sorted.slice(0, Math.max(1, Math.round(sorted.length * brightestFraction)));
  }
  let sx = 0;
  let sy = 0;
  for (const [p] of pts) {
    sx += p % width;
    sy += Math.floor(p / width);
  }
  return { x: sx / pts.length, y: sy / pts.length, n: pts.length };
}

async function writeSheet(name, raws, pass) {
  const { width, height } = CONFIG.json.cell;
  const sheet = contactSheet(raws.map((r) => r[pass]), { width, height, anchor: CONFIG.json.anchor, scale: 2 });
  await writeFile(path.join(evidence, `${name}.${pass}.sheet.png`), encodePng(sheet.width, sheet.height, sheet.rgba));
}

const angle = (v) => Math.atan2(v[1], v[0]);
const angleDiffDeg = (a, b) => Math.abs((((a - b) * 180) / Math.PI + 540) % 360 - 180);

let first = null;

test('bakes all 8 directions with a consistent manifest, anchors and team mask', async (t) => {
  if (guard(t)) return;
  first = await bakeFixture('primitive-building', 'building-a', { ownBrowser: true });
  const { manifest, outDir, raw } = first;
  assert.equal(manifest.status, 'PROVISIONAL');
  assert.equal(manifest.productArt, false);
  assert.equal(manifest.postprocess.ramp.status, 'NOT_RUN');
  assert.equal(manifest.postprocess.quantize.status, 'NOT_RUN');
  assert.deepEqual(manifest.frames.map((f) => f.direction), DIRECTIONS);
  const teamIds = materialTable(readJson('fixtures/primitive-building.json').json).filter((m) => m.team).map((m) => m.id);
  const { width, height } = CONFIG.json.cell;
  let teamTotal = 0;
  for (const f of manifest.frames) {
    const b = f.bboxInCell;
    assert.ok(b.x > 0 && b.y > 0 && b.x + b.w < width && b.y + b.h < height, `${f.direction} inside cell`);
    assert.equal(f.frame.ax, CONFIG.json.anchor.x - b.x);
    assert.equal(f.frame.ay, CONFIG.json.anchor.y - b.y);
    const decoded = {};
    for (const pass of ['color', 'mask', 'matid']) {
      const bytes = readFileSync(path.join(outDir, f.files[pass].path));
      assert.equal(sha256(bytes), f.files[pass].sha256, `${f.direction}.${pass} hash`);
      decoded[pass] = decodePng(bytes);
      assert.equal(decoded[pass].width, f.frame.width);
      assert.equal(decoded[pass].height, f.frame.height);
    }
    const { color, mask, matid } = decoded;
    let team = 0;
    for (let i = 0; i < mask.rgba.length; i += 4) {
      const isTeam = matid.rgba[i + 3] !== 0 && teamIds.includes(matid.rgba[i]);
      assert.equal(mask.rgba[i + 3] !== 0, isTeam, `${f.direction} mask coverage at ${i / 4}`);
      assert.equal(color.rgba[i + 3] !== 0, matid.rgba[i + 3] !== 0, `${f.direction} pass coverage at ${i / 4}`);
      if (isTeam) {
        team++;
        assert.ok(mask.rgba[i] === mask.rgba[i + 1] && mask.rgba[i + 1] === mask.rgba[i + 2], 'mask is grey');
      }
    }
    assert.equal(team, f.teamPixels);
    teamTotal += team;
  }
  assert.ok(teamTotal > 0, 'team material visible in at least one direction');
  const fragment = JSON.parse(readFileSync(path.join(outDir, 'artpack-units-fragment.json')));
  const dirs = fragment.units['fixture-primitive-building'].actions.idle.directions;
  assert.deepEqual(Object.keys(dirs).sort(), ['ne', 'nw', 'se', 'sw']);
  for (const d of Object.values(dirs)) {
    assert.equal(d.frames.length, d.masks.length);
    assert.equal(d.frames[0].ax, d.masks[0].ax);
    assert.ok(existsSync(path.join(outDir, d.frames[0].file)) && existsSync(path.join(outDir, d.masks[0].file)));
  }
  stats.building = manifest.frames.map((f) => ({ direction: f.direction, frame: f.frame, teamPixels: f.teamPixels }));
  await writeSheet('building', DIRECTIONS.map((d) => raw[d]), 'color');
  await writeSheet('building', DIRECTIONS.map((d) => raw[d]), 'mask');
});

test('a second bake in a fresh browser is byte-identical (tolerance stats recorded)', async (t) => {
  if (guard(t)) return;
  assert.ok(first, 'first bake must have succeeded');
  const second = await bakeFixture('primitive-building', 'building-b', { ownBrowser: true });
  const files = (dir) => readdirSync(dir, { recursive: true }).filter((f) => f.endsWith('.png') || f.endsWith('.json')).sort();
  assert.deepEqual(files(second.outDir), files(first.outDir));
  const perDirection = DIRECTIONS.map((d) => ({ direction: d, ...diffStats(first.raw[d].color, second.raw[d].color) }));
  const differingFiles = files(first.outDir).filter(
    (f) => !readFileSync(path.join(first.outDir, f)).equals(readFileSync(path.join(second.outDir, f))),
  );
  stats.determinism = { perDirection, differingFiles };
  assert.deepEqual(differingFiles, [], `differing outputs: ${JSON.stringify(perDirection)}`);
});

test('the model faces each named direction on screen', async (t) => {
  if (guard(t)) return;
  const { raw } = await bakeFixture('direction-probe', 'direction-probe');
  const markerId = materialTable(readJson('fixtures/direction-probe.json').json).find((m) => m.name === 'marker').id;
  const c = Object.fromEntries(DIRECTIONS.map((d) => [d, centroid(raw[d], [markerId])]));
  const mean = { x: DIRECTIONS.reduce((s, d) => s + c[d].x, 0) / 8, y: DIRECTIONS.reduce((s, d) => s + c[d].y, 0) / 8 };
  stats.direction = {};
  for (const d of DIRECTIONS) {
    assert.ok(c[d].n > 0, `${d}: marker visible`);
    const off = [c[d].x - mean.x, c[d].y - mean.y];
    stats.direction[d] = off.map((v) => Number(v.toFixed(2)));
    EXPECTED_SIGNS[d].forEach((s, i) => {
      if (s === 0) assert.ok(Math.abs(off[i]) < 3, `${d} axis ${i} offset ${off[i]} should be ~0`);
      else assert.ok(Math.sign(off[i]) === s && Math.abs(off[i]) >= 3, `${d} axis ${i} offset ${off[i]} sign ${s}`);
    });
  }
  await writeSheet('direction-probe', DIRECTIONS.map((d) => raw[d]), 'color');
});

test('the lit side stays put on screen in every direction (light is fixed, not rotated)', async (t) => {
  if (guard(t)) return;
  const [lx, ly] = CONFIG.json.light.directionCamera;
  const expected = angle([lx, -ly]);
  const measure = (raw) =>
    DIRECTIONS.map((d) => {
      const all = centroid(raw[d], null);
      const bright = centroid(raw[d], null, { brightestFraction: 0.1 });
      return angle([bright.x - all.x, bright.y - all.y]);
    });
  const fixed = measure((await bakeFixture('light-probe-sphere', 'light-fixed')).raw);
  const follow = measure((await bakeFixture('light-probe-sphere', 'light-follow', { debug: { lightFollowsModel: true } })).raw);
  const spread = (as) => Math.max(...as.flatMap((a) => as.map((b) => angleDiffDeg(a, b))));
  stats.light = {
    expectedDeg: (expected * 180) / Math.PI,
    fixedDeg: fixed.map((a) => Number(((a * 180) / Math.PI).toFixed(2))),
    fixedSpreadDeg: spread(fixed),
    controlFollowDeg: follow.map((a) => Number(((a * 180) / Math.PI).toFixed(2))),
    controlSpreadDeg: spread(follow),
  };
  assert.ok(spread(fixed) <= 8, `fixed light spread ${spread(fixed)} deg`);
  for (const a of fixed) assert.ok(angleDiffDeg(a, expected) <= 25, `bright side ${a} vs expected ${expected}`);
  // Negative control: a light that rotates with the model must fail the same check.
  assert.ok(spread(follow) > 45, `control spread ${spread(follow)} deg should be large`);
});

test('empty and clipped renders fail without leaving output; existing output is refused', async (t) => {
  if (guard(t)) return;
  await assert.rejects(bakeFixture('empty', 'empty-out'), (e) => e.code === 'EMPTY');
  await assert.rejects(bakeFixture('oversize', 'oversize-out'), (e) => e.code === 'CLIPPED');
  for (const name of ['empty-out', 'oversize-out']) assert.equal(existsSync(path.join(evidence, name)), false);
  assert.deepEqual(readdirSync(evidence).filter((f) => f.includes('.staging-')), []);
  await mkdir(path.join(evidence, 'occupied'));
  await assert.rejects(bakeFixture('primitive-building', 'occupied'), (e) => e.code === 'OUTPUT_EXISTS');
});
