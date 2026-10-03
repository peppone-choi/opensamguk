// board-lint.mjs 적색 프로브: 위반을 하나씩 심은 보드에서 정확히 그만큼 세고, 깨끗한 보드에서는 0 인지 본다.
// 그리고 금지어 목록이 V3System 「쓰지 않는 말」 원본(boards_v3_shell.py WORDS)을 빠짐없이 덮는지 본다.
//
//   node --test tools/web/board-lint.test.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { execFileSync, spawnSync } from 'node:child_process';
import { FORBIDDEN, KEYS, boardFiles, contrastFloorFailures, contrastMeasured, lintBoards, toMarkdown, updateContrastBaseline } from './board-lint.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

const board = (body) => `<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>시험 보드</title></head><body><x-dc>
<helmet><style>
.btn{display:inline-flex;align-items:center;height:44px;padding:0 16px;cursor:pointer;font-size:14px}.btn.sm{height:32px}
.tip{display:none}.has:hover .tip{display:block}
label.f{display:flex;align-items:center;gap:8px;height:44px;width:300px}
.ib{position:relative;width:28px;height:28px;padding:0;border:0;cursor:pointer}.ib::before{content:'';position:absolute;inset:-8px}
</style></helmet>
<div style="width:390px;height:1200px;overflow:hidden;position:relative;display:flex;flex-direction:column;gap:8px">
${body}
</div></x-dc>
<script type="text/x-dc" data-dc-script data-props='{"$preview":{"width":390,"height":1200}}'>class Component {}</script>
</body></html>`;

const BAD = board(`
<p style="margin:0;font-size:12px;line-height:16px"><span style="color:#999999">faint note</span> <span data-lint="skip" style="color:#bbbbbb">skipped note</span></p>
<button type="button" class="btn">확인</button>
<button type="button" class="btn sm">작게</button>
<button type="button" class="btn" disabled style="opacity:.5">꺼진 단추</button>
<style>@media (max-width: 999px){.btn{letter-spacing:0}}</style>
<div style="position:relative;height:50px"><button type="button" class="btn" style="width:120px">덮인 단추</button><div style="position:absolute;inset:0"></div></div>
<div style="position:relative;height:50px"><button type="button" class="btn" style="width:120px">딤 아래 단추</button><div class="scrim" style="position:absolute;inset:0;background:rgba(0,0,0,.5)"></div></div>
<div style="position:relative;height:50px"><button type="button" class="mk" style="width:44px;height:44px;cursor:pointer">표식</button><div class="sheet" style="position:absolute;inset:0"><span>시트</span></div></div>
<span class="btn">가짜 단추</span>
<span title="여기에만 있는 정보">?</span>
<div class="has">올리면<span class="tip">드러남</span></div>
<p>휘하 장수 🙂 · 縣 · 군량 · 200년 3월 · 200년 3월 중순</p>
<p style="text-decoration:line-through">휘하</p>
<p><s>군량</s></p>
<label class="f">이름 <input></label>
<button type="button" class="btn" style="position:absolute;left:10px;top:1300px">밖</button>
<span style="position:absolute;left:-9999px">화면 읽기 전용</span>
<svg width="390" height="60" style="overflow:visible"><text x="370" y="30">지도 끝 글자가 잘림</text></svg>
<div style="overflow:hidden;width:390px"><div style="display:flex;gap:4px"><button type="button" class="btn" style="width:300px;flex-shrink:0">1순</button><button type="button" class="btn" style="width:300px;flex-shrink:0">2순</button></div></div>
<div data-lint="skip"><p>설계 설명: 縣 보기 🙂</p></div>
<p>진류현 <span class="muted">陳留</span> · 양성현 <span class="hj">陽城</span> · <span>logo-wordmark.png</span> <img src="x.png" alt="오픈삼국" style="width:40px;height:12px"><img src="x.png" alt="오픈삼국" style="width:40px;height:12px"></p>`);

const GOOD = board(`
<button type="button" class="btn">확인</button>
<div style="padding:8px"><button type="button" class="ib" aria-label="닫기"></button></div>
<label class="f">이름 <input></label>
<p>하후돈 · 조조 소속 · 200년 3월 중순 · 200년 3월 월단평 · 금 · 쌀 ▲ · 양성현 <span class="hj">陽城</span></p>
<p><a href="Other.dc.html">문장 속 링크</a>는 예외다</p>
<div class="inp">hahoudon.png</div>`);

