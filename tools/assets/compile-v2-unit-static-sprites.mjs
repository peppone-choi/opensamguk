#!/usr/bin/env node

import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { constants, promises as fs } from 'node:fs';
import { dirname, isAbsolute, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url));
const REPOSITORY_ROOT = resolve(SCRIPT_DIRECTORY, '..', '..');
const SOURCE_MANIFEST_PATH = resolve(
  REPOSITORY_ROOT,
  'assets/battle/v2/units/source/manifest.json',
);
const SOURCE_ROOT = dirname(SOURCE_MANIFEST_PATH);
const BUILD_ROOT = resolve(REPOSITORY_ROOT, 'build/sprite-gen/v2-units');
const SPRITE_ROOT = resolve(REPOSITORY_ROOT, 'assets/battle/v2/units/sprites');
const OUTPUT_MANIFEST_PATH = resolve(SPRITE_ROOT, 'manifest.json');
const VISUAL_QA_PATH = resolve(SPRITE_ROOT, 'visual-qa.json');
const EXPECTED_RECORD_COUNT = 105;
const DEFAULT_SPRITE_GEN_ROOT = '/Users/apple/.codex/skills/sprite-gen';
const PNG_SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const COMPILER_INPUT_FILENAME = 'compile-input.json';
const COMPILER_SCHEMA = 'opensamguk.v2.unit-static-sprite-compiler.v1';
const SOURCE_MANIFEST_SCHEMA = 'opensamguk.v2.roster-static-raw-identity-masters.v2';
const RUNTIME_MANIFEST_SCHEMA = 'opensamguk.v2.unit-static-runtime-sprites.v1';
const QA_SUBJECT_SCHEMA = 'opensamguk.v2.unit-static-sprite-qa-subject.v1';
const VISUAL_QA_SCHEMA = 'opensamguk.v2.unit-static-sprite-visual-qa.v1';

const STATIC_REQUEST = Object.freeze({
  states: {
    idle: {
      frames: 1,
      fps: 4,
      loop: true,
      action: 'locked accepted raw identity master; static idle pose only; no generated motion',
    },
  },
});

const FIT_CONTRACT = Object.freeze({
  cellSize: 256,
  chromaKey: '#FF00FF',
  resample: 'kcentroid',
  alignX: 'alpha-centroid',
  alignY: 'bottom',
  pixelUnfake: true,
  logicalHeight: 64,
  paletteSize: 48,
  outline: 'on',
});

const PYTHON_COMPOSITE = [
  'from PIL import Image',
  'import sys',
  'source_path, output_path = sys.argv[1:3]',
  'with Image.open(source_path) as opened:',
  '    source = opened.convert("RGBA")',
  'background = Image.new("RGBA", source.size, (255, 0, 255, 255))',
  'background.alpha_composite(source)',
  'background.save(output_path, format="PNG", optimize=False, compress_level=9)',
].join('\n');

function usage() {
  return `Usage: node tools/assets/compile-v2-unit-static-sprites.mjs [options]

Compile the 105 accepted RAW_IDENTITY_MASTER unit images through sprite-gen's
deterministic component-row extractor into 256px static runtime sprite atlases.

Options:
  --concurrency <n>  Maximum concurrent sprite-gen runs (1-4, default: 4)
  --only <value>     Compile matching variantId, slug, or displayName; repeatable/comma-separated
  --force            Rebuild records even when matching compiler provenance is present
  --dry-run          Validate the input and print planned work without running sprite-gen
  --print-qa-subject Print the ordered source/sprite hash subject for visual review, then stop
  --help             Show this message
`;
}

