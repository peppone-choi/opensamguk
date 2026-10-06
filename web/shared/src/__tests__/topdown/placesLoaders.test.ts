import { afterEach, describe, expect, it, vi } from 'vitest';
import { adminTexels, footprints, labelCandidates, mapLabelText, parsePlaces, type PlacesData } from '../../map/topdown/places';
import { fetchBytes, gunzip, joinUrl } from '../../map/topdown/loaders';
import { clothSlant, type SpriteSheet } from '../../map/topdown/sprites';

function places(): PlacesData {
  return {
    schemaVersion: 1,
    provinceCount: 3,
    provinceAdmin: [[0, 0, 0], [1, 0, 0], [-1, -1, -1]],
    counties: [
      { id: 'J1', name: '양적', kind: 'COUNTY', cityId: 122 },
      { id: 'J2', name: '영음', kind: 'COUNTY', cityId: 123 },
    ],
    commanderies: [{ id: 'C1', name: '영천군', kind: 'COMMANDERY', seatCityId: 122 }],
    ju: [{ name: '예주', anchor: [1600, 1000] }],
    cities: [{
      id: 122, name: '양적', level: 8, cell: [1569, 981], provinceIndex: 0, countyIndex: 0, commanderyIndex: 0, isSeat: true,
      footprint: { originCol: 1564, originRow: 976, span: 11, innerSpan: 5 }, roofCell: [1569, 981], gates: 'NS', site: null, households: 20000,
    }],
    passes: [],
    labels: [{ id: 'city:122', text: '양적', kind: 'commanderySeat', anchor: [1569, 981], priority: 800000, footprintSpan: 11 }],
  };
}

describe('places', () => {
  it('구역 소속을 평면 값(구역 번호 + 1) 자리에 1을 더해 싣는다', () => {
    const { width, height, data } = adminTexels(places(), 8);
    expect([width, height]).toEqual([8, 1]);
    expect(Array.from(data.subarray(0, 4))).toEqual([0, 0, 0, 0]); // 구역 없음
    expect(Array.from(data.subarray(4, 8))).toEqual([1, 1, 1, 0]); // 구역 0 → 縣 0 · 郡 0 · 州 0
    expect(Array.from(data.subarray(8, 12))).toEqual([2, 1, 1, 0]);
    expect(Array.from(data.subarray(12, 16))).toEqual([0, 0, 0, 0]); // 모름(-1) → 0
  });

  it('형식이 어긋나면 거절한다', () => {
    expect(() => parsePlaces({ ...places(), schemaVersion: 2 })).toThrow('schemaVersion');
    expect(() => parsePlaces({ ...places(), provinceCount: 4 })).toThrow('provinceAdmin');
    const bad = places();
    bad.labels[0].kind = 'city' as never;
    expect(() => parsePlaces(bad)).toThrow('label kind');
  });

  it('이름표에서 동명 구분 괄호 한자를 뺀다', () => {
    expect(mapLabelText('하양(河內郡)')).toBe('하양');
    expect(mapLabelText('장안(京兆尹)')).toBe('장안');
    expect(mapLabelText('영천군')).toBe('영천군');
    expect(mapLabelText('(漢)')).toBe('(漢)'); // 다 지워지면 원래 글자를 둔다
  });

  it('이름표 후보와 발자국을 칸 좌표로 바꾼다', () => {
    const data = parsePlaces(places());
    expect(labelCandidates(data)[0]).toMatchObject({ anchor: { col: 1569, row: 981 }, footprintSpan: 11, kind: 'commanderySeat' });
    expect(footprints(data)).toEqual([{ cityId: 122, originCol: 1564, originRow: 976, span: 11 }]);
  });

  it('州 이름표는 bake 데이터 키를 화면 이름으로 바꾸고(원장 D25), 다른 종류 이름표는 그대로 둔다', () => {
    const data = places();
    data.labels.push(
      { id: 'ju:9', text: '량주', kind: 'ju', anchor: [600, 700], priority: 1, footprintSpan: 0 },
      { id: 'ju:0', text: '사예', kind: 'ju', anchor: [1300, 900], priority: 1, footprintSpan: 0 },
      { id: 'ju:1', text: '예주', kind: 'ju', anchor: [1600, 1000], priority: 1, footprintSpan: 0 },
      { id: 'county:x', text: '사예', kind: 'county', anchor: [1, 1], priority: 1, footprintSpan: 1 },
    );
    const text = Object.fromEntries(labelCandidates(parsePlaces(data)).map((label) => [label.id, label.text]));
    expect(text).toMatchObject({ 'ju:9': '서량', 'ju:0': '사례', 'ju:1': '예주', 'county:x': '사예', 'city:122': '양적' });
  });
});

