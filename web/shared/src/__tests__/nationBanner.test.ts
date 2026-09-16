// 세력색이 지형에 묻히지 않게 하는 세 규칙.
//   1) 지형과 색상이 겹치는 국가(황록~황갈)는 땅에 칠할 때 채도를 올린다.
//   2) 깃발·국경 띠는 같은 색상의 **선명한** 색을 쓴다.
//   3) 무채색 국가는 따로 가려낸다 — 'color' 합성으로는 색이 안 나온다.
import { describe, expect, it } from 'vitest';
import {
  bannerColor,
  isAchromaticNationColor,
  nationGlyph,
  normaliseNationColor,
} from '../index';
import type { Rgb } from '../index';

function hsl({ r, g, b }: Rgb): [number, number, number] {
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  if (max === min) return [0, 0, l];
  const d = max - min;
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  let h = max === r ? (g - b) / d + (g < b ? 6 : 0) : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  h /= 6;
  return [h * 360, s, l];
}

describe('normaliseNationColor — 지형 색상대', () => {
  it('원소 #E5BD11·공손찬 #F76E19·도겸 #CDE4AC 는 채도 0.7 로 칠한다', () => {
    for (const hex of ['#E5BD11', '#F76E19', '#CDE4AC']) {
      expect(hsl(normaliseNationColor(hex))[1]).toBeCloseTo(0.7, 2);
    }
  });

  it('지형과 먼 색상(조조 #2424E9·손견 #CA0533)은 예전 0.42 그대로다', () => {
    for (const hex of ['#2424E9', '#CA0533']) {
      expect(hsl(normaliseNationColor(hex))[1]).toBeCloseTo(0.42, 2);
    }
  });
});

describe('bannerColor', () => {
  it('색상은 땅 색과 같고 채도는 더 높다 — 같은 나라로 읽힌다', () => {
    for (const hex of ['#E5BD11', '#2424E9', '#CA0533', '#72B9C1', '#CCB7D8']) {
      const [fieldHue, fieldS] = hsl(normaliseNationColor(hex));
      const [bannerHue, bannerS] = hsl(bannerColor(hex));
      expect(Math.abs(bannerHue - fieldHue)).toBeLessThan(1);
      expect(bannerS).toBeGreaterThanOrEqual(0.62 - 1e-6);
      expect(bannerS).toBeGreaterThanOrEqual(fieldS - 0.09);
    }
  });

  it('무채색은 회색으로 남되 명도 차이는 지킨다 — 동탁·금선·한수가 서로 다르다', () => {
    const dong = bannerColor('#595959');
    const geum = bannerColor('#A9A9A9');
    const han = bannerColor('#FFFFFF');
    for (const c of [dong, geum, han]) expect(c.r).toBeCloseTo(c.g, 6);
    expect(dong.r).toBeLessThan(geum.r);
    expect(geum.r).toBeLessThan(han.r);
  });
});

describe('isAchromaticNationColor', () => {
  it('회색·흰색·검정만 걸린다', () => {
    expect(['#595959', '#A9A9A9', '#FFFFFF', '#000000'].map(isAchromaticNationColor))
      .toEqual([true, true, true, true]);
    expect(['#CCB7D8', '#AFEEEE', '#E5BD11', 'nope'].map(isAchromaticNationColor))
      .toEqual([false, false, false, false]);
  });
});

describe('nationGlyph', () => {
  // 2026-09-15 프로덕션(pep) 【역사모드2】 반동탁연합의 국가 이름 그대로.
  const LIVE = ['동탁', '원소', '유표', '조조', '유언', '원술', '손견', '도겸', '공손찬', '한복', '유우',
    '유대', '장로', '유비', '공융', '장연', '공손도', '㉿주우', '㉿금선', '㉿강단', '㉿한수', '㉿장순', '㉿소유', '㉿김상'];

  it('국가 이름 첫 글자 — 앞에 붙은 기호는 건너뛴다', () => {
    expect(nationGlyph('조조')).toBe('조');
    expect(nationGlyph('㉿김상')).toBe('김');
    expect(nationGlyph('')).toBeNull();
    expect(nationGlyph(undefined)).toBeNull();
    expect(nationGlyph('㉿')).toBeNull();
  });

  it('성씨가 겹쳐도 밀지 않는다 — 유씨 다섯은 다 「유」다', () => {
    for (const name of ['유표', '유언', '유우', '유대', '유비']) {
      expect(nationGlyph(name)).toBe('유');
    }
    expect(nationGlyph('공손찬')).toBe('공');
    expect(nationGlyph('공융')).toBe('공');
  });

  it('언제나 한 글자다 — 같은 나라는 어느 화면에서나 같은 글자를 단다', () => {
    for (const name of LIVE) {
      const glyph = nationGlyph(name);
      expect(glyph).not.toBeNull();
      expect([...(glyph as string)]).toHaveLength(1);
    }
  });
});
