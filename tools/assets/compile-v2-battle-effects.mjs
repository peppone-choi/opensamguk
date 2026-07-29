#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { promises as fs } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url));
const REPOSITORY_ROOT = resolve(SCRIPT_DIRECTORY, '..', '..');
const SOURCE_ROOT = resolve(REPOSITORY_ROOT, 'assets/battle/v2/effects/source');
const BUILD_ROOT = resolve(REPOSITORY_ROOT, 'build/sprite-gen/v2-battle-effects');
const ATLAS_ROOT = resolve(REPOSITORY_ROOT, 'assets/battle/v2/effects/atlases');
const RECEIPT_ROOT = resolve(REPOSITORY_ROOT, 'assets/battle/v2/effects/receipts');
const FINAL_MANIFEST_PATH = resolve(REPOSITORY_ROOT, 'assets/battle/v2/effects/manifest.json');

const DEFAULT_SPRITE_GEN_ROOT = '/Users/apple/.codex/skills/sprite-gen';
const SPRITE_GEN_ROOT = resolve(process.env.SPRITE_GEN_ROOT || DEFAULT_SPRITE_GEN_ROOT);
const SPRITE_PYTHON = resolve(SPRITE_GEN_ROOT, '.venv/bin/python');
const PREPARE_SCRIPT = resolve(SPRITE_GEN_ROOT, 'scripts/prepare_sprite_run.py');
const EXTRACT_SCRIPT = resolve(SPRITE_GEN_ROOT, 'scripts/extract_sprite_row_frames.py');
const COMPOSE_SCRIPT = resolve(SPRITE_GEN_ROOT, 'scripts/compose_sprite_atlas.py');

const PNG_SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const CELL_SIZE = 192;
const SAFE_MARGIN = 18;
const EFFECT_COUNT = 16;
const MIN_USED_PIXELS = 17;

const EFFECT_FIT = Object.freeze({
  resample: 'kcentroid',
  align_x: 'centroid',
  align_y: 'center',
  pixel_unfake: true,
  logical_height: 96,
  palette_size: 48,
  outline: true,
});

const SANITIZE_GUIDE_LINES_PYTHON = [
  'import json',
  'import os',
  'import sys',
  'from PIL import Image',
  '',
  'source_path, destination_path, chroma_hex = sys.argv[1:4]',
  'chroma_hex = chroma_hex.lstrip("#")',
  'if len(chroma_hex) != 6:',
  '    raise ValueError("chroma hex must contain exactly six digits")',
  'chroma_rgb = tuple(int(chroma_hex[index:index + 2], 16) for index in range(0, 6, 2))',
  'source = Image.open(source_path).convert("RGBA")',
  'source_width, source_height = source.size',
  'source_pixels = source.load()',
  '',
  'def near_chroma(pixel):',
  '    red, green, blue, alpha = pixel',
  '    return alpha >= 200 and max(abs(red - chroma_rgb[0]), abs(green - chroma_rgb[1]), abs(blue - chroma_rgb[2])) <= 96',
  '',
  'panel_left, panel_top = source_width, source_height',
  'panel_right, panel_bottom = -1, -1',
  'for y in range(source_height):',
  '    for x in range(source_width):',
  '        if near_chroma(source_pixels[x, y]):',
  '            panel_left = min(panel_left, x)',
  '            panel_top = min(panel_top, y)',
  '            panel_right = max(panel_right, x)',
  '            panel_bottom = max(panel_bottom, y)',
  'if panel_right < panel_left or panel_bottom < panel_top:',
  '    raise ValueError("could not locate the declared chroma panel")',
  '',
  '# Image generators often put the actual strip inside a large white canvas.',
  '# Crop only to the declared chroma panel before inspecting separator lines so',
  '# that exterior white margins cannot be misclassified as every guide column.',
  'image = source.crop((panel_left, panel_top, panel_right + 1, panel_bottom + 1))',
  'image.info.clear()',
  'width, height = image.size',
  'pixels = image.load()',
  '',
  'def near_white(pixel):',
  '    red, green, blue, alpha = pixel',
  '    return alpha >= 200 and red >= 240 and green >= 240 and blue >= 240',
  '',
  'white_rows = [',
  '    y for y in range(height)',
  '    if sum(1 for x in range(width) if near_white(pixels[x, y])) / width >= 0.60',
  ']',
  '',
  'def expand_bands(indices, limit):',
  '    return {',
  '        candidate',
  '        for index in indices',
  '        for candidate in range(max(0, index - 2), min(limit, index + 3))',
  '    }',
  '',
  'row_bands = expand_bands(white_rows, height)',
  '# Evaluate vertical guides after removing qualifying full-row bands. A white',
  '# canvas margin can otherwise make every column appear to be a guide line.',
  'white_columns = [',
  '    x for x in range(width)',
  '    if sum(1 for y in range(height) if y not in row_bands and near_white(pixels[x, y])) / height >= 0.30',
  ']',
  'column_bands = expand_bands(white_columns, width)',
  'replaced_pixels = 0',
  'for y in range(height):',
  '    for x in range(width):',
  '        if y in row_bands or x in column_bands:',
  '            pixels[x, y] = (chroma_rgb[0], chroma_rgb[1], chroma_rgb[2], 255)',
  '            replaced_pixels += 1',
  '',
  'temporary_path = destination_path + ".tmp-" + str(os.getpid())',
  'try:',
  '    image.save(temporary_path, format="PNG", optimize=False, compress_level=9)',
  '    os.replace(temporary_path, destination_path)',
  'finally:',
  '    if os.path.exists(temporary_path):',
  '        os.unlink(temporary_path)',
  'print(json.dumps({',
  '    "panelBBox": [panel_left, panel_top, panel_right, panel_bottom],',
  '    "sourceSize": [source_width, source_height],',
  '    "detectedRows": white_rows,',
  '    "detectedColumns": white_columns,',
  '    "rowBandPixels": len(row_bands),',
  '    "columnBandPixels": len(column_bands),',
  '    "replacedPixels": replaced_pixels,',
  '}, sort_keys=True))',
].join('\n');

