#!/usr/bin/env node
// 설계 보드 일관성 검사 (K10, 2026-09-30) — .dc.html 보드마다 어긋난 곳을 센다.
//
//   node tools/web/board-lint.mjs [파일·폴더 …] [--json out.json] [--md out.md] [--fail-on small,title,emoji,words]
//
// 폴더를 주면 그 안의 *.dc.html 을 모두 본다. 아무것도 안 주면 docs/design/ui-v3/project 다.
// 보드를 헤드리스 Chrome 으로 그린 뒤(바깥 요청은 막는다 — 글꼴 · support.js · /_blob 그림 없이) 센다:
//
//   small   누를 영역이 44px 미만인 것 — V3System 「누르는 것은 모두 44px 이상」, 2026-09-30 K0 결정 「보이는 크기가 아니라
//           누를 영역」(09-18 BRIEF 의 .btn.sm 보조 예외는 폐기). 누를 영역 = 가운데에서 훑은 elementFromPoint 적중 범위
//           (문장 속 링크는 WCAG 2.5.8 예외라 따로 센다. 보드 밖으로 잘린 것은 small 이 아니라 clipped 다)
//   fake    누르는 모양(cursor:pointer)인데 진짜 button · a · label · input 이 아닌 것 — BRIEF 「버튼은 진짜 <button>」
//   title   title 속성 전부 — 제품 tools/ci/web_ui_lint.py title_attr 와 같은 기준(표본 onlyInTitle = 정보가 title 에만 있음)
//   disabledAttr 네이티브 disabled — web_ui_lint native_disabled(탭을 삼켜 사유 시트가 안 열린다, aria-disabled 로)
//   dimmed  흐린 비활성(계산된 opacity < 1) — web_ui_lint dimmed_disabled · V3System 「흐리게 하지 않는다」
//   breakpoint 세 단(768 · 1200) 밖의 @media 폭 조건 — web_ui_lint adhoc_breakpoint
//   hover   :hover 로 display · visibility · opacity 를 드러내는 CSS 규칙(추정)
//   emoji   이모지 — BRIEF 「이모지 금지」 (▲▼ 같은 글자 기호는 세지 않는다)
//   words   V3System 「쓰지 않는 말」 표의 말(한자 칸은 hanja 가 센다). 취소선을 그은 글자(그 표 자체)는 세지 않는다
//   hanja   class="hj"(같은 읽기 지명 병기) · data-lint="skip" · 취소선 밖의 한자 전부 — 시스템 3.1.4 표기 규칙
//   covered 가운데가 다른 요소에 덮인 누를 것 — 결함(겹친 투명 상자 · 장식이 조작을 먹는 부류, 2026-09-30 K0 판정).
//           열린 층(대화상자 · 시트 · 딤 · 떠 있는 카드 · 열린 목록 role=listbox/menu) 아래 덮인 것은 정상이라 underLayer 로 따로 센다.
//           지도 표식 .mk 는 층 아래여도 결함
//   placeholder 보이는 글자에 남은 그림 파일 이름(「logo-wordmark.png」 같은 자리 표시) — 3.1.2 부터 결함
//   logo    워드마크(img alt="오픈삼국")가 한 화면에 둘 이상 — 시스템 3.1.4 「로고 한 번」, 둘째부터 센다
//   clipped 보드 뿌리(고정 크기, overflow hidden) 밖으로 나가 잘린 글자 · 누를 것 — BRIEF 「내용이 넘치면 잘린다」
//           (지도 SVG 글자 · 화면 읽기 전용 글자 · 안쪽 상자가 일부러 자른 줄은 빼고, 마지막 것은 innerCropped 로 센다)
//
//   contrast 글자 대비 미달 — axe color-contrast(WCAG AA 4.5:1 · 큰 글자 3:1, 제품 a11y 스모크 · 측정 도구와 같은 axe). 보드 색이
//           화면 토큰에서 어긋나면 화면에서 같은 빨강이 되풀이된다(2026-10-03 운영 콘솔 위험 표식, 원장 D57 · D73–D76a). 바탕이 그라데이션 ·
//           그림 · 겹친 상자라 axe 가 정하지 못한 글자는 contrastUnknown 으로 따로 센다 — 그래서 0 을 「대비 통과」로 읽지 않는다.
//           CI 는 --fail-on contrast 로 막는다(naming-lint, 2026-10-03). data-lint="skip" 설명 글은 뺀다.
//
// 보드의 설계 설명 글(주석)은 조상에 data-lint="skip" 을 달면 words · hanja · emoji · placeholder 에서 빠진다(크기 검사는 그대로).
// 누를 영역 · 덮임은 elementFromPoint 로 재므로 보드 뿌리가 검사 화면 안에 들어와야 한다 — 미리보기 크기가 뿌리보다 작으면
// 검사 화면을 뿌리 크기까지 넓힌다.
//
// 잰 노드 하한(--contrast-floor 기준선.json, 2026-10-04): axe 는 잘린 글자를 미달 · 판정 못 함 어느 칸에도 세지 않는다(#1276).
// 그래서 「미달 0」만으로는 검사가 살아 있는지 모른다. 보드마다 대비를 잰 노드(통과 + 미달 + 판정 못 함)가 기준선보다
// 허용 차이(tolerance)를 넘게 줄면 걸린다. 기준선에 없는 새 보드는 0 이면 걸린다. 기준선 · 허용 차이는 CI 실측에서 뽑는다
// (tools/web/board-contrast-baseline.json 의 source · why). 보드를 바꿔 글자가 줄었으면 기준선을 같이 고친다 — 브라우저 없이
// board-lint JSON(CI artifact board-contrast-* 또는 로컬 --json)에서 그 보드들의 값만 덮어쓴다:
//   node tools/web/board-lint.mjs --update-contrast-floor tools/web/board-contrast-baseline.json --from board-lint.json [--source 설명]
//
// 보고용 도구다. --fail-on 에 적은 항목이 한 보드라도 0 이 아니거나, --contrast-floor 에 걸린 보드가 있으면 종료 코드 1 이다.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