function parseArgs(argv) {
  const options = { concurrency: 4, force: false, dryRun: false, printQaSubject: false, only: [] };
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
    if (argument === '--print-qa-subject') {
      options.printQaSubject = true;
      continue;
    }
    if (argument === '--concurrency' || argument === '--only') {
      const value = argv[index + 1];
      if (!value || value.startsWith('--')) throw new Error(`${argument} requires a value`);
      index += 1;
      if (argument === '--concurrency') {
        if (!/^[0-9]+$/.test(value)) {
          throw new Error('--concurrency must be an integer from 1 to 4');
        }
        const concurrency = Number(value);
        if (!Number.isSafeInteger(concurrency) || concurrency < 1 || concurrency > 4) {
          throw new Error('--concurrency must be an integer from 1 to 4');
        }
        options.concurrency = concurrency;
      } else {
        options.only.push(...value.split(',').map((item) => item.trim()).filter(Boolean));
      }
      continue;
    }
    throw new Error(`Unknown option: ${argument}`);
  }
  return options;
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (isPlainObject(value)) {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

function canonicalSha256(value) {
  return sha256(canonicalJson(value));
}

function sameCanonical(left, right) {
  return canonicalJson(left) === canonicalJson(right);
}

function assertExactKeys(value, expectedKeys, label) {
  if (!isPlainObject(value)) throw new Error(`${label} must be an object`);
  const actual = Object.keys(value).sort();
  const expected = [...expectedKeys].sort();
  if (!sameCanonical(actual, expected)) {
    throw new Error(`${label} must contain exactly: ${expectedKeys.join(', ')}`);
  }
}

function requireString(value, label) {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`${label} must be a non-empty string`);
  }
  return value;
}

function requireSafeSlug(value, label) {
  const slug = requireString(value, label);
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(slug)) {
    throw new Error(`${label} must be a lowercase hyphenated asset slug`);
  }
  return slug;
}

function relativePath(path) {
  return relative(REPOSITORY_ROOT, path).replaceAll('\\', '/');
}

function resolveRepositoryPath(path, label) {
  const candidate = requireString(path, label);
  if (isAbsolute(candidate)) throw new Error(`${label} must be repository-relative`);
  const resolved = resolve(REPOSITORY_ROOT, candidate);
  const rel = relative(REPOSITORY_ROOT, resolved);
  if (rel === '' || rel.startsWith('..') || isAbsolute(rel)) {
    throw new Error(`${label} escapes the repository root`);
  }
  return resolved;
}

async function assertRegularFile(path, label) {
  const stats = await fs.lstat(path);
  if (!stats.isFile() || stats.isSymbolicLink()) {
    throw new Error(`${label} must be a regular non-symlink file: ${path}`);
  }
}

async function assertExecutableFile(path, label) {
  let original;
  try {
    original = await fs.lstat(path);
  } catch (error) {
    throw new Error(`${label} is missing: ${path}`);
  }
  if (!original.isFile() && !original.isSymbolicLink()) {
    throw new Error(`${label} must be an executable file or symlink: ${path}`);
  }
  let resolved;
  try {
    resolved = await fs.realpath(path);
    const target = await fs.stat(resolved);
    if (!target.isFile()) throw new Error('resolved target is not a file');
    await fs.access(resolved, constants.X_OK);
  } catch (error) {
    throw new Error(`${label} must resolve to an executable file: ${path} (${error.message})`);
  }
}

async function readJson(path, label) {
  await assertRegularFile(path, label);
  try {
    return JSON.parse(await fs.readFile(path, 'utf8'));
  } catch (error) {
    throw new Error(`${label} is not readable JSON: ${error.message}`);
  }
}

async function readJsonIfPresent(path) {
  try {
    return JSON.parse(await fs.readFile(path, 'utf8'));
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    return null;
  }
}

async function fileFingerprint(path, label) {
  await assertRegularFile(path, label);
  const data = await fs.readFile(path);
  return { sha256: sha256(data), bytes: data.length };
}

async function executableFingerprint(path, label) {
  await assertExecutableFile(path, label);
  const resolvedPath = await fs.realpath(path);
  return fileFingerprint(resolvedPath, `${label} resolved target`);
}

async function readPngInfo(path, label) {
  await assertRegularFile(path, label);
  const data = await fs.readFile(path);
  if (data.length < 33 || !data.subarray(0, 8).equals(PNG_SIGNATURE)) {
    throw new Error(`${label} is not a PNG: ${path}`);
  }
  if (data.readUInt32BE(8) !== 13 || data.subarray(12, 16).toString('ascii') !== 'IHDR') {
    throw new Error(`${label} has no valid IHDR: ${path}`);
  }
  return {
    path: relativePath(path),
    bytes: data.length,
    sha256: createHash('sha256').update(data).digest('hex'),
    width: data.readUInt32BE(16),
    height: data.readUInt32BE(20),
    bitDepth: data[24],
    colorType: data[25],
  };
}

function assertRawMasterPng(info, label) {
  if (info.width !== 1024 || info.height !== 1024 || ![4, 6].includes(info.colorType)) {
    throw new Error(`${label} must be a 1024x1024 PNG with an alpha-capable color type`);
  }
}

