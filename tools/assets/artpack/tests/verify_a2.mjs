// Execute the unmodified, blob-pinned A2 validator and frame resolver. The only
// isolated dependency is battleUnits' four direction labels (pinned line 132).
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { registerHooks } from 'node:module';

const fixture = new URL('./fixtures/a2-manifest.ts', import.meta.url);
const bytes = readFileSync(fixture);
const blob = createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
assert.equal(blob, 'b84694776495f5e6516b4dc206c2a50c6f9b2a94', 'A2 fixture changed: explicitly re-compare the new contract');
registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === '../battleUnits' && context.parentURL === fixture.href) {
      return { url: "data:text/javascript,export const UNIT_FACINGS=['sw','nw','ne','se'];", shortCircuit: true };
    }
    return nextResolve(specifier, context);
  },
});
const a2 = await import(fixture.href);
const manifest = JSON.parse(readFileSync(process.argv[2], 'utf8'));
assert.deepEqual(a2.validateArtpackManifest(manifest), []);
assert.equal(a2.parseArtpackManifest(manifest), manifest);
assert.deepEqual([a2.EDGE_NE, a2.EDGE_SE, a2.EDGE_SW, a2.EDGE_NW], [1, 2, 4, 8]);
assert.deepEqual(a2.ARTPACK_ACTIONS, { idle: 2, move: 4, attack: 4, hit: 2, fall: 4 });
for (const [slot, unit] of Object.entries(manifest.units)) {
  for (const [action, def] of Object.entries(unit.actions)) {
    for (const [facing, target] of [['sw', 'sw'], ['nw', 'nw'], ['ne', 'nw'], ['se', 'sw']]) {
      const actual = a2.resolveUnitFrame(manifest, slot, action, facing, -1);
      const expected = def.directions[target];
      assert.deepEqual(actual.frame, expected.frames[def.frames - 1]);
      assert.deepEqual(actual.mask, expected.masks?.[def.frames - 1] ?? null);
      assert.equal(actual.flip, facing === 'ne' || facing === 'se');
      // Renderer draw origin: mirrored ax = w-ax, all in pack px / ppb.
      const anchor = actual.flip ? actual.frame.w - actual.frame.ax : actual.frame.ax;
      assert.ok(anchor >= 0 && anchor <= actual.frame.w);
    }
  }
  const noHit = structuredClone(manifest);
  delete noHit.units[slot].actions.hit;
  assert.deepEqual(a2.resolveUnitFrame(noHit, slot, 'hit', 'sw', 0), a2.resolveUnitFrame(noHit, slot, 'idle', 'sw', 0));
}
assert.equal(a2.resolveUnitFrame(manifest, 'missing-slot', 'idle', 'sw', 0), null);
// Confirm canonical validator rejects representative mutations, not just accepts
// our output. These cases must be invalid in BOTH implementations.
for (const mutate of [
  m => { m.schemaVersion = 2; },
  m => { m.terrain.P.ground[0].x = m.atlases[0].width; },
  m => { m.terrain.P.ground[0].ax = -1; },
  m => { m.transitions.R[0] = m.terrain.P.ground[0]; },
  m => { delete m.units.spear.actions.idle; },
  m => { m.units.spear.actions.idle.directions.sw.masks = []; },
  m => { m.provenance = [{source:'generated', note:'missing attribution'}]; },
]) {
  const changed = structuredClone(manifest);
  mutate(changed);
  assert.ok(a2.validateArtpackManifest(changed).length > 0);
}
console.log(`A2 validator/resolver PASS blob=${blob}`);
