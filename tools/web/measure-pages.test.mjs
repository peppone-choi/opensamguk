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
import { execFileSync, spawn, spawnSync } from 'node:child_process';
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

// 늦게 붙는 지도: 로드 2.5초 뒤 지도 뿌리가 생기고 3초 뒤 큰 캔버스 가운데 작은 마름모만 그린다(바다는 CSS 배경).
const LAZY = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>늦은 지도</title></head><body style="margin:0;background:#16324d">
<main><h1>늦은 지도</h1></main>
<script>
setTimeout(() => { const d = document.createElement('div'); d.className = 'os-iso-map'; d.style.cssText = 'width:600px;height:400px';
  d.innerHTML = '<canvas width="600" height="400"></canvas>'; document.body.appendChild(d); }, 2500);
setTimeout(() => { const g = document.querySelector('.os-iso-map canvas').getContext('2d'); g.fillStyle = '#6a8a4a';
  g.beginPath(); g.moveTo(300, 140); g.lineTo(390, 200); g.lineTo(300, 260); g.lineTo(210, 200); g.closePath(); g.fill(); }, 3000);
</script></body></html>`;

// 첫 그림 뒤에 오는 요청: 지도는 바로 그리고, 2.5초 뒤 /late.bin 을 받는다(networkidle 은 그 전에 이미 온다).
// /slow.bin(같이 2.5초 뒤)은 탭이 닫힐 때까지 조금씩 계속 온다 — 행이 끝날 때 받는 중인 요청으로 주소 · 받은 바이트가 남아야 한다.
const LATE = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>늦은 요청</title></head><body style="margin:0">
<div class="os-iso-map" style="width:600px;height:400px"><canvas width="600" height="400"></canvas></div>
<script>
const g = document.querySelector('canvas').getContext('2d'); g.fillStyle = '#486'; g.fillRect(0, 0, 600, 400);
setTimeout(() => fetch('/late.bin', { cache: 'no-store' }).then((r) => r.arrayBuffer()), 2500);
setTimeout(() => fetch('/slow.bin', { cache: 'no-store' }).then((r) => r.arrayBuffer()).catch(() => {}), 2500);
</script></body></html>`;

// 첫 그림 뒤 받기 시작한 요청을 1초 뒤 AbortController 로 끊는다 — 취소는 받는 중이 아니다(적재 창을 붙잡지 않는다).
const CANCEL = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>끊는 요청</title></head><body style="margin:0">
<div class="os-iso-map" style="width:600px;height:400px"><canvas width="600" height="400"></canvas></div>
<script>
const g = document.querySelector('canvas').getContext('2d'); g.fillStyle = '#486'; g.fillRect(0, 0, 600, 400);
setTimeout(() => { const ac = new AbortController();
  fetch('/slow.bin', { cache: 'no-store', signal: ac.signal }).then((r) => r.arrayBuffer()).catch(() => {});
  setTimeout(() => ac.abort(), 1000); }, 2500);
</script></body></html>`;

// 덮임: 투명 상자에 덮인 단추(진짜 덮임 — 스크롤해도 덮임)와, 첫 화면에서 아래 고정 탭 밑에 걸친 단추(스크롤하면 빠져나옴).
// 「걸친 단추」는 가운데(y 770)는 맞지만 아래 14px 가 탭(780–844)에 걸려 첫 화면에서는 누를 영역이 44 미만으로 잡힌다.
const COVER = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>덮임</title></head>
<body style="margin:0"><main style="padding:16px 16px 120px">
<div style="position:relative;width:200px;height:48px;margin-top:80px">
  <button type="button" style="width:200px;height:48px">덮인 단추</button>
  <div style="position:absolute;inset:0;background:transparent"></div>
</div>
<div style="height:640px"></div>
<button type="button" style="width:200px;height:48px">아래 탭 밑 단추</button>
<div style="height:600px"></div>
</main>
<button type="button" style="position:absolute;left:220px;top:746px;width:150px;height:48px">걸친 단추</button>
<nav style="position:fixed;left:0;right:0;bottom:0;height:64px;background:#222" aria-label="아래 탭"><a href="/good" style="display:inline-block;width:64px;height:64px;color:#fff">탭</a></nav>
</body></html>`;