let dir;
before(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'k10-board-'));
  fs.writeFileSync(path.join(dir, 'Bad.dc.html'), BAD);
  fs.writeFileSync(path.join(dir, 'Good.dc.html'), GOOD);
  fs.writeFileSync(path.join(dir, 'skip.html'), GOOD);
});
after(() => fs.rmSync(dir, { recursive: true, force: true }));

test('폴더에서는 *.dc.html 만 고른다', () => {
  assert.deepEqual(boardFiles([dir]).map((f) => path.basename(f)), ['Bad.dc.html', 'Good.dc.html']);
});

test('심은 위반을 정확히 센다(적색) · 깨끗한 보드는 0', async () => {
  const [bad, good] = await lintBoards(boardFiles([dir]));
  assert.deepEqual(bad.size, { w: 390, h: 1200 }); // 높이를 넉넉히 — 꽉 차면 flex 가 단추를 줄여 small 이 흔들린다
  // 투명 상자에 덮인 단추는 small 이 아니라 covered 다(무엇이 덮었는지 함께)
  assert.deepEqual(bad.counts, { small: 1, fake: 1, title: 1, hover: 1, disabledAttr: 1, dimmed: 1, breakpoint: 1, emoji: 1, words: 3, hanja: 2, clipped: 1, covered: 2, placeholder: 1, logo: 1, contrast: 1 }, `판정 못 함 ${bad.contrastUnknown}: ${JSON.stringify(bad.samples.contrastUnknown)} / ${JSON.stringify(bad.samples, null, 1)}`);
  // 글자 대비: 흰 바탕 #999 글자 하나(2.85:1). skip 안의 #bbb 는 세지 않는다. 판정 못 함은 따로 센다.
  // 대비 고정물은 BAD 맨 위 한 줄(16px)에 둔다: 뿌리(높이 1200 · overflow hidden 세로 flex)가 거의 차 있어, CI 처럼 글꼴이 달라 줄이 늘면
  // 아래쪽 글자가 뿌리 밖으로 밀려 잘리고, 잘린 글자는 axe 가 세지 않는다(#1276 CI 두 번 로컬 1 · CI 0). 글자는 라틴 문자다.
  assert.deepEqual(bad.samples.contrast.map((x) => [x.text, x.fg, x.need]), [['faint note', '#999999', '4.5:1']], `판정 못 함 ${bad.contrastUnknown}`);
  assert.equal(typeof bad.contrastUnknown, 'number');
  assert.deepEqual(bad.words, { 휘하: 1, 군량: 1, 'N년 N월(순 없음)': 1 });
  // 한자는 hj 밖의 縣 · 陳留 두 덩이 — hj 안 陽城 · skip 안 縣 · 취소선은 세지 않는다
  assert.deepEqual(bad.samples.hanja.map((x) => x.text), ['縣', '陳留']);
  assert.equal(bad.samples.small[0].h, 32);
  // 층 없이 덮인 단추와, 시트 아래여도 지도 표식은 결함. 딤 아래 단추는 정상(underLayer).
  assert.deepEqual(bad.samples.covered.map((x) => [x.text, x.layer ?? null]), [['덮인 단추', null], ['표식', '시트']]);
  assert.equal(bad.underLayer, 1);
  assert.equal(bad.samples.underLayer[0].layer, '딤');
  assert.equal(bad.innerCropped, 1); // 순 띠의 2순
  assert.equal(bad.lintSkipBlocks, 2); // 대비 고정물의 skip 하나 + 설계 설명 하나
  assert.deepEqual(good.counts, Object.fromEntries(KEYS.map((k) => [k, 0])), JSON.stringify(good.samples, null, 1));
  assert.equal(good.smallInline, 1);
  assert.match(toMarkdown([bad, good]), /\| \*\*합계\*\* \|/);
});

