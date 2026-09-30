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
import { defaultOptions, duplicateTransfers, inPageSnippet, run, slugOf } from './measure-pages.mjs';
import { createRequire } from 'node:module';
import { execFileSync, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

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
<div style="overflow:hidden;width:100%"><p style="width:3000px;text-align:right;margin:0">오른쪽 끝에서 잘리는 글자</p></div>
<div style="overflow-x:auto;width:100%"><p style="width:3000px;text-align:right;margin:0">스크롤 영역 안 글자</p></div>
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
  assert.ok(!row.error, `측정 실패: ${row.error}`);
  const full = JSON.parse(fs.readFileSync(path.join(outDir, 'bad-desktop-broadband.json'), 'utf8'));
  for (const id of ['map-first-draw-3s', 'no-duplicate-transfer', 'console-errors-0', 'network-failures-0', 'axe-critical-0', 'touch-target-44', 'no-title-only-info']) {
    assert.ok(row.failedChecks.includes(id), `${id} 가 걸려야 한다: ${row.failedChecks}`);
  }
  // 상한은 두지 않는다 — 기계 부하가 높으면 3.5초 타이머가 수십 초 늦게 돈다(부하 500 에서 34초). 잡혔는지와 3초 초과만 본다.
  assert.ok(full.firstMapDrawMs != null && full.firstMapDrawMs >= 3000, `지도 첫 그림 ${full.firstMapDrawMs}`);
  assert.equal(full.duplicates.count, 1);
  assert.ok(full.layout.horizontalOverflowPx > 0);
  assert.equal(full.layout.textCutRight, 1, JSON.stringify(full.layout.textCutRightSamples));
  assert.equal(full.layout.textCutRightInScroller, 1, JSON.stringify(full.layout.textCutRightInScrollerSamples));
  assert.ok(full.layout.textUnder12px >= 1);
  assert.ok(full.layout.hoverRevealRules >= 1);
  assert.equal(full.map.hitTest.isCanvas, true);
  assert.equal(full.map.probe.wheelChangedMap, false);
  assert.ok(full.axe.rules.some((r) => r.id === 'image-alt'));
});

test('깨끗한 화면: 걸리는 기준이 없다(모바일)', async () => {
  const [row] = await run(defaultOptions({ base, pages: ['/good'], profiles: ['mobile'], throttles: ['none'], out: outDir }));
  assert.ok(!row.error, `측정 실패: ${row.error}`);
  assert.deepEqual(row.failedChecks, []);
  assert.equal(row.firstMapDrawMs, null);
  assert.equal(row.overflowPx, 0);
  assert.equal(row.textCutRight, 0);
  assert.ok(fs.existsSync(path.join(outDir, 'summary.md')));
});

test('탭 안 스니펫: 같은 배치 검사를 CDP 없이 낸다', async () => {
  const { chromium } = createRequire(new URL('../../web/game/package.json', import.meta.url))('@playwright/test');
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
    await page.goto(`${base}/bad`, { waitUntil: 'load' });
    await page.waitForTimeout(4000);
    const r = JSON.parse(await page.evaluate(inPageSnippet()));
    assert.equal(r.mode, 'in-page');
    assert.equal(r.layout.smallTargets, 1);
    assert.equal(r.layout.titleOnly, 1);
    assert.ok(r.layout.horizontalOverflowPx > 0);
    assert.equal(r.map.hitTest.isCanvas, true);
    assert.ok(r.network.sameUrlRepeated.some((d) => d.url === '/big.bin' && d.count === 2), JSON.stringify(r.network.sameUrlRepeated));
    assert.ok(r.network.transferBytes > 400_000, `전송 ${r.network.transferBytes}`);
  } finally { await browser.close(); }
});

