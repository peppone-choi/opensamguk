#!/usr/bin/env node
// BAKE-0: primitive 3D model -> per-direction transparent PNG frames + bake manifest.
// Usage: node tools/assets/bake3d/bake.mjs --config <config.json> --model <model.json> --out <new dir>
//        [--chromium <executable>]
import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { directionYawDeg, pixelsPerUnit } from './lib/camera.mjs';
import { alphaBBox, crop, teamMask, touchesCellEdge } from './lib/image.mjs';
import { encodePng } from './lib/png.mjs';
import { openRenderer } from './lib/render.mjs';
import { BakeError, canonicalJson, materialTable, validateConfig, validateModel } from './lib/spec.mjs';

export const TOOL_VERSION = '0.1.0';
// The A2 tactical pack contract draws only the four diagonal directions.
const ARTPACK_DIRECTIONS = ['sw', 'nw', 'ne', 'se'];

const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

function sameAlpha(a, b) {
  for (let i = 3; i < a.length; i += 4) if ((a[i] === 0) !== (b[i] === 0)) return false;
  return true;
}

async function bakeInto(dir, { config, model, configBytes, modelBytes, renderer, debug }) {
  const materials = materialTable(model);
  const teamIds = materials.filter((m) => m.team).map((m) => m.id);
  const { width, height } = config.cell;
  const frames = [];
  const raw = {};
  await mkdir(path.join(dir, 'frames'));
  for (const direction of config.directions) {
    const r = await renderer.render({ config, model, materials, direction, debug });
    const bbox = alphaBBox(width, height, r.color);
    if (!bbox) throw new BakeError('EMPTY', `direction ${direction} rendered no opaque pixel`);
    if (touchesCellEdge(width, height, bbox)) {
      throw new BakeError('CLIPPED', `direction ${direction} reaches the cell edge (bbox ${JSON.stringify(bbox)}); enlarge cell or move anchor`);
    }
    if (!sameAlpha(r.color, r.matid)) throw new BakeError('PASS_MISMATCH', `direction ${direction} colour and material-id coverage differ`);
    const ax = config.anchor.x - bbox.x;
    const ay = config.anchor.y - bbox.y;
    if (ax < 0 || ay < 0 || ax > bbox.w || ay > bbox.h) {
      throw new BakeError('ANCHOR_OUTSIDE_FRAME', `direction ${direction} foot anchor (${ax}, ${ay}) lies outside the ${bbox.w}x${bbox.h} frame`);
    }
    const mask = teamMask(r.color, r.matid, teamIds);
    const files = {};
    let teamPixels = 0;
    let opaquePixels = 0;
    for (let i = 3; i < mask.length; i += 4) {
      if (mask[i]) teamPixels++;
      if (r.color[i]) opaquePixels++;
    }
    for (const [pass, pixels] of [['color', r.color], ['mask', mask], ['matid', r.matid]]) {
      const png = encodePng(bbox.w, bbox.h, crop(width, pixels, bbox));
      const rel = `frames/${direction}.${pass}.png`;
      await writeFile(path.join(dir, rel), png);
      files[pass] = { path: rel, sha256: sha256(png) };
    }
    raw[direction] = { color: r.color, matid: r.matid, mask, bbox };
    frames.push({
      direction,
      yawDeg: directionYawDeg(config.camera, direction),
      bboxInCell: bbox,
      frame: { width: bbox.w, height: bbox.h, ax, ay },
      opaquePixels,
      teamPixels,
      files,
    });
  }

  const manifest = {
    schema: 'opensamguk-bake3d-manifest/0',
    status: config.status,
    approvalRef: config.approvalRef ?? null,
    productArt: false,
    tool: { name: 'opensamguk-bake3d', version: TOOL_VERSION },
    env: renderer.env,
    source: {
      model: { id: model.id, kind: model.kind, sha256: sha256(modelBytes) },
      config: { sha256: sha256(configBytes) },
    },
    cell: config.cell,
    anchorInCell: config.anchor,
    camera: { ...config.camera, pixelsPerUnit: pixelsPerUnit(config.camera) },
    light: config.light,
    materials: materials.map(({ name, id, team }) => ({ name, id, team })),
    postprocess: {
      ramp: { status: 'NOT_RUN', reason: 'palette and material ramps are not approved; colours are unchanged' },
      quantize: { status: 'NOT_RUN', reason: 'no approved palette; no colour reduction applied' },
      shadow: { status: 'NOT_RUN', reason: 'ground shadow is not rendered by BAKE-0' },
    },
    frames,
  };
  await writeFile(path.join(dir, 'manifest.json'), canonicalJson(manifest));

  const dirs = {};
  for (const f of frames.filter((x) => ARTPACK_DIRECTIONS.includes(x.direction))) {
    const anchor = { ax: f.frame.ax, ay: f.frame.ay };
    dirs[f.direction] = { frames: [{ file: f.files.color.path, ...anchor }], masks: [{ file: f.files.mask.path, ...anchor }] };
  }
  const fragment = {
    note: 'Fragment in tools/assets/artpack input-spec form (units section only). Not a complete artpack spec: terrain, shadow declaration and final art are outside BAKE-0.',
    status: config.status,
    units: { [model.id]: { actions: { idle: { frames: 1, directions: dirs } } } },
    provenance: [{ source: 'procedural', note: `opensamguk-bake3d ${TOOL_VERSION} ${config.status} bake of ${model.kind} ${model.id}` }],
  };
  await writeFile(path.join(dir, 'artpack-units-fragment.json'), canonicalJson(fragment));
  return { manifest, raw };
}