// 첫 화면 아래: 투명 상자에 덮인 단추(결함)와, 상자는 30×30 이지만 ::before 로 누를 영역을 44 로 넓힌 단추(결함 아님).
// 예전에는 화면 밖을 상자 크기로만 재서 앞의 것을 못 보고 뒤의 것을 44 미만으로 셌다.
const BELOW = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>아래쪽</title>
<style>.ext{position:relative;width:30px;height:30px;padding:0;border:0}.ext::before{content:'';position:absolute;inset:-7px}</style></head>
<body style="margin:0"><main style="padding:16px">
<div style="height:1400px"></div>
<div style="position:relative;width:200px;height:48px">
  <button type="button" style="width:200px;height:48px">아래 덮인 단추</button>
  <div style="position:absolute;inset:0"></div>
</div>
<div style="height:60px"></div>
<button type="button" class="ext">넓힌</button>
<div style="height:1400px"></div>
</main></body></html>`;

// 44 미만 원인: 상자는 48 인데 옆 상자가 오른쪽 26px 를 덮어 누를 영역이 줄어든 단추(overlap)와, 상자 자체가 30 인 단추(box).
const CAUSE = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>원인</title></head><body style="margin:0"><main style="padding:16px">
<div style="position:relative;width:48px;height:48px;margin-bottom:24px">
  <button type="button" style="width:48px;height:48px;padding:0;border:0">겹침</button>
  <div style="position:absolute;top:0;right:0;width:20px;height:48px"></div>
</div>
<button type="button" style="width:30px;height:30px;padding:0;border:0">작음</button>
</main></body></html>`;