// 도움말은 머리 주석 전체다(고정 줄 수로 자르면 설명이 중간에서 끊긴다) — 첫 import 줄 앞까지.
function helpText() {
  const lines = fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n');
  return lines.slice(0, lines.findIndex((l) => l.startsWith('import '))).join('\n');
}

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const webRequire = createRequire(path.join(ROOT, 'web/game/package.json'));

export const KEYS = ['small', 'fake', 'title', 'hover', 'disabledAttr', 'dimmed', 'breakpoint', 'emoji', 'words', 'hanja', 'clipped', 'covered', 'placeholder', 'logo', 'contrast'];

// V3System 「쓰지 않는 말」(docs/design/ui-v3/boards_v3_shell.py WORDS). 표가 바뀌면 board-lint.test.mjs 가 깨진다.
// 「전(錢)」의 「전」 · 「곡(穀)」의 「곡」은 한 글자라 다른 말과 겹친다 — 한자만 센다.
// 「년 월(표기)」은 날짜를 「200년 3월 중순」처럼 순까지 쓰라는 뜻으로 읽고, 순이 없는 「N년 N월」을 센다(해석).
// hanja: true 인 말은 class="hj"(같은 읽기 지명의 한자 병기, 시스템 3.1.2) 안에서는 세지 않는다.
export const FORBIDDEN = [
  { word: '휘하', use: '부', re: '휘하' },
  { word: '縣', use: '현', re: '縣' , hanja: true },
  { word: '郡', use: '군', re: '郡' , hanja: true },
  { word: '城', use: '성', re: '城' , hanja: true },
  { word: '省', use: '구역', re: '省' , hanja: true },
  { word: '자금', use: '금', re: '자금' },
  { word: '錢', use: '금', re: '錢' , hanja: true },
  { word: '국고', use: '수도 창고', re: '국고' },
  { word: '군량', use: '쌀', re: '군량' },
  { word: '穀', use: '쌀', re: '穀' , hanja: true },
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
  // 시스템 3.1.4 V31SystemIndex 「날짜」: 달마다 하는 일(월단평 등)만 순 없이 적어도 된다 — 알려진 것은 월단평뿐이라 그것만 뺀다.
  // 제품 web_copy_lint retired_term 에만 있던 말(2026-09-30 맞춤). 숙련전환 · 군량매매는 숙련 · 군량이 이미 센다.
  { word: '세율', use: '쓰지 않는다(web_copy_lint)', re: '세율' },
  { word: '빙의', use: '쓰지 않는다(web_copy_lint)', re: '빙의' },
  { word: 'N년 N월(순 없음)', use: '200년 3월 중순', re: '\\d+\\s*년\\s*\\d+\\s*월(?!\\s*(?:[상중하]순|월단평))' },
];

