#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { promises as fs } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url));
const REPOSITORY_ROOT = resolve(SCRIPT_DIRECTORY, '..', '..');
const CATALOG_PATH = resolve(
  REPOSITORY_ROOT,
  'docs/superpowers/plans/2026-07-29-v2-expanded-recruitable-unit-catalog.md',
);
const BUILD_ROOT = resolve(REPOSITORY_ROOT, 'build/perfectpixel/v2-battle/roster-static');
const ASSET_ROOT = resolve(REPOSITORY_ROOT, 'assets/battle/v2/units/source');
const MANIFEST_PATH = resolve(ASSET_ROOT, 'manifest.json');
const RECEIPT_LEDGER_PATH = resolve(ASSET_ROOT, 'source-receipt-ledger.v1.json');
const SCRIPT_PATH = fileURLToPath(import.meta.url);
const DEFAULT_PPGEN =
  '/Users/apple/perfectpixel-studio/skill/perfectpixel/skills/perfectpixel/bin/ppgen';
const PNG_SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const EXPECTED_VARIANT_COUNT = 105;
const MANIFEST_SCHEMA = 'opensamguk.v2.roster-static-raw-identity-masters.v2';
const LEGACY_MANIFEST_SCHEMA = 'opensamguk.v2.roster-static-raw-identity-masters.v1';
const RECEIPT_SCHEMA = 'opensamguk.v2.roster-static-generation-receipt.v2';
const RECEIPT_FILENAME = 'generation-receipt.v2.json';
const RECEIPT_LEDGER_SCHEMA = 'opensamguk.v2.roster-static-source-receipt-ledger.v1';

function usage() {
  return `Usage: node tools/assets/generate-v2-roster-static-sprites.mjs [options]

Generate or verify RAW_IDENTITY_MASTER 1024px source images from the v2 recruitable roster catalog.

Options:
  --concurrency <n>  Maximum ppgen processes at once (default: 4)
  --only <value>     Generate matching stable variant ID, slug, or Korean display name; repeatable
  --force            Generate every selected image; never reuse the tracked receipt ledger
  --adopt-existing   Resolve one complete 105-record set from current v2 receipts or unchanged v1 records
  --seal-existing-ledger
                     Verify existing build-cache receipts and write the tracked source receipt ledger
  --dry-run          Parse and report the work without invoking ppgen or copying assets
  --help             Show this message
`;
}

function parseArgs(argv) {
  const options = {
    concurrency: 4,
    force: false,
    adoptExisting: false,
    sealExistingLedger: false,
    dryRun: false,
    only: [],
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--help') return { help: true };
    if (argument === '--force') {
      options.force = true;
      continue;
    }
    if (argument === '--adopt-existing') {
      options.adoptExisting = true;
      continue;
    }
    if (argument === '--seal-existing-ledger') {
      options.sealExistingLedger = true;
      continue;
    }
    if (argument === '--dry-run') {
      options.dryRun = true;
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
  if ([options.force, options.adoptExisting, options.sealExistingLedger].filter(Boolean).length > 1) {
    throw new Error('--force, --adopt-existing, and --seal-existing-ledger are mutually exclusive');
  }
  if (options.adoptExisting && options.only.length > 0) {
    throw new Error('--adopt-existing requires the full 105-record set; --only is not allowed');
  }
  if (options.sealExistingLedger && options.only.length > 0) {
    throw new Error('--seal-existing-ledger requires the full 105-record set; --only is not allowed');
  }
  return options;
}

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => (
      `${JSON.stringify(key)}:${canonicalJson(value[key])}`
    )).join(',')}}`;
  }
  return JSON.stringify(value);
}

function sha256Bytes(value) {
  return createHash('sha256').update(value).digest('hex');
}

function canonicalSha256(value) {
  return sha256Bytes(Buffer.from(canonicalJson(value), 'utf8'));
}

function cellsFromTableRow(line) {
  return line
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((cell) => cell.trim().replaceAll('`', ''));
}

function slugFor(variantId) {
  const slug = variantId.replace(/^variant\./, '').replaceAll('_', '-').replaceAll('.', '-');
  if (!/^[a-z0-9-]+$/.test(slug)) throw new Error(`Unsafe slug for ${variantId}`);
  return slug;
}

function parseCatalog(markdown) {
  const rows = [];
  for (const line of markdown.split(/\r?\n/)) {
    if (!line.trim().startsWith('|')) continue;
    const cells = cellsFromTableRow(line);
    if (cells.length !== 8 || !cells[3].startsWith('variant.')) continue;
    rows.push({
      catalogNumber: Number.parseInt(cells[0], 10),
      parentName: cells[1],
      displayName: cells[2],
      variantId: cells[3],
      slug: slugFor(cells[3]),
      mobility: cells[4],
      equipment: cells[5],
      role: cells[6],
      gate: cells[7],
    });
  }

  if (rows.length !== EXPECTED_VARIANT_COUNT) {
    throw new Error(`Expected ${EXPECTED_VARIANT_COUNT} variant table rows, found ${rows.length}`);
  }
  const ids = new Set(rows.map((row) => row.variantId));
  const names = new Set(rows.map((row) => row.displayName));
  const slugs = new Set(rows.map((row) => row.slug));
  if (ids.size !== rows.length) throw new Error('Catalog contains duplicate stable variant IDs');
  if (names.size !== rows.length) throw new Error('Catalog contains duplicate Korean display names');
  if (slugs.size !== rows.length) throw new Error('Catalog IDs produce duplicate asset slugs');
  if (rows.some((row) => !Number.isSafeInteger(row.catalogNumber))) {
    throw new Error('Catalog contains a non-numeric variant row number');
  }
  return rows;
}

function subjectInstruction(row) {
  const id = row.variantId;
  if (id.startsWith('variant.war_elephant.')) {
    return 'Subject: show one full elephant with its practical crew and equipment.';
  }
  if (id.startsWith('variant.beast_handlers.')) {
    return 'Subject: show one handler together with the controlled animal.';
  }
  if (
    id.startsWith('variant.river_transport.') ||
    id.startsWith('variant.battle_fleet.') ||
    id.startsWith('variant.jinfan_raiders.')
  ) {
    return 'Subject: show one complete vessel with a minimal, readable crew.';
  }
  if (
    /^(variant\.(stone_thrower_corps|ram_corps|siege_tower_corps|bridge_engineers|grain_transport|wagon_convoy|wooden_ox_transport)\.)/.test(
      id,
    )
  ) {
    return 'Subject: show the complete platform or convoy with only a minimal crew.';
  }
  if (row.mobility.includes('기마')) {
    return 'Subject: show one full horse and rider, never a cropped mount.';
  }
  return 'Subject: show one full-body soldier.';
}