function assertRuntimeSpritePng(info, label) {
  if (info.width !== 256 || info.height !== 256 || info.bitDepth !== 8 || info.colorType !== 6) {
    throw new Error(`${label} must be a 256x256 8-bit RGBA PNG`);
  }
}

function sourcePathFromRecord(record, index) {
  const sourcePath = resolveRepositoryPath(record.path, `records[${index}].path`);
  const sourceRelative = relative(SOURCE_ROOT, sourcePath);
  if (sourceRelative === '' || sourceRelative.startsWith('..') || isAbsolute(sourceRelative)) {
    throw new Error(`records[${index}].path must be inside assets/battle/v2/units/source`);
  }
  return sourcePath;
}

async function loadSourceManifest() {
  const sourceManifest = await readJson(SOURCE_MANIFEST_PATH, 'source manifest');
  if (!isPlainObject(sourceManifest)) throw new Error('source manifest root must be an object');
  if (sourceManifest.schema !== SOURCE_MANIFEST_SCHEMA) {
    throw new Error(`source manifest schema must be ${SOURCE_MANIFEST_SCHEMA}; v1 inputs are forbidden`);
  }
  if (sourceManifest.status !== 'RAW_IDENTITY_MASTER') {
    throw new Error('source manifest status must be RAW_IDENTITY_MASTER');
  }
  if (!Array.isArray(sourceManifest.records) || sourceManifest.records.length !== EXPECTED_RECORD_COUNT) {
    throw new Error(`source manifest must have exactly ${EXPECTED_RECORD_COUNT} records`);
  }
  if (sourceManifest.count !== EXPECTED_RECORD_COUNT) {
    throw new Error(`source manifest count must be ${EXPECTED_RECORD_COUNT}`);
  }
  if (!isPlainObject(sourceManifest.catalog) || typeof sourceManifest.catalog.path !== 'string') {
    throw new Error('source manifest must contain a catalog reference with a path');
  }

  const sourceManifestInfo = {
    path: relativePath(SOURCE_MANIFEST_PATH),
    bytes: (await fs.stat(SOURCE_MANIFEST_PATH)).size,
    sha256: createHash('sha256').update(await fs.readFile(SOURCE_MANIFEST_PATH)).digest('hex'),
  };
  const seenVariantIds = new Set();
  const seenSlugs = new Set();
  const seenDisplayNames = new Set();
  const records = [];

  for (const [index, raw] of sourceManifest.records.entries()) {
    if (!isPlainObject(raw)) throw new Error(`records[${index}] must be an object`);
    const variantId = requireString(raw.variantId, `records[${index}].variantId`);
    const slug = requireSafeSlug(raw.slug, `records[${index}].slug`);
    const displayName = requireString(raw.displayName, `records[${index}].displayName`);
    if (seenVariantIds.has(variantId)) throw new Error(`duplicate variantId: ${variantId}`);
    if (seenSlugs.has(slug)) throw new Error(`duplicate slug: ${slug}`);
    if (seenDisplayNames.has(displayName)) throw new Error(`duplicate displayName: ${displayName}`);
    seenVariantIds.add(variantId);
    seenSlugs.add(slug);
    seenDisplayNames.add(displayName);

    const sourcePath = sourcePathFromRecord(raw, index);
    const source = await readPngInfo(sourcePath, `${variantId} raw master`);
    assertRawMasterPng(source, `${variantId} raw master`);
    const declaredHash = requireString(raw.hash, `records[${index}].hash`);
    if (!/^[0-9a-f]{64}$/.test(declaredHash) || declaredHash !== source.sha256) {
      throw new Error(`${variantId} raw master SHA-256 does not match the source manifest`);
    }
    if (!Number.isSafeInteger(raw.bytes) || raw.bytes <= 0 || raw.bytes !== source.bytes) {
      throw new Error(`${variantId} raw master byte count does not match the source manifest`);
    }
    records.push({
      raw,
      variantId,
      slug,
      displayName,
      sourcePath,
      source,
    });
  }

  return { sourceManifest, sourceManifestInfo, records };
}