function parseArgs(argv) {
  const opts = { paths: [], json: null, md: null, failOn: [], channel: 'chrome', contrastFloor: null, updateFloor: null, from: null, source: null };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => { const v = argv[++i]; if (v === undefined) throw new Error(`${a} 에 값이 없다`); return v; };
    if (a === '--json') opts.json = next();
    else if (a === '--md') opts.md = next();
    else if (a === '--fail-on') opts.failOn = next().split(',').map((s) => s.trim()).filter(Boolean);
    else if (a === '--channel') opts.channel = next();
    else if (a === '--contrast-floor') opts.contrastFloor = next();
    else if (a === '--update-contrast-floor') opts.updateFloor = next();
    else if (a === '--from') opts.from = next();
    else if (a === '--source') opts.source = next();
    else if (a === '-h' || a === '--help') { console.log(helpText()); process.exit(0); }
    else if (a.startsWith('--')) throw new Error(`모르는 인자: ${a}`);
    else opts.paths.push(a);
  }
  for (const k of opts.failOn) if (!KEYS.includes(k)) throw new Error(`--fail-on 에 모르는 항목: ${k} (가능: ${KEYS.join(', ')})`);
  if (opts.updateFloor && !opts.from) throw new Error('--update-contrast-floor 에는 --from <board-lint.json> 이 있어야 한다');
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
  // 누를 영역(2026-09-30 K0 결정: 보이는 크기가 아니라 누를 영역 44 이상). 가운데에서 바깥으로 1px 씩 훑어
  // elementFromPoint 가 그 요소(또는 그 안쪽)를 돌려주는 폭 · 높이를 잰다 — 패딩 · ::before 확장은 들어가고, 다른 요소가 덮은 곳은 빠진다.
  const hitArea = (el) => {
    const r = el.getBoundingClientRect();
    const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    const mine = (x, y) => {
      if (x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) return false;
      const h = document.elementFromPoint(x, y);
      return !!h && (h === el || el.contains(h));
    };
    // 보이는 상자가 44 이상이면 가운데와 ±21px 네 점만 본다(겹친 투명 상자가 덮었는지).
    const half = minTarget / 2 - 1;
    if (r.width >= minTarget && r.height >= minTarget && [[0, 0], [-half, 0], [half, 0], [0, -half], [0, half]].every(([dx, dy]) => mine(cx + dx, cy + dy))) {
      return { w: r.width, h: r.height, covered: false };
    }
    if (!mine(cx, cy)) return { w: 0, h: 0, covered: true, top: document.elementFromPoint(cx, cy) };
    const reach = (dx, dy) => { let d = 0; while (d < 64 && mine(cx + dx * (d + 1), cy + dy * (d + 1))) d += 1; return d; };
    return { w: reach(-1, 0) + reach(1, 0) + 1, h: reach(0, -1) + reach(0, 1) + 1, covered: false };
  };
  // 열린 층(2026-09-30 K0 판정): 대화상자 · 하단 시트 · 딤이 열린 보드에서 그 아래가 덮인 것은 정상이다.
  const LAYER = '[role=dialog],[role=alertdialog],[aria-modal="true"],dialog[open],.sheet,.scrim,.dim,.pop,[role=listbox],[role=menu]';
  const layerKind = (l) => (l.matches('.scrim,.dim') ? '딤' : l.matches('.sheet') ? '시트' : l.matches('.pop') ? '떠 있는 카드' : l.matches('[role=listbox],[role=menu]') ? '열린 목록' : '대화상자');
  const pathOf = (node) => {
    const parts = [];
    for (let e = node; e && e !== root && parts.length < 3; e = e.parentElement) parts.unshift(describe(e).el);
    return parts.join(' > ');
  };
  const small = []; const smallInline = []; const clipped = []; const clippedCtl = []; const covered = []; const underLayer = [];
  for (const el of [...real, ...fake]) {
    const r = el.getBoundingClientRect();
    if (outside(r)) {
      if (croppedInside(el, r)) innerCropped += 1; else clipped.push(describe(el));
      clippedCtl.push(el);
      continue;
    }
    const hit = hitArea(el);
    // 가운데가 다른 요소에 덮인 것은 크기 문제가 아니다. 모달 · 시트가 열린 상태를 그린 보드라면 뒤가 덮이는 것이 맞다
    // (겹친 투명 상자가 입력을 먹는 사고일 수도 있다) — 무엇이 덮었는지와 함께 참고로 따로 센다.
    if (hit.covered) {
      const top = hit.top;
      const layer = top ? top.closest(LAYER) : null;
      const under = layer && !layer.contains(el) ? layerKind(layer) : null;
      const item = { ...describe(el), by: top ? pathOf(top) : null, byText: top ? describe(top).text : null, ...(under ? { layer: under } : {}) };
      // 지도 표식(.mk)은 층 아래여도 결함이다(K0 판정) — 고를 표식이 가려지면 고를 수 없다.
      if (under && !el.classList.contains('mk')) underLayer.push(item); else covered.push(item);
      continue;
    }
    let w = hit.w, h = hit.h;
    const label = el.tagName !== 'LABEL' && el.id ? root.querySelector(`label[for="${CSS.escape(el.id)}"]`) : null;
    if (label) { const lh = hitArea(label); w = Math.max(w, lh.w); h = Math.max(h, lh.h); }
    if (w >= minTarget && h >= minTarget) continue;
    const inline = el.tagName === 'A' && getComputedStyle(el).display === 'inline' && (el.parentElement?.innerText || '').trim().length > (el.innerText || '').trim().length + 1;
    (inline ? smallInline : small).push({ ...describe(el), hitW: w, hitH: h });
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
    pieces.push({ text: n.textContent, struck: struck(el), hj: !!el.closest('.hj'), field: !!el.closest('input,textarea,select,.inp,[role=textbox]'), where: describe(el).el });
  }
  for (const el of root.querySelectorAll('[alt],[title],[aria-label],[placeholder]')) {
    if (el.closest('[data-lint="skip"]')) continue;
    for (const a of ['alt', 'title', 'aria-label', 'placeholder']) {
      const v = el.getAttribute(a);
      if (v && v.trim()) pieces.push({ text: v, struck: false, hj: !!el.closest('.hj'), attr: true, where: `${el.tagName.toLowerCase()}[${a}]` });
    }
  }

  const words = {}; const wordSamples = [];
  for (const f of forbidden) {
    const re = new RegExp(f.re, 'gu');
    let count = 0;
    if (f.hanja) continue; // 한자는 아래 hanja 항목이 모두 센다(縣 · 郡 · 城 · 省 · 錢 · 穀 포함) — 두 번 세지 않는다
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

  // 한자(시스템 3.1.4 「표기」 · 「같은 읽기 지명」): 화면 글자는 한글이다. 한자는 같은 읽기 지명을 가를 때만 class="hj" 로 단다.
  // 그래서 hj · data-lint="skip" · 취소선 밖의 한자는 모두 센다. 범위는 Unicode Han 전체(확장 · 호환 글자 포함).
  // 2026-09-30 까지는 「쓰지 않는 말」 표의 縣 · 郡 · 城 · 省 · 錢 · 穀만 세서 陳留 · 許褚 · 荀彧 같은 병기를 놓쳤다.
  const HAN = /\p{Script=Han}+/gu;
  const hanja = [];
  for (const p of pieces) {
    if (p.struck || p.hj) continue;
    for (const m of p.text.matchAll(HAN)) {
      const i = m.index;
      hanja.push({ text: m[0], at: p.where, context: p.text.slice(Math.max(0, i - 10), i + m[0].length + 10).replace(/\s+/g, ' ').trim() });
    }
  }

  // 이모지: 기본이 그림 표시인 글자, 또는 VS16(U+FE0F)을 붙인 그림 글자, 키캡.
  const EMOJI = /\p{Emoji_Presentation}|\p{Extended_Pictographic}️|[#*0-9]️?⃣/gu;
  const emoji = [];
  for (const p of pieces) for (const m of p.text.matchAll(EMOJI)) emoji.push({ ch: m[0], at: p.where });

  // 그림 자리 표시: 보이는 글자에 그림 파일 이름이 남은 것(예: 「logo-wordmark.png」) — 로고 · 그림이 실제 그림으로 바뀐 뒤(3.1.2)엔 결함이다.
  const PLACEHOLDER = /[\w-]+\.(?:png|jpe?g|webp|gif|svg)\b/giu;
  const placeholder = [];
  // 입력칸 안의 파일 이름(올린 그림 이름 등)은 화면 내용이라 뺀다.
  for (const p of pieces) if (!p.attr && !p.field) for (const m of p.text.matchAll(PLACEHOLDER)) placeholder.push({ text: m[0], at: p.where });

  // 로고 한 번(시스템 3.1.4 「로고 한 번」): 한 화면에 워드마크(img alt="오픈삼국")는 하나. 둘째부터 결함으로 센다.
  const logos = [...root.querySelectorAll('img[alt="오픈삼국"]')].filter(rendered).map((el) => { const r = el.getBoundingClientRect(); return { w: Math.round(r.width), h: Math.round(r.height), top: Math.round(r.top - rr.top) }; });

  // title: 제품 web_ui_lint title_attr 와 같은 기준 — 보이는 글자와 같아도 title= 은 모두 센다(터치에선 안 보인다).
  // 표본의 onlyInTitle 은 그 정보가 title 에만 있는지(더 나쁜 경우)를 알린다.
  const titleOnly = [];
  for (const el of root.querySelectorAll('[title]')) {
    const t = (el.getAttribute('title') || '').trim();
    if (!t) continue;
    const seen = `${el.innerText || ''} ${el.getAttribute('aria-label') || ''} ${el.getAttribute('alt') || ''}`;
    titleOnly.push({ ...describe(el), title: t.slice(0, 60), onlyInTitle: !seen.includes(t) });
  }

  // 비활성(제품 web_ui_lint native_disabled · dimmed_disabled): 네이티브 disabled 는 탭을 삼켜 사유가 안 열린다 — aria-disabled 로.
  // 비활성은 흐리지 않고 점선 + 사유(V3System 「흐리게 하지 않는다」) — 계산된 opacity 가 1 보다 작으면 센다.
  const disabledAttr = [...root.querySelectorAll('[disabled]')].filter(rendered).map(describe);
  const dimmed = [];
  for (const el of root.querySelectorAll('[disabled],[aria-disabled="true"]')) {
    if (!rendered(el)) continue;
    let op = 1;
    for (let e = el; e && e !== root; e = e.parentElement) op *= Number(getComputedStyle(e).opacity);
    if (op < 0.999) dimmed.push({ ...describe(el), opacity: Math.round(op * 100) / 100 });
  }
  // 화면 폭(제품 web_ui_lint adhoc_breakpoint): @media 폭 조건은 세 단(768 · 1200, 767.98 · 1199.98)만. em · rem 은 늘 센다.
  const ALLOWED_WIDTHS = new Set(['768', '1200', '767.98', '1199.98']);
  const breakpoints = [];
  const WIDTH = /\((?:max|min)-width\s*:\s*([\d.]+)(px|em|rem)\s*\)|\(\s*width\s*[<>]=?\s*([\d.]+)(px|em|rem)\s*\)|\(\s*([\d.]+)(px|em|rem)\s*[<>]=?\s*width\b/g;
  const walkMedia = (rules) => {
    for (const rule of rules) {
      if (rule instanceof CSSMediaRule) {
        for (const m of rule.conditionText.matchAll(WIDTH)) {
          const v = m[1] ?? m[3] ?? m[5]; const u = m[2] ?? m[4] ?? m[6];
          if (u !== 'px' || !ALLOWED_WIDTHS.has(v)) breakpoints.push(rule.conditionText.slice(0, 80));
        }
      }
      if (rule.cssRules) walkMedia(rule.cssRules);
    }
  };
  for (const sheet of document.styleSheets) { try { walkMedia(sheet.cssRules); } catch { /* 바깥 글꼴 시트 */ } }

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
      disabledAttr: disabledAttr.length, dimmed: dimmed.length, breakpoint: breakpoints.length,
      emoji: emoji.length, words: Object.values(words).reduce((a, b) => a + b, 0), hanja: hanja.length, clipped: clipped.length, covered: covered.length, placeholder: placeholder.length, logo: Math.max(0, logos.length - 1),
    },
    smallInline: smallInline.length,
    underLayer: underLayer.length,
    // 종류별 합계는 표본(상한 25)이 아니라 전체에서 센다: 「요소.클래스 높이」 → 개수
    smallByKind: small.reduce((m, x) => { const k = `${x.el} 누를 영역 ${Math.min(x.hitW, x.hitH)}px`; m[k] = (m[k] ?? 0) + 1; return m; }, {}),
    innerCropped,
    lintSkipBlocks: root.querySelectorAll('[data-lint="skip"]').length,
    words,
    samples: {
      small: small.slice(0, 25), fake: fake.slice(0, 15).map(describe), title: titleOnly.slice(0, 15), hover: hover.slice(0, 10),
      disabledAttr: disabledAttr.slice(0, 10), dimmed: dimmed.slice(0, 10), breakpoint: breakpoints.slice(0, 10),
      emoji: emoji.slice(0, 15), words: wordSamples, hanja: hanja.slice(0, 20), clipped: clipped.slice(0, 15), covered: covered.slice(0, 20), underLayer: underLayer.slice(0, 10), placeholder: placeholder.slice(0, 10), logo: logos,
    },
  };
}

