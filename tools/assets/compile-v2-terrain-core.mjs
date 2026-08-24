#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { promises as fs } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_PATH = fileURLToPath(import.meta.url);
const SCRIPT_DIRECTORY = dirname(SCRIPT_PATH);
const REPOSITORY_ROOT = resolve(SCRIPT_DIRECTORY, '..', '..');
const SOURCE_ROOT = resolve(REPOSITORY_ROOT, 'assets/battle/v2/terrain/source');
const TILE_ROOT = resolve(REPOSITORY_ROOT, 'assets/battle/v2/terrain/tiles');
const MANIFEST_PATH = resolve(REPOSITORY_ROOT, 'assets/battle/v2/terrain/manifest.json');
const DEFAULT_SPRITE_GEN_ROOT = resolve(REPOSITORY_ROOT, 'tools/sprite-gen');
const PNG_SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const TERRAIN_SCHEMA = 'opensamguk.v2.terrain-core.v2';
const TRANSFORM_SCHEMA = 'opensamguk.v2.terrain-core.transform.v1';
const EDGE_COMPARISON_METHOD = 'decoded-rgba-opposite-edges-exact-v1';
const MIN_SOURCE_SIZE = 1024;
const EXPECTED_SHEET_COUNT = 8;
const EXPECTED_TILE_COUNT = 32;

const CELL_POSITIONS = Object.freeze([
  Object.freeze({ column: 0, row: 0 }),
  Object.freeze({ column: 1, row: 0 }),
  Object.freeze({ column: 0, row: 1 }),
  Object.freeze({ column: 1, row: 1 }),
]);

const SOURCE_SHEETS = Object.freeze([
  Object.freeze({
    file: 'ground-a.png',
    chromaKey: null,
    tiles: Object.freeze([
      Object.freeze({ slug: 'grass', category: 'ground' }),
      Object.freeze({ slug: 'packed_earth', category: 'ground' }),
      Object.freeze({ slug: 'sand', category: 'ground' }),
      Object.freeze({ slug: 'mud', category: 'ground' }),
    ]),
  }),
  Object.freeze({
    file: 'ground-b.png',
    chromaKey: null,
    tiles: Object.freeze([
      Object.freeze({ slug: 'marsh', category: 'ground' }),
      Object.freeze({ slug: 'forest_floor', category: 'ground' }),
      Object.freeze({ slug: 'rock_scree', category: 'ground' }),
      Object.freeze({ slug: 'field', category: 'ground' }),
    ]),
  }),
  Object.freeze({
    file: 'route-hydro.png',
    chromaKey: null,
    tiles: Object.freeze([
      Object.freeze({ slug: 'footpath', category: 'route' }),
      Object.freeze({ slug: 'packed_road', category: 'route' }),
      Object.freeze({ slug: 'shallow_water', category: 'water' }),
      Object.freeze({ slug: 'river', category: 'water' }),
    ]),
  }),
  Object.freeze({
    file: 'hydro-crossing.png',
    chromaKey: null,
    tiles: Object.freeze([
      Object.freeze({ slug: 'deep_water', category: 'water' }),
      Object.freeze({ slug: 'rapid_water', category: 'water' }),
      Object.freeze({ slug: 'ford', category: 'crossing' }),
      Object.freeze({ slug: 'bank_reed', category: 'water_bank' }),
    ]),
  }),
  Object.freeze({
    file: 'height-fort.png',
    chromaKey: 'magenta',
    tiles: Object.freeze([
      Object.freeze({ slug: 'cliff_earth', category: 'height' }),
      Object.freeze({ slug: 'cliff_rock', category: 'height' }),
      Object.freeze({ slug: 'embankment_terrace', category: 'height' }),
      Object.freeze({ slug: 'wall_palisade', category: 'fortification' }),
    ]),
  }),
  Object.freeze({
    file: 'fort-crossing.png',
    chromaKey: 'magenta',
    tiles: Object.freeze([
      Object.freeze({ slug: 'wall_earth', category: 'fortification' }),
      Object.freeze({ slug: 'gate_camp', category: 'fortification' }),
      Object.freeze({ slug: 'bridge_timber', category: 'crossing' }),
      Object.freeze({ slug: 'bridge_pontoon', category: 'crossing' }),
    ]),
  }),
  Object.freeze({
    file: 'vegetation.png',
    chromaKey: 'magenta',
    tiles: Object.freeze([
      Object.freeze({ slug: 'tree_broadleaf', category: 'vegetation' }),
      Object.freeze({ slug: 'tree_willow', category: 'vegetation' }),
      Object.freeze({ slug: 'bamboo', category: 'vegetation' }),
      Object.freeze({ slug: 'reed_lotus', category: 'vegetation' }),
    ]),
  }),
  Object.freeze({
    file: 'props.png',
    chromaKey: 'magenta',
    tiles: Object.freeze([
      Object.freeze({ slug: 'cart_wagon', category: 'prop' }),
      Object.freeze({ slug: 'cargo', category: 'prop' }),
      Object.freeze({ slug: 'fence', category: 'prop' }),
      Object.freeze({ slug: 'camp_tent', category: 'prop' }),
    ]),
  }),
]);