function runCommand(command, args, label) {
  return new Promise((resolveCommand, rejectCommand) => {
    let stdout = '';
    let stderr = '';
    let settled = false;
    let child;
    const finish = (callback) => {
      if (settled) return;
      settled = true;
      callback();
    };
    try {
      child = spawn(command, args, { cwd: REPOSITORY_ROOT, shell: false });
    } catch (error) {
      rejectCommand(new Error(`${label} could not start: ${error.message}`));
      return;
    }
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    child.on('error', (error) => {
      finish(() => rejectCommand(new Error(`${label} could not start: ${error.message}`)));
    });
    child.on('close', (code, signal) => {
      finish(() => {
        if (code === 0) {
          resolveCommand({ stdout, stderr });
          return;
        }
        const combined = `${stderr}\n${stdout}`.trim();
        const tail = combined ? combined.split(/\r?\n/).slice(-12).join('\n') : 'no command output';
        rejectCommand(new Error(`${label} failed (code=${code}, signal=${signal ?? 'none'}):\n${tail}`));
      });
    });
  });
}

async function writeTextAtomically(path, text) {
  await fs.mkdir(dirname(path), { recursive: true });
  const temporary = `${path}.tmp-${process.pid}-${Math.random().toString(16).slice(2)}`;
  try {
    await fs.writeFile(temporary, text, 'utf8');
    await fs.rename(temporary, path);
  } finally {
    await fs.rm(temporary, { force: true });
  }
}

async function writeJsonAtomically(path, value) {
  await writeTextAtomically(path, `${JSON.stringify(value, null, 2)}\n`);
}

async function copyAtomically(source, destination) {
  await fs.mkdir(dirname(destination), { recursive: true });
  const temporary = `${destination}.tmp-${process.pid}-${Math.random().toString(16).slice(2)}`;
  try {
    await fs.copyFile(source, temporary);
    await fs.rename(temporary, destination);
  } finally {
    await fs.rm(temporary, { force: true });
  }
}

function spriteGenPaths() {
  const root = resolve(process.env.SPRITE_GEN_ROOT || DEFAULT_SPRITE_GEN_ROOT);
  return {
    root,
    python: resolve(root, '.venv/bin/python'),
    prepare: resolve(root, 'scripts/prepare_sprite_run.py'),
    extract: resolve(root, 'scripts/extract_sprite_row_frames.py'),
    compose: resolve(root, 'scripts/compose_sprite_atlas.py'),
  };
}

async function compilerContract(paths) {
  const [script, python, prepare, extract, compose] = await Promise.all([
    fileFingerprint(fileURLToPath(import.meta.url), 'compiler script'),
    executableFingerprint(paths.python, 'sprite-gen venv Python'),
    fileFingerprint(paths.prepare, 'sprite-gen prepare script'),
    fileFingerprint(paths.extract, 'sprite-gen extract script'),
    fileFingerprint(paths.compose, 'sprite-gen compose script'),
  ]);
  const toolchain = {
    pythonSha256: python.sha256,
    prepareSha256: prepare.sha256,
    extractSha256: extract.sha256,
    composeSha256: compose.sha256,
  };
  const hashes = {
    scriptSha256: script.sha256,
    requestSha256: canonicalSha256(STATIC_REQUEST),
    fitSha256: canonicalSha256(FIT_CONTRACT),
    compositeSha256: sha256(PYTHON_COMPOSITE),
    toolchainSha256: canonicalSha256(toolchain),
  };
  const canonicalContract = { schema: COMPILER_SCHEMA, hashes, toolchain };
  return {
    ...canonicalContract,
    hashes: {
      ...hashes,
      contractSha256: canonicalSha256(canonicalContract),
    },
  };
}

async function assertSpriteGenPaths(paths) {
  await Promise.all([
    assertExecutableFile(paths.python, 'sprite-gen venv Python'),
    assertRegularFile(paths.prepare, 'sprite-gen prepare script'),
    assertRegularFile(paths.extract, 'sprite-gen extract script'),
    assertRegularFile(paths.compose, 'sprite-gen compose script'),
  ]);
}

function staticIdleRequest() {
  return JSON.stringify(STATIC_REQUEST);
}

async function compositeRawMaster(paths, sourcePath, rawPath) {
  await fs.mkdir(dirname(rawPath), { recursive: true });
  const temporary = `${rawPath}.tmp-${process.pid}-${Math.random().toString(16).slice(2)}.png`;
  try {
    await runCommand(
      paths.python,
      ['-c', PYTHON_COMPOSITE, sourcePath, temporary],
      `Pillow magenta composite for ${relativePath(sourcePath)}`,
    );
    await fs.rename(temporary, rawPath);
  } finally {
    await fs.rm(temporary, { force: true });
  }
}

