#!/usr/bin/env node
// 설계 보드 일관성 검사 (K10, 2026-09-30) — .dc.html 보드마다 어긋난 곳을 센다.
//
//   node tools/web/board-lint.mjs [파일·폴더 …] [--json out.json] [--md out.md] [--fail-on small,title,emoji,words]
//
// 폴더를 주면 그 안의 *.dc.html 을 모두 본다. 아무것도 안 주면 docs/design/ui-v3/project 다.
// 보드를 헤드리스 Chrome 으로 그린 뒤(바깥 요청은 막는다 — 글꼴 · support.js · /_blob 그림 없이) 센다:
//
//   small   누를 것 중 44px 미만 — V3System 「누르는 것은 모두 44px 이상」, 09-18 BRIEF 「터치 대상 ≥44px」
//           (문장 속 링크는 WCAG 2.5.8 예외라 따로 센다. 보드 밖으로 잘린 것은 small 이 아니라 clipped 다)
//   fake    누르는 모양(cursor:pointer)인데 진짜 button · a · label · input 이 아닌 것 — BRIEF 「버튼은 진짜 <button>」
//   title   title 속성에만 있는 정보 — V3System 「호버 · title 로만 보이는 정보는 두지 않는다」
//   hover   :hover 로 display · visibility · opacity 를 드러내는 CSS 규칙(추정)
//   emoji   이모지 — BRIEF 「이모지 금지」 (▲▼ 같은 글자 기호는 세지 않는다)
//   words   V3System 「쓰지 않는 말」 표의 말. 취소선을 그은 글자(그 표 자체)는 세지 않는다
//   clipped 보드 뿌리(고정 크기, overflow hidden) 밖으로 나가 잘린 글자 · 누를 것 — BRIEF 「내용이 넘치면 잘린다」
//           (지도 SVG 글자 · 화면 읽기 전용 글자 · 안쪽 상자가 일부러 자른 줄은 빼고, 마지막 것은 innerCropped 로 센다)
//
// 보드의 설계 설명 글(주석)은 조상에 data-lint="skip" 을 달면 words · emoji 에서 빠진다(크기 검사는 그대로).
//
// 보고용 도구다. --fail-on 에 적은 항목이 한 보드라도 0 이 아니면 종료 코드 1 이다.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const webRequire = createRequire(path.join(ROOT, 'web/game/package.json'));

export const KEYS = ['small', 'fake', 'title', 'hover', 'emoji', 'words', 'clipped'];

// V3System 「쓰지 않는 말」(docs/design/ui-v3/boards_v3_shell.py WORDS). 표가 바뀌면 board-lint.test.mjs 가 깨진다.
// 「전(錢)」의 「전」 · 「곡(穀)」의 「곡」은 한 글자라 다른 말과 겹친다 — 한자만 센다.
// 「년 월(표기)」은 날짜를 「200년 3월 중순」처럼 순까지 쓰라는 뜻으로 읽고, 순이 없는 「N년 N월」을 센다(해석).
export const FORBIDDEN = [
  { word: '휘하', use: '부', re: '휘하' },
  { word: '縣', use: '현', re: '縣' },
  { word: '郡', use: '군', re: '郡' },
  { word: '城', use: '성', re: '城' },
  { word: '省', use: '구역', re: '省' },
  { word: '자금', use: '금', re: '자금' },
  { word: '錢', use: '금', re: '錢' },
  { word: '국고', use: '수도 창고', re: '국고' },
  { word: '군량', use: '쌀', re: '군량' },
  { word: '穀', use: '쌀', re: '穀' },
  { word: '병량', use: '쌀', re: '병량' },
  { word: '예턴', use: '명령 목록 · 예약', re: '예턴' },
  { word: '사령턴', use: '명령 목록 · 예약', re: '사령턴' },
  { word: '휴식', use: '빈 순', re: '휴식' },
  { word: '계책 손패', use: '계책 덱(손패는 칸 이름)', re: '계책\\s*손패' },
  { word: '숙련', use: '쓰지 않는다', re: '숙련' },
  { word: '명성', use: '쓰지 않는다', re: '명성' },
  { word: '계급', use: '쓰지 않는다', re: '계급' },
  { word: '삭턴', use: '쓰지 않는다', re: '삭턴' },
  { word: '벌점', use: '쓰지 않는다', re: '벌점' },
  { word: 'N년 N월(순 없음)', use: '200년 3월 중순', re: '\\d+\\s*년\\s*\\d+\\s*월(?!\\s*[상중하]순)' },
];