export async function lintBoards(files, { channel = 'chrome' } = {}) {
  let chromium;
  try { ({ chromium } = webRequire('@playwright/test')); } catch {
    throw new Error('@playwright/test 를 web/game 에서 찾지 못했다. 먼저: pnpm -C web install --frozen-lockfile --filter @opensamguk/web-game...');
  }
  let AxeBuilder;
  try { ({ AxeBuilder } = webRequire('@axe-core/playwright')); } catch {
    throw new Error('@axe-core/playwright 를 web/game 에서 찾지 못했다. 먼저: pnpm -C web install --frozen-lockfile --filter @opensamguk/web-game...');
  }
  const launch = () => chromium.launch({ channel, headless: true });
  let browser = await launch();
  const results = [];
  const lintOne = async (file, size) => {
    const context = await browser.newContext({ viewport: { width: Math.max(size.width, 320), height: Math.max(size.height, 320) } });
    try {
      const page = await context.newPage();
      await page.route('**/*', (route) => (route.request().url().startsWith('file:') ? route.continue() : route.abort()));
      await page.goto(pathToFileURL(file).href, { waitUntil: 'load' });
      // 누를 영역 · 덮임은 elementFromPoint 로 재서 뿌리가 검사 화면 안에 있어야 한다. 미리보기 크기가 없거나 뿌리보다 작으면
      // 화면을 뿌리 크기까지 넓힌다(아니면 화면 밖 누를 것이 「덮임」으로 잘못 잡힌다).
      const rootSize = await page.evaluate(() => {
        const r = (document.querySelector('x-dc > div') || document.body).getBoundingClientRect();
        return { w: Math.ceil(r.right), h: Math.ceil(r.bottom) };
      });
      const vp = page.viewportSize();
      if (rootSize.w > vp.width || rootSize.h > vp.height) {
        await page.setViewportSize({ width: Math.max(vp.width, rootSize.w), height: Math.max(vp.height, rootSize.h) });
      }
      const r = await page.evaluate(lintInPage, { forbidden: FORBIDDEN, minTarget: 44 });
      // 글자 대비: lintInPage 와 같은 화면에서 axe color-contrast 하나만. 설계 설명 글(data-lint="skip")은 뺀다.
      const axe = await new AxeBuilder({ page }).withRules(['color-contrast']).exclude('[data-lint="skip"]').analyze();
      const failed = axe.violations.flatMap((v) => v.nodes);
      r.counts.contrast = failed.length;
      r.contrastUnknown = axe.incomplete.reduce((a, v) => a + v.nodes.length, 0);
      // 잰 노드 = 통과 + 미달 + 판정 못 함. 환경(글꼴)에 따라 줄이 늘어 글자가 뿌리 밖으로 잘리면 이 수가 준다 — 잘린 글자는 axe 가 아예 세지 않는다(#1276).
      r.contrastPass = axe.passes.reduce((a, v) => a + v.nodes.length, 0);
      // 판정 못 한 까닭(axe 메시지 id — bgImage · bgOverlap · bgGradient · pseudoContent 등)을 표본으로 남긴다. 환경에 따라 수가 달라지면 이것으로 본다.
      r.samples.contrastUnknown = axe.incomplete.flatMap((v) => v.nodes).slice(0, 10).map((n) => ({ target: n.target.join(' '), text: n.html.replace(/<[^>]*>/g, '').trim().slice(0, 30), why: n.any?.[0]?.data?.messageKey ?? n.any?.[0]?.message?.slice(0, 80) ?? null }));
      r.samples.contrast = failed.slice(0, 15).map((n) => {
        const d = n.any?.[0]?.data ?? {};
        return { target: n.target.join(' '), text: n.html.replace(/<[^>]*>/g, '').trim().slice(0, 40), fg: d.fgColor, bg: d.bgColor, ratio: d.contrastRatio, need: d.expectedContrastRatio };
      });
      return r;
    } finally {
      await context.close().catch(() => {});
    }
  };
  try {
    for (const file of files) {
      const html = fs.readFileSync(file, 'utf8');
      const size = previewSize(html) ?? { width: 1440, height: 1000 };
      const base = { board: path.relative(ROOT, file).startsWith('..') ? file : path.relative(ROOT, file), name: path.basename(file), preview: size };
      // 헤드리스 Chrome 이 도중에 닫힐 수 있다(2026-09-30 에는 다른 세션의 넓은 패턴 pkill 이 죽였다). 한 번 다시 띄워 재고,
      // 그래도 안 되면 오류로 남기고 넘어간다(오류가 있으면 종료 코드 1).
      let r = null; let lastError = null;
      for (let attempt = 0; attempt < 2 && !r; attempt++) {
        try { r = await lintOne(file, size); } catch (e) {
          lastError = e;
          if (!/closed|crash|disconnected/i.test(String(e.message))) break;
          console.error(`브라우저가 닫혔다(${base.name}) — 다시 띄운다`);
          await browser.close().catch(() => {});
          browser = await launch();
        }
      }
      if (r) results.push({ ...base, ...r });
      else results.push({ ...base, error: String(lastError?.message ?? lastError).slice(0, 300), size: { w: 0, h: 0 }, targets: 0, counts: Object.fromEntries(KEYS.map((k) => [k, 0])), smallInline: 0, underLayer: 0, contrastUnknown: 0, contrastPass: 0, innerCropped: 0, words: {}, samples: {} });
    }
  } finally {
    await browser.close().catch(() => {});
  }
  return results;
}

