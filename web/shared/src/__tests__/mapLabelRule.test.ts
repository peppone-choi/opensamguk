import { describe, expect, it } from 'vitest';
import { cityFitSprite, cityFitSpriteKeys, cityMapLabel } from '../WorldMapCanvas';

const county = {
  mapLabel: '신풍', level: 11, layers: [] as string[], isCommanderySeat: false, isCapital: false,
  commanderyName: '경조윤', provinceKind: undefined as string | undefined,
  administrativeKind: undefined as 'COUNTY' | 'EXTERNAL_SETTLEMENT' | 'COMMANDERY' | undefined,
};

describe('확대 수준별 城 이름표', () => {
  it('郡 수준에서는 郡治를 郡 이름으로, 수도를 제 이름으로만 단다', () => {
    expect(cityMapLabel(county, 'COMMANDERY', true)).toBeNull();
    expect(cityMapLabel({ ...county, mapLabel: '장안(京兆尹)', isCommanderySeat: true }, 'COMMANDERY', true))
      .toMatchObject({ text: '경조윤' });
    expect(cityMapLabel({ ...county, mapLabel: '낙양', isCapital: true }, 'COMMANDERY', false))
      .toMatchObject({ text: '낙양' });
    // 省 지도가 있으면 郡 층 표지가 이미 郡 이름이다.
    expect(cityMapLabel({ ...county, mapLabel: '하남윤', administrativeKind: 'COMMANDERY' }, 'COMMANDERY', false))
      .toMatchObject({ text: '하남윤' });
    // 표에 없는 종류(요충)도 郡 수준에서는 달지 않는다 — 예전에는 늘 그렸다.
    expect(cityMapLabel({ ...county, provinceKind: 'STRATEGIC_SITE' }, 'COMMANDERY', true)).toBeNull();
  });

  it('縣 수준에서는 문턱을 넘어야 縣 이름을 달고, 郡治 · 수도는 늘 단다', () => {
    expect(cityMapLabel(county, 'COUNTY', false)).toBeNull();
    expect(cityMapLabel(county, 'COUNTY', true)).toMatchObject({ text: '신풍' });
    expect(cityMapLabel({ ...county, mapLabel: '장안(京兆尹)', isCommanderySeat: true }, 'COUNTY', false))
      .toMatchObject({ text: '장안(京兆尹)' });
  });

  it('겹칠 때 남길 차례는 수도 > 내 위치 · 고른 城 > 郡治 > 큰 城', () => {
    const priority = (city: Partial<typeof county>) => cityMapLabel({ ...county, ...city }, 'COUNTY', true)!.priority;
    expect(priority({ isCapital: true })).toBeGreaterThan(priority({ layers: ['current'] }));
    expect(priority({ layers: ['selected'] })).toBeGreaterThan(priority({ isCommanderySeat: true }));
    expect(priority({ isCommanderySeat: true })).toBeGreaterThan(priority({ level: 9 }));
    expect(priority({ level: 9 })).toBeGreaterThan(priority({ level: 11 }));
  });
});

describe('城 그림은 그릴 폭에 맞는 한 장만 청한다', () => {
  it('다 받았을 때 고를 그림과 같은 열쇠를 청한다', () => {
    const images: Record<string, string> = {};
    for (const level of [5, 9, 11]) {
      for (const key of [`1x:${level}`, `2x:${level}`, `L128:${level}`, `L256:${level}`]) images[key] = key;
      for (const size of [32, 64, 128, 256]) images[`R${size}:jingzhou:${level}`] = `R${size}:jingzhou:${level}`;
    }
    const cases: Array<[number, number, 'neutral' | 'jingzhou']> = [
      [11, 20, 'neutral'], [11, 100, 'neutral'], [5, 500, 'neutral'], [9, 60, 'jingzhou'], [11, 60, 'jingzhou'],
    ];
    for (const [level, width, style] of cases) {
      const wanted = cityFitSpriteKeys(level, width, style)[0];
      expect(cityFitSprite(images, level, width, style)).toBe(images[wanted]);
    }
  });

  it('지역 양식 그림 뒤에 무양식 그림을 대비로 둔다', () => {
    expect(cityFitSpriteKeys(9, 60, 'jingzhou')).toEqual(['R64:jingzhou:9', '2x:9']);
    // 지역 양식이 없는 등급(1–4)은 무양식 한 장만.
    expect(cityFitSpriteKeys(3, 60, 'jingzhou')).toEqual(['2x:3']);
  });
});
