import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ARCHITECTURE_BY_JU, architectureForJu } from '../iso/regionalArchitecture';
import { cityFitSprite } from '../HanMapCanvas';

const world = JSON.parse(readFileSync(resolve(__dirname, '../../../../infra/src/main/resources/map/han-world-v3.json'), 'utf8')) as {
  cities: { meta?: { ju?: string } }[];
};

describe('13州 → 10개 건축 양식', () => {
  it('원장의 모든 州를 빠짐없이 분류하고 정확히 열 양식을 사용한다', () => {
    const fromWorld = new Set(world.cities.map((city) => city.meta?.ju).filter((name): name is string => Boolean(name) && name !== '동이'));
    expect([...fromWorld].sort()).toEqual(Object.keys(ARCHITECTURE_BY_JU).sort());
    expect(new Set(Object.values(ARCHITECTURE_BY_JU)).size).toBe(10);
    expect(architectureForJu('동이')).toBe('neutral');
    expect(architectureForJu('미상')).toBe('neutral');
  });
});

describe('작전실 城 원본 선택', () => {
  it('16/24/32/48px 와 DPR 1/1.5/2/3 에서 필요한 해상도를 고른다', () => {
    const images = Object.fromEntries([32, 64, 128, 256].map((size) =>
      [`R${size}:jiangdong:7`, `jiangdong-${size}`]));
    for (const cssWidth of [16, 24, 32, 48]) {
      for (const dpr of [1, 1.5, 2, 3]) {
        const pixels = cssWidth * dpr;
        const size = [32, 64, 128, 256].find((candidate) => candidate >= pixels) ?? 256;
        expect(cityFitSprite(images, 7, pixels, 'jiangdong')).toBe(`jiangdong-${size}`);
      }
    }
  });

  it('없는 州 원본과 수·관·진·이 1–4레벨은 기존 작전실 그림을 쓴다', () => {
    const images = { '1x:1': 'legacy-pass', '1x:7': 'legacy-city', 'R32:jiangdong:7': 'regional-city' };
    expect(cityFitSprite(images, 1, 20, 'jiangdong')).toBe('legacy-pass');
    expect(cityFitSprite(images, 7, 20, 'neutral')).toBe('legacy-city');
  });
});
