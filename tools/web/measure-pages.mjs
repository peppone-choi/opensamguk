#!/usr/bin/env node
// 페이지 품질 측정 — 첫 그림 · 요청 수 · 전송 크기 · 모바일 동작 · 접근성 위반 (K10, 2026-09-30).
//
// 2026-09-30 지도 M1 기준선(K0 스크래치 measure-login-map.cjs)을 저장소 도구로 옮기고 넓힌 것이다.
// 전송 크기(CDP encodedDataLength 합)는 그 스크립트와 같은 식이다. 지도 첫 그림 판정은 2026-10-01에 촘촘하게 바꿨다
// (mapState 주석) — 09-30 기준선 시간과는 정의가 다르다. 늦게 붙는 지도는 망이 잠잠해진 뒤 --map-grace-ms(15초)까지 기다린다.
//
//   node tools/web/measure-pages.mjs --out <dir> [--base https://sam.peppone.dev]
//        [--pages /login,/join,/board] [--profiles desktop,mobile] [--throttle none,broadband]
//        [--repeat 1] [--no-axe] [--no-probe] [--map-selector .os-iso-map] [--cdp-url http://127.0.0.1:9222]
//   node tools/web/measure-pages.mjs --print-snippet   # 사용자 브라우저 탭 안에서 돌릴 JS(아래 inPageSnippet)
//
// - 실행마다 새 브라우저 컨텍스트(콜드 캐시)다. 시스템 Chrome(channel=chrome)을 쓴다 — 브라우저를 내려받지 않는다.
// - --cdp-url 은 사용자가 직접 로그인해 둔 Chrome(--remote-debugging-port)에 붙는다. 로그인 뒤 화면용이다.
//   이 도구는 계정을 만들거나 자격증명을 입력하지 않는다. 그 브라우저에서는 새 탭 하나만 열고 캐시를 끈 채
//   재고 탭을 닫는다(쿠키는 그대로라 「콜드」는 HTTP 캐시만 뜻한다).
// - 남의 서버에 반복 요청하지 않는다. pep 도 필요한 만큼만 잰다: 한 번 실행 = 페이지 × 프로필 × 망 × repeat 번 적재.
// - 측정을 못 한 행(오류 행)이 하나라도 있으면 종료 코드 1 이다. 기준(checks)이 걸린 것은 실패가 아니다(측정 도구다).
// - 기준(checks)은 문서에 있는 것만 쓴다. 문서가 크기를 정하지 않은 「큰 자원」 같은 것은 문턱 없이 전부 적는다.
//
// 의존성은 web/game 의 @playwright/test · @axe-core/playwright 다(없으면 설치 명령을 알려 주고 멈춘다).
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

// 도움말은 머리 주석 전체다(고정 줄 수로 자르면 설명이 중간에서 끊긴다) — 첫 import 줄 앞까지.
function helpText() {
  const lines = fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n');
  return lines.slice(0, lines.findIndex((l) => l.startsWith('import '))).join('\n');
}

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const webRequire = createRequire(path.join(ROOT, 'web/game/package.json'));

function load(name) {
  try {
    return webRequire(name);
  } catch {
    console.error(`${name} 를 web/game 에서 찾지 못했다. 먼저: pnpm -C web install --frozen-lockfile --filter @opensamguk/web-game...`);
    process.exit(2);
  }
}

export const PROFILES = {
  desktop: { viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1, isMobile: false, hasTouch: false },
  mobile: {
    viewport: { width: 390, height: 844 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true,
    userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36',
  },
};

// 광대역 기준: 50 Mbps 내려받기, 10 Mbps 올리기, RTT 20ms (M1 기준선과 같다).
export const THROTTLES = {
  none: null,
  broadband: { offline: false, latency: 20, downloadThroughput: (50 * 1000 * 1000) / 8, uploadThroughput: (10 * 1000 * 1000) / 8 },
};

const AXE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']; // web/game/e2e/a11y-smoke.spec.ts 와 같다
const COMPRESSIBLE = /^(text\/|application\/(json|javascript|x-javascript|xml|.*\+json|.*\+xml)|image\/svg\+xml)/;

function parseArgs(argv) {
  const opts = {
    base: 'https://sam.peppone.dev', pages: ['/login', '/join', '/board'], profiles: ['desktop', 'mobile'],
    throttles: ['none'], repeat: 1, axe: true, probe: true, mapSelector: '.os-iso-map', timeoutMs: 90_000, mapGraceMs: 15_000,
    channel: 'chrome', cdpUrl: null, out: null,
  };
  const list = (v) => v.split(',').map((s) => s.trim()).filter(Boolean);
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => { const v = argv[++i]; if (v === undefined) throw new Error(`${a} 에 값이 없다`); return v; };
    if (a === '--base') opts.base = next().replace(/\/$/, '');
    else if (a === '--pages') opts.pages = list(next());
    else if (a === '--profiles') opts.profiles = list(next());
    else if (a === '--throttle') opts.throttles = list(next());
    else if (a === '--repeat') opts.repeat = Math.max(1, Number(next()));
    else if (a === '--out') opts.out = next();
    else if (a === '--no-axe') opts.axe = false;
    else if (a === '--no-probe') opts.probe = false;
    else if (a === '--map-selector') opts.mapSelector = next();
    else if (a === '--timeout-ms') opts.timeoutMs = Number(next());
    else if (a === '--map-grace-ms') opts.mapGraceMs = Number(next());
    else if (a === '--channel') opts.channel = next();
    else if (a === '--cdp-url') opts.cdpUrl = next();
    else if (a === '--print-snippet') { console.log(inPageSnippet()); process.exit(0); }
    else if (a === '-h' || a === '--help') { console.log(helpText()); process.exit(0); }
    else throw new Error(`모르는 인자: ${a}`);
  }
  if (!opts.out) throw new Error('--out <dir> 가 필요하다');
  for (const p of opts.profiles) if (!PROFILES[p]) throw new Error(`모르는 프로필: ${p}`);
  for (const t of opts.throttles) if (!(t in THROTTLES)) throw new Error(`모르는 망: ${t}`);
  return opts;
}