const TOPOLOGY_UNSPECIFIED_CATEGORIES = new Set(['route', 'water', 'water_bank', 'crossing']);
const SINGLE_MODULE_CATEGORIES = new Set(['vegetation', 'prop']);

const CROP_PROGRAM = String.raw`
import sys
from pathlib import Path
from PIL import Image

source = Path(sys.argv[1])
destination = Path(sys.argv[2])
x = int(sys.argv[3])
y = int(sys.argv[4])
source_size = int(sys.argv[5])
tile_size = int(sys.argv[6])
with Image.open(source) as image:
    if image.size != (source_size, source_size):
        raise ValueError(f"expected {source_size}x{source_size} source, got {image.size}")
    if image.mode not in ("RGB", "RGBA"):
        raise ValueError(f"expected RGB or RGBA source, got {image.mode}")
    tile = image.crop((x, y, x + tile_size, y + tile_size))
    if tile.size != (tile_size, tile_size):
        raise ValueError(f"expected {tile_size}x{tile_size} tile, got {tile.size}")
    tile.save(destination, "PNG", optimize=False)
`;

const EDGE_COMPARISON_PROGRAM = String.raw`
import json
import sys
from pathlib import Path
from PIL import Image

source = Path(sys.argv[1])
expected_size = int(sys.argv[2])
with Image.open(source) as image:
    rgba = image.convert("RGBA")
    if rgba.size != (expected_size, expected_size):
        raise ValueError(f"expected {expected_size}x{expected_size} tile, got {rgba.size}")
    pixels = rgba.load()
    top_bottom = sum(pixels[x, 0] != pixels[x, expected_size - 1] for x in range(expected_size))
    left_right = sum(pixels[0, y] != pixels[expected_size - 1, y] for y in range(expected_size))
print(json.dumps({
    "topBottomMismatchPixels": top_bottom,
    "leftRightMismatchPixels": left_right,
}, sort_keys=True, separators=(",", ":")))
`;

function usage() {
  return `Usage: node tools/assets/compile-v2-terrain-core.mjs [options]

Compile eight adopted existing even-square 2x2 terrain source sheets into 32 native half-size candidate tiles.

Options:
  --force         Rebuild every selected tile even when a valid output exists
  --dry-run       Report source/output status without invoking Pillow or writing files
  --only <value>  Select a bf2 logical ID or tile slug; repeatable and comma-separated
  --help          Show this help text
`;
}

function parseArgs(argv) {
  const options = { force: false, dryRun: false, only: [] };
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
    if (argument === '--only') {
      const value = argv[index + 1];
      if (!value || value.startsWith('--')) throw new Error('--only requires a value');
      index += 1;
      options.only.push(...value.split(',').map((item) => item.trim()).filter(Boolean));
      continue;
    }
    throw new Error(`Unknown option: ${argument}`);
  }
  return options;
}