function validateRuntimeManifest(runtimeManifest, variantId) {
  if (!isPlainObject(runtimeManifest) || runtimeManifest.engine !== 'component-row') {
    throw new Error(`${variantId} sprite-gen manifest must declare component-row engine`);
  }
  if (runtimeManifest.game_input !== 'sprite-sheet-alpha.png' || runtimeManifest.degraded_static_fallback !== false) {
    throw new Error(`${variantId} sprite-gen manifest is not a non-fallback alpha atlas`);
  }
  const layout = runtimeManifest.frame_layout;
  const idle = layout?.rows?.idle;
  if (!isPlainObject(layout) || layout.sheetWidth !== 256 || layout.sheetHeight !== 256 || !Array.isArray(idle) || idle.length !== 1) {
    throw new Error(`${variantId} sprite-gen frame_layout must contain exactly one 256px idle cell`);
  }
  const rect = idle[0];
  if (!isPlainObject(rect) || rect.x !== 0 || rect.y !== 0 || rect.w !== 256 || rect.h !== 256) {
    throw new Error(`${variantId} idle frame_layout cell is not the expected full 256px atlas`);
  }
  const animation = runtimeManifest.animation?.rows?.idle;
  if (!isPlainObject(animation) || animation.frames !== 1 || animation.loop !== true) {
    throw new Error(`${variantId} runtime animation must contain one looping idle frame`);
  }
  return layout;
}

async function loadRuntimeResult(runDirectory, variantId) {
  const runtimeManifestPath = resolve(runDirectory, 'manifest.json');
  const framesManifestPath = resolve(runDirectory, 'frames/frames-manifest.json');
  const runtimeManifest = await readJson(runtimeManifestPath, `${variantId} runtime manifest`);
  const frameLayout = validateRuntimeManifest(runtimeManifest, variantId);
  const framesManifest = await readJson(
    framesManifestPath,
    `${variantId} frames manifest`,
  );
  const engineRevision = requireString(framesManifest.engine_revision, `${variantId} engine_revision`);
  if (framesManifest.ok !== true) throw new Error(`${variantId} frames manifest is not OK`);
  const atlasPath = resolve(runDirectory, 'sprite-sheet-alpha.png');
  const atlas = await readPngInfo(atlasPath, `${variantId} sprite-gen atlas`);
  assertRuntimeSpritePng(atlas, `${variantId} sprite-gen atlas`);
  return {
    runtimeManifest,
    frameLayout,
    engineRevision,
    atlasPath,
    atlas,
    artifacts: {
      runtimeManifest: await fileFingerprint(runtimeManifestPath, `${variantId} runtime manifest`),
      framesManifest: await fileFingerprint(framesManifestPath, `${variantId} frames manifest`),
    },
  };
}

async function reuseCompiledRecord(record, outputPath, runDirectory, compiler) {
  const provenance = await readJsonIfPresent(resolve(runDirectory, COMPILER_INPUT_FILENAME));
  if (!isPlainObject(provenance) || provenance.schema !== COMPILER_SCHEMA) return null;
  if (provenance.variantId !== record.variantId || provenance.slug !== record.slug) return null;
  if (provenance.displayName !== record.displayName || provenance.acceptedRawMasterLockedAsBase !== true) return null;
  if (!sameCanonical(provenance.compiler, compiler)) return null;
  if (!sameCanonical(provenance.source, record.source)) return null;
  try {
    const runtime = await loadRuntimeResult(runDirectory, record.variantId);
    const sprite = await readPngInfo(outputPath, `${record.variantId} installed sprite`);
    assertRuntimeSpritePng(sprite, `${record.variantId} installed sprite`);
    if (!sameCanonical(provenance.sprite, sprite)) return null;
    if (provenance.engineRevision !== runtime.engineRevision) return null;
    if (!sameCanonical(provenance.runtimeArtifacts, runtime.artifacts)) return null;
    if (runtime.atlas.sha256 !== sprite.sha256 || runtime.atlas.bytes !== sprite.bytes) return null;
    if (!sameCanonical(provenance.frame_layout, runtime.frameLayout)) return null;
    return { status: 'reused', record, runtime, sprite };
  } catch {
    return null;
  }
}

