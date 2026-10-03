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
import { execFileSync } from 'node:child_process';
import { FORBIDDEN, KEYS, boardFiles, lintBoards, toMarkdown } from './board-lint.mjs';

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
<p style="color:#999999">faint note</p>
<p style="text-decoration:line-through">휘하</p>
<p><s>군량</s></p>
<label class="f">이름 <input></label>
<button type="button" class="btn" style="position:absolute;left:10px;top:1300px">밖</button>
<span style="position:absolute;left:-9999px">화면 읽기 전용</span>
<svg width="390" height="60" style="overflow:visible"><text x="370" y="30">지도 끝 글자가 잘림</text></svg>
<div style="overflow:hidden;width:390px"><div style="display:flex;gap:4px"><button type="button" class="btn" style="width:300px;flex-shrink:0">1순</button><button type="button" class="btn" style="width:300px;flex-shrink:0">2순</button></div></div>
<div data-lint="skip"><p>설계 설명: 縣 보기 🙂</p><p style="color:#bbbbbb">skipped note</p></div>
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
  assert.deepEqual(bad.counts, { small: 1, fake: 1, title: 1, hover: 1, disabledAttr: 1, dimmed: 1, breakpoint: 1, emoji: 1, words: 3, hanja: 2, clipped: 1, covered: 2, placeholder: 1, logo: 1, contrast: 1 }, JSON.stringify(bad.samples, null, 1));
  // 글자 대비: 흰 바탕 #999 글자 하나(2.85:1). skip 안의 #bbb 는 세지 않는다. 판정 못 함은 따로 센다.
  // 대비 고정물 글자는 라틴 문자다: CI 리눅스에는 한글 글꼴이 없어 한글이 빈 상자로 그려지고, axe 가 그것을 아이콘 글자로 보고
  // 대비 검사에서 뺀다(로컬 맥 1 · CI 0, #1276 첫 CI). 실제 보드를 CI 에서 검사하려면 한글 글꼴을 깔아야 한다.
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
  assert.equal(bad.lintSkipBlocks, 1);
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