// CDP 모드(사용자가 로그인해 둔 Chrome 에 붙기): 데스크톱 · 모바일 모두 재고, 끝나면 그 브라우저의 탭 수가 그대로여야 한다.
// 2026-09-30 데스크톱 행이 터치 끄기에서 실패하고 빈 탭 10개를 사용자 브라우저에 남긴 사고의 적색 프로브다.
test('CDP 모드: 데스크톱 · 모바일을 재고 우리 탭을 남기지 않는다', async () => {
  const port = 9300 + Math.floor(Math.random() * 500);
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'k10-cdp-'));
  const chromePath = process.env.CHROME_PATH
    || (process.platform === 'darwin' ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : 'google-chrome');
  // 자기 프로세스 그룹으로 띄운다 — 끝낼 때 이 Chrome 과 그 자식만 끈다(남의 프로세스는 건드리지 않는다).
  const proc = spawn(chromePath, ['--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${dataDir}`, '--no-first-run', 'about:blank'], { stdio: 'ignore', detached: true });
  const pages = async () => (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).filter((t) => t.type === 'page').length;
  try {
    let ready = false;
    for (let i = 0; i < 100 && !ready; i++) {
      try { await fetch(`http://127.0.0.1:${port}/json/version`); ready = true; } catch { await new Promise((r) => setTimeout(r, 200)); }
    }
    assert.ok(ready, 'Chrome 디버그 포트가 뜨지 않았다');
    const before = await pages();
    const rows = await run(defaultOptions({ base, pages: ['/good'], profiles: ['desktop', 'mobile'], throttles: ['none'], out: outDir, cdpUrl: `http://127.0.0.1:${port}`, axe: false }));
    assert.deepEqual(rows.map((r) => r.error ?? null), [null, null], JSON.stringify(rows.map((r) => r.error)));
    assert.equal(await pages(), before, '우리 탭이 남았다');
  } finally {
    const exited = new Promise((r) => { if (proc.exitCode !== null) r(); else proc.once('exit', r); });
    try { process.kill(-proc.pid, 'SIGTERM'); } catch { proc.kill(); }
    await Promise.race([exited, new Promise((r) => setTimeout(r, 10_000))]);
    // 리눅스 Chrome 은 자식이 본 프로세스보다 늦게 끝나며 프로필에 쓴다(2026-09-30 CI ENOTEMPTY). 넉넉히 다시 지우고,
    // 끝내 못 지워도 이 테스트가 보는 것(측정 · 탭 수)과 무관하니 경고만 남긴다 — 임시 폴더는 OS 가 치운다.
    try {
      fs.rmSync(dataDir, { recursive: true, force: true, maxRetries: 20, retryDelay: 250 });
    } catch (e) {
      console.warn(`임시 프로필을 못 지웠다(무시): ${e.code ?? e.message}`);
    }
  }
});

test('--help 는 머리 주석 끝까지 보인다', () => {
  const out = execFileSync(process.execPath, [fileURLToPath(new URL('./measure-pages.mjs', import.meta.url)), '--help'], { encoding: 'utf8' });
  // 머리 주석(첫 import 앞) 전체와 같아야 한다 — 고정 줄 수로 자르면 주석이 늘 때 조용히 잘린다.
  const lines = fs.readFileSync(fileURLToPath(new URL('./measure-pages.mjs', import.meta.url)), 'utf8').split('\n');
  assert.equal(out.trimEnd(), lines.slice(0, lines.findIndex((l) => l.startsWith('import '))).join('\n').trimEnd());
});

// 모든 측정이 goto 에서 실패해도(없는 폴더에) 오류 행과 summary 가 남아야 한다 — 전에는 summary 쓰기가 ENOENT 로 죽었다.
test('첫 goto 가 실패해도 오류 행과 summary 를 남긴다', async () => {
  const dead = http.createServer();
  await new Promise((r) => dead.listen(0, '127.0.0.1', r));
  const port = dead.address().port;
  await new Promise((r) => dead.close(r)); // 닫힌 포트 — 연결 거부
  const out = path.join(outDir, 'nested', 'fresh');
  const rows = await run(defaultOptions({ base: `http://127.0.0.1:${port}`, pages: ['/nothing'], profiles: ['desktop'], throttles: ['none'], out, axe: false, timeoutMs: 15_000 }));
  assert.equal(rows.length, 1);
  assert.ok(rows[0].error, '오류 행이어야 한다');
  assert.ok(fs.existsSync(path.join(out, 'summary.json')), 'summary.json 이 없다');
});