export function slugOf(pagePath) {
  const s = pagePath.replace(/^\/+|\/+$/g, '').replace(/[^0-9A-Za-z가-힣]+/g, '-');
  return s || 'root';
}

// 같은 URL 을 두 번 이상 실제로 내려받은 것(바이트 > 0). 문서가 「큰」 크기를 정하지 않았으므로 문턱 없이 전부.
export function duplicateTransfers(requests) {
  const byUrl = new Map();
  for (const r of requests) {
    if (!/^https?:/.test(r.url) || !(r.bytes > 0) || r.status === 304) continue;
    const g = byUrl.get(r.url) ?? { url: r.url, count: 0, bytes: 0 };
    g.count += 1; g.bytes += r.bytes;
    byUrl.set(r.url, g);
  }
  return [...byUrl.values()].filter((g) => g.count > 1).sort((a, b) => b.bytes - a.bytes);
}

function summarizeNetwork(requests, originOf) {
  const http = requests.filter((r) => /^https?:/.test(r.url));
  const sum = (arr) => arr.reduce((a, r) => a + (r.bytes || 0), 0);
  const byType = {};
  for (const r of http) {
    const k = r.type || 'Other';
    byType[k] ??= { count: 0, bytes: 0 };
    byType[k].count += 1; byType[k].bytes += r.bytes || 0;
  }
  const uncompressed = http.filter((r) => r.mime && COMPRESSIBLE.test(r.mime) && !r.enc && (r.bytes || 0) > 1024 && r.status !== 304);
  const failed = http.filter((r) => r.failed || (r.status && r.status >= 400));
  const dups = duplicateTransfers(http);
  const short = (u) => (originOf(u) ? u.replace(/^https?:\/\/[^/]+/, '') : u).slice(0, 140);
  return {
    requests: requests.length,
    httpRequests: http.length,
    transferBytes: sum(requests),
    pending: http.filter((r) => r.bytes === undefined && !r.failed).length,
    byType,
    duplicates: { count: dups.length, extraBytes: dups.reduce((a, g) => a + g.bytes - g.bytes / g.count, 0), list: dups.slice(0, 15).map((g) => ({ ...g, url: short(g.url) })) },
    uncompressed: { count: uncompressed.length, bytes: sum(uncompressed), list: uncompressed.sort((a, b) => b.bytes - a.bytes).slice(0, 10).map((r) => ({ url: short(r.url), bytes: r.bytes, mime: r.mime })) },
    failed: failed.slice(0, 20).map((r) => ({ url: short(r.url), status: r.status ?? null, error: r.failed ?? null })),
    failedCount: failed.length,
    top10: [...http].sort((a, b) => (b.bytes || 0) - (a.bytes || 0)).slice(0, 10).map((r) => ({ url: short(r.url), bytes: r.bytes, status: r.status, enc: r.enc })),
  };
}

// ---- 페이지 안에서 도는 함수들 (page.evaluate) ----

function initObservers() {
  window.__k10 = { lcp: null, lcpEl: null, cls: 0 };
  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) {
        window.__k10.lcp = e.startTime;
        const el = e.element;
        window.__k10.lcpEl = el ? `${el.tagName.toLowerCase()}${el.id ? `#${el.id}` : ''}${typeof el.className === 'string' && el.className ? `.${el.className.trim().split(/\s+/)[0]}` : ''}` : (e.url || null);
      }
    }).observe({ type: 'largest-contentful-paint', buffered: true });
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) if (!e.hadRecentInput) window.__k10.cls += e.value;
    }).observe({ type: 'layout-shift', buffered: true });
  } catch { /* 옵저버가 없는 브라우저 */ }
}

// 첫 그림: 지도 뿌리 안 canvas 들 중 하나라도 20×20 표본 400점 중 8점(2 %) 이상 알파 > 0 이면 칠해졌다고 본다.
// 2026-10-01 바꿈 — 09-30 M1 기준선은 첫 canvas 81점 중 20점 초과였다. v3.1 로그인처럼 바다가 CSS 배경이고 캔버스에
// 가운데 마름모만 그리는 지도는 390 화면에서 그 문턱을 못 넘어 「안 그려짐」으로 잘못 읽혔다. 옛 값과 그대로 견주지 않는다.
function mapState(selector) {
  const root = document.querySelector(selector);
  const canvases = root ? [...root.querySelectorAll('canvas')] : [];
  const c = canvases[0] ?? null;
  let painted = false;
  for (const cv of canvases) {
    if (!(cv.width > 0)) continue;
    try {
      const g = cv.getContext('2d');
      if (!g) { painted = true; break; } // WebGL 등 — 읽을 수 없으면 그려진 것으로 본다
      const w = cv.width, h = cv.height; let n = 0;
      for (let i = 0; i < 20; i++) for (let j = 0; j < 20; j++) {
        const d = g.getImageData(Math.floor((w * (i + 0.5)) / 20), Math.floor((h * (j + 0.5)) / 20), 1, 1).data;
        if (d[3] > 0) n++;
      }
      if (n >= 8) { painted = true; break; }
    } catch { painted = true; break; }
  }
  const lod = document.querySelector('[data-map-lod]')?.dataset.mapLod ?? null;
  return { hasRoot: !!root, hasCanvas: !!c, painted, lod };
}