const EFFECT_SETS = Object.freeze([
  {
    id: 'physical',
    chroma: { name: 'magenta', hex: '#FF00FF' },
    styleAnchor: 'physical-style-anchor.png',
    description: 'Core visual-only physical battle effects: projectiles, impacts, debris, dust, splashes, collapse, and morale break.',
    style:
      'Match the attached physical effect style anchor for pixel density, hard outlines, palette, material readability, and animation scale. ' +
      'Each state is one clean standalone battle effect on the requested chroma background; never add text, UI, scenery, characters, or unrelated effects.',
    states: [
      effect('arrow_flight', 4, 12, true, 'an arrow travelling cleanly through the air'),
      effect('crossbow_bolt_flight', 4, 12, true, 'a crossbow bolt travelling cleanly through the air'),
      effect('stone_flight', 4, 10, true, 'a thrown stone travelling cleanly through the air'),
      effect('metal_impact', 6, 16, false, 'a brief metallic impact burst'),
      effect('wood_impact', 6, 14, false, 'a brief splintering wood impact burst'),
      effect('earth_impact', 6, 14, false, 'a brief earth and grit impact burst'),
      effect('dust_step', 6, 12, false, 'a small infantry footstep dust puff'),
      effect('cavalry_dust', 6, 12, false, 'a stronger cavalry hoof dust plume'),
      effect('mud_splash', 6, 12, false, 'a compact mud splash'),
      effect('water_splash', 6, 14, false, 'a compact water splash'),
      effect('wall_collapse', 8, 12, false, 'a compact masonry wall collapse with dust'),
      effect('morale_break', 6, 10, false, 'a restrained morale break visual cue'),
    ],
  },
  {
    id: 'fire',
    chroma: { name: 'green', hex: '#00FF00' },
    styleAnchor: 'fire-style-anchor.png',
    description: 'Core visual-only fire battle effects: burning arrows, flame, smoke, embers, and sparks.',
    style:
      'Match the attached fire effect style anchor for pixel density, hard outlines, palette, flame readability, and animation scale. ' +
      'Each state is one clean standalone battle effect on the requested chroma background; never add text, UI, scenery, characters, or unrelated effects.',
    states: [
      effect('fire_arrow_flight', 4, 12, true, 'a burning arrow travelling cleanly through the air'),
      effect('small_fire', 8, 10, true, 'a compact looping ground fire'),
      effect('smoke_plume', 8, 8, true, 'a compact looping smoke plume'),
      effect('ember_burst', 6, 14, false, 'a brief burst of embers and sparks'),
    ],
  },
]);

function effect(id, frames, fps, loop, action) {
  return Object.freeze({ id, frames, fps, loop, action });
}

function usage() {
  return [
    'Usage: node tools/assets/compile-v2-battle-effects.mjs [options]',
    '',
    'Compile supplied component-row battle-effect source strips into clean alpha atlases.',
    '',
    'Options:',
    '  --force                 Rebuild non-empty sprite-gen run directories',
    '  --dry-run               Report required source inputs without writing files',
    '  --prepare-only          Write selected sprite-gen guides/prompts from style anchors, then stop',
    '  --verify-no-fallback    Add an isolated no-slot-fallback preflight; publication also forbids fallback',
    '  --only-set <name[,..]>  Compile physical and/or fire; repeatable',
    '  --help                  Show this message',
    '',
    'The compiler uses only $SPRITE_GEN_ROOT/.venv/bin/python for sprite-gen stages.',
    '',
  ].join('\n');
}

function parseArgs(argv) {
  const options = { force: false, dryRun: false, prepareOnly: false, verifyNoFallback: false, onlySets: [] };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--help') return { help: true };
    if (argument === '--force') {
      options.force = true;
      continue;
    }
    if (argument === '--dry-run') {
      options.dryRun = true;
      continue;
    }
    if (argument === '--prepare-only') {
      options.prepareOnly = true;
      continue;
    }
    if (argument === '--verify-no-fallback') {
      options.verifyNoFallback = true;
      continue;
    }
    if (argument === '--only-set') {
      const value = argv[index + 1];
      if (!value || value.startsWith('--')) throw new Error('--only-set requires a value');
      index += 1;
      const names = value.split(',').map((name) => name.trim()).filter(Boolean);
      if (names.length === 0) throw new Error('--only-set requires at least one set name');
      options.onlySets.push(...names);
      continue;
    }
    throw new Error('Unknown option: ' + argument);
  }
  return options;
}

function relativePath(path) {
  return relative(REPOSITORY_ROOT, path).replaceAll('\\', '/');
}

function pathForSource(fileName) {
  return resolve(SOURCE_ROOT, fileName);
}

function runDirectoryFor(effectSet) {
  return resolve(BUILD_ROOT, effectSet.id);
}