// 라벨이 있는 입력(K0 10-02): c1 은 라벨 44 + 입력 20 → 통과, c2 는 라벨도 30 → 44 미만,
// c3 은 키만 큰 입력(20×46)과 너비만 넓은 for 라벨(120×20) → 섞어 재면 120×46 으로 거짓 통과하므로 44 미만이어야 한다.
// c4 는 44×44 입력 + 120×20 for 라벨 → 입력만으로 44×44 라 통과다(라벨 넓이가 더 커도).
// c5 는 꾸민 체크 상자: 입력 위를 라벨 자식(span)이 덮는다 → 덮임이 아니고, 라벨(160×48)로 재서 통과다(#1212 CodeRabbit).
const LABELS = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>라벨</title>
<style>input{margin:0}label{box-sizing:border-box}</style></head><body style="margin:0"><main style="padding:16px;display:grid;gap:24px;justify-items:start">
<label style="display:inline-flex;align-items:center;gap:8px;min-height:44px;min-width:44px;padding:0 8px"><input type="checkbox" id="c1" style="width:20px;height:20px">동의</label>
<label style="display:inline-flex;align-items:center;gap:8px;height:30px;padding:0 8px"><input type="checkbox" id="c2" style="width:20px;height:20px">작은 동의</label>
<div style="display:flex;align-items:center;gap:8px"><input type="checkbox" id="c3" style="width:20px;height:46px"><label for="c3" style="display:inline-block;width:120px;height:20px">긴 글</label></div>
<div style="display:flex;align-items:center;gap:8px"><input type="checkbox" id="c4" style="width:44px;height:44px"><label for="c4" style="display:inline-block;width:120px;height:20px">넓은 라벨</label></div>
<label style="position:relative;display:inline-block;width:160px;height:48px"><input type="checkbox" id="c5" style="position:absolute;inset:0;width:100%;height:100%"><span style="position:absolute;inset:0;background:#ddd">꾸민 체크</span></label>
</main></body></html>`;

let server; let base; let outDir;

before(async () => {
  server = http.createServer((req, res) => {
    const send = (status, type, body) => { res.writeHead(status, { 'content-type': type, 'cache-control': 'no-store' }); res.end(body); };
    if (req.url === '/bad') return send(200, 'text/html; charset=utf-8', BAD);
    if (req.url === '/good') return send(200, 'text/html; charset=utf-8', GOOD);
    if (req.url === '/lazy') return send(200, 'text/html; charset=utf-8', LAZY);
    if (req.url === '/late') return send(200, 'text/html; charset=utf-8', LATE);
    if (req.url === '/cancel') return send(200, 'text/html; charset=utf-8', CANCEL);
    if (req.url === '/cover') return send(200, 'text/html; charset=utf-8', COVER);
    if (req.url === '/below') return send(200, 'text/html; charset=utf-8', BELOW);
    if (req.url === '/cause') return send(200, 'text/html; charset=utf-8', CAUSE);
    if (req.url === '/labels') return send(200, 'text/html; charset=utf-8', LABELS);
    if (req.url === '/late.bin') return send(200, 'application/octet-stream', Buffer.alloc(150_000, 3));
    if (req.url === '/slow.bin') {
      // 0.2초마다 4KB, 연결이 끊길 때(탭 닫힘)까지 — 부하가 높아도 행이 끝나기 전에 끝나지 않는다(안전 상한 10분).
      // content-length 는 크게 알린다(省 PNG 처럼 「큰 자원을 알리고 앞부분만 받다 끊김」을 흉내 낸다).
      res.writeHead(200, { 'content-type': 'application/octet-stream', 'cache-control': 'no-store', 'content-length': '50000000' });
      let n = 0; const iv = setInterval(() => { res.write(Buffer.alloc(4096, 1)); if (++n >= 3000) { clearInterval(iv); res.end(); } }, 200);
      req.on('close', () => clearInterval(iv));
      return undefined;
    }
    if (req.url === '/to-login') { res.writeHead(302, { location: '/good' }); return res.end(); }
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

// CLI 는 오류 행이 있으면 종료 코드 1 이다(2026-10-01: 전부 오류 행인데 0 으로 끝나 「잰 줄 알았는데 안 잰」 결과가 초록으로 보였다).
test('CLI: 측정을 못 한 행이 있으면 종료 코드 1', async () => {
  const dead = http.createServer();
  await new Promise((r) => dead.listen(0, '127.0.0.1', r));
  const port = dead.address().port;
  await new Promise((r) => dead.close(r));
  const res = spawnSync(process.execPath, [fileURLToPath(new URL('./measure-pages.mjs', import.meta.url)), '--base', `http://127.0.0.1:${port}`,
    '--pages', '/x', '--profiles', 'desktop', '--throttle', 'none', '--no-axe', '--timeout-ms', '15000', '--out', path.join(outDir, 'cli-exit')], { encoding: 'utf8' });
  assert.equal(res.status, 1, `종료 코드 ${res.status}\n${res.stderr}`);
  assert.match(res.stderr, /측정 실패 1\/1/);
});

// 2026-10-01 pep v3.1 로그인에서 놓친 두 가지: 늦게 붙는 지도 뿌리, 큰 캔버스의 작은 그림(옛 81점 > 20 문턱 미달).
test('늦게 붙고 작게 그리는 지도도 첫 그림을 잡는다', async () => {
  const [row] = await run(defaultOptions({ base, pages: ['/lazy'], profiles: ['desktop'], throttles: ['none'], out: outDir, axe: false, probe: false }));
  assert.ok(!row.error, `측정 실패: ${row.error}`);
  assert.ok(row.firstMapDrawMs != null && row.firstMapDrawMs >= 3000, `지도 첫 그림 ${row.firstMapDrawMs}`);
});