function pageMetrics() {
  const nav = performance.getEntriesByType('navigation')[0];
  const fcp = performance.getEntriesByName('first-contentful-paint')[0];
  const fp = performance.getEntriesByName('first-paint')[0];
  const r = (v) => (v == null ? null : Math.round(v));
  return {
    finalUrl: location.href,
    ttfbMs: r(nav?.responseStart),
    firstPaintMs: r(fp?.startTime),
    fcpMs: r(fcp?.startTime),
    lcpMs: r(window.__k10?.lcp),
    lcpElement: window.__k10?.lcpEl ?? null,
    cls: window.__k10 ? Math.round(window.__k10.cls * 1000) / 1000 : null,
    domContentLoadedMs: r(nav?.domContentLoadedEventEnd),
    loadMs: r(nav?.loadEventEnd),
  };
}

// 모바일 동작 · 터치 대상 · 호버/title 전용 정보. 44 = V3System 「누르는 것은 모두 44px 이상」.
function layoutChecks(minTarget) {
  const vw = window.innerWidth, vh = window.innerHeight;
  const se = document.scrollingElement || document.documentElement;
  const describe = (el) => {
    const r = el.getBoundingClientRect();
    const cls = typeof el.className === 'string' && el.className.trim() ? `.${el.className.trim().split(/\s+/).slice(0, 2).join('.')}` : '';
    const text = (el.innerText || el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('placeholder') || el.value || '').trim().replace(/\s+/g, ' ').slice(0, 30);
    return { el: `${el.tagName.toLowerCase()}${el.id ? `#${el.id}` : ''}${cls}`, text, w: Math.round(r.width), h: Math.round(r.height) };
  };
  const shown = (el) => {
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return false;
    const cs = getComputedStyle(el);
    return cs.visibility !== 'hidden' && cs.display !== 'none' && Number(cs.opacity) !== 0 && !el.closest('[aria-hidden="true"],[inert]');
  };
  const SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[role=tab],[role=checkbox],[role=radio],[role=switch],[role=menuitem],[role=option],[tabindex]:not([tabindex="-1"])';
  const targets = [...document.querySelectorAll(SEL)].filter(shown);
  // 누를 영역(2026-09-30 K0 결정: 보이는 크기가 아니라 누를 영역 44). 화면 안의 요소는 가운데에서 바깥으로
  // elementFromPoint 를 훑어 재고(패딩 · ::before 확장 포함, 덮인 곳 제외), 화면 밖 요소는 상자 크기로 잰다(rectOnly).
  let rectOnly = 0;
  const hitArea = (el) => {
    const r = el.getBoundingClientRect();
    const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    if (cx < 0 || cy < 0 || cx >= vw || cy >= vh) { rectOnly += 1; return { w: r.width, h: r.height }; }
    const mine = (x, y) => {
      if (x < 0 || y < 0 || x >= vw || y >= vh) return false;
      const h = document.elementFromPoint(x, y);
      return !!h && (h === el || el.contains(h));
    };
    const half = minTarget / 2 - 1;
    if (r.width >= minTarget && r.height >= minTarget && [[0, 0], [-half, 0], [half, 0], [0, -half], [0, half]].every(([dx, dy]) => mine(cx + dx, cy + dy))) return { w: r.width, h: r.height };
    if (!mine(cx, cy)) { const top = document.elementFromPoint(cx, cy); return { w: 0, h: 0, covered: true, by: top ? describe(top).el : null }; }
    const reach = (dx, dy) => { let d = 0; while (d < 64 && mine(cx + dx * (d + 1), cy + dy * (d + 1))) d += 1; return d; };
    return { w: reach(-1, 0) + reach(1, 0) + 1, h: reach(0, -1) + reach(0, 1) + 1 };
  };
  const small = []; const smallInline = []; const covered = [];
  for (const el of targets) {
    let r = hitArea(el);
    // 가운데가 다른 요소에 덮인 것은 크기 문제가 아니라 따로 센다(열린 모달이면 맞고, 아니면 겹친 투명 상자 사고).
    if (r.covered) { covered.push({ ...describe(el), by: r.by }); continue; }
    // 라벨로 감싸거나 for 로 이은 입력은 라벨까지가 누르는 자리다.
    const label = el.closest('label') || (el.id ? document.querySelector(`label[for="${CSS.escape(el.id)}"]`) : null);
    if (label) {
      const lr = hitArea(label);
      r = { width: Math.max(r.w, lr.w), height: Math.max(r.h, lr.h) };
    } else r = { width: r.w, height: r.h };
    if (r.width >= minTarget && r.height >= minTarget) continue;
    const cs = getComputedStyle(el);
    // WCAG 2.5.8 문장 속 링크 예외: inline 표시 + 부모 글자가 링크 글자보다 길다.
    const inline = el.tagName === 'A' && cs.display === 'inline' && (el.parentElement?.innerText || '').trim().length > (el.innerText || '').trim().length + 1;
    (inline ? smallInline : small).push({ ...describe(el), hitW: Math.round(r.width), hitH: Math.round(r.height) });
  }
  const titleOnly = [];
  for (const el of document.querySelectorAll('body [title]')) {
    const t = (el.getAttribute('title') || '').trim();
    if (!t || !shown(el)) continue;
    const seen = `${el.innerText || ''} ${el.getAttribute('aria-label') || ''} ${el.getAttribute('alt') || ''} ${el.value || ''}`;
    if (!seen.includes(t)) titleOnly.push({ ...describe(el), title: t.slice(0, 60) });
  }
  // :hover 로 display · visibility · opacity 를 드러내는 규칙(추정). 같은 출처 스타일시트만 읽힌다.
  const hoverReveal = [];
  const walk = (rules) => {
    for (const rule of rules) {
      if (rule.cssRules && !(rule instanceof CSSStyleRule)) { walk(rule.cssRules); continue; }
      if (!(rule instanceof CSSStyleRule) || !rule.selectorText.includes(':hover')) continue;
      // 이 화면에 그 규칙이 걸리는 요소가 있을 때만 센다(공용 CSS 에만 있는 규칙은 뺀다).
      try { if (!document.querySelector(rule.selectorText.replace(/:hover/g, ''))) continue; } catch { /* 읽을 수 없는 선택자는 센다 */ }
      const s = rule.style;
      const reveals = (s.display && s.display !== 'none') || s.visibility === 'visible' || s.opacity === '1';
      if (reveals) hoverReveal.push(rule.selectorText.slice(0, 120));
    }
  };
  let unreadableSheets = 0;
  for (const sheet of document.styleSheets) {
    try { walk(sheet.cssRules); } catch { unreadableSheets += 1; }
  }
  // 오른쪽으로 잘린 글자: 문서 폭은 그대로인데(안쪽 상자가 overflow 로 숨김) 글자가 화면 오른쪽 밖으로 나간 것.
  // horizontalOverflowPx 만으로는 못 잡는다(2026-09-30 pep 모바일 작전실). 가로 스크롤 줄(overflow-x auto · scroll) 안은 의도라 뺀다.
  // 가로 스크롤 영역 안에서 잘린 글자는 따로 센다(textCutRightInScroller). 칩 · 탭 줄이면 의도지만, 본문 전체가 가로 스크롤
  // 영역이면 모바일에서 화면이 잘려 보인다(2026-09-30 pep 모바일 작전실) — 영역 크기를 함께 적어 판정에 쓴다.
  const cutRight = []; const cutRightInScroller = [];
  for (const el of document.body.querySelectorAll('*')) {
    const own = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim());
    if (!own || !shown(el)) continue;
    const r = el.getBoundingClientRect();
    if (r.right <= vw + 1) continue;
    let scroller = null;
    for (let a = el.parentElement; a && a !== document.body; a = a.parentElement) {
      const ox = getComputedStyle(a).overflowX;
      if (ox === 'auto' || ox === 'scroll') { scroller = a; break; }
    }
    if (!scroller) { cutRight.push(describe(el)); continue; }
    const sr = scroller.getBoundingClientRect();
    cutRightInScroller.push({ ...describe(el), scroller: describe(scroller).el, scrollerW: Math.round(sr.width), scrollerH: Math.round(sr.height) });
  }
  let tinyText = 0; const tinySamples = [];
  for (const el of document.body.querySelectorAll('*')) {
    const own = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim());
    if (!own || !shown(el)) continue;
    if (parseFloat(getComputedStyle(el).fontSize) < 12) {
      tinyText += 1;
      if (tinySamples.length < 8) tinySamples.push({ ...describe(el), fontSize: getComputedStyle(el).fontSize });
    }
  }
  return {
    viewport: { w: vw, h: vh },
    viewportMeta: document.querySelector('meta[name=viewport]')?.getAttribute('content') ?? null,
    horizontalOverflowPx: Math.max(0, se.scrollWidth - se.clientWidth),
    textCutRight: cutRight.length,
    textCutRightSamples: cutRight.slice(0, 15),
    textCutRightInScroller: cutRightInScroller.length,
    textCutRightInScrollerSamples: cutRightInScroller.slice(0, 15),
    pageHeightPx: se.scrollHeight,
    targets: targets.length,
    smallTargets: small.length,
    smallTargetsInline: smallInline.length,
    smallTargetsRule: '누를 영역(elementFromPoint 훑기) 44 — 화면 밖 요소는 상자 크기(rectOnly)',
    targetsMeasuredByRectOnly: rectOnly,
    coveredTargets: covered.length,
    coveredTargetSamples: covered.slice(0, 15),
    smallTargetSamples: small.slice(0, 20),
    titleOnly: titleOnly.length,
    titleOnlySamples: titleOnly.slice(0, 20),
    hoverRevealRules: hoverReveal.length,
    hoverRevealSamples: hoverReveal.slice(0, 10),
    unreadableStyleSheets: unreadableSheets,
    textUnder12px: tinyText,
    textUnder12pxSamples: tinySamples,
  };
}