async function compileRecord(record, options, paths, compiler) {
  const runDirectory = resolve(BUILD_ROOT, record.slug);
  const outputPath = resolve(SPRITE_ROOT, `${record.slug}.png`);
  if (options.dryRun) {
    return { status: 'dry-run', record, runDirectory, outputPath };
  }
  if (!options.force) {
    const reused = await reuseCompiledRecord(record, outputPath, runDirectory, compiler);
    if (reused) return reused;
  }

  const request = staticIdleRequest();
  await runCommand(
    paths.python,
    [
      paths.prepare,
      '--out-dir',
      runDirectory,
      '--character-id',
      record.slug,
      '--base-image',
      record.sourcePath,
      '--description',
      `Accepted raw identity master for ${record.displayName}; locked static runtime identity.`,
      '--cell-size',
      String(FIT_CONTRACT.cellSize),
      '--chroma-key',
      FIT_CONTRACT.chromaKey,
      '--fit-resample',
      FIT_CONTRACT.resample,
      '--fit-align-x',
      FIT_CONTRACT.alignX,
      '--fit-align-y',
      FIT_CONTRACT.alignY,
      '--fit-pixel-unfake',
      '--fit-logical-height',
      String(FIT_CONTRACT.logicalHeight),
      '--fit-palette-size',
      String(FIT_CONTRACT.paletteSize),
      '--fit-outline',
      FIT_CONTRACT.outline,
      '--request-json',
      request,
      '--force',
    ],
    `sprite-gen prepare for ${record.variantId}`,
  );

  await compositeRawMaster(paths, record.sourcePath, resolve(runDirectory, 'raw/idle.png'));
  await runCommand(
    paths.python,
    [paths.extract, '--run-dir', runDirectory],
    `sprite-gen extract for ${record.variantId}`,
  );
  await runCommand(
    paths.python,
    [paths.compose, '--run-dir', runDirectory],
    `sprite-gen compose for ${record.variantId}`,
  );

  const runtime = await loadRuntimeResult(runDirectory, record.variantId);
  await copyAtomically(runtime.atlasPath, outputPath);
  const sprite = await readPngInfo(outputPath, `${record.variantId} installed sprite`);
  assertRuntimeSpritePng(sprite, `${record.variantId} installed sprite`);
  if (sprite.sha256 !== runtime.atlas.sha256 || sprite.bytes !== runtime.atlas.bytes) {
    throw new Error(`${record.variantId} installed sprite does not match the composed atlas`);
  }
  await writeJsonAtomically(resolve(runDirectory, COMPILER_INPUT_FILENAME), {
    schema: COMPILER_SCHEMA,
    variantId: record.variantId,
    slug: record.slug,
    displayName: record.displayName,
    acceptedRawMasterLockedAsBase: true,
    compiler,
    source: record.source,
    sprite,
    engineRevision: runtime.engineRevision,
    runtimeArtifacts: runtime.artifacts,
    frame_layout: runtime.frameLayout,
  });
  return { status: 'compiled', record, runtime, sprite };
}

async function runPool(records, options, paths, compiler) {
  const outcomes = new Array(records.length);
  let cursor = 0;
  async function worker() {
    while (true) {
      const index = cursor;
      cursor += 1;
      if (index >= records.length) return;
      const record = records[index];
      try {
        outcomes[index] = await compileRecord(record, options, paths, compiler);
      } catch (error) {
        outcomes[index] = { status: 'failed', record, message: error.message };
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(options.concurrency, records.length) }, worker));
  return outcomes;
}

async function qaSubject(records) {
  const bindings = [];
  for (const record of records) {
    const sprite = await readPngInfo(
      resolve(SPRITE_ROOT, `${record.slug}.png`),
      `${record.variantId} QA subject sprite`,
    );
    assertRuntimeSpritePng(sprite, `${record.variantId} QA subject sprite`);
    bindings.push({
      variantId: record.variantId,
      sourceSha256: record.source.sha256,
      spriteSha256: sprite.sha256,
    });
  }
  const subject = {
    schema: QA_SUBJECT_SCHEMA,
    count: EXPECTED_RECORD_COUNT,
    records: bindings,
  };
  return { ...subject, subjectSha256: canonicalSha256(subject) };
}