// 로그인이 풀려 /login 으로 넘어가거나 404 화면이 뜨면 그 값은 엉뚱한 화면의 「통과」다 — 오류 행이어야 한다.
test('요청한 화면이 아니면(넘어감 · 404) 오류 행이다', async () => {
  const out = path.join(outDir, 'wrong-page');
  const rows = await run(defaultOptions({ base, pages: ['/to-login', '/gone'], profiles: ['desktop'], throttles: ['none'], out, axe: false, probe: false, mapGraceMs: 0 }));
  assert.equal(rows.length, 2);
  assert.match(rows[0].error ?? '', /요청한 화면이 아니다: \/to-login — \/good 로 넘어갔다/);
  assert.match(rows[1].error ?? '', /요청한 화면이 아니다: \/gone — 문서 응답 404/);
  assert.ok(fs.existsSync(path.join(out, 'to-login-desktop-none-wrong-page.png')), '넘어간 화면 캡처가 없다');
});

// 첫 그림 뒤 요청이 적재 창에 들어오고, 끝까지 받는 중인 요청은 주소 · 받은 바이트로 남는다(2026-10-01: 창이 networkidle
// 직후 닫혀 첫 그림 뒤 provinces 요청을 놓쳤고, 받는 중인 요청은 개수만 남았다).
test('첫 그림 뒤 요청을 적재 창에 넣고, 받는 중인 요청은 주소 · 바이트를 남긴다', async () => {
  const out = path.join(outDir, 'late');
  const [row] = await run(defaultOptions({ base, pages: ['/late'], profiles: ['desktop'], throttles: ['none'], out, axe: false, probe: false, settleQuietMs: 3000, settleMaxMs: 6000 }));
  assert.ok(!row.error, `측정 실패: ${row.error}`);
  const r = JSON.parse(fs.readFileSync(path.join(out, 'late-desktop-none.json'), 'utf8'));
  assert.ok(r.top10.some((x) => x.url === '/late.bin' && x.bytes > 100_000), `첫 그림 뒤 /late.bin 이 적재 창에 없다: ${JSON.stringify(r.top10.map((x) => x.url))}`);
  // 받는 중인 요청이 있으니 조용한 3초를 못 채우고 상한(6초)까지 기다린다. 첫 그림 뒤 요청 수는 부하에 따라 달라 개수만 본다.
  assert.equal(r.postDrawSettle?.hitMax, true, JSON.stringify(r.postDrawSettle));
  assert.equal(typeof r.postDrawSettle.lateRequests, 'number');
  const slow = (r.pendingList ?? []).find((x) => x.url === '/slow.bin');
  assert.ok(slow && slow.partialBytes > 0, `받는 중인 /slow.bin 이 주소 · 바이트로 남지 않았다: ${JSON.stringify(r.pendingList)}`);
});

// 취소된 요청이 「받는 중」으로 남으면 조용한 시간을 끝내 못 채워 상한까지 기다리고, pending 이 취소를 덜 온 자원으로 적는다
// (2026-10-01 리뷰 지적: loadingFailed 가 canceled 를 아무것도 적지 않았다).
test('취소된 요청은 받는 중이 아니다: 적재 창을 붙잡지 않고 pending 에도 없다', async () => {
  const out = path.join(outDir, 'cancel');
  const [row] = await run(defaultOptions({ base, pages: ['/cancel'], profiles: ['desktop'], throttles: ['none'], out, axe: false, probe: false, settleQuietMs: 3000, settleMaxMs: 20000 }));
  assert.ok(!row.error, `측정 실패: ${row.error}`);
  const r = JSON.parse(fs.readFileSync(path.join(out, 'cancel-desktop-none.json'), 'utf8'));
  assert.equal(r.postDrawSettle?.hitMax, false, `취소된 요청이 적재 창을 상한까지 붙잡았다: ${JSON.stringify(r.postDrawSettle)}`);
  assert.ok(!(r.pendingList ?? []).some((x) => x.url === '/slow.bin'), `취소된 요청이 받는 중으로 남았다: ${JSON.stringify(r.pendingList)}`);
  assert.equal(r.pending, 0);
  assert.ok(r.canceledCount >= 1, `취소 수 ${r.canceledCount}`);
  assert.equal(r.failedCount, 0);
  // 취소는 개수만이 아니라 주소 · 서버가 알린 크기 · 끊기 전 받은 바이트로 남는다(10-01: /login 취소 2건이 무엇인지 몰랐다).
  const c = (r.canceledList ?? []).find((x) => x.url === '/slow.bin');
  assert.ok(c && c.partialBytes > 0 && c.offeredBytes === 50_000_000, `취소 목록: ${JSON.stringify(r.canceledList)}`);
  assert.ok(r.canceledPartialBytes >= c.partialBytes && r.wireBytes >= r.transferBytes + c.partialBytes, `선 위 바이트 ${r.wireBytes} · 다 받은 ${r.transferBytes}`);
});