// 대비를 잰 노드 = 통과 + 미달 + 판정 못 함. 잘린 글자는 어느 칸에도 없으므로, 이 수가 줄면 글자가 잘렸거나 검사가 덜 돈 것이다.
export const contrastMeasured = (r) => (r.contrastPass ?? 0) + (r.counts?.contrast ?? 0) + (r.contrastUnknown ?? 0);

// 기준선({ tolerance, boards: { 보드 이름: 잰 노드 } })보다 tolerance 를 넘게 줄었거나, 기준선에 없는 보드에서 0 이면 걸린다.
// 검사하지 못한 보드(error)는 따로 실패하므로 빼고, 기준선에만 있는 보드(지운 보드 · 일부만 검사한 경우)는 보지 않는다.
export function contrastFloorFailures(results, baseline) {
  const tolerance = baseline.tolerance ?? 0;
  const out = [];
  for (const r of results) {
    if (r.error) continue;
    const got = contrastMeasured(r);
    const base = baseline.boards?.[r.name];
    if (base === undefined ? got === 0 : got < base - tolerance) out.push({ name: r.name, got, base: base ?? null, tolerance });
  }
  return out;
}

// board-lint 결과로 기준선의 보드 값을 덮어쓴다(결과에 있는 보드만 — 한 보드만 고친 PR 도 쓸 수 있게). 나머지 칸은 그대로 둔다.
export function updateContrastBaseline(previous, results, source) {
  const boards = { ...(previous.boards ?? {}) };
  for (const r of results) if (!r.error) boards[r.name] = contrastMeasured(r);
  const sorted = Object.fromEntries(Object.keys(boards).sort().map((k) => [k, boards[k]]));
  return { ...previous, source, boards: sorted };
}