async function validateVisualQa(records) {
  const subject = await qaSubject(records);
  const qa = await readJson(VISUAL_QA_PATH, 'unit static sprite visual QA');
  assertExactKeys(qa, ['schema', 'subjectSha256', 'count', 'records'], 'visual QA root');
  if (qa.schema !== VISUAL_QA_SCHEMA) throw new Error(`visual QA schema must be ${VISUAL_QA_SCHEMA}`);
  if (qa.subjectSha256 !== subject.subjectSha256) throw new Error('visual QA subjectSha256 is stale');
  if (qa.count !== EXPECTED_RECORD_COUNT || !Array.isArray(qa.records) || qa.records.length !== EXPECTED_RECORD_COUNT) {
    throw new Error(`visual QA must contain exactly ${EXPECTED_RECORD_COUNT} ordered records`);
  }
  for (const [index, decision] of qa.records.entries()) {
    assertExactKeys(
      decision,
      ['variantId', 'sourceSha256', 'spriteSha256', 'identity', 'silhouetteAt64px'],
      `visual QA records[${index}]`,
    );
    const binding = subject.records[index];
    if (
      decision.variantId !== binding.variantId ||
      decision.sourceSha256 !== binding.sourceSha256 ||
      decision.spriteSha256 !== binding.spriteSha256
    ) {
      throw new Error(`visual QA records[${index}] does not match the ordered QA subject`);
    }
    if (decision.identity !== 'PASS' || decision.silhouetteAt64px !== 'PASS') {
      throw new Error(`visual QA records[${index}] requires identity=PASS and silhouetteAt64px=PASS`);
    }
  }
  return {
    path: relativePath(VISUAL_QA_PATH),
    sha256: (await fileFingerprint(VISUAL_QA_PATH, 'unit static sprite visual QA')).sha256,
    subjectSha256: subject.subjectSha256,
  };
}

function outputRecord(outcome) {
  const { record, runtime, sprite } = outcome;
  const raw = record.raw;
  return {
    status: 'STATIC_RUNTIME_SPRITE_CANDIDATE',
    staticRuntimeContract: 'idle-only; full directional animation pending',
    catalogNumber: raw.catalogNumber,
    parentName: raw.parentName,
    displayName: record.displayName,
    variantId: record.variantId,
    slug: record.slug,
    mobility: raw.mobility,
    equipment: raw.equipment,
    role: raw.role,
    gate: raw.gate,
    source: record.source,
    sprite,
    spriteGenEngineRevision: runtime.engineRevision,
    frame_layout: runtime.frameLayout,
  };
}

async function validateFullPublication(records, outcomes) {
  if (outcomes.length !== EXPECTED_RECORD_COUNT || outcomes.some((outcome) => !['compiled', 'reused'].includes(outcome.status))) {
    throw new Error('refusing manifest publication without 105 complete compiled/reused outcomes');
  }
  const expectedNames = new Set(records.map((record) => `${record.slug}.png`));
  const directoryEntries = await fs.readdir(SPRITE_ROOT, { withFileTypes: true });
  const pngNames = directoryEntries.filter((entry) => entry.isFile() && entry.name.endsWith('.png')).map((entry) => entry.name);
  if (pngNames.length !== EXPECTED_RECORD_COUNT) {
    throw new Error(`runtime sprite directory must contain exactly ${EXPECTED_RECORD_COUNT} PNG files, found ${pngNames.length}`);
  }
  if (pngNames.some((name) => !expectedNames.has(name)) || expectedNames.size !== new Set(pngNames).size) {
    throw new Error('runtime sprite directory does not exactly match the expected 105 asset slugs');
  }

  const spriteHashes = new Set();
  const engineRevisions = new Set();
  for (const outcome of outcomes) {
    const expectedPath = resolve(SPRITE_ROOT, `${outcome.record.slug}.png`);
    const current = await readPngInfo(expectedPath, `${outcome.record.variantId} final installed sprite`);
    assertRuntimeSpritePng(current, `${outcome.record.variantId} final installed sprite`);
    if (current.sha256 !== outcome.sprite.sha256 || current.bytes !== outcome.sprite.bytes) {
      throw new Error(`${outcome.record.variantId} final installed sprite changed during compilation`);
    }
    if (spriteHashes.has(current.sha256)) {
      throw new Error(`refusing manifest publication: duplicate sprite SHA-256 for ${outcome.record.variantId}`);
    }
    spriteHashes.add(current.sha256);
    engineRevisions.add(outcome.runtime.engineRevision);
  }
  if (engineRevisions.size !== 1) {
    throw new Error('refusing manifest publication: sprite-gen engine revision changed during the batch; rerun with --force');
  }
  return { engineRevision: [...engineRevisions][0], records: outcomes.map(outputRecord) };
}