function promptHints(row) {
  const hints = [];
  const id = row.variantId;
  const isNavalVessel =
    id.startsWith('variant.river_transport.') ||
    id.startsWith('variant.battle_fleet.') ||
    id.startsWith('variant.jinfan_raiders.');
  if (row.equipment.includes('노')) {
    hints.push(
      'Weapon disambiguation: a Korean 노 weapon token means a crossbow; show an explicit horizontal stocked crossbow, not a vertical bow. On naval rows, any separate propulsion 노 remains a visible oar.',
    );
  }
  if (row.equipment.includes('투창')) {
    hints.push('Equipment visibility: show a readable bundle of javelins, not only one generic spear.');
  }
  if (row.mobility.includes('짐말')) {
    hints.push('Mobility visibility: include one complete loaded pack animal beside the unit.');
  }
  if (isNavalVessel || row.mobility.includes('선박') || row.mobility.includes('바지선')) {
    hints.push('Platform visibility: the naval vessel itself must dominate the silhouette; do not render only sailors.');
  }

  const special = {
    'white-tuft-guard-command-guard':
      'Identity cue: show a clearly visible white tuft on the guard.',
    'wooden-ox-transport-mountain-supply':
      'Identity cue: show a mechanical wooden ox transport device; show no living ox, horse, donkey, or other animal.',
    'siege-tower-corps-elevated-fire':
      'Platform cue: show an elevated wooden tower weapon with the shooter visibly above ground level.',
    'poison-spring-ambushers-gorge-ambush':
      'Role cue: include one clearly visible constructed trap in the ambush silhouette.',
    'black-mountain-mobile-archer':
      'Weapon cue: the mobile archer must visibly carry and use a bow.',
    'battle-fleet-fire-attack':
      'Platform cue: show a small fire-laden tow craft and its tow connection, not a generic burning warship.',
    'banshun-white-bamboo-crossbow':
      'Weapon cue: show pale bamboo crossbow limbs and stock, visibly distinct from ordinary wood.',
    'jingzhou-amphibious-river-land-garrison':
      'Weapon cue: show a held stocked horizontal crossbow plus a spear; show no ordinary bow.',
    'rattan-armour-rough-skirmisher':
      'Weapon cue: show a central short bow plus a separate bundle of short throwing javelins; show no long spear.',
  }[row.slug];
  if (special) hints.push(special);
  return hints;
}

function legacyPromptFor(row) {
  return [
    'Original late Eastern Han / Three Kingdoms transparent full-body pixel battle sprite, 3/4 front view.',
    'Practical cloth and lamellar equipment, earth-tone palette, strong readable silhouette at 64px.',
    'No text, no fantasy, no modern gear, no Total War, no Koei, and no franchise imitation.',
    `Korean display name: ${row.displayName}.`,
    `Mobility/platform: ${row.mobility}.`,
    `Equipment: ${row.equipment}.`,
    `Tactical role: ${row.role}.`,
    subjectInstruction(row),
  ].join(' ');
}

function promptFor(row) {
  return [legacyPromptFor(row), ...promptHints(row)].join(' ');
}

function requestFor(row) {
  return {
    baseOnly: true,
    description: promptFor(row),
    outputFilename: 'base.png',
    style: 'pixel',
    timeout: '5m',
  };
}

function rowIdentity(row) {
  return {
    catalogNumber: row.catalogNumber,
    parentName: row.parentName,
    displayName: row.displayName,
    variantId: row.variantId,
    slug: row.slug,
    mobility: row.mobility,
    equipment: row.equipment,
    role: row.role,
    gate: row.gate,
  };
}

function bindingsFor(row, context) {
  const prompt = promptFor(row);
  return {
    catalogSha256: context.catalogSha256,
    catalogRowsSha256: context.catalogRowsSha256,
    catalogRowSha256: canonicalSha256(rowIdentity(row)),
    promptSha256: sha256Bytes(Buffer.from(prompt, 'utf8')),
    requestSha256: canonicalSha256(requestFor(row)),
    scriptSha256: context.scriptSha256,
  };
}

function sourceAssetPathFor(row) {
  return resolve(ASSET_ROOT, `${row.slug}.png`);
}

function inputBindingsFor(row, context) {
  const bindings = bindingsFor(row, context);
  return {
    catalogSha256: bindings.catalogSha256,
    catalogRowsSha256: bindings.catalogRowsSha256,
    catalogRowSha256: bindings.catalogRowSha256,
    promptSha256: bindings.promptSha256,
    requestSha256: bindings.requestSha256,
  };
}

function requireSha256(value, label) {
  if (typeof value !== 'string' || !/^[0-9a-f]{64}$/.test(value)) {
    throw new Error(`${label} must be a lowercase SHA-256`);
  }
  return value;
}