export function toMarkdown(results) {
  const total = Object.fromEntries(KEYS.map((k) => [k, results.reduce((a, r) => a + r.counts[k], 0)]));
  const allWords = {};
  for (const r of results) for (const [w, n] of Object.entries(r.words)) allWords[w] = (allWords[w] ?? 0) + n;
  const fmtWords = (ws) => Object.entries(ws).sort((a, b) => b[1] - a[1]).map(([w, n]) => `${w} ${n}`).join(' · ') || '—';
  const rows = results.map((r) => r.error ? `| ${r.name} | 검사 실패: ${r.error.replace(/\|/g, '/')} |` : `| ${r.name} | ${r.size.w}×${r.size.h} | ${r.targets} | ${r.counts.small}${r.smallInline ? ` (+문장 속 링크 ${r.smallInline})` : ''} | ${r.counts.fake} | ${r.counts.title} | ${r.counts.hover} | ${r.counts.disabledAttr} | ${r.counts.dimmed} | ${r.counts.breakpoint} | ${r.counts.emoji} | ${r.counts.words} | ${fmtWords(r.words)} | ${r.counts.hanja} | ${r.counts.clipped} | ${r.innerCropped} | ${r.counts.covered} | ${r.underLayer} | ${r.counts.placeholder} | ${r.counts.logo} | ${r.counts.contrast} | ${r.contrastUnknown ?? 0} | ${(r.contrastPass ?? 0) + r.counts.contrast + (r.contrastUnknown ?? 0)} |`);
  const kinds = {};
  for (const r of results) for (const [k, n] of Object.entries(r.smallByKind ?? {})) kinds[k] = (kinds[k] ?? 0) + n;
  const topKinds = Object.entries(kinds).sort((a, b) => b[1] - a[1]).slice(0, 12).map(([k, n]) => `\`${k}\` ${n}`).join(' · ') || '—';
  return [
    '# 설계 보드 일관성 검사 — tools/web/board-lint.mjs', '',
    `- 보드 ${results.length}장. 기준: V3System(44px · 호버/title 전용 금지 · 쓰지 않는 말), 09-18 BRIEF(진짜 button · 이모지 금지 · 고정 크기에서 잘림).`,
    '- 「N년 N월(순 없음)」은 V3System 「년 월(표기) → 200년 3월 중순」의 해석이다.', '',
    '| 보드 | 크기 | 누를 것 | 누를 영역 44 미만 | 가짜 누를 것 | title 속성 | hover 드러냄 | 네이티브 disabled | 흐린 비활성 | 세 단 밖 폭 | 이모지 | 금지어 | 금지어 내역 | 한자(hj 밖) | 뿌리 밖 잘림 | 안쪽 자름(참고) | 덮인 누를 것 | 열린 층 아래(정상) | 그림 자리 표시 | 로고 중복 | 대비 미달 | 대비 판정 못 함(참고) | 대비 잰 노드 |',
    '|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|',
    ...rows,
    `| **합계** | | ${results.reduce((a, r) => a + r.targets, 0)} | ${total.small} | ${total.fake} | ${total.title} | ${total.hover} | ${total.disabledAttr} | ${total.dimmed} | ${total.breakpoint} | ${total.emoji} | ${total.words} | ${fmtWords(allWords)} | ${total.hanja} | ${total.clipped} | ${results.reduce((a, r) => a + r.innerCropped, 0)} | ${total.covered} | ${results.reduce((a, r) => a + r.underLayer, 0)} | ${total.placeholder} | ${total.logo} | ${total.contrast} | ${results.reduce((a, r) => a + (r.contrastUnknown ?? 0), 0)} | ${results.reduce((a, r) => a + (r.contrastPass ?? 0) + r.counts.contrast + (r.contrastUnknown ?? 0), 0)} |`, '', `누를 영역 44 미만 종류: ${topKinds}`, '',
  ].join('\n');
}

