// 깃발 동적 착색 — 자작 12x12 픽셀아트(`tools/assets/build_flag_assets.py`가 생성)를
// 천 grayscale(질감)+깃대 2레이어로 나눠 둔 에셋
// (`/flags/flag-cloth.png` = 천 명도, `/flags/flag-pole.png` = 깃대)을 nation 색으로 캔바스 틴트한다.
//
// 합성식(앵커 color-blend, 강도 1.0, 그림자 부드럽게): 천 픽셀의 명도(주름·질감)는 보존하되 색상·채도는
// nation 색으로 교체하고, 명도를 nation 색의 명도에 앵커시킨다(어두운 나라=어둡게). 그림자는 clip 대신
// 비례 압축으로 검정 뭉개짐을 막는다. 깃대는 정적(원본 탄색). 색마다 1회 계산 후 캐시.

const STRENGTH = 1.0;

/** RGB(0..255) → HSL(0..1). */
function rgbToHsl(r: number, g: number, b: number): [number, number, number] {
    r /= 255; g /= 255; b /= 255;
    const max = Math.max(r, g, b), min = Math.min(r, g, b);
    const l = (max + min) / 2;
    if (max === min) return [0, 0, l];
    const d = max - min;
    const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    let h: number;
    if (max === r) h = (g - b) / d + (g < b ? 6 : 0);
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    return [h / 6, s, l];
}

/** HSL(0..1) → RGB(0..255). */
function hslToRgb(h: number, s: number, l: number): [number, number, number] {
    if (s === 0) { const v = Math.round(l * 255); return [v, v, v]; }
    const hue = (p: number, q: number, t: number) => {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1 / 6) return p + (q - p) * 6 * t;
        if (t < 1 / 2) return q;
        if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
        return p;
    };
    const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
    const p = 2 * l - q;
    return [
        Math.round(hue(p, q, h + 1 / 3) * 255),
        Math.round(hue(p, q, h) * 255),
        Math.round(hue(p, q, h - 1 / 3) * 255),
    ];
}

function hexToRgb(hex: string): [number, number, number] {
    const m = hex.replace('#', '');
    const n = parseInt(m.length === 3 ? m.split('').map((c) => c + c).join('') : m, 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

/** 나부끼는 4프레임. 빌더가 사인파 위상 4단계를 천/깃대로 나눠 그린다. */
export const FLAG_FRAMES = 4;

let assetsPromise: Promise<{ cloth: HTMLImageElement[]; pole: HTMLImageElement[] }> | null = null;
function loadAssets() {
    if (!assetsPromise) {
        const load = (src: string) =>
            new Promise<HTMLImageElement>((res, rej) => {
                const img = new Image();
                img.onload = () => res(img);
                img.onerror = rej;
                img.src = src;
            });
        const cloth = Array.from({ length: FLAG_FRAMES }, (_, i) => load(`/flags/flag-cloth-${i}.png`));
        const pole = Array.from({ length: FLAG_FRAMES }, (_, i) => load(`/flags/flag-pole-${i}.png`));
        assetsPromise = Promise.all([Promise.all(cloth), Promise.all(pole)])
            .then(([cloth, pole]) => ({ cloth, pole }));
    }
    return assetsPromise;
}

const cache = new Map<string, string[]>();

/** 한 프레임의 천을 nation 색으로 앵커 틴트 + 깃대 합성 → dataURL. */
function tintFrame(cloth: HTMLImageElement, pole: HTMLImageElement, nh: number, ns: number, nl: number): string {
    const w = cloth.width, h = cloth.height;
    const canvas = document.createElement('canvas');
    canvas.width = w; canvas.height = h;
    const ctx = canvas.getContext('2d')!;
    ctx.drawImage(cloth, 0, 0);
    const id = ctx.getImageData(0, 0, w, h);
    const d = id.data;
    for (let i = 0; i < d.length; i += 4) {
        if (d[i + 3] === 0) continue;
        const L = d[i] / 255;            // 천 명도(질감)
        const dev = (L - 0.5) * 2;       // ±1 정규화 편차
        const soft = dev >= 0 ? dev * (1 - nl) : dev * nl; // 비례 압축(clip 없음)
        const rL = Math.max(0, Math.min(1, nl + soft * STRENGTH));
        const [tr, tg, tb] = hslToRgb(nh, ns, rL);
        d[i] = tr; d[i + 1] = tg; d[i + 2] = tb;
    }
    ctx.putImageData(id, 0, 0);
    ctx.drawImage(pole, 0, 0); // 깃대(정적) 위에 합성
    return canvas.toDataURL('image/png');
}

/** nation 색 hex → 틴트된 4프레임 dataURL 배열. 색마다 1회 계산 후 캐시. 브라우저 전용. */
export async function tintFlag(colorHex: string): Promise<string[]> {
    const cached = cache.get(colorHex);
    if (cached) return cached;
    const { cloth, pole } = await loadAssets();
    const [nr, ng, nb] = hexToRgb(colorHex);
    const [nh, ns, nl] = rgbToHsl(nr, ng, nb);
    const frames = cloth.map((c, i) => tintFrame(c, pole[i], nh, ns, nl));
    cache.set(colorHex, frames);
    return frames;
}