// Bakes into a staging directory and publishes it to `outDir` only when every direction
// passed its checks. Refuses an existing `outDir`; never leaves a partial output behind.
export async function bake({ config, model, configBytes, modelBytes, outDir, renderer, executablePath, debug }) {
  validateConfig(config);
  validateModel(model);
  const target = path.resolve(outDir);
  if (existsSync(target)) throw new BakeError('OUTPUT_EXISTS', `${outDir} already exists`);
  const staging = `${target}.staging-${process.pid}`;
  await mkdir(staging);
  const own = !renderer;
  try {
    if (own) renderer = await openRenderer({ executablePath });
    const result = await bakeInto(staging, { config, model, configBytes, modelBytes, renderer, debug });
    await rename(staging, target);
    return { ...result, outDir: target };
  } catch (e) {
    await rm(staging, { recursive: true, force: true });
    throw e;
  } finally {
    if (own && renderer) await renderer.close();
  }
}

export async function bakeFiles({ configPath, modelPath, ...rest }) {
  const configBytes = await readFile(configPath);
  const modelBytes = await readFile(modelPath);
  return bake({ config: JSON.parse(configBytes), model: JSON.parse(modelBytes), configBytes, modelBytes, ...rest });
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 2) {
    const k = argv[i];
    const v = argv[i + 1];
    if (!['--config', '--model', '--out', '--chromium'].includes(k) || v === undefined) {
      throw new Error(`usage: bake.mjs --config <file> --model <file> --out <new dir> [--chromium <exe>] (bad argument ${k})`);
    }
    out[k.slice(2)] = v;
  }
  if (!out.config || !out.model || !out.out) throw new Error('--config, --model and --out are required');
  return out;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const a = parseArgs(process.argv.slice(2));
    const { manifest, outDir } = await bakeFiles({
      configPath: a.config,
      modelPath: a.model,
      outDir: a.out,
      executablePath: a.chromium ?? process.env.BAKE3D_CHROMIUM_PATH,
    });
    console.log(`${manifest.status} bake of ${manifest.source.model.id}: ${manifest.frames.length} directions -> ${outDir}`);
  } catch (e) {
    console.error(e.message);
    process.exitCode = 1;
  }
}