function mapGeometry(selector) {
  const root = document.querySelector(selector);
  const vw = window.innerWidth, vh = window.innerHeight;
  if (!root) return null;
  const r = root.getBoundingClientRect();
  return {
    mapW: Math.round(r.width), mapH: Math.round(r.height), vw, vh,
    areaPctOfViewport: Math.round(((r.width * r.height) / (vw * vh)) * 100),
    top: Math.round(r.top + window.scrollY),
    // 스크롤 없이 첫 화면에 보이는 지도 높이의 몫(%). 맨 아래 몇 px 만 걸쳐도 참이 되는 판정은 쓰지 않는다.
    firstViewportVisiblePct: r.height > 0 ? Math.round((Math.max(0, Math.min(r.top + window.scrollY + r.height, vh) - Math.max(r.top + window.scrollY, 0)) / r.height) * 100) : 0,
  };
}

// 가운데 점이 지도 캔버스인지(겹친 투명 상자가 입력을 먹지 않는지) — 지시문 §0 「그려졌다 ≠ 조작된다」.
function mapHitTest(selector) {
  const root = document.querySelector(selector);
  if (!root) return null;
  const r = root.getBoundingClientRect();
  const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
  const el = document.elementFromPoint(cx, cy);
  const canvas = root.querySelector('canvas');
  const desc = el ? `${el.tagName.toLowerCase()}${typeof el.className === 'string' && el.className ? `.${el.className.trim().split(/\s+/)[0]}` : ''}` : null;
  return { cx: Math.round(cx), cy: Math.round(cy), hit: desc, isCanvas: !!el && el === canvas, insideMap: !!el && root.contains(el) };
}

function mapFingerprint(selector) {
  const root = document.querySelector(selector);
  const c = root?.querySelector('canvas');
  if (!c) return null;
  let h = 0;
  try {
    const g = c.getContext('2d');
    if (g) {
      for (let i = 1; i < 16; i++) for (let j = 1; j < 16; j++) {
        const d = g.getImageData(Math.floor((c.width * i) / 16), Math.floor((c.height * j) / 16), 1, 1).data;
        h = (h * 31 + d[0] * 7 + d[1] * 13 + d[2] * 17 + d[3]) >>> 0;
      }
    } else {
      const s = c.toDataURL();
      for (let i = 0; i < s.length; i += 97) h = (h * 31 + s.charCodeAt(i)) >>> 0;
    }
  } catch { return null; }
  return { hash: h, lod: document.querySelector('[data-map-lod]')?.dataset.mapLod ?? null, scrollY: Math.round(window.scrollY) };
}