function relativePath(path) {
  return relative(REPOSITORY_ROOT, path).replaceAll('\\', '/');
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

function canonicalJson(value) {
  if (value === null || typeof value === 'boolean' || typeof value === 'string') return JSON.stringify(value);
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new Error('Canonical JSON does not support non-finite numbers');
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (typeof value === 'object') {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
      .join(',')}}`;
  }
  throw new Error(`Canonical JSON does not support ${typeof value}`);
}

function canonicalSha256(value) {
  return sha256(canonicalJson(value));
}

function sameCanonical(left, right) {
  return canonicalJson(left) === canonicalJson(right);
}

function pngIhdr(metadata) {
  return {
    width: metadata.width,
    height: metadata.height,
    bitDepth: metadata.bitDepth,
    colorType: metadata.colorType,
    compression: metadata.compression,
    filter: metadata.filter,
    interlace: metadata.interlace,
  };
}

function pngArtifact(metadata) {
  return {
    sha256: metadata.hash,
    bytes: metadata.bytes,
    ihdr: pngIhdr(metadata),
  };
}

async function fileArtifact(path) {
  const data = await fs.readFile(path);
  return { sha256: sha256(data), bytes: data.length };
}

function textArtifact(text) {
  const data = Buffer.from(text, 'utf8');
  return { sha256: sha256(data), bytes: data.length };
}

function assertStaticRegistry() {
  const expectedFiles = [
    'ground-a.png',
    'ground-b.png',
    'route-hydro.png',
    'hydro-crossing.png',
    'height-fort.png',
    'fort-crossing.png',
    'vegetation.png',
    'props.png',
  ];
  if (SOURCE_SHEETS.length !== EXPECTED_SHEET_COUNT) {
    throw new Error(`Terrain source registry must contain exactly ${EXPECTED_SHEET_COUNT} sheets`);
  }
  const sourceFiles = SOURCE_SHEETS.map((sheet) => sheet.file);
  if (sourceFiles.join('|') !== expectedFiles.join('|')) {
    throw new Error('Terrain source registry does not match the required sheet order');
  }
  if (new Set(sourceFiles).size !== sourceFiles.length) {
    throw new Error('Terrain source registry has duplicate sheet filenames');
  }
  const entries = [];
  for (const sheet of SOURCE_SHEETS) {
    if (!/^[a-z][a-z0-9-]*\.png$/.test(sheet.file) || sheet.tiles.length !== CELL_POSITIONS.length) {
      throw new Error(`Invalid static terrain sheet declaration: ${sheet.file}`);
    }
    if (sheet.chromaKey !== null && sheet.chromaKey !== 'magenta') {
      throw new Error(`Unsupported chroma policy for ${sheet.file}`);
    }
    sheet.tiles.forEach((tile, index) => {
      if (!/^[a-z][a-z0-9_]*$/.test(tile.slug)) throw new Error(`Unsafe terrain tile slug: ${tile.slug}`);
      if (!/^[a-z][a-z0-9_]*$/.test(tile.category)) throw new Error(`Unsafe terrain category: ${tile.category}`);
      entries.push({
        logicalId: `bf2.${tile.slug}`,
        slug: tile.slug,
        category: tile.category,
        sourceSheet: sheet.file,
        sourcePath: resolve(SOURCE_ROOT, sheet.file),
        chromaKey: sheet.chromaKey,
        cellPosition: CELL_POSITIONS[index],
      });
    });
  }
  if (entries.length !== EXPECTED_TILE_COUNT) {
    throw new Error(`Terrain registry must produce exactly ${EXPECTED_TILE_COUNT} tiles`);
  }
  for (const field of ['logicalId', 'slug']) {
    const values = entries.map((entry) => entry[field]);
    if (new Set(values).size !== values.length) throw new Error(`Terrain registry has duplicate ${field}s`);
  }
  return Object.freeze(entries.map(Object.freeze));
}

const REGISTRY = assertStaticRegistry();

function pngHeader(data, path) {
  if (data.length < 33 || !data.subarray(0, 8).equals(PNG_SIGNATURE)) {
    throw new Error(`${relativePath(path)} is not a PNG`);
  }
  if (data.readUInt32BE(8) !== 13 || data.subarray(12, 16).toString('ascii') !== 'IHDR') {
    throw new Error(`${relativePath(path)} has no valid IHDR`);
  }
  return {
    width: data.readUInt32BE(16),
    height: data.readUInt32BE(20),
    bitDepth: data[24],
    colorType: data[25],
    compression: data[26],
    filter: data[27],
    interlace: data[28],
  };
}

async function inspectPng(path, label) {
  const data = await fs.readFile(path);
  const header = pngHeader(data, path);
  return {
    ...header,
    bytes: data.length,
    hash: sha256(data),
    label,
  };
}

function assertSourcePng(metadata, path) {
  if (
    metadata.width !== metadata.height ||
    metadata.width < MIN_SOURCE_SIZE ||
    metadata.width % 2 !== 0 ||
    metadata.bitDepth !== 8 ||
    ![2, 6].includes(metadata.colorType) ||
    metadata.compression !== 0 ||
    metadata.filter !== 0
  ) {
    throw new Error(
      `${relativePath(path)} must be an even square 8-bit RGB/RGBA PNG at least ${MIN_SOURCE_SIZE}px with standard PNG encoding`,
    );
  }
}

function assertTilePng(metadata, entry, path) {
  const expectedColorTypes = entry.chromaKey ? [6] : [2, 6];
  if (
    metadata.width !== entry.tileSize ||
    metadata.height !== entry.tileSize ||
    metadata.bitDepth !== 8 ||
    !expectedColorTypes.includes(metadata.colorType) ||
    metadata.compression !== 0 ||
    metadata.filter !== 0
  ) {
    const expectedMode = entry.chromaKey ? 'RGBA' : 'RGB/RGBA';
    throw new Error(
      `${relativePath(path)} must be a ${entry.tileSize}x${entry.tileSize} 8-bit ${expectedMode} PNG`,
    );
  }
}

async function sourceMetadataFor(entries, options) {
  const uniqueSources = [...new Set(entries.map((entry) => entry.sourcePath))];
  const metadata = new Map();
  const missing = [];
  for (const sourcePath of uniqueSources) {
    try {
      const inspected = await inspectPng(sourcePath, 'source');
      assertSourcePng(inspected, sourcePath);
      metadata.set(sourcePath, inspected);
    } catch (error) {
      if (error?.code === 'ENOENT') {
        missing.push(sourcePath);
        continue;
      }
      if (options.dryRun) {
        process.stdout.write(`DRY-RUN INVALID-SOURCE ${relativePath(sourcePath)}: ${error.message}\n`);
        continue;
      }
      throw error;
    }
  }
  if (!options.dryRun && missing.length > 0) {
    throw new Error(`Missing required terrain source sheets: ${missing.map(relativePath).join(', ')}`);
  }
  return { metadata, missing, sourcePaths: uniqueSources };
}

function sharedSourceGeometry(sourceState, options) {
  const dimensions = new Set(
    [...sourceState.metadata.values()].map((metadata) => `${metadata.width}x${metadata.height}`),
  );
  if (dimensions.size > 1) {
    const message = `Terrain source sheets must share identical dimensions; found ${[...dimensions].join(', ')}`;
    if (options.dryRun) {
      process.stdout.write(`DRY-RUN DIMENSION-MISMATCH ${message}\n`);
      return null;
    }
    throw new Error(message);
  }
  if (dimensions.size === 0) return null;
  const first = [...sourceState.metadata.values()][0];
  return { width: first.width, height: first.height, tileSize: first.width / 2 };
}

function withSourceGeometry(entry, source) {
  if (!source || source.width !== source.height || source.width % 2 !== 0) {
    throw new Error(`Cannot assign native grid geometry to ${entry.logicalId}`);
  }
  const tileSize = source.width / 2;
  const cell = {
    ...entry.cellPosition,
    x: entry.cellPosition.column * tileSize,
    y: entry.cellPosition.row * tileSize,
    width: tileSize,
    height: tileSize,
  };
  return {
    ...entry,
    sourceSize: source.width,
    tileSize,
    sourceArtifact: pngArtifact(source),
    cell,
  };
}

function commandResult(executable, args) {
  return new Promise((resolveProcess) => {
    let stdout = '';
    let stderr = '';
    let child;
    try {
      child = spawn(executable, args, { cwd: REPOSITORY_ROOT, shell: false });
    } catch (error) {
      resolveProcess({ code: null, stdout, stderr, error });
      return;
    }
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    child.on('error', (error) => resolveProcess({ code: null, stdout, stderr, error }));
    child.on('close', (code) => resolveProcess({ code, stdout, stderr }));
  });
}

async function runOrThrow(executable, args, label) {
  const result = await commandResult(executable, args);
  if (result.error || result.code !== 0) {
    const detail = [result.stderr, result.stdout].filter(Boolean).join('\n').trim();
    throw new Error(`${label} failed: ${result.error?.message ?? `exit ${result.code}`}${detail ? `\n${detail}` : ''}`);
  }
}

async function assertExecutable(path, label) {
  try {
    await fs.access(path);
  } catch {
    throw new Error(`${label} is required at ${path}; set SPRITE_GEN_ROOT to an installed sprite-gen root`);
  }
}

function spriteGenTools() {
  const root = resolve(process.env.SPRITE_GEN_ROOT || DEFAULT_SPRITE_GEN_ROOT);
  return {
    root,
    python: resolve(root, '.venv/bin/python'),
    cutout: resolve(root, '.venv/bin/sprite-gen'),
  };
}

async function compilerContract(tools) {
  const [script, pythonInterpreter, cutoutTool] = await Promise.all([
    fileArtifact(SCRIPT_PATH),
    fileArtifact(tools.python),
    fileArtifact(tools.cutout),
  ]);
  const cropProgram = textArtifact(CROP_PROGRAM);
  const contract = {
    script: {
      path: relativePath(SCRIPT_PATH),
      fingerprint: script.sha256,
      artifact: script,
    },
    cropProgram: {
      language: 'python',
      fingerprint: cropProgram.sha256,
      artifact: cropProgram,
    },
    pythonInterpreter: {
      fingerprint: pythonInterpreter.sha256,
      artifact: pythonInterpreter,
    },
    cutoutTool: {
      command: 'sprite-gen cutout',
      fingerprint: cutoutTool.sha256,
      artifact: cutoutTool,
    },
  };
  const canonicalTransform = {
    schema: TRANSFORM_SCHEMA,
    compiler: {
      scriptFingerprint: contract.script.fingerprint,
      cropProgramFingerprint: contract.cropProgram.fingerprint,
      pythonInterpreterFingerprint: contract.pythonInterpreter.fingerprint,
      cutoutToolFingerprint: contract.cutoutTool.fingerprint,
    },
    output: { format: 'PNG', cropOptimize: false },
  };
  return {
    ...contract,
    canonicalTransform,
    canonicalTransformSha256: canonicalSha256(canonicalTransform),
  };
}

function transformFor(entry, compiler) {
  const canonical = {
    schema: TRANSFORM_SCHEMA,
    compilerTransformSha256: compiler.canonicalTransformSha256,
    source: {
      grid: '2x2',
      sourceSize: entry.sourceSize,
      cell: entry.cell,
    },
    crop: {
      programFingerprint: compiler.cropProgram.fingerprint,
      imageMode: 'RGB_OR_RGBA',
      optimize: false,
    },
    alpha: entry.chromaKey
      ? {
        operation: 'sprite-gen-cutout',
        key: entry.chromaKey,
        toolFingerprint: compiler.cutoutTool.fingerprint,
      }
      : {
        operation: 'preserve-source-alpha',
        key: null,
        toolFingerprint: null,
      },
  };
  const canonicalTransformSha256 = canonicalSha256(canonical);
  return {
    fingerprint: canonicalTransformSha256,
    canonicalTransformSha256,
    canonical,
  };
}

function temporaryPath(destination, suffix) {
  const random = createHash('sha256')
    .update(`${process.pid}-${Date.now()}-${Math.random()}`)
    .digest('hex')
    .slice(0, 16);
  return `${destination}.tmp-${process.pid}-${random}-${suffix}.png`;
}

async function moveAtomically(source, destination) {
  await fs.mkdir(dirname(destination), { recursive: true });
  await fs.rename(source, destination);
}

async function cropSourceCell(tools, entry, cropPath) {
  await runOrThrow(
    tools.python,
    [
      '-c',
      CROP_PROGRAM,
      entry.sourcePath,
      cropPath,
      String(entry.cell.x),
      String(entry.cell.y),
      String(entry.sourceSize),
      String(entry.tileSize),
    ],
    `Pillow crop for ${entry.logicalId}`,
  );
}

function sourceBinding(entry) {
  return {
    sheet: entry.sourceSheet,
    path: relativePath(entry.sourcePath),
    sha256: entry.sourceArtifact.sha256,
    bytes: entry.sourceArtifact.bytes,
    ihdr: entry.sourceArtifact.ihdr,
    cell: entry.cell,
  };
}

function outputBinding(destinationPath, output) {
  return {
    path: relativePath(destinationPath),
    sha256: output.hash,
    bytes: output.bytes,
    ihdr: pngIhdr(output),
  };
}

function priorRecordMatches(priorRecord, entry, transform, destinationPath, output) {
  if (!priorRecord || typeof priorRecord !== 'object') return false;
  if (priorRecord.logicalId !== entry.logicalId || priorRecord.slug !== entry.slug || priorRecord.category !== entry.category) {
    return false;
  }
  if (!sameCanonical(priorRecord.source, sourceBinding(entry))) return false;
  if (
    priorRecord.transform?.fingerprint !== transform.fingerprint ||
    priorRecord.transform?.canonicalTransformSha256 !== transform.canonicalTransformSha256
  ) {
    return false;
  }
  return sameCanonical(priorRecord.output, outputBinding(destinationPath, output));
}

async function decodedRgbaEdgeComparison(tools, entry, destinationPath) {
  const result = await commandResult(tools.python, [
    '-c',
    EDGE_COMPARISON_PROGRAM,
    destinationPath,
    String(entry.tileSize),
  ]);
  if (result.error || result.code !== 0) {
    const detail = [result.stderr, result.stdout].filter(Boolean).join('\n').trim();
    throw new Error(
      `Decoded RGBA edge comparison for ${entry.logicalId} failed: ` +
        `${result.error?.message ?? `exit ${result.code}`}${detail ? `\n${detail}` : ''}`,
    );
  }
  let compared;
  try {
    compared = JSON.parse(result.stdout);
  } catch (error) {
    throw new Error(`Decoded RGBA edge comparison for ${entry.logicalId} emitted invalid JSON: ${error.message}`);
  }
  const topBottom = compared?.topBottomMismatchPixels;
  const leftRight = compared?.leftRightMismatchPixels;
  if (!Number.isInteger(topBottom) || topBottom < 0 || !Number.isInteger(leftRight) || leftRight < 0) {
    throw new Error(`Decoded RGBA edge comparison for ${entry.logicalId} emitted invalid mismatch counts`);
  }
  const total = topBottom + leftRight;
  return {
    method: EDGE_COMPARISON_METHOD,
    decodedFormat: 'RGBA',
    mismatchCounts: {
      topBottomPixels: topBottom,
      leftRightPixels: leftRight,
      totalPixels: total,
    },
    status: total === 0 ? 'EXACT_MATCH' : 'MISMATCHES_PRESENT',
  };
}

async function compileTile(tools, entry, transform, priorRecord, force) {
  const destinationPath = resolve(TILE_ROOT, `${entry.slug}.png`);
  if (!force) {
    try {
      const current = await inspectPng(destinationPath, 'tile');
      assertTilePng(current, entry, destinationPath);
      if (priorRecordMatches(priorRecord, entry, transform, destinationPath, current)) {
        return {
          entry,
          destinationPath,
          output: current,
          transform,
          edgeComparison: await decodedRgbaEdgeComparison(tools, entry, destinationPath),
          rebuilt: false,
        };
      }
      process.stderr.write(`Rebuilding stale tile ${relativePath(destinationPath)}: v2 provenance does not match\n`);
    } catch (error) {
      if (error?.code !== 'ENOENT') {
        process.stderr.write(`Rebuilding invalid tile ${relativePath(destinationPath)}: ${error.message}\n`);
      }
    }
  }

  await fs.mkdir(dirname(destinationPath), { recursive: true });
  const cropPath = temporaryPath(destinationPath, 'crop');
  const outputPath = entry.chromaKey ? temporaryPath(destinationPath, 'cutout') : cropPath;
  try {
    await cropSourceCell(tools, entry, cropPath);
    if (entry.chromaKey) {
      await runOrThrow(
        tools.cutout,
        ['cutout', cropPath, '--out', outputPath, '--key', entry.chromaKey],
        `sprite-gen cutout for ${entry.logicalId}`,
      );
    }
    const output = await inspectPng(outputPath, 'tile');
    assertTilePng(output, entry, outputPath);
    await moveAtomically(outputPath, destinationPath);
    return {
      entry,
      destinationPath,
      output,
      transform,
      edgeComparison: await decodedRgbaEdgeComparison(tools, entry, destinationPath),
      rebuilt: true,
    };
  } finally {
    await Promise.all([
      fs.rm(cropPath, { force: true }),
      outputPath === cropPath ? Promise.resolve() : fs.rm(outputPath, { force: true }),
    ]);
  }
}

function topologyFor(entry) {
  if (TOPOLOGY_UNSPECIFIED_CATEGORIES.has(entry.category)) {
    return {
      orientationStatus: 'UNSPECIFIED',
      orientation: null,
      socketsStatus: 'UNMAPPED',
      sockets: [],
      transitionsStatus: 'MISSING',
      transitions: null,
    };
  }
  return {
    orientationStatus: 'NOT_APPLICABLE',
    orientation: null,
    socketsStatus: 'NOT_APPLICABLE',
    sockets: [],
    transitionsStatus: 'NOT_APPLICABLE',
    transitions: null,
  };
}

function placementModeFor(entry, edgeComparison) {
  if (SINGLE_MODULE_CATEGORIES.has(entry.category)) return 'single-module';
  if (entry.category === 'ground' && edgeComparison.mismatchCounts.totalPixels === 0) return 'single-module';
  return 'authored-preview-only';
}

function tileRecord(result) {
  const { entry, destinationPath, output, transform, edgeComparison } = result;
  // Matching transparent outer edges only proves seam equality. These candidate
  // crops have no authored repeat/topology evidence, so they are not tileable.
  const tileable = false;
  return {
    logicalId: entry.logicalId,
    slug: entry.slug,
    category: entry.category,
    sourceSheet: entry.sourceSheet,
    cell: entry.cell,
    path: relativePath(destinationPath),
    hash: output.hash,
    bytes: output.bytes,
    width: output.width,
    height: output.height,
    source: sourceBinding(entry),
    transform,
    output: outputBinding(destinationPath, output),
    alpha: entry.chromaKey ? 'cutout' : output.colorType === 6 ? 'preserved' : 'opaque',
    edgeComparison,
    tileable,
    topology: topologyFor(entry),
    placementMode: placementModeFor(entry, edgeComparison),
    composability: 'NOT_COMPOSABLE',
    evidenceClass: entry.chromaKey
      ? 'adopted-existing-chroma-candidate-deterministic-cutout'
      : 'adopted-existing-raw-candidate-deterministic-crop',
  };
}

async function writeJsonAtomically(path, value) {
  await fs.mkdir(dirname(path), { recursive: true });
  const temporary = `${path}.tmp-${process.pid}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  try {
    await fs.writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
    await fs.rename(temporary, path);
  } finally {
    await fs.rm(temporary, { force: true });
  }
}

