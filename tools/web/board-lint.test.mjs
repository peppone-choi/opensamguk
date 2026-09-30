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
import { FORBIDDEN, KEYS, boardFiles, lintBoards, toMarkdown } from './board-lint.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

const board = (body) => `<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>시험 보드</title></head><body><x-dc>
<helmet><style>
.btn{display:inline-flex;align-items:center;height:44px;padding:0 16px;cursor:pointer;font-size:14px}.btn.sm{height:32px}
.tip{display:none}.has:hover .tip{display:block}
label.f{display:flex;align-items:center;gap:8px;height:44px;width:300px}
</style></helmet>
<div style="width:390px;height:844px;overflow:hidden;position:relative;display:flex;flex-direction:column;gap:8px">
${body}
</div></x-dc>
<script type="text/x-dc" data-dc-script data-props='{"$preview":{"width":390,"height":844}}'>class Component {}</script>
</body></html>`;

const BAD = board(`
<button type="button" class="btn">확인</button>
<button type="button" class="btn sm">작게</button>
<span class="btn">가짜 단추</span>
<span title="여기에만 있는 정보">?</span>
<div class="has">올리면<span class="tip">드러남</span></div>
<p>휘하 장수 🙂 · 縣 · 군량 · 200년 3월 · 200년 3월 중순</p>
<p style="text-decoration:line-through">휘하</p>
<p><s>군량</s></p>
<label class="f">이름 <input></label>
<button type="button" class="btn" style="position:absolute;left:10px;top:900px">밖</button>
<span style="position:absolute;left:-9999px">화면 읽기 전용</span>
<svg width="390" height="60" style="overflow:visible"><text x="370" y="30">지도 끝 글자가 잘림</text></svg>
<div style="overflow:hidden;width:390px"><div style="display:flex;gap:4px"><button type="button" class="btn" style="width:300px;flex-shrink:0">1순</button><button type="button" class="btn" style="width:300px;flex-shrink:0">2순</button></div></div>
<div data-lint="skip"><p>설계 설명: 縣 보기 🙂</p></div>`);

const GOOD = board(`
<button type="button" class="btn">확인</button>
<label class="f">이름 <input></label>
<p>하후돈 · 조조 소속 · 200년 3월 중순 · 금 · 쌀 ▲</p>
<p><a href="Other.dc.html">문장 속 링크</a>는 예외다</p>`);

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
  assert.deepEqual(bad.size, { w: 390, h: 844 });
  assert.deepEqual(bad.counts, { small: 1, fake: 1, title: 1, hover: 1, emoji: 1, words: 4, clipped: 1 }, JSON.stringify(bad.samples, null, 1));
  assert.deepEqual(bad.words, { 휘하: 1, 縣: 1, 군량: 1, 'N년 N월(순 없음)': 1 });
  assert.equal(bad.samples.small[0].h, 32);
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