function startFrameCounter() {
  window.__k10frames = { n: 0, maxGap: 0, last: performance.now(), t0: performance.now(), on: true };
  const tick = (t) => {
    const f = window.__k10frames; if (!f.on) return;
    f.n += 1; f.maxGap = Math.max(f.maxGap, t - f.last); f.last = t;
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
}

function stopFrameCounter() {
  const f = window.__k10frames; f.on = false;
  const sec = (performance.now() - f.t0) / 1000;
  return { frames: f.n, seconds: Math.round(sec * 100) / 100, fps: Math.round(f.n / sec), maxFrameGapMs: Math.round(f.maxGap) };
}

// ---- 탭 안 스니펫 (CDP 없는 사용자 브라우저용) ----
// 사용자가 직접 로그인한 탭에서 개발자 도구나 브라우저 도구의 JS 실행으로 돌린다. 결과는 JSON 문자열 하나다.
// 콜드가 아니다(그 브라우저의 캐시가 그대로다). 전송 크기는 Resource Timing 의 transferSize 합이고,
// 캐시에서 온 것은 0 으로 세어 cachedResources 로 따로 적는다. 버퍼(기본 250개)가 차면 resourceBufferFull 이 참이고
// 요청 수는 하한이다. axe 는 돌리지 않는다(바깥 스크립트를 사용자 화면에 넣지 않는다).
export function inPageSnippet(mapSelector = '.os-iso-map') {
  const fns = [initObservers, pageMetrics, layoutChecks, mapGeometry, mapHitTest].map((f) => f.toString()).join('\n');
  return `(async () => {
${fns}
initObservers();
await new Promise((r) => setTimeout(r, 300));
const sel = ${JSON.stringify(mapSelector)};
const res = performance.getEntriesByType('resource');
const nav = performance.getEntriesByType('navigation')[0];
const byName = {};
for (const r of res) byName[r.name] = (byName[r.name] || 0) + 1;
const dups = Object.entries(byName).filter(([, n]) => n > 1).map(([u, n]) => ({ url: u.replace(location.origin, '').slice(0, 140), count: n }));
const net = {
  requests: res.length + 1,
  transferBytes: res.reduce((a, r) => a + (r.transferSize || 0), nav ? nav.transferSize || 0 : 0),
  cachedResources: res.filter((r) => r.transferSize === 0 && r.decodedBodySize > 0).length,
  resourceBufferFull: res.length >= 250,
  sameUrlRepeated: dups.slice(0, 15),
  top10: [...res].sort((a, b) => b.transferSize - a.transferSize).slice(0, 10).map((r) => ({ url: r.name.replace(location.origin, '').slice(0, 140), bytes: r.transferSize })),
};
return JSON.stringify({ tool: 'tools/web/measure-pages.mjs --print-snippet', mode: 'in-page', at: new Date().toISOString(), ...pageMetrics(), network: net, layout: layoutChecks(44), map: mapGeometry(sel) ? { geometry: mapGeometry(sel), hitTest: mapHitTest(sel) } : null, axe: '미실행(탭 안 모드)' });
})()`;
}

// ---- 한 번 적재 ----

async function measureOnce(args) {
  const { browser, cdpMode } = args;
  const context = cdpMode ? browser.contexts()[0] : await browser.newContext(PROFILES[args.profile]);
  const page = await context.newPage();
  try {
    return await measureInPage({ ...args, context, page });
  } catch (e) {
    // 사용자 브라우저(CDP)에 우리 탭을 남기지 않는다 — 어느 단계에서 실패해도 우리 탭만 닫는다.
    await page.close().catch(() => {});
    if (!cdpMode) await context.close().catch(() => {});
    throw e;
  }
}

async function measureInPage({ cdpMode, opts, AxeBuilder, pagePath, profile, throttle, runIndex, context, page }) {
  const url = `${opts.base}${pagePath}`;
  const prof = PROFILES[profile];
  const cdp = await context.newCDPSession(page);
  await cdp.send('Network.enable');
  if (cdpMode) {
    // 사용자의 브라우저: 캐시만 끄고 화면 크기 · 터치 · UA 를 이 탭에만 흉내 낸다.
    await cdp.send('Network.setCacheDisabled', { cacheDisabled: true });
    await cdp.send('Emulation.setDeviceMetricsOverride', { width: prof.viewport.width, height: prof.viewport.height, deviceScaleFactor: prof.deviceScaleFactor, mobile: prof.isMobile });
    // 끌 때 maxTouchPoints 0 을 보내면 Chrome 이 「1–16」으로 거부한다 — 끌 때는 enabled 만 보낸다.
    await cdp.send('Emulation.setTouchEmulationEnabled', prof.hasTouch ? { enabled: true, maxTouchPoints: 5 } : { enabled: false });
    if (prof.userAgent) await cdp.send('Network.setUserAgentOverride', { userAgent: prof.userAgent });
  } else {
    await cdp.send('Network.setCacheDisabled', { cacheDisabled: false });
  }
  if (THROTTLES[throttle]) await cdp.send('Network.emulateNetworkConditions', THROTTLES[throttle]);

  const reqs = new Map();
  cdp.on('Network.requestWillBeSent', (e) => {
    // 리다이렉트는 같은 requestId 로 이어진다 — 앞 홉도 한 요청으로 센다.
    if (reqs.has(e.requestId) && e.redirectResponse) {
      const prev = reqs.get(e.requestId);
      reqs.set(`${e.requestId}#${prev.url}`, { ...prev, status: e.redirectResponse.status, bytes: prev.bytes ?? 0 });
    }
    reqs.set(e.requestId, { url: e.request.url, type: e.type, t0: e.timestamp });
  });
  cdp.on('Network.responseReceived', (e) => {
    const r = reqs.get(e.requestId); if (!r) return;
    const h = e.response.headers;
    r.status = e.response.status; r.mime = e.response.mimeType;
    r.enc = h['content-encoding'] || h['Content-Encoding'] || '';
    r.fromCache = !!(e.response.fromDiskCache || e.response.fromServiceWorker);
  });
  cdp.on('Network.loadingFinished', (e) => { const r = reqs.get(e.requestId); if (r) { r.bytes = e.encodedDataLength; r.t1 = e.timestamp; } });
  cdp.on('Network.loadingFailed', (e) => { const r = reqs.get(e.requestId); if (r && !e.canceled) r.failed = e.errorText; });

  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 300)); });
  page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${String(e.message).slice(0, 300)}`));

  await page.addInitScript(initObservers);

  // 시간 값은 측정하는 기계의 CPU 경합에 흔들린다. 부하를 같이 적어 두고, 부하가 높을 때 잰 시간은 상한으로 읽는다.
  const hostBefore = { loadavg1: Math.round(os.loadavg()[0] * 10) / 10, cpus: os.cpus().length };
  const phases = {}; let tp = Date.now();
  const phase = (name) => { const now = Date.now(); phases[name] = now - tp; tp = now; };
  const t0 = Date.now();
  await page.goto(url, { waitUntil: 'commit', timeout: opts.timeoutMs });
  let idleAt = null;
  const idle = page.waitForLoadState('networkidle', { timeout: 60_000 }).then(() => { idleAt = Date.now() - t0; }).catch(() => {});
  let loadAt = null;
  page.waitForLoadState('load', { timeout: 60_000 }).then(() => { loadAt = Date.now() - t0; }).catch(() => {});

  // 지도 첫 그림: 지도가 있으면 칠해질 때까지, 지도가 없으면 망이 잠잠해질 때까지 본다.
  let firstMapDrawMs = null; let lastState = null;
  const deadline = t0 + opts.timeoutMs;
  while (Date.now() < deadline) {
    const st = await page.evaluate(mapState, opts.mapSelector).catch(() => null);
    lastState = st;
    if (st?.painted) { firstMapDrawMs = Date.now() - t0; break; }
    // 지도가 없는 화면이면 망이 잠잠해지고 mapGraceMs 가 더 지나도 뿌리가 없을 때 그만 본다(늦게 붙는 지도를 기다린다).
    if (idleAt !== null && loadAt !== null && st && !st.hasRoot && Date.now() - t0 > Math.max(idleAt, loadAt) + opts.mapGraceMs) break;
    await page.waitForTimeout(200);
  }
  await idle;
  await page.waitForTimeout(500); // LCP 후보가 끝나도록
  const settledMs = idleAt ?? Date.now() - t0;
  const loadIds = new Set(reqs.keys()); // 적재 수치는 여기까지의 요청만 — 조작 탐침이 부른 요청은 따로 센다
  phase('load');

  const metrics = await page.evaluate(pageMetrics);
  const layout = await page.evaluate(layoutChecks, 44);
  const geo = await page.evaluate(mapGeometry, opts.mapSelector);
  phase('collect');

  let axe = null;
  if (opts.axe) {
    try {
      const res = await new AxeBuilder({ page }).withTags(AXE_TAGS).analyze();
      const byImpact = { critical: 0, serious: 0, moderate: 0, minor: 0 };
      const nodesByImpact = { critical: 0, serious: 0, moderate: 0, minor: 0 };
      for (const v of res.violations) { byImpact[v.impact] = (byImpact[v.impact] ?? 0) + 1; nodesByImpact[v.impact] = (nodesByImpact[v.impact] ?? 0) + v.nodes.length; }
      axe = {
        tags: AXE_TAGS, violations: res.violations.length, nodes: res.violations.reduce((a, v) => a + v.nodes.length, 0), byImpact, nodesByImpact,
        rules: res.violations.map((v) => ({ id: v.id, impact: v.impact, nodes: v.nodes.length, help: v.help, targets: v.nodes.slice(0, 3).map((n) => n.target.join(' ')) })),
        incomplete: res.incomplete.length,
      };
    } catch (e) { axe = { error: String(e.message).slice(0, 300) }; }
    phase('axe');
  }

  const tag = `${slugOf(pagePath)}-${profile}-${throttle}${runIndex > 0 ? `-r${runIndex + 1}` : ''}`;
  fs.mkdirSync(opts.out, { recursive: true });
  // 캡처는 증거일 뿐이다 — 부하가 높아 시간이 넘어도 측정값은 살리고 실패를 기록한다.
  const screenshotErrors = [];
  await page.screenshot({ path: path.join(opts.out, `${tag}-viewport.png`), timeout: 60_000 }).catch((e) => screenshotErrors.push(`viewport: ${String(e.message).split('\n')[0].slice(0, 120)}`));

  let map = null;
  if (geo) {
    const root = await page.$(opts.mapSelector);
    if (root) {
      await root.scrollIntoViewIfNeeded();
      await root.screenshot({ path: path.join(opts.out, `${tag}-map.png`), timeout: 60_000 }).catch((e) => screenshotErrors.push(`map: ${String(e.message).split('\n')[0].slice(0, 120)}`));
    }
    map = { geometry: geo, hitTest: await page.evaluate(mapHitTest, opts.mapSelector) };
    phase('screenshot');
    // 조작 탐침(데스크톱): 휠 · 끌기 뒤 지도 그림이나 LOD 가 바뀌는지, 페이지가 대신 굴러가는지, 그동안 프레임.
    if (opts.probe && !prof.isMobile && map.hitTest) {
      const { cx, cy } = map.hitTest;
      const before = await page.evaluate(mapFingerprint, opts.mapSelector);
      await page.evaluate(startFrameCounter);
      await page.mouse.move(cx, cy);
      await page.mouse.wheel(0, -400);
      await page.waitForTimeout(700);
      const afterWheel = await page.evaluate(mapFingerprint, opts.mapSelector);
      await page.mouse.move(cx, cy);
      await page.mouse.down();
      await page.mouse.move(cx + 160, cy + 60, { steps: 30 });
      await page.mouse.up();
      await page.waitForTimeout(500);
      const afterDrag = await page.evaluate(mapFingerprint, opts.mapSelector);
      const frames = await page.evaluate(stopFrameCounter);
      map.probe = {
        wheelChangedMap: !!before && !!afterWheel && (before.hash !== afterWheel.hash || before.lod !== afterWheel.lod),
        wheelScrolledPage: !!before && !!afterWheel && before.scrollY !== afterWheel.scrollY,
        dragChangedMap: !!afterWheel && !!afterDrag && afterWheel.hash !== afterDrag.hash,
        lodBefore: before?.lod ?? null, lodAfterWheel: afterWheel?.lod ?? null,
        frames, note: '프레임은 탐침 동안 rAF 수(헤드리스, 추정)',
      };
    }
  }

  phase(map?.probe ? 'probe' : 'tail');
  const list = [...reqs.entries()].filter(([id]) => loadIds.has(id)).map(([, r]) => r);
  const afterLoad = [...reqs.entries()].filter(([id]) => !loadIds.has(id)).map(([, r]) => r);
  const network = summarizeNetwork(list, (u) => u.startsWith(opts.base));
  network.afterLoad = { requests: afterLoad.length, bytes: afterLoad.reduce((a, r) => a + (r.bytes || 0), 0), note: '망이 잠잠해진 뒤(조작 탐침 등)의 요청' };
  const checks = [
    { id: 'map-first-draw-3s', source: '프론트 지시문 §0 성능 예산(광대역 기준 3초)', applies: !!geo && throttle === 'broadband', pass: firstMapDrawMs != null && firstMapDrawMs <= 3000, value: firstMapDrawMs },
    { id: 'no-duplicate-transfer', source: '§0 「같은 큰 자원을 두 번 받지 않는다」 — 크기 기준이 없어 중복 전체', applies: true, pass: network.duplicates.count === 0, value: network.duplicates.count },
    { id: 'console-errors-0', source: '§0 검증 「콘솔 오류 0」', applies: true, pass: consoleErrors.length === 0, value: consoleErrors.length },
    { id: 'network-failures-0', source: '§0 검증 「네트워크 실패 0」', applies: true, pass: network.failedCount === 0, value: network.failedCount },
    { id: 'axe-critical-0', source: 'web/game/e2e/a11y-smoke.spec.ts', applies: !!axe && !axe.error, pass: axe?.byImpact?.critical === 0, value: axe?.byImpact?.critical ?? null },
    { id: 'touch-target-44', source: 'V3System 「누르는 것은 모두 44px 이상」 + 2026-09-30 K0 「누를 영역 기준」(문장 속 링크 제외)', applies: true, pass: layout.smallTargets === 0, value: layout.smallTargets },
    { id: 'no-title-only-info', source: '§0 「호버 전용 표시 금지」 — title 에만 있는 정보', applies: true, pass: layout.titleOnly === 0, value: layout.titleOnly },
  ];

  const result = {
    tool: 'tools/web/measure-pages.mjs', url, pagePath, profile, throttle, run: runIndex + 1, cdpMode, at: new Date().toISOString(),
    ...metrics, firstMapDrawMs, networkSettledMs: settledMs, loadObservedMs: loadAt, lastMapState: lastState,
    ...network, map, layout, axe, consoleErrors: consoleErrors.slice(0, 20), consoleErrorCount: consoleErrors.length, checks, phasesMs: phases, screenshotErrors,
    host: { ...hostBefore, loadavg1After: Math.round(os.loadavg()[0] * 10) / 10 },
  };
  fs.writeFileSync(path.join(opts.out, `${tag}.json`), JSON.stringify(result, null, 2));
  await page.close();
  if (!cdpMode) await context.close();
  return { tag, result };
}

const mb = (b) => (b / 1e6).toFixed(2);
const ms = (v) => (v == null ? '—' : String(v));

export function summaryRow({ tag, result: r }) {
  return {
    tag, page: r.pagePath, profile: r.profile, throttle: r.throttle, run: r.run, loadavg1: r.host.loadavg1, cpus: r.host.cpus,
    fcpMs: r.fcpMs, lcpMs: r.lcpMs, firstMapDrawMs: r.firstMapDrawMs, settledMs: r.networkSettledMs, cls: r.cls,
    requests: r.requests, MB: Number(mb(r.transferBytes)), duplicates: r.duplicates.count, duplicateExtraMB: Number(mb(r.duplicates.extraBytes)),
    uncompressed: r.uncompressed.count, failed: r.failedCount, consoleErrors: r.consoleErrorCount,
    overflowPx: r.layout.horizontalOverflowPx, textCutRight: r.layout.textCutRight, smallTargets: r.layout.smallTargets, coveredTargets: r.layout.coveredTargets, titleOnly: r.layout.titleOnly, textUnder12px: r.layout.textUnder12px,
    axe: r.axe && !r.axe.error ? r.axe.byImpact : null, axeNodes: r.axe && !r.axe.error ? r.axe.nodes : null,
    mapFirstViewportPct: r.map?.geometry?.firstViewportVisiblePct ?? null, mapHitCanvas: r.map?.hitTest?.isCanvas ?? null,
    wheelChangedMap: r.map?.probe?.wheelChangedMap ?? null, failedChecks: r.checks.filter((c) => c.applies && !c.pass).map((c) => c.id),
  };
}

export function summaryMarkdown(rows, meta) {
  const head = '| 화면 | 프로필 | 망 | FCP | LCP | 지도 첫 그림 | 잠잠 | 요청 | MB | 중복 | 무압축 | 실패 | 콘솔 오류 | 가로 넘침 | 오른쪽 잘린 글자 | 44 미만 | title 전용 | 12px 미만 글자 | axe 치명·심각·보통·경미 | 걸린 기준 |';
  const sep = '|' + '---|'.repeat(20);
  const lines = rows.map((x) => x.error ? `| ${x.page} | ${x.profile} | ${x.throttle} | 측정 실패: ${x.error.replace(/\|/g, '/')} |` : `| ${x.page}${x.run > 1 ? ` (${x.run})` : ''} | ${x.profile} | ${x.throttle} | ${ms(x.fcpMs)} | ${ms(x.lcpMs)} | ${ms(x.firstMapDrawMs)} | ${ms(x.settledMs)} | ${x.requests} | ${x.MB} | ${x.duplicates}${x.duplicates ? ` (+${x.duplicateExtraMB} MB)` : ''} | ${x.uncompressed} | ${x.failed} | ${x.consoleErrors} | ${x.overflowPx} | ${x.textCutRight} | ${x.smallTargets} | ${x.titleOnly} | ${x.textUnder12px} | ${x.axe ? `${x.axe.critical}·${x.axe.serious}·${x.axe.moderate}·${x.axe.minor}` : '—'} | ${x.failedChecks.join(', ') || '없음'} |`);
  return [`# 페이지 측정 — ${meta.base}`, '', `- 시각: ${meta.at} · 도구: tools/web/measure-pages.mjs · 브라우저: ${meta.browser}`, '- 시간 단위 ms. FCP · LCP 는 탐색 시작 기준, 지도 첫 그림 · 잠잠은 goto 호출 기준(M1 기준선과 같은 식).', `- 측정 기계 부하(1분 평균 / CPU 수): ${rows.filter((x) => !x.error).map((x) => `${x.loadavg1}`).join(' · ')} / ${rows.find((x) => !x.error)?.cpus ?? '—'} — 부하가 CPU 수보다 크게 높으면 시간 값은 상한으로 읽는다(요청 수 · 바이트는 영향 없음).`, '', head, sep, ...lines, ''].join('\n');
}