async function priorV2Records() {
  let previous;
  try {
    previous = JSON.parse(await fs.readFile(MANIFEST_PATH, 'utf8'));
  } catch (error) {
    if (error?.code !== 'ENOENT') {
      process.stderr.write(`Ignoring unreadable prior terrain manifest: ${error.message}\n`);
    }
    return new Map();
  }
  if (previous?.schema !== TERRAIN_SCHEMA || !Array.isArray(previous.records)) return new Map();
  const records = new Map();
  for (const record of previous.records) {
    if (!record || typeof record.logicalId !== 'string' || records.has(record.logicalId)) return new Map();
    records.set(record.logicalId, record);
  }
  return records;
}

function terrainManifest(results, compiler) {
  const records = results.map(tileRecord);
  const hashes = new Set(records.map((record) => record.hash));
  if (hashes.size !== EXPECTED_TILE_COUNT) {
    throw new Error('Refusing manifest publication: terrain tiles contain duplicate hashes');
  }
  const sourceDimensions = new Set(results.map((result) => `${result.entry.sourceSize}x${result.entry.sourceSize}`));
  const tileDimensions = new Set(results.map((result) => `${result.entry.tileSize}x${result.entry.tileSize}`));
  if (sourceDimensions.size !== 1 || tileDimensions.size !== 1) {
    throw new Error('Refusing manifest publication: terrain source geometry is inconsistent');
  }
  const sourceSize = results[0].entry.sourceSize;
  const tileSize = results[0].entry.tileSize;
  const sourceBySheet = new Map(results.map((result) => [result.entry.sourceSheet, result.entry]));
  return {
    schema: TERRAIN_SCHEMA,
    status: 'STATIC_TERRAIN_CORE_CANDIDATE',
    sourceProvenance: {
      kind: 'adopted-existing',
      origin: {
        kind: 'adopted-existing',
        provider: null,
        model: null,
      },
      sourceSheets: SOURCE_SHEETS.map((sheet) => ({
        file: sheet.file,
        path: relativePath(resolve(SOURCE_ROOT, sheet.file)),
        grid: '2x2',
        artifact: sourceBySheet.get(sheet.file).sourceArtifact,
        chromaKey: sheet.chromaKey === 'magenta' ? '#FF00FF' : null,
        origin: {
          kind: 'adopted-existing',
          provider: null,
          model: null,
        },
      })),
    },
    compiler,
    artCellMeters: 4,
    sourceSheet: { width: sourceSize, height: sourceSize, columns: 2, rows: 2 },
    sourceCell: { width: tileSize, height: tileSize },
    outputTile: { width: tileSize, height: tileSize },
    layerOrder: ['ground', 'route', 'water', 'water_bank', 'height', 'crossing', 'fortification', 'vegetation', 'prop'],
    simulationAuthority: false,
    placementContract: {
      composability: 'NOT_COMPOSABLE',
      rendererReady: false,
    },
    count: records.length,
    records,
  };
}