// V3System 「쓰지 않는 말」 원본의 왼쪽 칸을 낱말로 쪼개, 모두 FORBIDDEN 의 어느 규칙에 걸리는지 본다.
test('금지어 목록이 V3System 원본을 빠짐없이 덮는다', () => {
  const src = fs.readFileSync(path.join(ROOT, 'docs/design/ui-v3/boards_v3_shell.py'), 'utf8');
  const block = src.match(/WORDS = \[([\s\S]*?)\]\n/);
  assert.ok(block, 'boards_v3_shell.py 에서 WORDS 를 못 찾았다');
  const lefts = [...block[1].matchAll(/\('([^']+)',\s*'[^']*'\)/g)].map((m) => m[1]);
  assert.ok(lefts.length >= 9, `WORDS 행 ${lefts.length}`);
  const samples = [];
  for (const left of lefts) {
    for (const part of left.split(' · ')) {
      const han = part.match(/\(([㐀-鿿])\)/); // 전(錢) → 錢
      if (han) samples.push(han[1]);
      else if (part.startsWith('년 월')) samples.push('200년 3월');
      else samples.push(part.replace(/\(.*\)/, '').trim()); // 계책 손패(화면 이름) → 계책 손패
    }
  }
  const missing = samples.filter((t) => !FORBIDDEN.some((f) => new RegExp(f.re, 'u').test(t)));
  assert.deepEqual(missing, [], `FORBIDDEN 에 없는 말: ${missing.join(', ')}`);
});

test('--help 는 머리 주석 끝(종료 코드 규칙)까지 보인다', () => {
  const out = execFileSync(process.execPath, [path.join(ROOT, 'tools/web/board-lint.mjs'), '--help'], { encoding: 'utf8' });
  for (const want of ['placeholder', 'logo', 'clipped', '종료 코드 1']) assert.ok(out.includes(want), `도움말에 「${want}」가 없다`);
  const lines = fs.readFileSync(path.join(ROOT, 'tools/web/board-lint.mjs'), 'utf8').split('\n');
  assert.equal(out.trimEnd(), lines.slice(0, lines.findIndex((l) => l.startsWith('import '))).join('\n').trimEnd());
});

// 미리보기 크기가 뿌리보다 작아도(뿌리가 검사 화면 밖으로 나가도) 화면 밖 누를 것을 「덮임」으로 잘못 세지 않는다.
test('미리보기가 뿌리보다 작아도 덮임 오탐이 없다', async () => {
  const tall = board(`<div style="height:900px"></div><button type="button" class="btn">아래 단추</button>`)
    .replace('"height":1200', '"height":300');
  const f = path.join(dir, 'Tall.dc.html');
  fs.writeFileSync(f, tall);
  try {
    const [r] = await lintBoards([f]);
    assert.equal(r.counts.covered, 0, JSON.stringify(r.samples.covered));
    assert.equal(r.counts.small, 0, JSON.stringify(r.samples.small));
  } finally { fs.rmSync(f, { force: true }); }
});