function parseArgs(argv) {
  const opts = { paths: [], json: null, md: null, failOn: [], channel: 'chrome' };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => { const v = argv[++i]; if (v === undefined) throw new Error(`${a} 에 값이 없다`); return v; };
    if (a === '--json') opts.json = next();
    else if (a === '--md') opts.md = next();
    else if (a === '--fail-on') opts.failOn = next().split(',').map((s) => s.trim()).filter(Boolean);
    else if (a === '--channel') opts.channel = next();
    else if (a === '-h' || a === '--help') { console.log(fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').slice(0, 22).join('\n')); process.exit(0); }
    else if (a.startsWith('--')) throw new Error(`모르는 인자: ${a}`);
    else opts.paths.push(a);
  }
  for (const k of opts.failOn) if (!KEYS.includes(k)) throw new Error(`--fail-on 에 모르는 항목: ${k} (가능: ${KEYS.join(', ')})`);
  if (opts.paths.length === 0) opts.paths.push(path.join(ROOT, 'docs/design/ui-v3/project'));
  return opts;
}

export function boardFiles(paths) {
  const out = [];
  for (const p of paths) {
    const st = fs.statSync(p);
    if (st.isDirectory()) {
      for (const n of fs.readdirSync(p).sort()) if (n.endsWith('.dc.html')) out.push(path.resolve(p, n));
    } else out.push(path.resolve(p));
  }
  return out;
}

function previewSize(html) {
  const m = html.match(/"\$preview"\s*:\s*\{\s*"width"\s*:\s*(\d+)\s*,\s*"height"\s*:\s*(\d+)/);
  return m ? { width: Number(m[1]), height: Number(m[2]) } : null;
}

// ---- 보드 안에서 도는 검사 (page.evaluate) ----
function lintInPage({ forbidden, minTarget }) {
  const root = document.querySelector('x-dc > div') || document.body;
  const rr = root.getBoundingClientRect();
  const rendered = (el) => el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden';
  const describe = (el) => {
    const r = el.getBoundingClientRect();
    const cls = typeof el.className === 'string' && el.className.trim() ? `.${el.className.trim().split(/\s+/).slice(0, 3).join('.')}` : '';
    const text = (el.innerText || el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('placeholder') || '').trim().replace(/\s+/g, ' ').slice(0, 30);
    return { el: `${el.tagName.toLowerCase()}${cls}`, text, w: Math.round(r.width), h: Math.round(r.height) };
  };
  const outside = (r) => r.right > rr.right + 1 || r.bottom > rr.bottom + 1 || r.left < rr.left - 1 || r.top < rr.top - 1;
  // 보드 뿌리가 자른 것만 「잘림」이다. 안쪽 상자가 overflow 로 일부러 자른 줄(순 띠 · 긴 목록)은 innerCropped 로 따로 센다.
  const croppedInside = (el, r) => {
    for (let a = el.parentElement; a && a !== root; a = a.parentElement) {
      const cs = getComputedStyle(a);
      if (cs.overflowX === 'visible' && cs.overflowY === 'visible') continue;
      const ar = a.getBoundingClientRect();
      if (r.right > ar.right + 1 || r.bottom > ar.bottom + 1 || r.left < ar.left - 1 || r.top < ar.top - 1) return true;
    }
    return false;
  };
  // 화면 읽기 전용 글자(left:-9999px · 1px 상자 · clip)는 보이지 않는 것이 맞다.
  const srOnly = (el, r) => {
    const cs = getComputedStyle(el);
    if (cs.position !== 'absolute' && cs.position !== 'fixed') return false;
    return r.width <= 1 || r.height <= 1 || (cs.clip && cs.clip !== 'auto') || cs.clipPath.startsWith('inset(50%')
      || r.right < rr.left - 100 || r.left > rr.right + 100 || r.bottom < rr.top - 100;
  };
  let innerCropped = 0;

  // 누를 것: 진짜 조작 요소 + 누르는 모양(cursor:pointer)의 바깥쪽 요소
  const REAL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[role=tab],[role=checkbox],[role=radio],[role=switch],[role=menuitem],[role=option]';
  const real = new Set([...root.querySelectorAll(REAL)].filter(rendered));
  // 입력을 감싼 라벨은 라벨 전체가 누르는 자리다(안쪽 입력 대신 센다). for= 로 이은 라벨은 입력 크기에 합친다.
  for (const label of root.querySelectorAll('label')) {
    if (label.control && label.contains(label.control) && rendered(label)) { real.delete(label.control); real.add(label); }
  }
  const fake = [];
  for (const el of root.querySelectorAll('*')) {
    if (real.has(el) || !rendered(el)) continue;
    if (getComputedStyle(el).cursor !== 'pointer') continue;
    const p = el.parentElement;
    if (el.closest(`${REAL},label`)) continue; // 진짜 요소 안쪽
    if (p && p !== root && getComputedStyle(p).cursor === 'pointer') continue; // 바깥쪽 하나만
    fake.push(el);
  }
  const small = []; const smallInline = []; const clipped = []; const clippedCtl = [];
  for (const el of [...real, ...fake]) {
    const r = el.getBoundingClientRect();
    if (outside(r)) {
      if (croppedInside(el, r)) innerCropped += 1; else clipped.push(describe(el));
      clippedCtl.push(el);
      continue;
    }
    let w = r.width, h = r.height;
    const label = el.tagName !== 'LABEL' && el.id ? root.querySelector(`label[for="${CSS.escape(el.id)}"]`) : null;
    if (label) { const lr = label.getBoundingClientRect(); w = Math.max(w, lr.width); h = Math.max(h, lr.height); }
    if (w >= minTarget && h >= minTarget) continue;
    const inline = el.tagName === 'A' && getComputedStyle(el).display === 'inline' && (el.parentElement?.innerText || '').trim().length > (el.innerText || '').trim().length + 1;
    (inline ? smallInline : small).push(describe(el));
  }

  // 글자 조각 모으기: 보이는 글자(취소선 조상 제외) + 읽히는 속성
  const struck = (el) => { for (let e = el; e && e !== document.body; e = e.parentElement) if ((getComputedStyle(e).textDecorationLine || '').includes('line-through')) return true; return false; };
  const pieces = [];
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    const el = n.parentElement;
    if (!el || ['STYLE', 'SCRIPT'].includes(el.tagName) || !n.textContent.trim() || !rendered(el)) continue;
    const r = el.getBoundingClientRect();
    // 지도 그림(SVG) 글자는 가장자리에서 잘리는 것이 정상이다.
    if (outside(r) && el.children.length === 0 && !el.closest('svg') && !srOnly(el, r) && !clippedCtl.some((c) => c.contains(el))) {
      if (croppedInside(el, r)) innerCropped += 1; else clipped.push({ ...describe(el), kind: 'text' });
    }
    if (el.closest('[data-lint="skip"]')) continue; // 보드 주석(설계 설명) — 표기 검사에서 뺀다
    pieces.push({ text: n.textContent, struck: struck(el), where: describe(el).el });
  }
  for (const el of root.querySelectorAll('[alt],[title],[aria-label],[placeholder]')) {
    if (el.closest('[data-lint="skip"]')) continue;
    for (const a of ['alt', 'title', 'aria-label', 'placeholder']) {
      const v = el.getAttribute(a);
      if (v && v.trim()) pieces.push({ text: v, struck: false, where: `${el.tagName.toLowerCase()}[${a}]` });
    }
  }

  const words = {}; const wordSamples = [];
  for (const f of forbidden) {
    const re = new RegExp(f.re, 'gu');
    let count = 0;
    for (const p of pieces) {
      if (p.struck) continue;
      for (const m of p.text.matchAll(re)) {
        count += 1;
        if (wordSamples.length < 40) {
          const i = m.index;
          wordSamples.push({ word: f.word, use: f.use, at: p.where, context: p.text.slice(Math.max(0, i - 12), i + m[0].length + 12).replace(/\s+/g, ' ').trim() });
        }
      }
    }
    if (count) words[f.word] = count;
  }

  // 이모지: 기본이 그림 표시인 글자, 또는 VS16(U+FE0F)을 붙인 그림 글자, 키캡.
  const EMOJI = /\p{Emoji_Presentation}|\p{Extended_Pictographic}️|[#*0-9]️?⃣/gu;
  const emoji = [];
  for (const p of pieces) for (const m of p.text.matchAll(EMOJI)) emoji.push({ ch: m[0], at: p.where });

  const titleOnly = [];
  for (const el of root.querySelectorAll('[title]')) {
    const t = (el.getAttribute('title') || '').trim();
    if (!t) continue;
    const seen = `${el.innerText || ''} ${el.getAttribute('aria-label') || ''} ${el.getAttribute('alt') || ''}`;
    if (!seen.includes(t)) titleOnly.push({ ...describe(el), title: t.slice(0, 60) });
  }

  const hover = [];
  const walk = (rules) => {
    for (const rule of rules) {
      if (rule.cssRules && !(rule instanceof CSSStyleRule)) { walk(rule.cssRules); continue; }
      if (!(rule instanceof CSSStyleRule) || !rule.selectorText.includes(':hover')) continue;
      // 이 화면에 그 규칙이 걸리는 요소가 있을 때만 센다(공용 CSS 에만 있는 규칙은 뺀다).
      try { if (!root.querySelector(rule.selectorText.replace(/:hover/g, ''))) continue; } catch { /* 읽을 수 없는 선택자는 센다 */ }
      const s = rule.style;
      if ((s.display && s.display !== 'none') || s.visibility === 'visible' || s.opacity === '1') hover.push(rule.selectorText.slice(0, 120));
    }
  };
  for (const sheet of document.styleSheets) { try { walk(sheet.cssRules); } catch { /* 바깥 글꼴 시트 */ } }

  return {
    size: { w: Math.round(rr.width), h: Math.round(rr.height) },
    targets: real.size + fake.length,
    counts: {
      small: small.length, fake: fake.length, title: titleOnly.length, hover: hover.length,
      emoji: emoji.length, words: Object.values(words).reduce((a, b) => a + b, 0), clipped: clipped.length,
    },
    smallInline: smallInline.length,
    // 종류별 합계는 표본(상한 25)이 아니라 전체에서 센다: 「요소.클래스 높이」 → 개수
    smallByKind: small.reduce((m, x) => { const k = `${x.el} ${Math.min(x.w, x.h)}px`; m[k] = (m[k] ?? 0) + 1; return m; }, {}),
    innerCropped,
    lintSkipBlocks: root.querySelectorAll('[data-lint="skip"]').length,
    words,
    samples: {
      small: small.slice(0, 25), fake: fake.slice(0, 15).map(describe), title: titleOnly.slice(0, 15), hover: hover.slice(0, 10),
      emoji: emoji.slice(0, 15), words: wordSamples, clipped: clipped.slice(0, 15),
    },
  };
}

export async function lintBoards(files, { channel = 'chrome' } = {}) {
  let chromium;
  try { ({ chromium } = webRequire('@playwright/test')); } catch {
    throw new Error('@playwright/test 를 web/game 에서 찾지 못했다. 먼저: pnpm -C web install --frozen-lockfile --filter @opensamguk/web-game...');
  }
  const browser = await chromium.launch({ channel, headless: true });
  const results = [];
  try {
    for (const file of files) {
      const html = fs.readFileSync(file, 'utf8');
      const size = previewSize(html) ?? { width: 1440, height: 1000 };
      const context = await browser.newContext({ viewport: { width: Math.max(size.width, 320), height: Math.max(size.height, 320) } });
      const page = await context.newPage();
      await page.route('**/*', (route) => (route.request().url().startsWith('file:') ? route.continue() : route.abort()));
      await page.goto(pathToFileURL(file).href, { waitUntil: 'load' });
      const r = await page.evaluate(lintInPage, { forbidden: FORBIDDEN, minTarget: 44 });
      results.push({ board: path.relative(ROOT, file).startsWith('..') ? file : path.relative(ROOT, file), name: path.basename(file), preview: size, ...r });
      await context.close();
    }
  } finally {
    await browser.close();
  }
  return results;
}

export function toMarkdown(results) {
  const total = Object.fromEntries(KEYS.map((k) => [k, results.reduce((a, r) => a + r.counts[k], 0)]));
  const allWords = {};
  for (const r of results) for (const [w, n] of Object.entries(r.words)) allWords[w] = (allWords[w] ?? 0) + n;
  const fmtWords = (ws) => Object.entries(ws).sort((a, b) => b[1] - a[1]).map(([w, n]) => `${w} ${n}`).join(' · ') || '—';
  const rows = results.map((r) => `| ${r.name} | ${r.size.w}×${r.size.h} | ${r.targets} | ${r.counts.small}${r.smallInline ? ` (+문장 속 링크 ${r.smallInline})` : ''} | ${r.counts.fake} | ${r.counts.title} | ${r.counts.hover} | ${r.counts.emoji} | ${r.counts.words} | ${fmtWords(r.words)} | ${r.counts.clipped} | ${r.innerCropped} |`);
  const kinds = {};
  for (const r of results) for (const [k, n] of Object.entries(r.smallByKind ?? {})) kinds[k] = (kinds[k] ?? 0) + n;
  const topKinds = Object.entries(kinds).sort((a, b) => b[1] - a[1]).slice(0, 12).map(([k, n]) => `\`${k}\` ${n}`).join(' · ') || '—';
  return [
    '# 설계 보드 일관성 검사 — tools/web/board-lint.mjs', '',
    `- 보드 ${results.length}장. 기준: V3System(44px · 호버/title 전용 금지 · 쓰지 않는 말), 09-18 BRIEF(진짜 button · 이모지 금지 · 고정 크기에서 잘림).`,
    '- 「N년 N월(순 없음)」은 V3System 「년 월(표기) → 200년 3월 중순」의 해석이다.', '',
    '| 보드 | 크기 | 누를 것 | 44 미만 | 가짜 누를 것 | title 전용 | hover 드러냄 | 이모지 | 금지어 | 금지어 내역 | 뿌리 밖 잘림 | 안쪽 자름(참고) |',
    '|---|---|---|---|---|---|---|---|---|---|---|---|',
    ...rows,
    `| **합계** | | ${results.reduce((a, r) => a + r.targets, 0)} | ${total.small} | ${total.fake} | ${total.title} | ${total.hover} | ${total.emoji} | ${total.words} | ${fmtWords(allWords)} | ${total.clipped} | ${results.reduce((a, r) => a + r.innerCropped, 0)} |`, '', `44 미만 종류(요소.클래스 짧은 변): ${topKinds}`, '',
  ].join('\n');
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const files = boardFiles(opts.paths);
  if (files.length === 0) throw new Error('검사할 .dc.html 이 없다');
  const results = await lintBoards(files, opts);
  const md = toMarkdown(results);
  if (opts.json) fs.writeFileSync(opts.json, JSON.stringify({ at: new Date().toISOString(), forbidden: FORBIDDEN, results }, null, 2));
  if (opts.md) fs.writeFileSync(opts.md, md);
  console.log(md);
  const failing = results.filter((r) => opts.failOn.some((k) => r.counts[k] > 0));
  if (failing.length) {
    console.error(`--fail-on ${opts.failOn.join(',')}: ${failing.length}장이 걸렸다 — ${failing.map((r) => r.name).join(', ')}`);
    process.exit(1);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((e) => { console.error(e.message ?? e); process.exit(2); });
}