function updateFloorFile(opts) {
  const previous = fs.existsSync(opts.updateFloor) ? JSON.parse(fs.readFileSync(opts.updateFloor, 'utf8')) : {};
  const { at, results } = JSON.parse(fs.readFileSync(opts.from, 'utf8'));
  const next = updateContrastBaseline(previous, results, opts.source ?? `board-lint ${at}`);
  fs.writeFileSync(opts.updateFloor, `${JSON.stringify(next, null, 2)}\n`);
  const changed = results.filter((r) => !r.error && previous.boards?.[r.name] !== next.boards[r.name]);
  console.log(`기준선 ${opts.updateFloor}: 보드 ${results.length}장을 읽어 ${changed.length}장을 바꿨다${changed.length ? ` — ${changed.slice(0, 10).map((r) => `${r.name} ${previous.boards?.[r.name] ?? '없음'} → ${next.boards[r.name]}`).join(', ')}` : ''}`);
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.updateFloor) { updateFloorFile(opts); return; }
  const files = boardFiles(opts.paths);
  if (files.length === 0) throw new Error('검사할 .dc.html 이 없다');
  const results = await lintBoards(files, opts);
  const md = toMarkdown(results);
  if (opts.json) fs.writeFileSync(opts.json, JSON.stringify({ at: new Date().toISOString(), forbidden: FORBIDDEN, results }, null, 2));
  if (opts.md) fs.writeFileSync(opts.md, md);
  console.log(md);
  const errored = results.filter((r) => r.error);
  if (errored.length) console.error(`검사하지 못한 보드 ${errored.length}장: ${errored.map((r) => r.name).join(', ')}`);
  const failing = results.filter((r) => r.error || opts.failOn.some((k) => r.counts[k] > 0));
  const floor = opts.contrastFloor ? contrastFloorFailures(results, JSON.parse(fs.readFileSync(opts.contrastFloor, 'utf8'))) : [];
  if (opts.contrastFloor) {
    // 하한이 실제로 돌았다는 표시(걸린 것이 없어도 찍는다) — CI 로그에서 배선을 확인한다.
    console.error(`--contrast-floor ${path.relative(ROOT, path.resolve(opts.contrastFloor))}: 보드 ${results.length}장 · 잰 노드 ${results.reduce((a, r) => a + contrastMeasured(r), 0)} · 기준선 아래 ${floor.length}장`);
    for (const f of floor) console.error(`  [contrastFloor] ${f.name}: ${f.base === null ? '기준선에 없는 보드인데 잰 노드 0' : `잰 노드 ${f.got} < 기준선 ${f.base} − 허용 ${f.tolerance}`}`);
    if (floor.length) console.error('  글자가 잘렸거나 검사가 덜 돌았다(axe 는 잘린 글자를 세지 않는다). 보드를 바꿔 글자가 줄었으면 기준선을 같이 고친다: --update-contrast-floor <기준선> --from <board-lint.json>');
  }
  if (failing.length) {
    console.error(`--fail-on ${opts.failOn.join(',')}: ${failing.length}장이 걸렸다 — ${failing.map((r) => r.name).join(', ')}`);
    // CI 로그만 보고 고칠 수 있게 걸린 칸의 표본을 찍는다(보드 · 대상 · 글자 · 색 · 대비 등, 칸마다 5개까지).
    for (const r of failing) for (const k of opts.failOn) {
      if (!(r.counts?.[k] > 0)) continue;
      for (const x of (r.samples?.[k] ?? []).slice(0, 5)) console.error(`  [${k}] ${r.name}: ${JSON.stringify(x)}`);
    }
  }
  if (failing.length || floor.length) process.exit(1);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((e) => { console.error(e.message ?? e); process.exit(2); });
}
