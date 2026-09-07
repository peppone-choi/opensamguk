// Assembles src/<Name>.body.html (+ optional src/<Name>.css) into <Name>.dc.html
// Tokens: @@ISO(width,height,variant)@@  @@PT(w,h,seed[,nationHex])@@  @@FLAG(hex,size)@@
import { readFileSync, writeFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const root = new URL('.', import.meta.url).pathname;
const shared = readFileSync(join(root, 'src/_shared.css'), 'utf8');

// ---------- portrait placeholder (silhouette; real assets are RTK14-derived and never embedded) ----------
const PT_PALETTES = [
  ['#3a2f22', '#6b4f2a', '#d3b064'],
  ['#22303a', '#3f5963', '#7aa7c7'],
  ['#2f2225', '#6b3a3a', '#c96b5d'],
  ['#263022', '#4f6b3a', '#8fa77a'],
  ['#2a2a2a', '#4a4a4a', '#b9b2a3'],
];
// 저장소 사본은 실제 초상을 넣지 않는다. 이름 키(hahoudon 등)는 결정적 실루엣 seed 로만 쓴다.
// 캔버스 발행본은 RTK14 파생 초상 3종을 CDN 경로에서 읽었다(ADR-LITE-048).
function portrait(w, h, seedOrKey = 0, nation) {
  const seed = typeof seedOrKey === 'number' ? seedOrKey : [...String(seedOrKey)].reduce((a, c) => a + c.charCodeAt(0), 0);
  const p = PT_PALETTES[seed % PT_PALETTES.length];
  const sq = Math.abs(w - h) < 2;
  const hat = seed % 3; // 0 none, 1 cap, 2 helmet
  const id = `g${w}x${h}s${seed}`;
  const headR = sq ? h * 0.22 : w * 0.2;
  const cx = w / 2, cy = sq ? h * 0.42 : h * 0.34;
  const body = sq
    ? `M${w * 0.1} ${h} C${w * 0.12} ${h * 0.72} ${w * 0.35} ${h * 0.66} ${cx} ${h * 0.66} C${w * 0.65} ${h * 0.66} ${w * 0.88} ${h * 0.72} ${w * 0.9} ${h} Z`
    : `M${w * 0.02} ${h} C${w * 0.05} ${h * 0.6} ${w * 0.3} ${h * 0.55} ${cx} ${h * 0.55} C${w * 0.7} ${h * 0.55} ${w * 0.95} ${h * 0.6} ${w * 0.98} ${h} Z`;
  const hatShape = hat === 1
    ? `<path d="M${cx - headR * 1.05} ${cy - headR * 0.35} Q${cx} ${cy - headR * 1.55} ${cx + headR * 1.05} ${cy - headR * 0.35} Z" fill="${p[1]}"/>`
    : hat === 2
      ? `<path d="M${cx - headR * 1.15} ${cy - headR * 0.2} Q${cx} ${cy - headR * 1.7} ${cx + headR * 1.15} ${cy - headR * 0.2} L${cx + headR * 1.15} ${cy + headR * 0.15} L${cx - headR * 1.15} ${cy + headR * 0.15} Z" fill="${p[1]}"/><rect x="${cx - 2}" y="${cy - headR * 1.9}" width="4" height="${headR * 0.5}" fill="${p[2]}"/>`
      : '';
  const ring = nation ? `<rect x="1" y="1" width="${w - 2}" height="${h - 2}" fill="none" stroke="${nation}" stroke-width="2"/>` : '';
  return `<svg viewBox="0 0 ${w} ${h}" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="초상 자리표시자"><defs><linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${p[0]}"/><stop offset="1" stop-color="#0c0f0e"/></linearGradient></defs><rect width="${w}" height="${h}" fill="url(#${id})"/><path d="${body}" fill="${p[1]}"/><circle cx="${cx}" cy="${cy}" r="${headR}" fill="${p[1]}" stroke="${p[0]}" stroke-width="1"/>${hatShape}<rect x="${w * 0.2}" y="${h * 0.62}" width="${w * 0.6}" height="${sq ? 0 : 3}" fill="${p[2]}" opacity=".6"/>${ring}</svg>`;
}

function flag(hex, s = 14) {
  return `<svg width="${s}" height="${s}" viewBox="0 0 14 14" xmlns="http://www.w3.org/2000/svg" aria-hidden="true"><rect x="2" y="1" width="1.5" height="12" fill="#8a8477"/><path d="M3.5 1.5 L12 3.2 L9.5 5.2 L12 7.4 L3.5 8.6 Z" fill="${hex}"/></svg>`;
}

// ---------- isometric province map ----------
const NATIONS = {
  wei: { name: '위', color: '#3f6fb5' }, shu: { name: '촉', color: '#4f9a5a' }, wu: { name: '오', color: '#c9573f' },
  yuan: { name: '원소', color: '#b58a3f' }, dong: { name: '동탁', color: '#7a4fa8' }, none: { name: '공백', color: null },
};
function isoMap(W, H, variant = 'full') {
  const cols = 22, rows = 16, tw = W / (cols * 0.5 + rows * 0.5) * 0.98, th = tw / 2;
  const ox = W / 2, oy = 24;
  const seedRand = (a, b) => { const x = Math.sin(a * 12.9898 + b * 78.233) * 43758.5453; return x - Math.floor(x); };
  const own = (c, r) => {
    const d = (cx, cy, rad) => Math.hypot(c - cx, (r - cy) * 1.3) < rad;
    if (d(6, 4, 4.2)) return 'yuan';
    if (d(11, 6, 3.6)) return 'dong';
    if (d(12, 11, 4.4)) return 'shu';
    if (d(16, 9, 3.2)) return 'wu';
    if (d(6, 10, 3.0)) return 'wei';
    return 'none';
  };
  const isWater = (c, r) => (r === 13 && c > 10) || (c === 18 && r > 6) || (r === 2 && c < 3) || seedRand(c, r) > 0.94;
  let tiles = '';
  for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) {
    const x = ox + (c - r) * tw / 2, y = oy + (c + r) * th / 2;
    const water = isWater(c, r);
    const o = own(c, r);
    const base = water ? '#14232a' : `hsl(${86 + seedRand(c, r) * 14} ${14 + seedRand(r, c) * 8}% ${18 + seedRand(c + 1, r) * 7}%)`;
    const tint = NATIONS[o].color;
    tiles += `<path d="M${x} ${y} L${x + tw / 2} ${y + th / 2} L${x} ${y + th} L${x - tw / 2} ${y + th / 2} Z" fill="${base}" stroke="#0c0f0e" stroke-width=".6"/>`;
    if (tint && !water) tiles += `<path d="M${x} ${y} L${x + tw / 2} ${y + th / 2} L${x} ${y + th} L${x - tw / 2} ${y + th / 2} Z" fill="${tint}" opacity=".32"/>`;
    if (!water && seedRand(c * 3, r * 7) > 0.8) tiles += `<path d="M${x - tw * 0.12} ${y + th * 0.62} L${x} ${y + th * 0.3} L${x + tw * 0.12} ${y + th * 0.62} Z" fill="#0c0f0e" opacity=".35"/>`;
  }
  const P = (c, r) => [ox + (c - r) * tw / 2, oy + (c + r) * th / 2 + th / 2];
  const cities = [
    { c: 6, r: 4, n: '업', lv: 'capital', o: 'yuan' }, { c: 3, r: 5, n: '진양', lv: 'city', o: 'yuan' },
    { c: 11, r: 6, n: '낙양', lv: 'capital', o: 'dong' }, { c: 9, r: 8, n: '장안', lv: 'city', o: 'dong' },
    { c: 12, r: 11, n: '성도', lv: 'capital', o: 'shu' }, { c: 14, r: 12, n: '한중', lv: 'town', o: 'shu' },
    { c: 16, r: 9, n: '건업', lv: 'capital', o: 'wu' }, { c: 17, r: 7, n: '오', lv: 'town', o: 'wu' },
    { c: 6, r: 10, n: '허창', lv: 'capital', o: 'wei' }, { c: 8, r: 11, n: '완', lv: 'town', o: 'wei' },
    { c: 14, r: 4, n: '북해', lv: 'city', o: 'none' }, { c: 3, r: 9, n: '양양', lv: 'city', o: 'none' },
  ];
  const roads = [[6, 4, 11, 6], [11, 6, 9, 8], [9, 8, 12, 11], [11, 6, 6, 10], [6, 10, 8, 11], [8, 11, 16, 9], [16, 9, 17, 7], [6, 4, 14, 4], [12, 11, 14, 12], [6, 10, 3, 9]];
  let roadSvg = '';
  for (const [a, b, c, d] of roads) { const [x1, y1] = P(a, b), [x2, y2] = P(c, d); roadSvg += `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="#d3b064" stroke-opacity=".28" stroke-width="1.2" stroke-dasharray="3 3"/>`; }
  const river = [[0, 6], [3, 7], [6, 8], [9, 9], [12, 9], [15, 10], [18, 11], [21, 11]].map(([c, r]) => P(c, r));
  const riverSvg = `<polyline points="${river.map(p => p.join(',')).join(' ')}" fill="none" stroke="#3e7f95" stroke-width="3" stroke-opacity=".7" stroke-linejoin="round"/>`;
  let citySvg = '';
  for (const ct of cities) {
    const [x, y] = P(ct.c, ct.r); const col = NATIONS[ct.o].color || '#8a8477';
    const s = ct.lv === 'capital' ? 1.25 : ct.lv === 'city' ? 1 : 0.8;
    citySvg += `<g transform="translate(${x} ${y})">`
      + `<ellipse cx="0" cy="2" rx="${13 * s}" ry="${6 * s}" fill="#0c0f0e" opacity=".45"/>`
      + `<path d="M${-9 * s} 0 L${-9 * s} ${-9 * s} L${-5 * s} ${-9 * s} L${-5 * s} ${-12 * s} L${-2 * s} ${-12 * s} L${-2 * s} ${-9 * s} L${2 * s} ${-9 * s} L${2 * s} ${-12 * s} L${5 * s} ${-12 * s} L${5 * s} ${-9 * s} L${9 * s} ${-9 * s} L${9 * s} 0 Z" fill="#c9bfa8" stroke="#0c0f0e" stroke-width=".8"/>`
      + `<path d="M${-9 * s} 0 L${9 * s} 0 L${11 * s} ${3 * s} L${-11 * s} ${3 * s} Z" fill="#6b6152"/>`
      + (ct.lv === 'capital' ? `<path d="M0 ${-19 * s} l1.6 3.4 3.7.4-2.8 2.5.8 3.7L0 ${-11.2 * s} l-3.3 1.9.8-3.7-2.8-2.5 3.7-.4z" fill="#ffd36d"/>` : '')
      + `<g transform="translate(${10 * s} ${-16 * s})"><rect x="0" y="0" width="1.4" height="14" fill="#8a8477"/><path d="M1.4 .5 L10 2.2 L7.6 4.4 L10 6.8 L1.4 8.2 Z" fill="${col}"/></g>`
      + `<text x="0" y="${14 * s + 4}" text-anchor="middle" font-family="'Noto Sans KR',sans-serif" font-size="${variant === 'small' ? 9 : 11}" font-weight="700" fill="#ece6d8" stroke="#0c0f0e" stroke-width="2.5" paint-order="stroke">${ct.n}</text></g>`;
  }
  // moving army marker + selected province ring
  const [ax, ay] = P(8, 9), [sx, sy] = P(6, 10);
  const armies = variant === 'bare' ? '' : `<g transform="translate(${ax} ${ay - 6})"><path d="M-7 0 L0 -12 L7 0 Z" fill="#d3b064" stroke="#0c0f0e" stroke-width="1"/><path d="M-3 0 L3 0 L3 4 L-3 4 Z" fill="#9c7f3f"/><text x="12" y="-2" font-family="'JetBrains Mono',monospace" font-size="10" fill="#ffd36d" stroke="#0c0f0e" stroke-width="2" paint-order="stroke">2순 후 도착</text></g>`
    + `<path d="M${sx} ${sy - 4} L${sx + tw / 2} ${sy + th / 2 - 4} L${sx} ${sy + th - 4} L${sx - tw / 2} ${sy + th / 2 - 4} Z" fill="none" stroke="#ffd36d" stroke-width="2"><animate attributeName="opacity" values="1;.35;1" dur="1.6s" repeatCount="indefinite"/></path>`;
  return `<svg viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" xmlns="http://www.w3.org/2000/svg" style="display:block"><rect width="${W}" height="${H}" fill="#0c0f0e"/><g>${tiles}</g>${riverSvg}${roadSvg}${citySvg}${armies}</svg>`;
}