function requestFor(effectSet) {
  return {
    version: 1,
    kind: 'sprite-gen-request',
    engine: 'component-row',
    cell: { size: CELL_SIZE, safe_margin: SAFE_MARGIN },
    states: Object.fromEntries(
      effectSet.states.map((state) => [
        state.id,
        {
          frames: state.frames,
          fps: state.fps,
          loop: state.loop,
          action: state.action,
        },
      ]),
    ),
    fit: { ...EFFECT_FIT },
    style: effectSet.style,
    chroma: { mode: 'rgb' },
    motion_phase_guides: false,
  };
}

async function readPng(path) {
  const data = await fs.readFile(path);
  if (data.length < 33 || !data.subarray(0, 8).equals(PNG_SIGNATURE)) {
    throw new Error(relativePath(path) + ' is not a PNG');
  }
  if (data.readUInt32BE(8) !== 13 || data.subarray(12, 16).toString('ascii') !== 'IHDR') {
    throw new Error(relativePath(path) + ' has no valid IHDR');
  }
  const width = data.readUInt32BE(16);
  const height = data.readUInt32BE(20);
  const colorType = data[25];
  if (width < 1 || height < 1) throw new Error(relativePath(path) + ' has invalid dimensions');
  return {
    path: relativePath(path),
    sha256: createHash('sha256').update(data).digest('hex'),
    bytes: data.length,
    width,
    height,
    colorType,
  };
}

async function readFileFingerprint(path, publishedPath) {
  const data = await fs.readFile(path);
  return {
    path: publishedPath,
    sha256: createHash('sha256').update(data).digest('hex'),
    bytes: data.length,
  };
}

async function compilerProvenance() {
  return {
    script: await readFileFingerprint(
      fileURLToPath(import.meta.url),
      relativePath(fileURLToPath(import.meta.url)),
    ),
    toolchain: {
      root: 'sprite-gen',
      prepare: await readFileFingerprint(PREPARE_SCRIPT, 'scripts/prepare_sprite_run.py'),
      extract: await readFileFingerprint(EXTRACT_SCRIPT, 'scripts/extract_sprite_row_frames.py'),
      compose: await readFileFingerprint(COMPOSE_SCRIPT, 'scripts/compose_sprite_atlas.py'),
    },
  };
}

async function readJson(path, label) {
  let parsed;
  try {
    parsed = JSON.parse(await fs.readFile(path, 'utf8'));
  } catch (error) {
    throw new Error(label + ' is not valid JSON: ' + error.message);
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error(label + ' must be a JSON object');
  }
  return parsed;
}

function ensureEmptyErrors(value, label) {
  if (!Array.isArray(value)) throw new Error(label + ' must contain an errors array');
  if (value.length > 0) throw new Error(label + ' reported errors: ' + value.join('; '));
}

function expectEqual(actual, expected, label) {
  if (actual !== expected) {
    throw new Error(label + ' expected ' + String(expected) + ', found ' + String(actual));
  }
}

function requireObject(value, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(label + ' must be an object');
  }
  return value;
}

function cloneJson(value) {
  return JSON.parse(JSON.stringify(value));
}

function errorTail(result) {
  const output = [result.stdout, result.stderr].filter(Boolean).join('\n').trim();
  if (!output) return '';
  return '\n' + output.slice(-4000);
}

function runProcess(executable, args) {
  return new Promise((resolveProcess) => {
    let stdout = '';
    let stderr = '';
    let settled = false;
    const finish = (value) => {
      if (settled) return;
      settled = true;
      resolveProcess(value);
    };
    let child;
    try {
      child = spawn(executable, args, { cwd: REPOSITORY_ROOT, shell: false });
    } catch (error) {
      finish({ code: null, error, stdout, stderr });
      return;
    }
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    child.on('error', (error) => finish({ code: null, error, stdout, stderr }));
    child.on('close', (code) => finish({ code, stdout, stderr }));
  });
}

async function runSpriteGen(stage, script, args) {
  await runSpriteGenPython(stage, [script, ...args]);
}

async function runSpriteGenPython(stage, args) {
  const result = await runProcess(SPRITE_PYTHON, args);
  if (result.error || result.code !== 0) {
    const reason = result.error ? result.error.message : 'exit code ' + String(result.code);
    throw new Error(stage + ' failed: ' + reason + errorTail(result));
  }
  return result.stdout;
}

async function copyAtomically(source, destination) {
  await fs.mkdir(dirname(destination), { recursive: true });
  const temporary = destination + '.tmp-' + process.pid + '-' + Math.random().toString(16).slice(2);
  try {
    await fs.copyFile(source, temporary);
    await fs.rename(temporary, destination);
  } finally {
    await fs.rm(temporary, { force: true });
  }
}

async function sanitizeGuideLinesIntoRunRaw(source, destination, effectSet) {
  const stdout = await runSpriteGenPython(effectSet.id + ' guide-line sanitation', [
    '-c',
    SANITIZE_GUIDE_LINES_PYTHON,
    source,
    destination,
    effectSet.chroma.hex,
  ]);
  let report;
  try {
    report = JSON.parse(stdout.trim());
  } catch (error) {
    throw new Error(effectSet.id + ' guide-line sanitation did not emit JSON: ' + error.message);
  }
  for (const key of ['detectedRows', 'detectedColumns']) {
    if (!Array.isArray(report[key])) {
      throw new Error(effectSet.id + ' guide-line sanitation report is missing ' + key);
    }
  }
  if (!Array.isArray(report.panelBBox) || report.panelBBox.length !== 4) {
    throw new Error(effectSet.id + ' guide-line sanitation report is missing panelBBox');
  }
  for (const key of ['rowBandPixels', 'columnBandPixels', 'replacedPixels']) {
    if (!Number.isSafeInteger(report[key]) || report[key] < 0) {
      throw new Error(effectSet.id + ' guide-line sanitation report has invalid ' + key);
    }
  }
  return report;
}

