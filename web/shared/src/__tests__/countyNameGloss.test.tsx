import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
  countyGlossForJurisdiction,
  PlaceNameWithGloss,
  splitCountyGloss,
} from '../iso/countyNameGloss';
import {
  COUNTY_GLOSS_BY_JURISDICTION_ID,
  COUNTY_GLOSS_BY_SIMPLIFIED_STEM,
} from '../iso/countyNameGloss.generated';

const ROOT = resolve(__dirname, '../../../..');

// 이슈 #838: 영천군 「양성현」이 둘(陽城·襄城). 표시명은 그대로, 목록의 縣에만 작은 漢字 병기.
describe('countyGlossForJurisdiction', () => {
  it('목록의 縣은 繁體 병기를 받는다 — 영천군 양성현 둘이 갈린다', () => {
    expect(countyGlossForJurisdiction('82893')).toBe('陽城');
    expect(countyGlossForJurisdiction('83031')).toBe('襄城');
  });

  it('목록 밖·빈 값·프로토타입 키는 병기가 없다', () => {
    expect(countyGlossForJurisdiction('82828')).toBeUndefined(); // 낙양 — 郡 안에 짝이 없다
    expect(countyGlossForJurisdiction(undefined)).toBeUndefined();
    expect(countyGlossForJurisdiction(null)).toBeUndefined();
    expect(countyGlossForJurisdiction('constructor')).toBeUndefined();
  });

  it('생성 표는 커밋된 목록과 같은 관할·같은 병기다', () => {
    const list = JSON.parse(readFileSync(
      resolve(ROOT, 'data/curated/han/county-display-name-collisions-v1.json'), 'utf-8',
    )) as { collisions: { members: { jurisdictionId: string; gloss: string; simplifiedStem: string }[] }[] };
    const members = list.collisions.flatMap((c) => c.members);
    expect(members.length).toBeGreaterThanOrEqual(2);
    expect(Object.fromEntries(members.map((m) => [m.jurisdictionId, m.gloss]))).toEqual(COUNTY_GLOSS_BY_JURISDICTION_ID);
    expect(Object.fromEntries(members.map((m) => [m.simplifiedStem, m.gloss]))).toEqual(COUNTY_GLOSS_BY_SIMPLIFIED_STEM);
    // 병기에는 县/縣 꼬리가 없다.
    expect(members.filter((m) => /[县縣]$/.test(m.gloss))).toEqual([]);
  });
});

describe('splitCountyGloss', () => {
  it('서버 표시명의 簡體 꼬리를 繁體 병기로 바꾼다', () => {
    expect(splitCountyGloss('영천군 양성현(阳城)')).toEqual({ name: '영천군 양성현', gloss: '陽城' });
    expect(splitCountyGloss('영천군 양성현(襄城)')).toEqual({ name: '영천군 양성현', gloss: '襄城' });
    expect(splitCountyGloss('영릉군 영도현(营道)')).toEqual({ name: '영릉군 영도현', gloss: '營道' });
  });

  it('목록 밖 꼬리와 꼬리 없는 이름은 그대로 둔다', () => {
    expect(splitCountyGloss('와구(渦口)')).toEqual({ name: '와구(渦口)' });
    expect(splitCountyGloss('경조윤 장안현')).toEqual({ name: '경조윤 장안현' });
    expect(splitCountyGloss('여릉군 양성현')).toEqual({ name: '여릉군 양성현' }); // 다른 郡의 陽城 — 짝이 없다
  });

  it('han-world-v3 에서 병기로 갈리는 城은 같은 郡 안 짝이고, 가른 뒤에도 이름+병기가 城마다 하나다', () => {
    const world = JSON.parse(readFileSync(
      resolve(ROOT, 'infra/src/main/resources/map/han-world-v3.json'), 'utf-8',
    )) as { cities: { id: number; meta: { displayName: string } }[] };
    const split = world.cities
      .map((c) => ({ id: c.id, ...splitCountyGloss(c.meta.displayName) }))
      .filter((c) => c.gloss);
    // 런타임은 同音 3쌍(陽城·襄城 / 泠道·營道 / 安豐·安風)만 꼬리로 가른다. 0건 통과가 아님을 못박는다.
    expect(split.map((c) => c.id).sort((a, b) => a - b)).toEqual([129, 134, 490, 495, 527, 528, 857, 869, 996, 1399, 1417, 1603]);
    const shown = new Set(split.map((c) => `${c.name}|${c.gloss}`));
    expect(shown.size).toBe(split.length);
  });
});

describe('PlaceNameWithGloss', () => {
  it('병기가 있으면 이름 뒤에 작은 繁體 span 을 단다', () => {
    const { container } = render(<PlaceNameWithGloss name="양성현" gloss="陽城" />);
    expect(container.textContent).toBe('양성현陽城');
    const gloss = container.querySelector('span.os-place-gloss');
    expect(gloss?.textContent).toBe('陽城');
    expect(gloss?.getAttribute('lang')).toBe('zh-Hant');
  });

  it('병기가 없으면 이름만 낸다', () => {
    const { container } = render(<PlaceNameWithGloss name="장안현" />);
    expect(container.textContent).toBe('장안현');
    expect(container.querySelector('span')).toBeNull();
  });
});