// --fail-on contrast 게이트(CI naming-lint 단계, 2026-10-03): 대비 미달 보드가 있으면 종료 코드 1 이고, 걸린 노드를 로그에 찍는다.
// 깨끗한 보드만 있으면 0. 잰 노드 수(통과 + 미달 + 판정 못 함)는 표에 남는다.
test('--fail-on contrast: 미달이면 exit 1 · 걸린 노드를 찍는다, 깨끗하면 0', async () => {
  const cli = path.join(ROOT, 'tools/web/board-lint.mjs');
  const bad = spawnSync(process.execPath, [cli, path.join(dir, 'Bad.dc.html'), '--fail-on', 'contrast'], { encoding: 'utf8' });
  assert.equal(bad.status, 1, bad.stderr);
  assert.match(bad.stderr, /\[contrast\] Bad\.dc\.html: .*faint note.*#999999/);
  const good = spawnSync(process.execPath, [cli, path.join(dir, 'Good.dc.html'), '--fail-on', 'contrast'], { encoding: 'utf8' });
  assert.equal(good.status, 0, good.stderr);
  assert.match(good.stdout, /대비 잰 노드/);
  const [r] = await lintBoards([path.join(dir, 'Good.dc.html')]);
  assert.ok(r.contrastPass > 0, `깨끗한 보드에서 잰 노드가 있어야 한다(조회가 살아 있는지): ${r.contrastPass}`);
});

// 잰 노드 하한(--contrast-floor, 2026-10-04): 기준선보다 허용 차이를 넘게 줄었거나 기준선에 없는 보드가 0 이면 걸린다.
test('잰 노드 하한: 기준선보다 허용 넘게 줄면 · 새 보드가 0 이면 걸린다', () => {
  const r = (name, pass, fail = 0, unknown = 0, extra = {}) => ({ name, contrastPass: pass, contrastUnknown: unknown, counts: { contrast: fail }, ...extra });
  assert.equal(contrastMeasured(r('A.dc.html', 6, 1, 2)), 9);
  const baseline = { tolerance: 1, boards: { 'A.dc.html': 10, 'B.dc.html': 10, 'Gone.dc.html': 5 } };
  const got = contrastFloorFailures([
    r('A.dc.html', 9), // 10 − 1 = 9: 허용 안
    r('B.dc.html', 8), // 8 < 9: 걸림
    r('New.dc.html', 0), // 기준선 없음 + 0: 걸림
    r('New2.dc.html', 3), // 기준선 없음 + 3: 통과
    r('Err.dc.html', 0, 0, 0, { error: '열지 못함' }), // 검사 실패는 따로 실패한다
  ], baseline);
  assert.deepEqual(got.map((f) => [f.name, f.got, f.base]), [['B.dc.html', 8, 10], ['New.dc.html', 0, null]]);
  // 갱신은 결과에 있는 보드만 덮어쓰고(검사 실패 제외) 나머지 칸 · 다른 보드는 그대로 둔다. 보드 이름 순으로 쓴다.
  const next = updateContrastBaseline({ tolerance: 1, why: 'w', boards: { 'B.dc.html': 10, 'A.dc.html': 10 } }, [r('B.dc.html', 8), r('C.dc.html', 4), r('Err.dc.html', 0, 0, 0, { error: 'x' })], 'src');
  assert.deepEqual(next, { tolerance: 1, why: 'w', source: 'src', boards: { 'A.dc.html': 10, 'B.dc.html': 8, 'C.dc.html': 4 } });
  assert.deepEqual(Object.keys(next.boards), ['A.dc.html', 'B.dc.html', 'C.dc.html']);
});

// CI 적색 확인과 같은 순서: 같은 보드에서 글자를 뿌리 밖으로 밀어 자르면(axe 는 잘린 글자를 세지 않는다) 잰 노드가 줄어 exit 1,
// 같은 자름에 기준선을 같이 고치면 0.
test('--contrast-floor: 글자가 잘려 덜 재면 exit 1 · 기준선을 같이 고치면 0', () => {
  const cli = path.join(ROOT, 'tools/web/board-lint.mjs');
  const node = (...args) => spawnSync(process.execPath, [cli, ...args], { encoding: 'utf8' });
  const base = path.join(dir, 'baseline.json');
  const goodJson = path.join(dir, 'good.json');
  const clipJson = path.join(dir, 'clip.json');
  const clipDir = fs.mkdtempSync(path.join(dir, 'clip-'));
  const clipped = path.join(clipDir, 'Good.dc.html');
  const first = '<button type="button" class="btn">확인</button>';
  assert.ok(GOOD.includes(first));
  fs.writeFileSync(clipped, GOOD.replace(first, `<div style="height:1300px;flex-shrink:0"></div>${first}`));

  assert.equal(node(path.join(dir, 'Good.dc.html'), '--fail-on', 'contrast', '--json', goodJson).status, 0);
  fs.writeFileSync(base, JSON.stringify({ tolerance: 1, boards: {} }));
  const made = node('--update-contrast-floor', base, '--from', goodJson, '--source', '시험');
  assert.equal(made.status, 0, made.stderr);
  const before = JSON.parse(fs.readFileSync(base, 'utf8')).boards['Good.dc.html'];
  assert.ok(before > 1, `깨끗한 보드에서 잰 노드가 있어야 한다(조회가 살아 있는지): ${before}`);

  const red = node(clipped, '--fail-on', 'contrast', '--contrast-floor', base, '--json', clipJson);
  assert.equal(red.status, 1, red.stderr);
  assert.match(red.stderr, new RegExp(`\\[contrastFloor\\] Good\\.dc\\.html: 잰 노드 \\d+ < 기준선 ${before} − 허용 1`));

  assert.equal(node('--update-contrast-floor', base, '--from', clipJson).status, 0);
  const green = node(clipped, '--fail-on', 'contrast', '--contrast-floor', base);
  assert.equal(green.status, 0, green.stderr);
  assert.match(green.stderr, /--contrast-floor .*기준선 아래 0장/);
});