describe('loaders', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('같은 주소를 동시에 받으면 한 번만 부르고, 실패는 남기지 않는다', async () => {
    let calls = 0;
    let fail = true;
    vi.stubGlobal('fetch', vi.fn(async () => {
      calls += 1;
      if (fail) return new Response('no', { status: 503 });
      return new Response(new Uint8Array([1, 2, 3]));
    }));
    const url = `/test-${Math.random()}.bin`;
    await Promise.all([
      expect(fetchBytes(url)).rejects.toThrow('503'),
      expect(fetchBytes(url)).rejects.toThrow('503'),
    ]);
    expect(calls).toBe(1);
    fail = false;
    expect(new Uint8Array(await fetchBytes(url))).toEqual(new Uint8Array([1, 2, 3]));
    expect(calls).toBe(2);
  });

  it('keep 자원은 페이지 동안 다시 받지 않고, 조각은 끝나면 놓아 준다', async () => {
    let calls = 0;
    vi.stubGlobal('fetch', vi.fn(async () => {
      calls += 1;
      return new Response(new Uint8Array([7]));
    }));
    const kept = `/kept-${Math.random()}.json`;
    await fetchBytes(kept, { keep: true });
    await fetchBytes(kept, { keep: true });
    expect(calls).toBe(1);
    const chunk = `/chunk-${Math.random()}.bin`;
    await fetchBytes(chunk);
    await fetchBytes(chunk);
    expect(calls).toBe(3);
  });

  it('.gz 는 받은 뒤 푼다', async () => {
    const plain = new Uint8Array([10, 20, 30, 40]);
    const zipped = await new Response(new Response(plain).body!.pipeThrough(new CompressionStream('gzip'))).arrayBuffer();
    vi.stubGlobal('fetch', vi.fn(async () => new Response(zipped)));
    expect(new Uint8Array(await fetchBytes(`/grid-${Math.random()}.bin.gz`))).toEqual(plain);
    expect(new Uint8Array(await gunzip(zipped))).toEqual(plain);
  });

  it('서버 고르기 query 가 붙어도 .gz 는 푼다(로그인 미리보기 · 게이트웨이 프록시)', async () => {
    const plain = new Uint8Array([7, 8, 9]);
    const zipped = await new Response(new Response(plain).body!.pipeThrough(new CompressionStream('gzip'))).arrayBuffer();
    vi.stubGlobal('fetch', vi.fn(async () => new Response(zipped)));
    const url = joinUrl(`/api/game/api/map/topdown/${Math.random()}?server=pep`, 'places.json.gz');
    expect(url.endsWith('places.json.gz?server=pep')).toBe(true);
    expect(new Uint8Array(await fetchBytes(url))).toEqual(plain);
  });

  it('주소를 슬래시 하나로 잇는다', () => {
    expect(joinUrl('/map/bake/', '/grid/L2.bin.gz')).toBe('/map/bake/grid/L2.bin.gz');
  });
});

describe('sprites', () => {
  it('천 가운데 기울기를 원작 도구처럼 맞춘다', () => {
    // 한 줄에 한 칸씩 오른쪽으로 밀리는 천(기울기 1)
    const roles = new Uint8Array(16 * 16);
    for (let y = 4; y < 12; y += 1) for (let x = 4 + y - 4; x < 8 + y - 4; x += 1) roles[y * 16 + x] = 1;
    const sheet: SpriteSheet = { width: 16, height: 16, rgba: new Uint8ClampedArray(16 * 16 * 4), roles };
    const slant = clothSlant(sheet, 0);
    expect(slant.b).toBeCloseTo(1, 9);
    expect(slant.a + slant.b * 4).toBeCloseTo(6, 9); // 4행 천 가운데 = (4 + 8) / 2
  });
});

describe('joinUrl', () => {
  it('경로를 잇고, 바탕의 query는 끝으로 옮긴다', () => {
    expect(joinUrl('/map/bake/', '/grid/L2.bin.gz')).toBe('/map/bake/grid/L2.bin.gz');
    expect(joinUrl('/api/map/topdown/abc?server=pep', 'manifest.json')).toBe('/api/map/topdown/abc/manifest.json?server=pep');
    expect(joinUrl('/api/map/topdown/abc/?server=a%20b', 'grid/L0/1_2.bin.gz')).toBe('/api/map/topdown/abc/grid/L0/1_2.bin.gz?server=a%20b');
  });
});