async function writeJsonAtomically(destination, value) {
  await fs.mkdir(dirname(destination), { recursive: true });
  const temporary = destination + '.tmp-' + process.pid + '-' + Math.random().toString(16).slice(2);
  try {
    await fs.writeFile(temporary, JSON.stringify(value, null, 2) + '\n', 'utf8');
    await fs.rename(temporary, destination);
  } finally {
    await fs.rm(temporary, { force: true });
  }
}

async function writeTextAtomically(destination, text) {
  await fs.mkdir(dirname(destination), { recursive: true });
  const temporary = destination + '.tmp-' + process.pid + '-' + Math.random().toString(16).slice(2);
  try {
    await fs.writeFile(temporary, text, 'utf8');
    await fs.rename(temporary, destination);
  } finally {
    await fs.rm(temporary, { force: true });
  }
}

async function inspectFile(path, label) {
  try {
    return { label, path, png: await readPng(path), error: null };
  } catch (error) {
    return { label, path, png: null, error: error.message };
  }
}

async function preflightSet(effectSet) {
  const anchor = await inspectFile(pathForSource(effectSet.styleAnchor), effectSet.id + ' style anchor');
  const sources = await Promise.all(
    effectSet.states.map((state) => inspectFile(pathForSource(state.id + '.png'), effectSet.id + '/' + state.id)),
  );
  return {
    effectSet,
    anchor,
    sources,
    ok: !anchor.error && sources.every((source) => !source.error),
  };
}

async function preflightStyleAnchor(effectSet) {
  const anchor = await inspectFile(pathForSource(effectSet.styleAnchor), effectSet.id + ' style anchor');
  return { effectSet, anchor, ok: !anchor.error };
}

function preflightMessages(preflight) {
  const messages = [];
  if (preflight.anchor.error) {
    messages.push(preflight.anchor.label + ': ' + relativePath(preflight.anchor.path) + ' (' + preflight.anchor.error + ')');
  }
  for (const source of preflight.sources) {
    if (source.error) {
      messages.push(source.label + ': ' + relativePath(source.path) + ' (' + source.error + ')');
    }
  }
  return messages;
}

async function assertPreflightInputStable(input) {
  const expected = input.png;
  if (!expected) throw new Error(input.label + ' has no preflight PNG metadata');

  let current;
  try {
    current = await readPng(input.path);
  } catch (error) {
    throw new Error(input.label + ' changed or became unreadable during compilation: ' + error.message);
  }

  const changed = ['sha256', 'bytes', 'width', 'height', 'colorType'].filter((key) => current[key] !== expected[key]);
  if (changed.length > 0) {
    throw new Error(
      input.label +
        ' changed during compilation (' +
        changed.join(', ') +
        '); refusing to publish an atlas from stale source input',
    );
  }
}

async function assertPreflightInputsStable(results) {
  for (const result of results) {
    await assertPreflightInputStable(result.preflight.anchor);
    for (const source of result.preflight.sources) await assertPreflightInputStable(source);
  }
}

function printDryRun(preflights) {
  let present = 0;
  let missingOrInvalid = 0;
  for (const preflight of preflights) {
    const inputs = [preflight.anchor, ...preflight.sources];
    const failed = inputs.filter((input) => input.error);
    present += inputs.length - failed.length;
    missingOrInvalid += failed.length;
    process.stdout.write(
      'DRY-RUN ' +
        preflight.effectSet.id +
        ': inputs=' +
        String(inputs.length) +
        ' present=' +
        String(inputs.length - failed.length) +
        ' unavailable=' +
        String(failed.length) +
        ' run=' +
        relativePath(runDirectoryFor(preflight.effectSet)) +
        '\n',
    );
    for (const message of preflightMessages(preflight)) {
      process.stdout.write('DRY-RUN MISSING_OR_INVALID ' + message + '\n');
    }
  }
  process.stdout.write(
    'DRY-RUN Summary: sets=' +
      String(preflights.length) +
      ' sourceInputsPresent=' +
      String(present) +
      ' unavailable=' +
      String(missingOrInvalid) +
      ' writes=0 manifest=not-published\n',
  );
}

function validatePreparedRequest(prepared, effectSet) {
  const cell = requireObject(prepared.cell, effectSet.id + ' sprite request cell');
  expectEqual(cell.width, CELL_SIZE, effectSet.id + ' sprite request cell.width');
  expectEqual(cell.height, CELL_SIZE, effectSet.id + ' sprite request cell.height');
  expectEqual(cell.safe_margin_x, SAFE_MARGIN, effectSet.id + ' sprite request cell.safe_margin_x');
  expectEqual(cell.safe_margin_y, SAFE_MARGIN, effectSet.id + ' sprite request cell.safe_margin_y');

  const chroma = requireObject(prepared.chroma_key, effectSet.id + ' sprite request chroma_key');
  expectEqual(String(chroma.hex).toUpperCase(), effectSet.chroma.hex, effectSet.id + ' sprite request chroma hex');

  const fit = requireObject(prepared.fit, effectSet.id + ' sprite request fit');
  for (const [key, expected] of Object.entries(EFFECT_FIT)) {
    expectEqual(fit[key], expected, effectSet.id + ' sprite request fit.' + key);
  }

  const states = requireObject(prepared.states, effectSet.id + ' sprite request states');
  expectEqual(Object.keys(states).length, effectSet.states.length, effectSet.id + ' sprite request state count');
  for (const state of effectSet.states) {
    const actual = requireObject(states[state.id], effectSet.id + ' request state ' + state.id);
    expectEqual(actual.frames, state.frames, state.id + ' request frames');
    expectEqual(actual.fps, state.fps, state.id + ' request fps');
    expectEqual(actual.loop, state.loop, state.id + ' request loop');
  }
}