export function defaultOptions(overrides = {}) {
  return { ...parseArgs(['--out', overrides.out ?? '.']), ...overrides };
}

// 도구 본체. 테스트는 이것을 부른다.
export async function run(opts) {
  const { chromium } = load('@playwright/test');
  const axeMod = load('@axe-core/playwright');
  const AxeBuilder = axeMod.default ?? axeMod.AxeBuilder ?? axeMod;
  const cdpMode = !!opts.cdpUrl;
  const launch = () => (cdpMode ? chromium.connectOverCDP(opts.cdpUrl) : chromium.launch({ channel: opts.channel, headless: true }));
  // 결과 폴더는 측정 전에 만든다 — 모든 측정이 goto 전에 실패해도 오류 행이 든 summary 를 남겨야 한다.
  fs.mkdirSync(opts.out, { recursive: true });
  let browser = await launch();
  const results = [];
  try {
    for (const pagePath of opts.pages) for (const profile of opts.profiles) for (const throttle of opts.throttles) for (let i = 0; i < opts.repeat; i++) {
      // 헤드리스 Chrome 이 측정 중 닫힐 수 있다(2026-09-30 에는 다른 세션의 넓은 패턴 pkill 이 죽였다). 한 번만 다시 띄워 재고,
      // 그래도 안 되면 오류 행으로 남긴다.
      let out = null; let lastError = null;
      for (let attempt = 0; attempt < 2 && !out; attempt++) {
        try {
          out = await measureOnce({ browser, cdpMode, opts, AxeBuilder, pagePath, profile, throttle, runIndex: i });
        } catch (e) {
          lastError = e;
          if (cdpMode || !/closed|crash|disconnected/i.test(String(e.message))) break;
          console.error(`브라우저가 닫혔다(${pagePath} ${profile} ${throttle}) — 다시 띄운다`);
          await browser.close().catch(() => {});
          browser = await launch();
        }
      }
      if (!out) {
        const row = { tag: `${slugOf(pagePath)}-${profile}-${throttle}`, page: pagePath, profile, throttle, run: i + 1, error: String(lastError?.message ?? lastError).slice(0, 300) };
        results.push(row);
        console.log(JSON.stringify(row));
        continue;
      }
      const row = summaryRow(out);
      results.push(row);
      console.log(JSON.stringify(row));
    }
  } finally {
    // 사용자 브라우저(CDP)는 닫지 않는다 — 우리 탭은 이미 닫았고, 연결은 프로세스가 끝날 때 끊긴다.
    if (!cdpMode) await browser.close();
  }
  const meta = { base: opts.base, at: new Date().toISOString(), browser: cdpMode ? `CDP ${opts.cdpUrl}` : `system ${opts.channel} (headless)` };
  fs.writeFileSync(path.join(opts.out, 'summary.json'), JSON.stringify({ ...meta, rows: results }, null, 2));
  fs.writeFileSync(path.join(opts.out, 'summary.md'), summaryMarkdown(results, meta));
  return results;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  // 측정을 못 한 행이 하나라도 있으면 실패다 — 오류 행을 남기고도 0 으로 끝나면 「잰 줄 알았는데 안 잰」 결과가 초록으로 읽힌다.
  run(parseArgs(process.argv.slice(2))).then((rows) => {
    const failed = rows.filter((r) => r.error);
    if (failed.length) {
      console.error(`측정 실패 ${failed.length}/${rows.length}: ${failed.map((r) => `${r.page} ${r.profile} ${r.throttle} — ${r.error}`).join(' | ')}`);
      process.exit(1);
    }
    process.exit(0);
  }, (e) => { console.error(e); process.exit(1); });
}