function validatePublishedRuntimeManifest(manifest) {
  if (!isPlainObject(manifest) || manifest.schema !== RUNTIME_MANIFEST_SCHEMA) {
    throw new Error(`unit runtime manifest schema must be ${RUNTIME_MANIFEST_SCHEMA}`);
  }
  if (manifest.simulationAuthority !== false) {
    throw new Error('unit runtime manifest must declare simulationAuthority=false');
  }
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    process.stdout.write(usage());
    return;
  }
  const { sourceManifest, sourceManifestInfo, records } = await loadSourceManifest();
  if (options.printQaSubject) {
    if (options.only.length > 0 || options.dryRun || options.force) {
      throw new Error('--print-qa-subject cannot be combined with --only, --dry-run, or --force');
    }
    process.stdout.write(`${JSON.stringify(await qaSubject(records), null, 2)}\n`);
    return;
  }
  const selection = new Set(options.only);
  const selected = selection.size === 0
    ? records
    : records.filter((record) => selection.has(record.variantId) || selection.has(record.slug) || selection.has(record.displayName));
  if (selection.size > 0 && selected.length === 0) {
    throw new Error('--only did not match a variantId, slug, or displayName');
  }

  if (options.dryRun) {
    for (const record of selected) {
      process.stdout.write(`DRY-RUN ${record.variantId}: ${record.source.path} -> assets/battle/v2/units/sprites/${record.slug}.png\n`);
    }
    process.stdout.write(`Summary: selected=${selected.length} dry-run=${selected.length} failed=0\n`);
    return;
  }

  await fs.rm(OUTPUT_MANIFEST_PATH, { force: true });
  const paths = spriteGenPaths();
  await assertSpriteGenPaths(paths);
  const compiler = await compilerContract(paths);
  const outcomes = await runPool(selected, options, paths, compiler);
  const failures = outcomes.filter((outcome) => outcome.status === 'failed');
  const compiled = outcomes.filter((outcome) => outcome.status === 'compiled');
  const reused = outcomes.filter((outcome) => outcome.status === 'reused');
  for (const outcome of failures) {
    process.stderr.write(`FAILED ${outcome.record.variantId}: ${outcome.message}\n`);
  }

  const fullRun = selection.size === 0 && selected.length === EXPECTED_RECORD_COUNT;
  let manifestPublished = false;
  if (failures.length === 0 && fullRun) {
    const publication = await validateFullPublication(records, outcomes);
    const visualQa = await validateVisualQa(records);
    const runtimeManifest = {
      schema: RUNTIME_MANIFEST_SCHEMA,
      status: 'STATIC_RUNTIME_SPRITE_CANDIDATE',
      staticRuntimeContract: 'idle-only; full directional animation pending',
      simulationAuthority: false,
      count: EXPECTED_RECORD_COUNT,
      catalog: sourceManifest.catalog,
      sourceManifest: {
        ...sourceManifestInfo,
        status: sourceManifest.status,
      },
      compiler,
      visualQa,
      spriteGen: {
        engine: 'component-row',
        engineRevision: publication.engineRevision,
        request: STATIC_REQUEST,
        fit: FIT_CONTRACT,
      },
      records: publication.records,
    };
    validatePublishedRuntimeManifest(runtimeManifest);
    await writeJsonAtomically(OUTPUT_MANIFEST_PATH, runtimeManifest);
    manifestPublished = true;
  } else {
    process.stdout.write('Manifest not published (partial selection or failed compilation).\n');
  }
  process.stdout.write(
    `Summary: selected=${selected.length} compiled=${compiled.length} reused=${reused.length} failed=${failures.length} manifest=${manifestPublished ? 'published' : 'not-published'}\n`,
  );
  if (failures.length > 0) process.exitCode = 1;
}

main().catch((error) => {
  process.stderr.write(`Error: ${error.message}\n`);
  process.exitCode = 1;
});