function validateExtraction(framesManifest, effectSet) {
  expectEqual(framesManifest.ok, true, effectSet.id + ' frames manifest ok');
  ensureEmptyErrors(framesManifest.errors, effectSet.id + ' frames manifest');
  const rows = Array.isArray(framesManifest.rows) ? framesManifest.rows : null;
  if (!rows) throw new Error(effectSet.id + ' frames manifest must contain rows');
  expectEqual(rows.length, effectSet.states.length, effectSet.id + ' extracted row count');
  const rowsByState = new Map(rows.map((row) => [row.state, row]));
  for (const state of effectSet.states) {
    const row = requireObject(rowsByState.get(state.id), effectSet.id + ' extraction row ' + state.id);
    expectEqual(row.ok, true, state.id + ' extraction row ok');
    expectEqual(row.frames, state.frames, state.id + ' extracted frames');
    if (!Array.isArray(row.files) || row.files.length !== state.frames) {
      throw new Error(state.id + ' extraction files do not match declared frame count');
    }
    if (!Array.isArray(row.frame_records) || row.frame_records.length !== state.frames) {
      throw new Error(state.id + ' extraction frame records do not match declared frame count');
    }
    for (const frame of row.frame_records) {
      const usedPixels = Number(frame?.nontransparent_pixels);
      if (!Number.isFinite(usedPixels) || usedPixels < MIN_USED_PIXELS) {
        throw new Error(
          state.id +
            ' extraction frame has ' +
            String(usedPixels) +
            ' used pixels; expected at least ' +
            String(MIN_USED_PIXELS),
        );
      }
    }
    if (Array.isArray(row.errors) && row.errors.length > 0) {
      throw new Error(state.id + ' extraction row errors: ' + row.errors.join('; '));
    }
  }
}

function validateNoFallbackExtraction(framesManifest, effectSet) {
  validateExtraction(framesManifest, effectSet);
  const extractArgs = requireObject(framesManifest.extract_args, effectSet.id + ' extraction arguments');
  expectEqual(extractArgs.segmentation, 'projection', effectSet.id + ' extraction segmentation');
  expectEqual(extractArgs.min_used_pixels, MIN_USED_PIXELS, effectSet.id + ' extraction minimum used pixels');
  if (extractArgs.allow_slot_fallback === true) {
    throw new Error(effectSet.id + ' extraction enabled slot fallback');
  }
  for (const row of framesManifest.rows) {
    expectEqual(row.method, 'components', effectSet.id + ' no-fallback extraction method for ' + row.state);
  }
}

function validateComposition(report, runtimeManifest, effectSet) {
  expectEqual(report.ok, true, effectSet.id + ' atlas report ok');
  ensureEmptyErrors(report.errors, effectSet.id + ' atlas report');

  const animation = requireObject(runtimeManifest.animation, effectSet.id + ' runtime animation');
  const animationRows = requireObject(animation.rows, effectSet.id + ' runtime animation rows');
  const frameLayout = requireObject(runtimeManifest.frame_layout, effectSet.id + ' runtime frame layout');
  const layoutRows = requireObject(frameLayout.rows, effectSet.id + ' runtime layout rows');

  const expectedWidth = Math.max(...effectSet.states.map((state) => state.frames)) * CELL_SIZE;
  const expectedHeight = effectSet.states.length * CELL_SIZE;
  expectEqual(frameLayout.sheetWidth, expectedWidth, effectSet.id + ' atlas width');
  expectEqual(frameLayout.sheetHeight, expectedHeight, effectSet.id + ' atlas height');
  expectEqual(frameLayout.cellWidth, CELL_SIZE, effectSet.id + ' layout cell width');
  expectEqual(frameLayout.cellHeight, CELL_SIZE, effectSet.id + ' layout cell height');

  for (const state of effectSet.states) {
    const animationRow = requireObject(animationRows[state.id], effectSet.id + ' animation row ' + state.id);
    const rectangles = layoutRows[state.id];
    expectEqual(animationRow.frames, state.frames, state.id + ' composed frames');
    expectEqual(animationRow.fps, state.fps, state.id + ' composed fps');
    expectEqual(animationRow.loop, state.loop, state.id + ' composed loop');
    if (!Array.isArray(animationRow.durations_ms) || animationRow.durations_ms.length !== state.frames) {
      throw new Error(state.id + ' durations do not match declared frame count');
    }
    if (!Array.isArray(rectangles) || rectangles.length !== state.frames) {
      throw new Error(state.id + ' frame layout does not match declared frame count');
    }
    for (const rectangle of rectangles) {
      const rect = requireObject(rectangle, state.id + ' frame rectangle');
      expectEqual(rect.w, CELL_SIZE, state.id + ' frame rectangle width');
      expectEqual(rect.h, CELL_SIZE, state.id + ' frame rectangle height');
    }
  }
}

