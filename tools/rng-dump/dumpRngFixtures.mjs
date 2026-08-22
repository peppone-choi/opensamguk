import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { writeFileSync } from 'node:fs';

const here = dirname(fileURLToPath(import.meta.url));
const SRC = resolve(here, '../../legacy/devsam-core2026/packages/common/src');

const { LiteHashDRBG } = await import(`${SRC}/util/LiteHashDRBG.ts`);
const { RandUtil } = await import(`${SRC}/util/RandUtil.ts`);
const { createTournamentSeedKey } = await import(`${SRC}/util/TournamentRNG.ts`);

const hex = (u8) => Buffer.from(u8).toString('hex');
const floatBits = (d) => { const b = Buffer.alloc(8); b.writeDoubleBE(d, 0); return b.toString('hex'); };

const out = {};

// (1) raw SHA-512 blocks for stateIdx 0..4 on seed 'HelloWorld' (= rng.test.ts testVector)
{ const rng = new LiteHashDRBG('HelloWorld'); out.helloWorldBlocks = hex(rng.nextBytes(64 * 5)); }

// (2) byte-stream draws incl baseBytes pad
{ const rng = new LiteHashDRBG('HelloWorld');
  out.bytesSeq = { b10: hex(rng.nextBytes(10)), b32: hex(rng.nextBytes(32)), b1: hex(rng.nextBytes(1)),
    b64: hex(rng.nextBytes(64)), b5: hex(rng.nextBytes(5)), b16pad18: hex(rng.nextBytes(16, 18)) }; }

// (3) bit draws
{ const rng = new LiteHashDRBG('HelloWorld'); out.bitsSeq = {};
  for (const bits of [10, 4, 15, 32, 7, 99, 512, 1, 2, 3]) out.bitsSeq[String(bits)] = hex(rng.nextBits(bits)); }

// (4) nextFloat1 — 18 draws as raw 64-bit hex
{ const rng = new LiteHashDRBG('HelloWorld'); out.floatSeq = Array.from({ length: 18 }, () => floatBits(rng.nextFloat1())); }

// (5) nextInt draws (incl rejection-sampling i99 and default path)
{ const rng = new LiteHashDRBG('HelloWorld');
  out.intSeq = { i255: rng.nextInt(0xff), i65535: rng.nextInt((1 << 16) - 1), i4G: rng.nextInt(0xffffffff),
    iDefault: rng.nextInt(), i15: rng.nextInt(0x0f), i18: rng.nextInt(0x12), i99: rng.nextInt(99) }; }

// (5b) inclusive-max golden: a deterministic block where nextInt(max) draws EXACTLY max (proves n==max ACCEPTED, not just in-range).
//      Find a seed/max pair from a known block whose first nextInt(max) == max; the frozen historical TS fixture
//      (ADR-LITE-042; not current product authority) records nextInt(0x99)→0x99.
{ const rng = new LiteHashDRBG('inclusiveMax');
  out.intInclusiveMax = { max: 0x99, draw: rng.nextInt(0x99) }; }   // assert draw === max in the Kotlin test

// (6) alignment-stress: >5 block refills, alternating widths
{ const rng = new LiteHashDRBG('alignStress'); const seq = [];
  for (let i = 0; i < 40; i++) { seq.push(['bits7', hex(rng.nextBits(7))]); seq.push(['bytes1', hex(rng.nextBytes(1))]); seq.push(['int99', rng.nextInt(99)]); }
  out.alignStress = seq; }

// (7) RandUtil draws on a fixed seed
{ const mk = () => new RandUtil(new LiteHashDRBG('randUtilSeed'));
  const range = (n) => Array.from({ length: n }, (_, i) => i);
  let ru = mk(); out.randUtil = { nextInt_5_10: Array.from({ length: 8 }, () => ru.nextInt(5, 10)) };
  ru = mk(); out.randUtil.nextRangeInt_0_9 = Array.from({ length: 8 }, () => ru.nextRangeInt(0, 9));
  ru = mk(); out.randUtil.nextBool_half = Array.from({ length: 16 }, () => ru.nextBool(0.5));
  ru = mk(); out.randUtil.nextBool_0_3 = Array.from({ length: 16 }, () => ru.nextBool(0.3));
  ru = mk(); out.randUtil.nextBit = Array.from({ length: 16 }, () => ru.nextBit());
  ru = mk(); out.randUtil.nextFloat1 = Array.from({ length: 6 }, () => floatBits(ru.nextFloat1()));
  out.randUtil.shuffle = {};
  for (const n of [0, 1, 2, 8, 10, 17]) { const r = mk(); out.randUtil.shuffle[String(n)] = r.shuffle(range(n)); }
  ru = mk(); out.randUtil.choiceArray = Array.from({ length: 6 }, () => ru.choice([0, 1, 2, 3, 4, 5]));
  ru = mk(); out.randUtil.choiceSet = ru.choice(new Set([5, 3, 1, 2, 8, 0]));
  ru = mk(); out.randUtil.choiceRecord = ru.choice({ c: 'c', a: 'a', b: 'b', 4: 'x', 2: 't', '3': 'q' });
  ru = mk(); out.randUtil.choiceWeight = ru.choiceUsingWeight({ a: 0.1, b: 10, tt: 2, x: -1, c: 20, d: 0, e: 6 });
  ru = mk(); out.randUtil.choiceWeightNumeric = ru.choiceUsingWeight({ 10: 5, 2: 5, 1: 5, 3: 5 }); }

// (8) seed-string serializers (str/int + tournament double-pipe quirk)
{ const serializeSeed = (...values) =>
    values.map((v) => (typeof v === 'string' ? `str(${v.length},${v})` : `int(${Math.floor(v)})`)).join('|');
  out.seeds = {
    mixed: serializeSeed('ConquerCity', 190, 3, 'attacker가나', 42),
    floored: serializeSeed(3.9, -2.1),
    tournamentNoGame: createTournamentSeedKey('base', { openYear: 200, openMonth: 1, stage: 0, phase: 0, matchIndex: 0, participantIndex: 0 }),
    tournamentWithGame: createTournamentSeedKey('base', { openYear: 200, openMonth: 1, stage: 0, phase: 0, matchIndex: 0, participantIndex: 0, gameIndex: 7, extraSeed: 'xs' }),
  };
  out.seedDrawMixed = hex(new LiteHashDRBG(out.seeds.mixed).nextBytes(16)); }

writeFileSync(resolve(here, '../../common/src/test/resources/rng/rng-fixtures.json'), JSON.stringify(out, null, 2) + '\n');
console.log('wrote rng-fixtures.json');