// 첫 화면에서 아래 고정 탭 밑에 걸친 단추는 덮임이 아니다(스크롤하면 맞는다). 투명 상자에 덮인 단추는 덮임이다.
// (2026-10-02: 조정 「천도」 · 전투 단추가 첫 화면 위치만 보고 덮임으로 잡혔다.)
test('덮임 · 44: 스크롤하면 빠져나오는 고정 탭 밑 · 가장자리 걸침은 세지 않고, 투명 상자 덮임은 센다', async () => {
  const out = path.join(outDir, 'cover');
  const [row] = await run(defaultOptions({ base, pages: ['/cover'], profiles: ['mobile'], throttles: ['none'], out, axe: false, probe: false, mapGraceMs: 0, settleQuietMs: 500 }));
  assert.ok(!row.error, `측정 실패: ${row.error}`);
  const r = JSON.parse(fs.readFileSync(path.join(out, 'cover-mobile-none.json'), 'utf8'));
  assert.equal(r.layout.coveredTargets, 1, JSON.stringify(r.layout.coveredTargetSamples));
  assert.equal(r.layout.coveredTargetSamples[0].text, '덮인 단추');
  assert.equal(r.layout.coveredAtFirstViewOnly, 1, JSON.stringify(r.layout.coveredAtFirstViewOnlySamples));
  assert.equal(r.layout.coveredAtFirstViewOnlySamples[0].text, '아래 탭 밑 단추');
  // 가장자리만 걸친 것도 스크롤하면 44 이상 — 44 미만으로 세지 않는다(10-02 외교 「천하 지도 보기」 104×29 오탐).
  assert.equal(r.layout.smallTargets, 0, JSON.stringify(r.layout.smallTargetSamples));
  assert.equal(r.layout.smallAtFirstViewOnly, 1, JSON.stringify(r.layout.smallAtFirstViewOnlySamples));
  assert.equal(r.layout.smallAtFirstViewOnlySamples[0].text, '걸친 단추');
});

// 화면 밖 요소도 가운데로 들여서 누를 영역 · 덮임을 잰다(2026-10-02 K0: K5 hitArea.ts 대조 — 전에는 상자 크기만).
test('화면 밖: 아래쪽 덮임을 잡고, ::before 로 넓힌 누를 영역은 44 로 잰다', async () => {
  const out = path.join(outDir, 'below');
  const [row] = await run(defaultOptions({ base, pages: ['/below'], profiles: ['desktop'], throttles: ['none'], out, axe: false, probe: false, mapGraceMs: 0, settleQuietMs: 500 }));
  assert.ok(!row.error, `측정 실패: ${row.error}`);
  const r = JSON.parse(fs.readFileSync(path.join(out, 'below-desktop-none.json'), 'utf8'));
  assert.equal(r.layout.coveredTargets, 1, JSON.stringify(r.layout.coveredTargetSamples));
  assert.equal(r.layout.coveredTargetSamples[0].text, '아래 덮인 단추');
  assert.equal(r.layout.smallTargets, 0, JSON.stringify(r.layout.smallTargetSamples));
  assert.equal(r.layout.targetsMeasuredByRectOnly, 0);
});