function effectPrompt(effectSet, state, preparedRequest) {
  const requestState = requireObject(preparedRequest.states[state.id], effectSet.id + ' prompt request state ' + state.id);
  const cell = requireObject(preparedRequest.cell, effectSet.id + ' prompt request cell');
  const chroma = requireObject(preparedRequest.chroma_key, effectSet.id + ' prompt request chroma');
  return [
    'Original historical physical pixel battle-effect sprite row.',
    'Create exactly ' + String(requestState.frames) + ' frames arranged left-to-right in invisible ' +
      String(cell.width) + 'x' + String(cell.height) + ' slots.',
    'Use a pure ' + effectSet.chroma.name + ' chroma background (' + String(chroma.hex).toUpperCase() + ') in every unused pixel.',
    'Put one complete isolated effect in each slot, with distinct temporal stages from the first frame to the last.',
    'Detached effect sprites and impact bursts are explicitly allowed and required when the action calls for them.',
    'Keep every effect inside the safe margin of ' + String(cell.safe_margin_x) + ' pixels.',
    'Preserve the attached style anchor pixel density, hard outlines, palette discipline, and material readability.',
    'No characters, scenery, text, UI, grid lines, shadows, glow, or blur.',
    'Specific action: ' + requestState.action + '.',
    'Use an original historical physical look; no franchise imitation.',
    '',
  ].join('\n');
}

function validateEffectPrompt(prompt, effectSet, state, preparedRequest) {
  const requestState = requireObject(preparedRequest.states[state.id], effectSet.id + ' prompt validation state ' + state.id);
  const chroma = requireObject(preparedRequest.chroma_key, effectSet.id + ' prompt validation chroma');
  const requiredFragments = [
    'exactly ' + String(requestState.frames) + ' frames',
    requestState.action,
    String(chroma.hex).toUpperCase(),
    'Detached effect sprites and impact bursts are explicitly allowed',
  ];
  for (const fragment of requiredFragments) {
    if (!prompt.includes(fragment)) {
      throw new Error(state.id + ' effect prompt is missing required fragment: ' + fragment);
    }
  }
  const forbiddenFragments = ['Do not draw detached effects', 'full-body'];
  for (const fragment of forbiddenFragments) {
    if (prompt.includes(fragment)) {
      throw new Error(state.id + ' effect prompt contains forbidden generic-sprite text: ' + fragment);
    }
  }
  const forbiddenCharacterLanguage = [
    /\bface\b/i,
    /\bfaces\b/i,
    /\bhair\b/i,
    /\bportrait\b/i,
    /\bidentity\b/i,
  ];
  for (const pattern of forbiddenCharacterLanguage) {
    if (pattern.test(prompt)) {
      throw new Error(state.id + ' effect prompt contains character face/hair language: ' + pattern);
    }
  }
}

async function writeEffectPrompts(effectSet, preparedRequest, runDirectory) {
  for (const state of effectSet.states) {
    const prompt = effectPrompt(effectSet, state, preparedRequest);
    validateEffectPrompt(prompt, effectSet, state, preparedRequest);
    await writeTextAtomically(resolve(runDirectory, 'prompts', state.id + '.txt'), prompt);
  }
}

async function sanitizeSourcesIntoRun(preflight, runDirectory) {
  const effectSet = preflight.effectSet;
  for (const source of preflight.sources) {
    const destination = resolve(runDirectory, 'raw', source.label.substring(source.label.indexOf('/') + 1) + '.png');
    await sanitizeGuideLinesIntoRunRaw(source.path, destination, effectSet);
  }
}

async function verifyNoFallbackExtraction(preflight, options) {
  const effectSet = preflight.effectSet;
  await fs.mkdir(BUILD_ROOT, { recursive: true });
  const runDirectory = await fs.mkdtemp(resolve(BUILD_ROOT, effectSet.id + '-no-fallback-'));
  await prepareRun(effectSet, preflight.anchor.path, { ...options, force: true }, runDirectory);
  await sanitizeSourcesIntoRun(preflight, runDirectory);

  await runSpriteGen(effectSet.id + ' no-fallback extract verification', EXTRACT_SCRIPT, [
    '--run-dir',
    runDirectory,
    '--segmentation',
    'projection',
    '--min-used-pixels',
    String(MIN_USED_PIXELS),
    '--repalette',
  ]);
  const framesManifest = await readJson(
    resolve(runDirectory, 'frames/frames-manifest.json'),
    effectSet.id + ' no-fallback frames manifest',
  );
  validateNoFallbackExtraction(framesManifest, effectSet);
  process.stdout.write('Verified ' + effectSet.id + ' extraction without slot fallback in ' + relativePath(runDirectory) + '.\n');
}

