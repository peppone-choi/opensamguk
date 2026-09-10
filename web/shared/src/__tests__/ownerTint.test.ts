import { describe, expect, it } from 'vitest';
import { indexTint, normaliseNationColor, ownerTint } from '../iso/tint';

describe('ownerTint', () => {
  it('국가색 표에 있는 縣 은 정규화한 그 나라 색이다', () => {
    expect(ownerTint(7, { 7: '#c62828' })).toEqual(normaliseNationColor('#c62828'));
  });

  it('표를 받았는데 그 縣 이 없으면 **칠하지 않는다** — 무소속은 지형 그대로', () => {
    expect(ownerTint(7, { 3: '#c62828' })).toBeNull();
    expect(ownerTint(7, {})).toBeNull();
  });

  it('비플레이 타일(-1)은 언제나 칠하지 않는다', () => {
    expect(ownerTint(-1, { 0: '#c62828' })).toBeNull();
    expect(ownerTint(-1, undefined)).toBeNull();
  });

  it('국가색 표 자체가 없는 화면(랩)에서만 색인 색으로 소속을 보여 준다', () => {
    expect(ownerTint(7, undefined)).toEqual(indexTint(7));
  });
});