// 44 미만은 원인을 나눈다 — 덮여서 줄어든 것(overlap)과 상자가 작은 것(box). (2026-10-02 K3: K0 표에서 고칠 곳이 다르다.)
test('44 미만 원인: 겹쳐서 줄어든 것과 상자가 작은 것을 나눈다', async () => {
  const out = path.join(outDir, 'cause');
  const [row] = await run(defaultOptions({ base, pages: ['/cause'], profiles: ['desktop'], throttles: ['none'], out, axe: false, probe: false, mapGraceMs: 0, settleQuietMs: 500 }));
  assert.ok(!row.error, `측정 실패: ${row.error}`);
  const r = JSON.parse(fs.readFileSync(path.join(out, 'cause-desktop-none.json'), 'utf8'));
  assert.deepEqual(r.layout.smallTargetsByCause, { box: 1, overlap: 1 }, JSON.stringify(r.layout.smallTargetSamples));
  assert.equal(r.layout.smallTargetSamples.find((x) => x.text === '겹침')?.cause, 'overlap');
  assert.equal(r.layout.smallTargetSamples.find((x) => x.text === '작음')?.cause, 'box');
});

// 라벨이 있는 입력은 라벨 영역까지 누르는 자리로 잰다 — 넓이가 큰 쪽 하나를 통째로(너비 · 높이를 섞지 않는다).
test('라벨: 라벨 44 + 입력 20 은 통과, 라벨 30 은 44 미만, 너비 · 높이를 섞은 거짓 통과는 없다', async () => {
  const out = path.join(outDir, 'labels');
  const [row] = await run(defaultOptions({ base, pages: ['/labels'], profiles: ['desktop'], throttles: ['none'], out, axe: false, probe: false, mapGraceMs: 0, settleQuietMs: 500 }));
  assert.ok(!row.error, `측정 실패: ${row.error}`);
  const r = JSON.parse(fs.readFileSync(path.join(out, 'labels-desktop-none.json'), 'utf8'));
  const ids = r.layout.smallTargetSamples.map((x) => x.el);
  assert.ok(!ids.some((x) => x.includes('#c1')), `라벨 44 인데 44 미만으로 셌다: ${JSON.stringify(r.layout.smallTargetSamples)}`);
  assert.ok(ids.some((x) => x.includes('#c2')), `라벨 30 을 놓쳤다: ${JSON.stringify(r.layout.smallTargetSamples)}`);
  assert.ok(ids.some((x) => x.includes('#c3')), `너비 · 높이를 섞어 거짓 통과했다: ${JSON.stringify(r.layout.smallTargetSamples)}`);
  // 리뷰 #1212: 입력만으로 44×44 면 넓이가 더 큰 for 라벨(120×20)이 있어도 통과다(넓이만 보고 라벨을 고르면 거짓 44 미만).
  assert.ok(!ids.some((x) => x.includes('#c4')), `44×44 입력이 넓은 라벨 때문에 44 미만이 됐다: ${JSON.stringify(r.layout.smallTargetSamples)}`);
  // #1212 CodeRabbit: 제 라벨(의 자식)에 덮인 입력은 덮임이 아니라 라벨로 잰다.
  assert.ok(!r.layout.coveredTargetSamples.some((x) => x.el.includes('#c5')), `제 라벨에 덮인 입력을 덮임으로 셌다: ${JSON.stringify(r.layout.coveredTargetSamples)}`);
  assert.ok(!ids.some((x) => x.includes('#c5')), `꾸민 체크를 라벨로 재지 않았다: ${JSON.stringify(r.layout.smallTargetSamples)}`);
});