function expand(html) {
  return html
    .replace(/@@ISO\((\d+),(\d+)(?:,(\w+))?\)@@/g, (_, w, h, v) => isoMap(+w, +h, v || 'full'))
    .replace(/@@PT\((\d+),(\d+),(\w+)(?:,(#[0-9a-fA-F]{6}))?\)@@/g, (_, w, h, s, n) => portrait(+w, +h, /^\d+$/.test(s) ? +s : s, n))
    .replace(/@@FLAG\((#[0-9a-fA-F]{6})(?:,(\d+))?\)@@/g, (_, hex, s) => flag(hex, s ? +s : 14));
}

const names = readdirSync(join(root, 'src')).filter(f => f.endsWith('.body.html')).map(f => f.replace('.body.html', ''));
for (const name of names) {
  const body = readFileSync(join(root, `src/${name}.body.html`), 'utf8');
  const cssPath = join(root, `src/${name}.css`);
  const extra = existsSync(cssPath) ? readFileSync(cssPath, 'utf8') : '';
  const out = `<!doctype html>\n<html>\n<head>\n  <meta charset="utf-8">\n  <script src="./support.js"></script>\n</head>\n<body>\n<x-dc>\n<helmet>\n  <style>\n${shared}\n${extra}\n  </style>\n</helmet>\n${expand(body)}\n</x-dc>\n</body>\n</html>\n`;
  if (out.includes('{{')) console.warn(`warning: ${name} contains {{ - holes will be treated as bindings`);
  writeFileSync(join(root, `${name}.dc.html`), out);
  console.log(`built ${name}.dc.html (${(out.length / 1024).toFixed(0)} KB)`);
}
