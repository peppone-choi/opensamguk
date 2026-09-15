// 깃대는 城 그림의 지붕에 꽂는다. 지붕 높이 표가 스프라이트와 어긋나면 깃발이 다시 뜨거나
// 성벽 속에 묻힌다 — 그래서 표를 믿지 않고 PNG 를 직접 풀어 대조한다.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { inflateSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';
import {
  SPRITE_GROUND_CENTER_Y,
  SPRITE_ROOF_TOP_PX,
  cityFlagBase,
  spriteRoofLift,
} from '../index';

/** 8비트 RGBA 비인터레이스 PNG 만 푼다 — iso2d 오브젝트 스프라이트가 그 형식이다. */
function decodeRgba(path: string): { width: number; height: number; pixels: Uint8Array } {
  const file = readFileSync(path);
  let offset = 8;
  let width = 0;
  let height = 0;
  const idat: Buffer[] = [];
  while (offset < file.length) {
    const length = file.readUInt32BE(offset);
    const type = file.toString('ascii', offset + 4, offset + 8);
    const body = file.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') {
      width = body.readUInt32BE(0);
      height = body.readUInt32BE(4);
      expect([body[8], body[9], body[12]]).toEqual([8, 6, 0]);
    } else if (type === 'IDAT') idat.push(body);
    offset += 12 + length;
  }
  const raw = inflateSync(Buffer.concat(idat));
  const bpp = 4;
  const stride = width * bpp;
  const pixels = new Uint8Array(width * height * bpp);
  for (let y = 0; y < height; y += 1) {
    const filter = raw[y * (stride + 1)];
    for (let x = 0; x < stride; x += 1) {
      const v = raw[y * (stride + 1) + 1 + x];
      const a = x >= bpp ? pixels[y * stride + x - bpp] : 0;
      const b = y > 0 ? pixels[(y - 1) * stride + x] : 0;
      const c = x >= bpp && y > 0 ? pixels[(y - 1) * stride + x - bpp] : 0;
      let out: number;
      if (filter === 0) out = v;
      else if (filter === 1) out = v + a;
      else if (filter === 2) out = v + b;
      else if (filter === 3) out = v + ((a + b) >> 1);
      else {
        const p = a + b - c;
        const pa = Math.abs(p - a);
        const pb = Math.abs(p - b);
        const pc = Math.abs(p - c);
        out = v + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
      }
      pixels[y * stride + x] = out & 0xff;
    }
  }
  return { width, height, pixels };
}

const OBJECTS = resolve(__dirname, '../../../gateway/public/sprites/iso2d/objects');

describe('SPRITE_ROOF_TOP_PX', () => {
  for (const [file, top] of Object.entries(SPRITE_ROOF_TOP_PX)) {
    it(`${file}: 가운데 열(x 120..136)의 알파 윗변과 같다`, () => {
      const { width, height, pixels } = decodeRgba(resolve(OBJECTS, `${file}.png`));
      let measured = -1;
      for (let y = 0; y < height && measured < 0; y += 1) {
        for (let x = 120; x <= 136; x += 1) {
          if (pixels[(y * width + x) * 4 + 3] > 40) {
            measured = y;
            break;
          }
        }
      }
      expect(measured).toBe(top);
    });
  }
});

describe('cityFlagBase', () => {
  it('작은 縣 은 깃발이 지붕에 붙는다 — 예전처럼 세계 96px 위에 뜨지 않는다', () => {
    // 장현(county-small), 배율 2(한 칸까지 당긴 상태), drawScale 1.
    const roof = spriteRoofLift('county-small', 0.85, 2)!;
    expect(roof).toBeCloseTo((SPRITE_GROUND_CENTER_Y - 132) * 0.85 * 2, 6); // 74.8
    const base = cityFlagBase(500, roof, 2);
    const old = 500 - Math.max(11, 96 * 2);
    // 예전 밑동은 지붕보다 117px 위였다. 이제는 지붕선 안쪽 4px.
    expect(500 - roof - old).toBeGreaterThan(100);
    expect(base).toBeCloseTo(500 - roof + 4, 6);
  });

  it('도성은 지붕이 높아서 깃발도 높이 선다 — 성벽 속에 묻히지 않는다', () => {
    const roof = spriteRoofLift('capital', 0.85, 1)!;
    expect(cityFlagBase(500, roof, 1)).toBeLessThan(500 - 96);
  });

  it('전체 보기에서도 밑동이 칸 중심에 붙어 버리지는 않는다', () => {
    const roof = spriteRoofLift('county-small', 0.85, 0.02)!;
    expect(cityFlagBase(500, roof, 0.02)).toBe(497);
  });

  it('그림이 없는 城 은 예전 규칙으로 돌아간다', () => {
    expect(spriteRoofLift('없는-그림', 0.85, 1)).toBeNull();
    expect(cityFlagBase(500, null, 1)).toBe(500 - 96);
  });
});
