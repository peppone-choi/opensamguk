// measure-pages.mjs 적색 프로브: 위반을 일부러 심은 화면에서 기준이 실제로 걸리고, 깨끗한 화면에서는 안 걸리는지 본다.
// 「exit 0 은 게이트의 증거가 아니다」 — 검사가 살아 있다는 증거는 깨뜨렸을 때 빨개지는 것이다.
//
//   node --test tools/web/measure-pages.test.mjs
//
// 시스템 Chrome 과 web/game 의 node_modules 가 있어야 한다. 네트워크는 이 프로세스 안의 로컬 서버만 쓴다.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { after, before, test } from 'node:test';
import { defaultOptions, duplicateTransfers, run, slugOf } from './measure-pages.mjs';

const PNG_1PX = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64');

const BAD = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>나쁜 화면</title>
<style>.tiny{font-size:10px}.tip{display:none}.has:hover .tip{display:block}</style></head><body>
<button type="button" style="width:30px;height:30px;padding:0">가</button>
<span title="여기에만 있는 정보">?</span>
<img src="/pic.png">
<p class="tiny">작은 글자</p>
<div class="has">마우스를 올리면<span class="tip">드러남</span></div>
<div class="os-iso-map" style="width:600px;height:400px"><canvas width="600" height="400"></canvas></div>
<div style="width:2000px;height:10px"></div>
<script>
// 본문을 읽어야 전송이 끝난다(안 읽으면 망이 잠잠해지지 않는다).
for (const u of ['/big.bin', '/big.bin', '/missing']) fetch(u, { cache: 'no-store' }).then((r) => r.arrayBuffer());
console.error('일부러 낸 오류');
setTimeout(() => { const g = document.querySelector('canvas').getContext('2d'); g.fillStyle = '#486'; g.fillRect(0, 0, 600, 400); }, 3500);
</script></body></html>`;

const GOOD = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>깨끗한 화면</title></head><body>
<main><h1>깨끗한 화면</h1><button type="button" style="min-width:44px;min-height:44px">확인</button><img src="/pic.png" alt="표식"></main>
</body></html>`;

let server; let base; let outDir;

before(async () => {
  server = http.createServer((req, res) => {
    const send = (status, type, body) => { res.writeHead(status, { 'content-type': type, 'cache-control': 'no-store' }); res.end(body); };
    if (req.url === '/bad') return send(200, 'text/html; charset=utf-8', BAD);
    if (req.url === '/good') return send(200, 'text/html; charset=utf-8', GOOD);
    if (req.url === '/pic.png' || req.url === '/favicon.ico') return send(200, 'image/png', PNG_1PX);
    if (req.url === '/big.bin') return send(200, 'application/octet-stream', Buffer.alloc(200_000, 7));
    return send(404, 'text/plain', 'none');
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
  outDir = fs.mkdtempSync(path.join(os.tmpdir(), 'k10-measure-'));
});

after(() => { server.close(); fs.rmSync(outDir, { recursive: true, force: true }); });

test('slugOf', () => {
  assert.equal(slugOf('/'), 'root');
  assert.equal(slugOf('/board/posts/12'), 'board-posts-12');
});

test('duplicateTransfers 는 304 · 0바이트를 세지 않는다', () => {
  const d = duplicateTransfers([
    { url: 'https://x/a', bytes: 10, status: 200 }, { url: 'https://x/a', bytes: 10, status: 200 },
    { url: 'https://x/b', bytes: 10, status: 200 }, { url: 'https://x/b', bytes: 0, status: 304 },
  ]);
  assert.deepEqual(d.map((g) => g.url), ['https://x/a']);
});

test('나쁜 화면: 심은 위반이 전부 걸린다(적색)', async () => {
  const [row] = await run(defaultOptions({ base, pages: ['/bad'], profiles: ['desktop'], throttles: ['broadband'], out: outDir }));
  const full = JSON.parse(fs.readFileSync(path.join(outDir, 'bad-desktop-broadband.json'), 'utf8'));
  for (const id of ['map-first-draw-3s', 'no-duplicate-transfer', 'console-errors-0', 'network-failures-0', 'axe-critical-0', 'touch-target-44', 'no-title-only-info']) {
    assert.ok(row.failedChecks.includes(id), `${id} 가 걸려야 한다: ${row.failedChecks}`);
  }
  assert.ok(full.firstMapDrawMs >= 3000 && full.firstMapDrawMs < 20_000, `지도 첫 그림 ${full.firstMapDrawMs}`);
  assert.equal(full.duplicates.count, 1);
  assert.ok(full.layout.horizontalOverflowPx > 0);
  assert.ok(full.layout.textUnder12px >= 1);
  assert.ok(full.layout.hoverRevealRules >= 1);
  assert.equal(full.map.hitTest.isCanvas, true);
  assert.equal(full.map.probe.wheelChangedMap, false);
  assert.ok(full.axe.rules.some((r) => r.id === 'image-alt'));
});

test('깨끗한 화면: 걸리는 기준이 없다(모바일)', async () => {
  const [row] = await run(defaultOptions({ base, pages: ['/good'], profiles: ['mobile'], throttles: ['none'], out: outDir }));
  assert.deepEqual(row.failedChecks, []);
  assert.equal(row.firstMapDrawMs, null);
  assert.equal(row.overflowPx, 0);
  assert.ok(fs.existsSync(path.join(outDir, 'summary.md')));
});
