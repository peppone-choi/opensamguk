import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { externalPlaceLevel } from '../iso/externalPlaceTier';

// 저장소 실물을 읽는다. 값을 여기 베껴 두면 검사와 대상이 같은 출처가 되어
// 통과가 아무것도 증명하지 못한다(가드는 축이 달라야 한다).
const ROOT = join(__dirname, '..', '..', '..', '..');
const tiles = JSON.parse(
  readFileSync(join(ROOT, 'data', 'map', 'han-tiles.json'), 'utf-8'),
) as {
  cities: { nameCh: string; kind: string }[];
  provinceRecords: { cityIndex?: number | null; administrativeSystem?: string }[];
};
const world = JSON.parse(
  readFileSync(join(ROOT, 'infra', 'src', 'main', 'resources', 'map', 'han.json'), 'utf-8'),
) as { cities: { level: number; meta?: { nameCh?: string } }[] };

/** 세계 생성기가 東夷傳 戶數로 매긴 등급. nameCh → level. */
const worldLevel = new Map<string, number>();
for (const city of world.cities) {
  const nameCh = city.meta?.nameCh;
  if (nameCh) worldLevel.set(nameCh, city.level);
}

/** 郡國 밖 城 → 그 城 을 가리키는 provinceRecord 의 행정 계통. */
const systemByName = new Map<string, string>();
for (const record of tiles.provinceRecords) {
  const index = record.cityIndex;
  if (index == null || !record.administrativeSystem) continue;
  const city = tiles.cities[index];
  if (city?.kind === 'EXTERNAL_PLACE') systemByName.set(city.nameCh, record.administrativeSystem);
}
const externals = tiles.cities.filter((city) => city.kind === 'EXTERNAL_PLACE');

describe('郡國 밖 세력의 아이콘 단', () => {
  it('밖 세력 37 곳이 전부 행정 계통에 닿는다 — 못 닿으면 계통 축이 죽은 것이다', () => {
    expect(externals.length).toBe(37);
    const unreached = externals.filter((city) => !systemByName.has(city.nameCh));
    expect(unreached.map((city) => city.nameCh)).toEqual([]);
  });

  it('세계가 등급을 매긴 곳은 계통 판정과 한 글자도 어긋나지 않는다', () => {
    // 이것이 이 표의 근거다. 서로 다른 데서 만들어진 두 분류(東夷傳 戶數 사다리 ·
    // provinceRecords 행정 계통)가 같은 답을 내야 한다. 어긋나면 표가 낡은 것이다.
    const graded = externals.filter((city) => worldLevel.has(city.nameCh));
    expect(graded.length).toBeGreaterThan(0);
    const mismatched = graded
      .map((city) => ({
        nameCh: city.nameCh,
        rule: externalPlaceLevel({
          nameCh: city.nameCh,
          administrativeSystem: systemByName.get(city.nameCh),
        }),
        world: worldLevel.get(city.nameCh),
      }))
      .filter((row) => row.rule !== row.world);
    expect(mismatched).toEqual([]);
  });

  it('유목·산지 7 계열만 야영(4)으로 선다', () => {
    const tribal = externals
      .filter((city) => externalPlaceLevel({
        nameCh: city.nameCh,
        administrativeSystem: systemByName.get(city.nameCh),
      }) === 4)
      .map((city) => city.nameCh)
      .sort();
    expect(tribal).toEqual(['南匈奴', '哀牢', '山越', '烏桓', '白馬氐', '西羌', '鮮卑'].sort());
  });

  it('東夷傳 戶數가 큰 두 곳은 한 단 위(6)에 선다', () => {
    expect(externalPlaceLevel({ nameCh: '夫餘', administrativeSystem: 'BUYEO' })).toBe(6);
    expect(externalPlaceLevel({ nameCh: '邪馬壹國', administrativeSystem: 'WA' })).toBe(6);
    // 같은 WA 계열이라도 소국은 城(5) 이다 — 계열로 뭉뚱그리지 않는다.
    expect(externalPlaceLevel({ nameCh: '奴國', administrativeSystem: 'WA' })).toBe(5);
  });

  it('근거가 없는 곳은 지어내지 않고 城(5) 으로 둔다', () => {
    // 挹婁·왜 소국 5 곳은 저장소에 戶數 근거가 없다. 텐트로 밀어 넣지 않는다.
    expect(externalPlaceLevel({ nameCh: '挹婁', administrativeSystem: 'YILOU' })).toBe(5);
    expect(externalPlaceLevel({ nameCh: '對馬國', administrativeSystem: 'TSUSHIMA' })).toBe(5);
    expect(externalPlaceLevel({})).toBe(5);
  });
});