async function compileSet(preflight, options) {
  const effectSet = preflight.effectSet;
  if (options.verifyNoFallback) await verifyNoFallbackExtraction(preflight, options);

  const prepared = await prepareRun(effectSet, preflight.anchor.path, options);
  const runDirectory = prepared.runDirectory;
  await sanitizeSourcesIntoRun(preflight, runDirectory);

  const extractArgs = [
    '--run-dir',
    runDirectory,
    '--segmentation',
    'projection',
    '--min-used-pixels',
    String(MIN_USED_PIXELS),
  ];
  if (options.force) extractArgs.push('--repalette');
  await runSpriteGen(effectSet.id + ' extract', EXTRACT_SCRIPT, extractArgs);

  const framesManifest = await readJson(
    resolve(runDirectory, 'frames/frames-manifest.json'),
    effectSet.id + ' frames manifest',
  );
  validateNoFallbackExtraction(framesManifest, effectSet);

  await runSpriteGen(effectSet.id + ' compose', COMPOSE_SCRIPT, [
    '--run-dir',
    runDirectory,
    '--min-used-pixels',
    String(MIN_USED_PIXELS),
  ]);

  const report = await readJson(
    resolve(runDirectory, 'sprite-sheet-alpha.report.json'),
    effectSet.id + ' atlas report',
  );
  const runtimeManifest = await readJson(resolve(runDirectory, 'manifest.json'), effectSet.id + ' runtime manifest');
  validateComposition(report, runtimeManifest, effectSet);

  const stagingAtlasPath = resolve(runDirectory, 'sprite-sheet-alpha.png');
  const atlas = await readPng(stagingAtlasPath);
  if (![4, 6].includes(atlas.colorType)) {
    throw new Error(effectSet.id + ' atlas must contain alpha (PNG color type 4 or 6)');
  }
  const expectedWidth = Math.max(...effectSet.states.map((state) => state.frames)) * CELL_SIZE;
  const expectedHeight = effectSet.states.length * CELL_SIZE;
  expectEqual(atlas.width, expectedWidth, effectSet.id + ' atlas PNG width');
  expectEqual(atlas.height, expectedHeight, effectSet.id + ' atlas PNG height');

  return {
    effectSet,
    preflight,
    runDirectory,
    stagingAtlasPath,
    atlas,
    framesManifest,
    runtimeManifest,
  };
}

async function prepareRun(effectSet, anchorPath, options, runDirectory = runDirectoryFor(effectSet)) {
  const request = requestFor(effectSet);
  const prepareArgs = [
    '--out-dir',
    runDirectory,
    '--character-id',
    'v2-battle-effects-' + effectSet.id,
    '--base-image',
    anchorPath,
    '--description',
    effectSet.description,
    '--request-json',
    JSON.stringify(request),
    '--chroma-key',
    effectSet.chroma.hex,
  ];
  if (options.force) prepareArgs.push('--force');

  await runSpriteGen(effectSet.id + ' prepare', PREPARE_SCRIPT, prepareArgs);

  const preparedRequestPath = resolve(runDirectory, 'sprite-request.json');
  const preparedRequest = await readJson(preparedRequestPath, effectSet.id + ' sprite request');
  validatePreparedRequest(preparedRequest, effectSet);
  await writeEffectPrompts(effectSet, preparedRequest, runDirectory);
  return { runDirectory, preparedRequest };
}

function effectRecord(result, state) {
  const source = result.preflight.sources.find((candidate) => candidate.label.endsWith('/' + state.id));
  if (!source || !source.png) throw new Error('Missing source metadata for ' + state.id);
  const layoutRows = result.runtimeManifest.frame_layout.rows;
  return {
    id: state.id,
    source: {
      path: source.png.path,
      sha256: source.png.sha256,
      bytes: source.png.bytes,
      width: source.png.width,
      height: source.png.height,
      colorType: source.png.colorType,
    },
    frames: state.frames,
    fps: state.fps,
    loop: state.loop,
    chroma: { ...result.effectSet.chroma },
    styleAnchor: {
      path: result.preflight.anchor.png.path,
      sha256: result.preflight.anchor.png.sha256,
    },
    frame_layout: cloneJson(layoutRows[state.id]),
  };
}

function extractionReceipt(result, compiler) {
  const manifest = result.framesManifest;
  const sourcesByState = new Map(
    result.preflight.sources.map((source) => [source.label.substring(source.label.indexOf('/') + 1), source]),
  );
  return {
    schema: 'opensamguk.v2.battle-effects-extraction-receipt.v1',
    setId: result.effectSet.id,
    noFallbackVerified: true,
    engine: manifest.engine,
    engineRevision: manifest.engine_revision,
    extraction: {
      segmentation: 'projection',
      allowSlotFallback: false,
      repalette: manifest.extract_args.repalette === true,
      minUsedPixels: MIN_USED_PIXELS,
    },
    compiler: cloneJson(compiler),
    styleAnchor: {
      path: result.preflight.anchor.png.path,
      sha256: result.preflight.anchor.png.sha256,
      bytes: result.preflight.anchor.png.bytes,
    },
    atlas: {
      path: relativePath(resolve(ATLAS_ROOT, result.effectSet.id + '.png')),
      sha256: result.atlas.sha256,
      bytes: result.atlas.bytes,
      width: result.atlas.width,
      height: result.atlas.height,
    },
    rows: result.effectSet.states.map((state) => {
      const row = manifest.rows.find((candidate) => candidate.state === state.id);
      const source = sourcesByState.get(state.id);
      if (!row || !source?.png) throw new Error('Cannot create extraction receipt for ' + state.id);
      return {
        state: state.id,
        source: {
          path: source.png.path,
          sha256: source.png.sha256,
          bytes: source.png.bytes,
        },
        frames: state.frames,
        method: row.method,
        engineRevision: row.engine_revision,
        frameRecords: row.frame_records.map((frame) => ({
          index: frame.index,
          nontransparentPixels: frame.nontransparent_pixels,
          bbox: cloneJson(frame.bbox),
          edgePixels: frame.edge_pixels,
          chromaAdjacentPixels: frame.chroma_adjacent_pixels,
        })),
      };
    }),
  };
}