async function readValidPng(path) {
  let data;
  try {
    const stats = await fs.lstat(path);
    if (!stats.isFile() || stats.isSymbolicLink()) {
      throw new Error(`${path} must be a regular non-symlink file`);
    }
    data = await fs.readFile(path);
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
  if (data.length < 33 || !data.subarray(0, 8).equals(PNG_SIGNATURE)) {
    throw new Error(`${path} is not a PNG`);
  }
  if (data.readUInt32BE(8) !== 13 || data.subarray(12, 16).toString('ascii') !== 'IHDR') {
    throw new Error(`${path} has no valid IHDR`);
  }
  const width = data.readUInt32BE(16);
  const height = data.readUInt32BE(20);
  const bitDepth = data[24];
  const colorType = data[25];
  if (width !== 1024 || height !== 1024 || ![4, 6].includes(colorType)) {
    throw new Error(`${path} must be 1024x1024 PNG color type 4 or 6`);
  }
  return {
    bytes: data.length,
    hash: createHash('sha256').update(data).digest('hex'),
    width,
    height,
    bitDepth,
    colorType,
    ihdrHash: canonicalSha256({ width, height, bitDepth, colorType }),
  };
}

function runPpgen(executable, args) {
  return new Promise((resolveProcess) => {
    let stdout = '';
    let stderr = '';
    let child;
    try {
      child = spawn(executable, args, { cwd: REPOSITORY_ROOT, shell: false });
    } catch (error) {
      resolveProcess({ code: null, error, stdout, stderr });
      return;
    }
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    child.on('error', (error) => resolveProcess({ code: null, error, stdout, stderr }));
    child.on('close', (code) => resolveProcess({ code, stdout, stderr }));
  });
}

function providerMetadata(stdout) {
  const fallback = { provider: null, model: null };
  try {
    const parsed = JSON.parse(stdout);
    const nested = parsed.result ?? parsed.metadata ?? parsed;
    return {
      provider: nested.provider ?? parsed.provider ?? fallback.provider,
      model: nested.model ?? parsed.model ?? fallback.model,
    };
  } catch {
    // Some ppgen versions emit progress before the final single-line JSON object.
  }
  const lines = stdout.trim().split(/\r?\n/).filter(Boolean).reverse();
  for (const line of lines) {
    try {
      const parsed = JSON.parse(line);
      const nested = parsed.result ?? parsed.metadata ?? parsed;
      return {
        provider: nested.provider ?? parsed.provider ?? fallback.provider,
        model: nested.model ?? parsed.model ?? fallback.model,
      };
    } catch {
      // ppgen may emit a single JSON object with surrounding whitespace; continue safely.
    }
  }
  return fallback;
}

function requireProviderMetadata(metadata, variantId) {
  for (const key of ['provider', 'model']) {
    if (typeof metadata[key] !== 'string' || metadata[key].trim() === '' || metadata[key] === 'unknown') {
      throw new Error(`${variantId} fresh generation did not report a concrete ${key}`);
    }
  }
  return { provider: metadata.provider, model: metadata.model };
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

async function writeJsonAtomically(path, value) {
  await fs.mkdir(dirname(path), { recursive: true });
  const temporary = `${path}.tmp-${process.pid}-${Math.random().toString(16).slice(2)}`;
  try {
    await fs.writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
    await fs.rename(temporary, path);
  } finally {
    await fs.rm(temporary, { force: true });
  }
}

async function readJsonIfPresent(path) {
  try {
    return JSON.parse(await fs.readFile(path, 'utf8'));
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw new Error(`${relativePath(path)} is not readable JSON: ${error.message}`);
  }
}

function requireExactKeys(value, keys, label) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${label} must be an object`);
  }
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  if (actual.length !== expected.length || actual.some((key, index) => key !== expected[index])) {
    throw new Error(`${label} has an unexpected field set`);
  }
}

function artifactFor(path, source) {
  return {
    path: relativePath(path),
    sha256: source.hash,
    bytes: source.bytes,
    ihdr: {
      width: source.width,
      height: source.height,
      bitDepth: source.bitDepth,
      colorType: source.colorType,
    },
    ihdrSha256: source.ihdrHash,
  };
}

function receiptFor(row, context, sourcePath, source, origin, metadata) {
  return {
    schema: RECEIPT_SCHEMA,
    variantId: row.variantId,
    slug: row.slug,
    origin,
    provider: metadata.provider,
    model: metadata.model,
    bindings: bindingsFor(row, context),
    artifact: artifactFor(sourcePath, source),
  };
}

async function validateReceipt(row, context, sourcePath, receiptPath) {
  const receipt = await readJsonIfPresent(receiptPath);
  if (!receipt) throw new Error(`${row.variantId} has no v2 generation receipt; use --force or --adopt-existing`);
  requireExactKeys(
    receipt,
    ['schema', 'variantId', 'slug', 'origin', 'provider', 'model', 'bindings', 'artifact'],
    `${row.variantId} receipt`,
  );
  if (
    receipt.schema !== RECEIPT_SCHEMA ||
    receipt.variantId !== row.variantId ||
    receipt.slug !== row.slug ||
    !['generated', 'adopted-v1'].includes(receipt.origin)
  ) {
    throw new Error(`${row.variantId} receipt identity is invalid`);
  }
  if (receipt.origin === 'generated') {
    requireProviderMetadata(receipt, row.variantId);
  } else if (receipt.provider !== null || receipt.model !== null) {
    throw new Error(`${row.variantId} adopted receipt must have null provider and model`);
  }
  requireExactKeys(
    receipt.bindings,
    [
      'catalogSha256',
      'catalogRowsSha256',
      'catalogRowSha256',
      'promptSha256',
      'requestSha256',
      'scriptSha256',
    ],
    `${row.variantId} receipt bindings`,
  );
  if (canonicalJson(receipt.bindings) !== canonicalJson(bindingsFor(row, context))) {
    throw new Error(`${row.variantId} receipt is not exactly bound to the current v2 inputs`);
  }
  requireExactKeys(
    receipt.artifact,
    ['path', 'sha256', 'bytes', 'ihdr', 'ihdrSha256'],
    `${row.variantId} receipt artifact`,
  );
  requireExactKeys(
    receipt.artifact.ihdr,
    ['width', 'height', 'bitDepth', 'colorType'],
    `${row.variantId} receipt artifact IHDR`,
  );
  if (receipt.artifact.path !== relativePath(sourcePath)) {
    throw new Error(`${row.variantId} receipt artifact path is invalid`);
  }
  const source = await readValidPng(sourcePath);
  if (!source) throw new Error(`${row.variantId} receipt artifact is missing`);
  const expectedArtifact = artifactFor(sourcePath, source);
  if (canonicalJson(receipt.artifact) !== canonicalJson(expectedArtifact)) {
    throw new Error(`${row.variantId} receipt artifact binding does not match base.png`);
  }
  return { receipt, source };
}

function creationFor(scriptSha256, metadata, receipt) {
  return {
    script: {
      path: relativePath(SCRIPT_PATH),
      sha256: requireSha256(scriptSha256, 'receipt creation script SHA-256'),
    },
    tool: {
      name: 'ppgen',
      provider: metadata.provider,
      model: metadata.model,
    },
    receiptSha256: canonicalSha256(receipt),
  };
}

function sourceReceiptFor(row, context, sourcePath, source, origin, creation) {
  return {
    variantId: row.variantId,
    slug: row.slug,
    catalogRow: rowIdentity(row),
    prompt: promptFor(row),
    request: requestFor(row),
    bindings: inputBindingsFor(row, context),
    origin,
    source: artifactFor(sourcePath, source),
    creation,
  };
}

function historicReceiptForSourceReceipt(row, receipt) {
  return {
    schema: RECEIPT_SCHEMA,
    variantId: receipt.variantId,
    slug: receipt.slug,
    origin: receipt.origin,
    provider: receipt.creation.tool.provider,
    model: receipt.creation.tool.model,
    bindings: {
      ...receipt.bindings,
      scriptSha256: receipt.creation.script.sha256,
    },
    artifact: {
      ...receipt.source,
      path: relativePath(resolve(BUILD_ROOT, row.slug, 'base.png')),
    },
  };
}

function sourceReceiptLedgerFor(catalog, context, records) {
  return {
    schema: RECEIPT_LEDGER_SCHEMA,
    catalog: {
      path: relativePath(CATALOG_PATH),
      sha256: context.catalogSha256,
      rowsSha256: context.catalogRowsSha256,
      expectedVariantCount: EXPECTED_VARIANT_COUNT,
      parsedVariantCount: catalog.length,
    },
    count: records.length,
    records,
  };
}

function sourceReceiptLedgerInfo(ledger) {
  const bytes = Buffer.from(`${JSON.stringify(ledger, null, 2)}\n`, 'utf8');
  return {
    path: relativePath(RECEIPT_LEDGER_PATH),
    sha256: sha256Bytes(bytes),
    canonicalSha256: canonicalSha256(ledger),
    schema: ledger.schema,
    count: ledger.count,
  };
}

function sourceOutcome(row, receipt, source, status = 'success', action = 'reuse') {
  const sourcePath = sourceAssetPathFor(row);
  return {
    status,
    action,
    row,
    sourcePath,
    destinationPath: sourcePath,
    source,
    metadata: {
      provider: receipt.creation.tool.provider,
      model: receipt.creation.tool.model,
    },
    receipt,
    receiptPath: RECEIPT_LEDGER_PATH,
  };
}

async function readSourceReceiptLedger(catalog, context) {
  let ledgerBytes;
  try {
    const stats = await fs.lstat(RECEIPT_LEDGER_PATH);
    if (!stats.isFile() || stats.isSymbolicLink()) {
      throw new Error(`${relativePath(RECEIPT_LEDGER_PATH)} must be a regular non-symlink file`);
    }
    ledgerBytes = await fs.readFile(RECEIPT_LEDGER_PATH);
  } catch (error) {
    if (error.code === 'ENOENT') {
      throw new Error(
        `Tracked source receipt ledger is missing at ${relativePath(RECEIPT_LEDGER_PATH)}; use --seal-existing-ledger only when historical cache receipts are available`,
      );
    }
    throw error;
  }

  let ledger;
  try {
    ledger = JSON.parse(ledgerBytes.toString('utf8'));
  } catch (error) {
    throw new Error(`${relativePath(RECEIPT_LEDGER_PATH)} is not readable JSON: ${error.message}`);
  }
  requireExactKeys(ledger, ['schema', 'catalog', 'count', 'records'], 'source receipt ledger');
  if (
    ledger.schema !== RECEIPT_LEDGER_SCHEMA ||
    ledger.count !== EXPECTED_VARIANT_COUNT ||
    !Array.isArray(ledger.records) ||
    ledger.records.length !== EXPECTED_VARIANT_COUNT
  ) {
    throw new Error('source receipt ledger must contain exactly one complete 105-record v1 set');
  }
  const expectedCatalog = {
    path: relativePath(CATALOG_PATH),
    sha256: context.catalogSha256,
    rowsSha256: context.catalogRowsSha256,
    expectedVariantCount: EXPECTED_VARIANT_COUNT,
    parsedVariantCount: catalog.length,
  };
  requireExactKeys(
    ledger.catalog,
    ['path', 'sha256', 'rowsSha256', 'expectedVariantCount', 'parsedVariantCount'],
    'source receipt ledger catalog',
  );
  if (canonicalJson(ledger.catalog) !== canonicalJson(expectedCatalog)) {
    throw new Error('source receipt ledger catalog binding is invalid');
  }

  const outcomes = [];
  const hashes = new Set();
  for (const [index, row] of catalog.entries()) {
    const receipt = ledger.records[index];
    const label = `source receipt ledger records[${index}]`;
    requireExactKeys(
      receipt,
      ['variantId', 'slug', 'catalogRow', 'prompt', 'request', 'bindings', 'origin', 'source', 'creation'],
      label,
    );
    if (receipt.variantId !== row.variantId || receipt.slug !== row.slug) {
      throw new Error(`${label} identity does not match catalog order`);
    }
    if (canonicalJson(receipt.catalogRow) !== canonicalJson(rowIdentity(row))) {
      throw new Error(`${row.variantId} source receipt catalog row does not match the current catalog`);
    }
    if (receipt.prompt !== promptFor(row) || canonicalJson(receipt.request) !== canonicalJson(requestFor(row))) {
      throw new Error(`${row.variantId} source receipt prompt or request does not match current inputs`);
    }
    requireExactKeys(
      receipt.bindings,
      ['catalogSha256', 'catalogRowsSha256', 'catalogRowSha256', 'promptSha256', 'requestSha256'],
      `${label} bindings`,
    );
    if (canonicalJson(receipt.bindings) !== canonicalJson(inputBindingsFor(row, context))) {
      throw new Error(`${row.variantId} source receipt bindings do not match current inputs`);
    }
    if (!['generated', 'adopted-v1'].includes(receipt.origin)) {
      throw new Error(`${row.variantId} source receipt origin is invalid`);
    }
    requireExactKeys(
      receipt.creation,
      ['script', 'tool', 'receiptSha256'],
      `${label} creation`,
    );
    requireExactKeys(receipt.creation.script, ['path', 'sha256'], `${label} creation script`);
    if (receipt.creation.script.path !== relativePath(SCRIPT_PATH)) {
      throw new Error(`${row.variantId} source receipt creation script path is invalid`);
    }
    requireSha256(receipt.creation.script.sha256, `${row.variantId} source receipt creation script SHA-256`);
    requireSha256(receipt.creation.receiptSha256, `${row.variantId} source receipt creation receipt SHA-256`);
    requireExactKeys(receipt.creation.tool, ['name', 'provider', 'model'], `${label} creation tool`);
    if (receipt.creation.tool.name !== 'ppgen') {
      throw new Error(`${row.variantId} source receipt creation tool is invalid`);
    }
    if (receipt.origin === 'generated') {
      requireProviderMetadata(receipt.creation.tool, row.variantId);
    } else if (receipt.creation.tool.provider !== null || receipt.creation.tool.model !== null) {
      throw new Error(`${row.variantId} adopted source receipt must not invent provider metadata`);
    }
    const sourcePath = sourceAssetPathFor(row);
    requireExactKeys(
      receipt.source,
      ['path', 'sha256', 'bytes', 'ihdr', 'ihdrSha256'],
      `${label} source`,
    );
    requireExactKeys(
      receipt.source.ihdr,
      ['width', 'height', 'bitDepth', 'colorType'],
      `${label} source IHDR`,
    );
    if (receipt.source.path !== relativePath(sourcePath)) {
      throw new Error(`${row.variantId} source receipt PNG path is invalid`);
    }
    const source = await readValidPng(sourcePath);
    if (!source) throw new Error(`${row.variantId} source receipt PNG is missing`);
    if (canonicalJson(receipt.source) !== canonicalJson(artifactFor(sourcePath, source))) {
      throw new Error(`${row.variantId} source receipt PNG binding does not match the tracked source`);
    }
    if (receipt.creation.receiptSha256 !== canonicalSha256(historicReceiptForSourceReceipt(row, receipt))) {
      throw new Error(`${row.variantId} source receipt creation provenance digest is invalid`);
    }
    if (hashes.has(source.hash)) throw new Error(`source receipt ledger contains duplicate artifact hash ${source.hash}`);
    hashes.add(source.hash);
    outcomes.push(sourceOutcome(row, receipt, source));
  }

  return {
    ledger,
    ledgerInfo: {
      path: relativePath(RECEIPT_LEDGER_PATH),
      sha256: sha256Bytes(ledgerBytes),
      canonicalSha256: canonicalSha256(ledger),
      schema: ledger.schema,
      count: ledger.count,
    },
    outcomes,
  };
}

async function validateHistoricReceipt(row, context, sourcePath, receiptPath) {
  const receipt = await readJsonIfPresent(receiptPath);
  if (!receipt) throw new Error(`${row.variantId} has no historical generation receipt`);
  requireExactKeys(
    receipt,
    ['schema', 'variantId', 'slug', 'origin', 'provider', 'model', 'bindings', 'artifact'],
    `${row.variantId} historical receipt`,
  );
  if (
    receipt.schema !== RECEIPT_SCHEMA ||
    receipt.variantId !== row.variantId ||
    receipt.slug !== row.slug ||
    !['generated', 'adopted-v1'].includes(receipt.origin)
  ) {
    throw new Error(`${row.variantId} historical receipt identity is invalid`);
  }
  if (receipt.origin === 'generated') {
    requireProviderMetadata(receipt, row.variantId);
  } else if (receipt.provider !== null || receipt.model !== null) {
    throw new Error(`${row.variantId} adopted historical receipt must not invent provider metadata`);
  }
  requireExactKeys(
    receipt.bindings,
    ['catalogSha256', 'catalogRowsSha256', 'catalogRowSha256', 'promptSha256', 'requestSha256', 'scriptSha256'],
    `${row.variantId} historical receipt bindings`,
  );
  const expectedInputs = inputBindingsFor(row, context);
  for (const [key, value] of Object.entries(expectedInputs)) {
    if (receipt.bindings[key] !== value) {
      throw new Error(`${row.variantId} historical receipt ${key} does not match current inputs`);
    }
  }
  requireSha256(receipt.bindings.scriptSha256, `${row.variantId} historical receipt script SHA-256`);
  requireExactKeys(
    receipt.artifact,
    ['path', 'sha256', 'bytes', 'ihdr', 'ihdrSha256'],
    `${row.variantId} historical receipt artifact`,
  );
  requireExactKeys(
    receipt.artifact.ihdr,
    ['width', 'height', 'bitDepth', 'colorType'],
    `${row.variantId} historical receipt artifact IHDR`,
  );
  if (receipt.artifact.path !== relativePath(sourcePath)) {
    throw new Error(`${row.variantId} historical receipt artifact path is invalid`);
  }
  const source = await readValidPng(sourcePath);
  if (!source) throw new Error(`${row.variantId} historical receipt artifact is missing`);
  if (canonicalJson(receipt.artifact) !== canonicalJson(artifactFor(sourcePath, source))) {
    throw new Error(`${row.variantId} historical receipt artifact binding does not match base.png`);
  }
  return { receipt, source };
}

async function sealExistingLedger(catalog, context, options) {
  const records = [];
  const outcomes = [];
  const hashes = new Set();
  for (const row of catalog) {
    const cacheSourcePath = resolve(BUILD_ROOT, row.slug, 'base.png');
    const receiptPath = resolve(BUILD_ROOT, row.slug, RECEIPT_FILENAME);
    const historical = await validateHistoricReceipt(row, context, cacheSourcePath, receiptPath);
    const sourcePath = sourceAssetPathFor(row);
    const source = await readValidPng(sourcePath);
    if (
      !source ||
      source.hash !== historical.source.hash ||
      source.bytes !== historical.source.bytes ||
      source.ihdrHash !== historical.source.ihdrHash
    ) {
      throw new Error(`${row.variantId} tracked source does not exactly match its historical receipt artifact`);
    }
    if (hashes.has(source.hash)) throw new Error(`source receipt ledger contains duplicate artifact hash ${source.hash}`);
    hashes.add(source.hash);
    const receipt = sourceReceiptFor(
      row,
      context,
      sourcePath,
      source,
      historical.receipt.origin,
      creationFor(
        historical.receipt.bindings.scriptSha256,
        { provider: historical.receipt.provider, model: historical.receipt.model },
        historical.receipt,
      ),
    );
    records.push(receipt);
    outcomes.push(sourceOutcome(row, receipt, source, options.dryRun ? 'dry-run' : 'success', 'seal'));
  }
  const ledger = sourceReceiptLedgerFor(catalog, context, records);
  if (!options.dryRun) await writeJsonAtomically(RECEIPT_LEDGER_PATH, ledger);
  return { ledger, ledgerInfo: sourceReceiptLedgerInfo(ledger), outcomes };
}

async function validateGeneratedReceiptIfPresent(row, context, sourcePath, receiptPath) {
  try {
    await fs.lstat(receiptPath);
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }

  // A present receipt must validate before this migration can decide whether to reuse it
  // or re-adopt the unchanged v1 artifact. This prevents a stale or malformed receipt
  // from being silently replaced after its source image has changed.
  const validated = await validateHistoricReceipt(row, context, sourcePath, receiptPath);
  return validated.receipt.origin === 'generated' ? validated : null;
}

async function processVariant(row, options, executable, context) {
  const outputDirectory = resolve(BUILD_ROOT, row.slug);
  const sourcePath = resolve(outputDirectory, 'base.png');
  const receiptPath = resolve(outputDirectory, RECEIPT_FILENAME);
  const destinationPath = resolve(ASSET_ROOT, `${row.slug}.png`);
  let metadata;
  let source;
  let receipt;
  if (options.force) {
    if (options.dryRun) return { status: 'dry-run', action: 'generate', row };
    await fs.mkdir(outputDirectory, { recursive: true });
    const request = requestFor(row);
    const result = await runPpgen(executable, [
      '-desc',
      request.description,
      '-style',
      request.style,
      '-baseonly',
      '-out',
      relativePath(outputDirectory),
      '-json',
      '-quiet',
      '-timeout',
      request.timeout,
    ]);
    if (result.error || result.code !== 0) {
      return {
        status: 'failed',
        row,
        message: result.error?.message ?? `ppgen exited with code ${result.code}`,
      };
    }
    try {
      metadata = requireProviderMetadata(providerMetadata(result.stdout), row.variantId);
    } catch (error) {
      return { status: 'failed', row, message: error.message };
    }
    try {
      source = await readValidPng(sourcePath);
    } catch (error) {
      return { status: 'failed', row, message: error.message };
    }
    if (!source) return { status: 'failed', row, message: 'ppgen did not create base.png' };
    receipt = receiptFor(row, context, sourcePath, source, 'generated', metadata);
    await writeJsonAtomically(receiptPath, receipt);
  } else {
    try {
      ({ receipt, source } = await validateReceipt(row, context, sourcePath, receiptPath));
    } catch (error) {
      return { status: 'failed', row, message: error.message };
    }
    metadata = { provider: receipt.provider, model: receipt.model };
    if (options.dryRun) return { status: 'dry-run', action: 'reuse', row };
  }

  try {
    await copyAtomically(sourcePath, destinationPath);
    return {
      status: 'success',
      row,
      sourcePath,
      destinationPath,
      source,
      metadata,
      receipt,
      receiptPath,
    };
  } catch (error) {
    return { status: 'failed', row, message: error.message };
  }
}

async function runPool(rows, options, executable, context) {
  const outcomes = new Array(rows.length);
  let nextIndex = 0;
  async function worker() {
    while (true) {
      const index = nextIndex;
      nextIndex += 1;
      if (index >= rows.length) return;
      try {
        outcomes[index] = await processVariant(rows[index], options, executable, context);
      } catch (error) {
        outcomes[index] = { status: 'failed', row: rows[index], message: error.message };
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(options.concurrency, rows.length) }, worker));
  return outcomes;
}

function relativePath(path) {
  return relative(REPOSITORY_ROOT, path).replaceAll('\\', '/');
}

async function adoptLegacySet(catalog, context, options) {
  const legacy = await readJsonIfPresent(MANIFEST_PATH);
  if (!legacy) throw new Error('--adopt-existing requires an existing v1 manifest');
  requireExactKeys(
    legacy,
    ['schema', 'status', 'generatedAt', 'catalog', 'generator', 'style', 'count', 'records'],
    'v1 manifest',
  );
  if (
    legacy.schema !== LEGACY_MANIFEST_SCHEMA ||
    legacy.status !== 'RAW_IDENTITY_MASTER' ||
    legacy.count !== EXPECTED_VARIANT_COUNT ||
    !Array.isArray(legacy.records) ||
    legacy.records.length !== EXPECTED_VARIANT_COUNT
  ) {
    throw new Error('--adopt-existing requires exactly one complete 105-record v1 manifest');
  }
  requireExactKeys(
    legacy.catalog,
    ['path', 'expectedVariantCount', 'parsedVariantCount'],
    'v1 manifest catalog',
  );
  if (
    legacy.catalog.path !== relativePath(CATALOG_PATH) ||
    legacy.catalog.expectedVariantCount !== EXPECTED_VARIANT_COUNT ||
    legacy.catalog.parsedVariantCount !== EXPECTED_VARIANT_COUNT
  ) {
    throw new Error('v1 manifest catalog reference is invalid');
  }
  requireExactKeys(legacy.generator, ['name', 'provider', 'model'], 'v1 manifest generator');
  if (
    legacy.generator.name !== 'ppgen' ||
    typeof legacy.generator.provider !== 'string' ||
    !legacy.generator.provider ||
    typeof legacy.generator.model !== 'string' ||
    !legacy.generator.model
  ) {
    throw new Error('v1 manifest generator provenance is invalid');
  }
  if (
    typeof legacy.generatedAt !== 'string' ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(legacy.generatedAt)
  ) {
    throw new Error('v1 manifest generatedAt is invalid');
  }
  const expectedStyle = {
    key: 'pixel',
    transparent: true,
    framing: 'full-body 3/4 front',
    artifactRole: 'raw AI identity master; not a final runtime sprite',
    sourceCell: { width: 1024, height: 1024 },
    renderHeights: [96, 64, 36],
  };
  if (canonicalJson(legacy.style) !== canonicalJson(expectedStyle)) {
    throw new Error('v1 manifest style contract is invalid');
  }

  const verified = [];
  const hashes = new Set();
  const recordKeys = [
    'catalogNumber',
    'parentName',
    'displayName',
    'variantId',
    'slug',
    'mobility',
    'equipment',
    'role',
    'gate',
    'prompt',
    'sourcePath',
    'path',
    'hash',
    'bytes',
    'provider',
    'model',
  ];
  for (const [index, row] of catalog.entries()) {
    const sourcePath = resolve(BUILD_ROOT, row.slug, 'base.png');
    const destinationPath = resolve(ASSET_ROOT, `${row.slug}.png`);
    const receiptPath = resolve(BUILD_ROOT, row.slug, RECEIPT_FILENAME);
    const generated = await validateGeneratedReceiptIfPresent(row, context, sourcePath, receiptPath);
    if (generated) {
      const destination = await readValidPng(destinationPath);
      if (
        !destination ||
        generated.source.hash !== destination.hash ||
        generated.source.bytes !== destination.bytes ||
        generated.source.ihdrHash !== destination.ihdrHash
      ) {
        throw new Error(`${row.variantId} generated receipt artifact verification failed`);
      }
      if (hashes.has(generated.source.hash)) {
        throw new Error(`v2 set contains duplicate artifact hash ${generated.source.hash}`);
      }
      hashes.add(generated.source.hash);
      verified.push({
        status: options.dryRun ? 'dry-run' : 'success',
        action: 'reuse',
        row,
        sourcePath,
        destinationPath,
        receiptPath,
        source: generated.source,
        receipt: generated.receipt,
        metadata: { provider: generated.receipt.provider, model: generated.receipt.model },
      });
      continue;
    }

    const record = legacy.records[index];
    requireExactKeys(record, recordKeys, `v1 manifest records[${index}]`);
    const expectedIdentity = rowIdentity(row);
    for (const [key, value] of Object.entries(expectedIdentity)) {
      if (record[key] !== value) throw new Error(`v1 record ${row.variantId} has mismatched ${key}`);
    }
    if (
      record.prompt !== legacyPromptFor(row) ||
      record.sourcePath !== relativePath(sourcePath) ||
      record.path !== relativePath(destinationPath) ||
      record.provider !== legacy.generator.provider ||
      record.model !== legacy.generator.model
    ) {
      throw new Error(`v1 record ${row.variantId} prompt, path, or provenance is invalid`);
    }
    const source = await readValidPng(sourcePath);
    const destination = await readValidPng(destinationPath);
    if (
      !source ||
      !destination ||
      source.hash !== destination.hash ||
      source.bytes !== destination.bytes ||
      source.ihdrHash !== destination.ihdrHash ||
      record.hash !== source.hash ||
      record.bytes !== source.bytes
    ) {
      throw new Error(`v1 record ${row.variantId} artifact verification failed`);
    }
    if (hashes.has(source.hash)) throw new Error(`v1 set contains duplicate artifact hash ${source.hash}`);
    hashes.add(source.hash);
    const receipt = receiptFor(
      row,
      context,
      sourcePath,
      source,
      'adopted-v1',
      { provider: null, model: null },
    );
    verified.push({
      status: options.dryRun ? 'dry-run' : 'success',
      action: 'adopt',
      row,
      sourcePath,
      destinationPath,
      receiptPath,
      source,
      receipt,
      metadata: { provider: null, model: null },
    });
  }

  if (!options.dryRun) {
    for (const outcome of verified) {
      await writeJsonAtomically(outcome.receiptPath, outcome.receipt);
    }
  }
  return verified;
}

function manifestFor(catalog, context, successes, ledgerInfo) {
  const hashes = new Set(successes.map((outcome) => outcome.source.hash));
  if (hashes.size !== EXPECTED_VARIANT_COUNT) {
    throw new Error('Refusing manifest publication: duplicate sprite hashes');
  }
  const origins = new Set(successes.map((outcome) => outcome.receipt.origin));
  const providers = new Set(successes.map((outcome) => outcome.metadata.provider));
  const models = new Set(successes.map((outcome) => outcome.metadata.model));
  const creationScripts = [...new Map(successes.map((outcome) => {
    const script = outcome.receipt.creation.script;
    return [`${script.path}:${script.sha256}`, script];
  })).values()].sort((left, right) => {
    const leftJson = canonicalJson(left);
    const rightJson = canonicalJson(right);
    if (leftJson < rightJson) return -1;
    if (leftJson > rightJson) return 1;
    return 0;
  });
  const singleCreationScript = creationScripts.length === 1 ? creationScripts[0] : null;
  return {
    schema: MANIFEST_SCHEMA,
    status: 'RAW_IDENTITY_MASTER',
    simulationAuthority: false,
    catalog: {
      path: relativePath(CATALOG_PATH),
      expectedVariantCount: EXPECTED_VARIANT_COUNT,
      parsedVariantCount: catalog.length,
      sha256: context.catalogSha256,
      rowsSha256: context.catalogRowsSha256,
    },
    generator: {
      name: 'ppgen',
      origin: origins.size === 1 ? [...origins][0] : 'mixed',
      provider: providers.size === 1 ? [...providers][0] : 'mixed',
      model: models.size === 1 ? [...models][0] : 'mixed',
      scriptPath: singleCreationScript?.path ?? 'mixed',
      scriptSha256: singleCreationScript?.sha256 ?? 'mixed',
      creationScripts,
    },
    verifier: {
      scriptPath: relativePath(SCRIPT_PATH),
      scriptSha256: context.scriptSha256,
    },
    receiptLedger: ledgerInfo,
    style: {
      key: 'pixel',
      transparent: true,
      framing: 'full-body 3/4 front',
      artifactRole: 'raw AI identity master; not a final runtime sprite',
      sourceCell: { width: 1024, height: 1024 },
      renderHeights: [96, 64, 36],
    },
    count: successes.length,
    records: successes.map((outcome) => ({
      ...rowIdentity(outcome.row),
      prompt: promptFor(outcome.row),
      bindings: outcome.receipt.bindings,
      sourcePath: relativePath(outcome.sourcePath),
      path: relativePath(outcome.destinationPath),
      hash: outcome.source.hash,
      bytes: outcome.source.bytes,
      ihdr: outcome.receipt.source.ihdr,
      ihdrSha256: outcome.source.ihdrHash,
      origin: outcome.receipt.origin,
      provider: outcome.metadata.provider,
      model: outcome.metadata.model,
      receiptPath: relativePath(RECEIPT_LEDGER_PATH),
      receiptSha256: canonicalSha256(outcome.receipt),
    })),
  };
}

async function trackedSourceOutcomeFromCacheOutcome(outcome, context) {
  const sourcePath = sourceAssetPathFor(outcome.row);
  const source = await readValidPng(sourcePath);
  if (!source) throw new Error(`${outcome.row.variantId} source PNG was not published`);
  if (
    source.hash !== outcome.source.hash ||
    source.bytes !== outcome.source.bytes ||
    source.ihdrHash !== outcome.source.ihdrHash
  ) {
    throw new Error(`${outcome.row.variantId} source PNG does not match its cache artifact`);
  }
  const receipt = sourceReceiptFor(
    outcome.row,
    context,
    sourcePath,
    source,
    outcome.receipt.origin,
    creationFor(outcome.receipt.bindings.scriptSha256, outcome.metadata, outcome.receipt),
  );
  return sourceOutcome(outcome.row, receipt, source, 'success', outcome.action ?? 'generate');
}

function sourceReceiptLedgerFromOutcomes(catalog, context, outcomes, existingLedger = null) {
  const replacements = new Map(outcomes.map((outcome) => [outcome.row.variantId, outcome.receipt]));
  const records = catalog.map((row, index) => {
    const replacement = replacements.get(row.variantId);
    if (replacement) return replacement;
    if (existingLedger) return existingLedger.records[index];
    throw new Error(`Cannot publish source receipt ledger without ${row.variantId}`);
  });
  return sourceReceiptLedgerFor(catalog, context, records);
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    process.stdout.write(usage());
    return;
  }
  const catalogBytes = await fs.readFile(CATALOG_PATH);
  const catalog = parseCatalog(catalogBytes.toString('utf8'));
  const context = {
    catalogSha256: sha256Bytes(catalogBytes),
    catalogRowsSha256: canonicalSha256(catalog.map(rowIdentity)),
    scriptSha256: sha256Bytes(await fs.readFile(SCRIPT_PATH)),
  };
  const selection = new Set(options.only);
  const selected = selection.size === 0
    ? catalog
    : catalog.filter((row) => selection.has(row.variantId) || selection.has(row.slug) || selection.has(row.displayName));
  if (selection.size > 0 && selected.length === 0) throw new Error('--only did not match a stable ID, slug, or display name');
  const fullRun = selection.size === 0 && selected.length === EXPECTED_VARIANT_COUNT;
  let outcomes;
  let ledgerInfo;

  if (options.sealExistingLedger) {
    const sealed = await sealExistingLedger(catalog, context, options);
    outcomes = sealed.outcomes;
    ledgerInfo = sealed.ledgerInfo;
  } else if (options.force || options.adoptExisting) {
    const existingLedger = !options.dryRun && !fullRun
      ? await readSourceReceiptLedger(catalog, context)
      : null;
    const executable = process.env.PPGEN || DEFAULT_PPGEN;
    const cacheOutcomes = options.adoptExisting
      ? await adoptLegacySet(catalog, context, options)
      : await runPool(selected, options, executable, context);
    const cacheFailures = cacheOutcomes.filter((outcome) => outcome.status === 'failed');
    if (!options.dryRun && cacheFailures.length === 0) {
      const trackedOutcomes = await Promise.all(
        cacheOutcomes.map((outcome) => trackedSourceOutcomeFromCacheOutcome(outcome, context)),
      );
      const ledger = sourceReceiptLedgerFromOutcomes(catalog, context, trackedOutcomes, existingLedger?.ledger);
      await writeJsonAtomically(RECEIPT_LEDGER_PATH, ledger);
      ledgerInfo = sourceReceiptLedgerInfo(ledger);
      const trackedById = new Map(trackedOutcomes.map((outcome) => [outcome.row.variantId, outcome]));
      outcomes = cacheOutcomes.map((outcome) => trackedById.get(outcome.row.variantId) ?? outcome);
    } else {
      outcomes = cacheOutcomes;
    }
  } else {
    const ledger = await readSourceReceiptLedger(catalog, context);
    ledgerInfo = ledger.ledgerInfo;
    const outcomesByVariantId = new Map(ledger.outcomes.map((outcome) => [outcome.row.variantId, outcome]));
    outcomes = selected.map((row) => {
      const outcome = outcomesByVariantId.get(row.variantId);
      if (!outcome) throw new Error(`source receipt ledger does not contain ${row.variantId}`);
      return options.dryRun ? { ...outcome, status: 'dry-run' } : outcome;
    });
  }
  const failures = outcomes.filter((outcome) => outcome.status === 'failed');
  const successes = outcomes.filter((outcome) => outcome.status === 'success');
  const dryRuns = outcomes.filter((outcome) => outcome.status === 'dry-run');

  for (const outcome of failures) {
    process.stderr.write(`FAILED ${outcome.row.variantId}: ${outcome.message}\n`);
  }
  for (const outcome of dryRuns) {
    process.stdout.write(`DRY-RUN ${outcome.action.toUpperCase()} ${outcome.row.variantId}\n`);
  }

  if (!options.dryRun && failures.length === 0 && fullRun && successes.length === EXPECTED_VARIANT_COUNT) {
    if (!ledgerInfo) throw new Error('Refusing manifest publication without a validated source receipt ledger');
    const manifest = manifestFor(catalog, context, successes, ledgerInfo);
    await writeJsonAtomically(MANIFEST_PATH, manifest);
    process.stdout.write(`Published RAW_IDENTITY_MASTER manifest for ${successes.length} source images.\n`);
  } else if (selection.size > 0 || options.dryRun || failures.length > 0) {
    process.stdout.write('Manifest not published (partial selection, dry run, or failures).\n');
  }

  process.stdout.write(
    `Summary: selected=${selected.length} success=${successes.length} dry-run=${dryRuns.length} failed=${failures.length}\n`,
  );
  if (failures.length > 0) process.exitCode = 1;
}

main().catch((error) => {
  process.stderr.write(`Error: ${error.message}\n`);
  process.exitCode = 1;
});