async function dryRun(entries, sourceState) {
  const missing = new Set(sourceState.missing);
  const sources = sourceState.sourcePaths;
  for (const sourcePath of sources) {
    const state = missing.has(sourcePath) ? 'MISSING' : sourceState.metadata.has(sourcePath) ? 'READY' : 'INVALID';
    const metadata = sourceState.metadata.get(sourcePath);
    const dimensions = metadata ? ` dimensions=${metadata.width}x${metadata.height}` : '';
    process.stdout.write(`DRY-RUN SOURCE ${state} ${relativePath(sourcePath)}${dimensions}\n`);
  }
  const observedDimensions = new Set(
    [...sourceState.metadata.values()].map((metadata) => `${metadata.width}x${metadata.height}`),
  );
  if (observedDimensions.size === 1) {
    const [dimensions] = observedDimensions;
    const tileSize = Number.parseInt(dimensions.split('x', 1)[0], 10) / 2;
    const coverage = sourceState.missing.length === 0 ? 'READY' : 'CANDIDATE';
    process.stdout.write(
      `DRY-RUN GEOMETRY ${coverage} source=${dimensions} cell=${tileSize}x${tileSize} observed-sheets=${sourceState.metadata.size}/${EXPECTED_SHEET_COUNT}\n`,
    );
  }
  for (const entry of entries) {
    const destinationPath = resolve(TILE_ROOT, `${entry.slug}.png`);
    let outputState = 'MISSING';
    try {
      const output = await inspectPng(destinationPath, 'tile');
      assertTilePng(output, entry, destinationPath);
      outputState = 'VALID';
    } catch (error) {
      if (error?.code !== 'ENOENT') outputState = 'INVALID';
    }
    process.stdout.write(
      `DRY-RUN TILE ${entry.logicalId} source=${relativePath(entry.sourcePath)} output=${outputState} path=${relativePath(destinationPath)}\n`,
    );
  }
  process.stdout.write(
    `Summary: selected=${entries.length} sources=${sources.length} missing-sources=${sourceState.missing.length} writes=0\n`,
  );
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    process.stdout.write(usage());
    return;
  }
  const selection = new Set(options.only);
  const selected = selection.size === 0
    ? REGISTRY
    : REGISTRY.filter((entry) => selection.has(entry.logicalId) || selection.has(entry.slug));
  if (selection.size > 0 && selected.length === 0) {
    throw new Error('--only did not match a bf2 logical ID or terrain tile slug');
  }
  const sourceState = await sourceMetadataFor(REGISTRY, options);
  const geometry = sharedSourceGeometry(sourceState, options);
  if (options.dryRun) {
    const dryRunEntries = selected.map((entry) => {
      const source = sourceState.metadata.get(entry.sourcePath);
      return source && geometry ? withSourceGeometry(entry, source) : entry;
    });
    await dryRun(dryRunEntries, sourceState);
    return;
  }
  if (!geometry) throw new Error('Terrain source geometry is unavailable');

  const compiledEntries = selected.map((entry) => {
    const source = sourceState.metadata.get(entry.sourcePath);
    return withSourceGeometry(entry, source);
  });

  const tools = spriteGenTools();
  await assertExecutable(tools.python, 'sprite-gen Pillow interpreter');
  await assertExecutable(tools.cutout, 'sprite-gen cutout command');
  const compiler = await compilerContract(tools);
  const previousRecords = await priorV2Records();
  const results = [];
  for (const entry of compiledEntries) {
    const transform = transformFor(entry, compiler);
    results.push(await compileTile(tools, entry, transform, previousRecords.get(entry.logicalId), options.force));
  }

  const fullRun = selection.size === 0 && compiledEntries.length === EXPECTED_TILE_COUNT;
  if (fullRun) {
    await writeJsonAtomically(MANIFEST_PATH, terrainManifest(results, compiler));
    process.stdout.write(`Published STATIC_TERRAIN_CORE_CANDIDATE manifest for ${results.length} tiles.\n`);
  } else {
    process.stdout.write('Manifest not published for partial selection.\n');
  }
  const rebuilt = results.filter((result) => result.rebuilt).length;
  process.stdout.write(`Summary: selected=${selected.length} rebuilt=${rebuilt} reused=${results.length - rebuilt}\n`);
}

main().catch((error) => {
  process.stderr.write(`Error: ${error.message}\n`);
  process.exitCode = 1;
});