function finalManifest(results, compiler) {
  const orderedResults = [...results].sort(
    (left, right) =>
      EFFECT_SETS.findIndex((effectSet) => effectSet.id === left.effectSet.id) -
      EFFECT_SETS.findIndex((effectSet) => effectSet.id === right.effectSet.id),
  );
  const count = orderedResults.reduce((total, result) => total + result.effectSet.states.length, 0);
  expectEqual(count, EFFECT_COUNT, 'final battle-effect count');
  return {
    schema: 'opensamguk.v2.battle-effects-core.v2',
    status: 'RUNTIME_EFFECT_CANDIDATE',
    count,
    visualOnly: true,
    simulationAuthority: false,
    compiler: {
      engine: 'sprite-gen-component-row',
      cell: { width: CELL_SIZE, height: CELL_SIZE, safeMargin: SAFE_MARGIN },
      fit: { ...EFFECT_FIT },
      ...cloneJson(compiler),
    },
    sets: orderedResults.map((result) => ({
      id: result.effectSet.id,
      chroma: { ...result.effectSet.chroma },
      styleAnchor: {
        path: result.preflight.anchor.png.path,
        sha256: result.preflight.anchor.png.sha256,
        bytes: result.preflight.anchor.png.bytes,
        width: result.preflight.anchor.png.width,
        height: result.preflight.anchor.png.height,
        colorType: result.preflight.anchor.png.colorType,
      },
      atlas: {
        path: relativePath(resolve(ATLAS_ROOT, result.effectSet.id + '.png')),
        sha256: result.atlas.sha256,
        bytes: result.atlas.bytes,
        width: result.atlas.width,
        height: result.atlas.height,
        colorType: result.atlas.colorType,
      },
      extraction: {
        noFallbackVerified: true,
        segmentation: 'projection',
        allowSlotFallback: false,
        method: 'components',
        framesManifestReceipt: cloneJson(result.extractionReceipt),
      },
      effects: result.effectSet.states.map((state) => effectRecord(result, state)),
    })),
  };
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    process.stdout.write(usage());
    return;
  }
  if (options.dryRun && options.prepareOnly) {
    throw new Error('--prepare-only cannot be combined with --dry-run');
  }

  const selectedNames = new Set(options.onlySets);
  const knownNames = new Set(EFFECT_SETS.map((effectSet) => effectSet.id));
  for (const selectedName of selectedNames) {
    if (!knownNames.has(selectedName)) {
      throw new Error('--only-set must name physical or fire, found ' + selectedName);
    }
  }
  const selected = selectedNames.size === 0
    ? EFFECT_SETS
    : EFFECT_SETS.filter((effectSet) => selectedNames.has(effectSet.id));
  const compiler = await compilerProvenance();

  if (options.prepareOnly) {
    const anchorPreflights = await Promise.all(selected.map((effectSet) => preflightStyleAnchor(effectSet)));
    const failedAnchors = anchorPreflights.filter((preflight) => !preflight.ok);
    if (failedAnchors.length > 0) {
      const details = failedAnchors
        .map((preflight) => preflight.anchor.label + ': ' + relativePath(preflight.anchor.path) + ' (' + preflight.anchor.error + ')')
        .join('\n');
      throw new Error('Style-anchor preflight failed before writing sprite-gen scaffolding:\n' + details);
    }
    for (const preflight of anchorPreflights) {
      const prepared = await prepareRun(preflight.effectSet, preflight.anchor.path, options);
      for (const state of preflight.effectSet.states) {
        process.stdout.write('PROMPT ' + relativePath(resolve(prepared.runDirectory, 'prompts', state.id + '.txt')) + '\n');
      }
    }
    process.stdout.write('Prepare-only complete: extraction=0 atlasWrites=0 manifest=not-published\n');
    return;
  }

  const preflights = await Promise.all(selected.map((effectSet) => preflightSet(effectSet)));

  if (options.dryRun) {
    printDryRun(preflights);
    return;
  }

  const failedPreflights = preflights.filter((preflight) => !preflight.ok);
  if (failedPreflights.length > 0) {
    const details = failedPreflights.flatMap(preflightMessages).join('\n');
    throw new Error('Source preflight failed before writing any atlas:\n' + details);
  }

  const results = [];
  for (const preflight of preflights) {
    process.stdout.write('Compiling ' + preflight.effectSet.id + ' effects.\n');
    results.push(await compileSet(preflight, options));
  }

  await assertPreflightInputsStable(results);
  process.stdout.write('Verified source inputs remained stable through compilation.\n');

  for (const result of results) {
    const publishedAtlasPath = resolve(ATLAS_ROOT, result.effectSet.id + '.png');
    await copyAtomically(result.stagingAtlasPath, publishedAtlasPath);
    process.stdout.write('Published ' + relativePath(publishedAtlasPath) + '.\n');

    const receiptPath = resolve(RECEIPT_ROOT, result.effectSet.id + '.json');
    await writeJsonAtomically(receiptPath, extractionReceipt(result, compiler));
    result.extractionReceipt = await readFileFingerprint(receiptPath, relativePath(receiptPath));
    process.stdout.write('Published ' + relativePath(receiptPath) + '.\n');
  }

  const fullRun = selectedNames.size === 0 && results.length === EFFECT_SETS.length;
  if (fullRun) {
    const manifest = finalManifest(results, compiler);
    await writeJsonAtomically(FINAL_MANIFEST_PATH, manifest);
    process.stdout.write('Published ' + relativePath(FINAL_MANIFEST_PATH) + ' for ' + String(manifest.count) + ' effects.\n');
  } else {
    process.stdout.write('Manifest not published because --only-set selected a partial effect set.\n');
  }
}

main().catch((error) => {
  process.stderr.write('Error: ' + error.message + '\n');
  process.exitCode = 1;
});
