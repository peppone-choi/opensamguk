'use client';
// 아이소 지도 랩 — 3D(glTF)·2D(스프라이트) 두 렌더러를 같은 데이터로 나란히 본다.
//
// 세 가지를 눈으로 확인하려고 만든 화면이다.
//   1. DEM 높낮이가 지도에 실제로 서는가 (NOAA ETOPO1 → 단차 0..6).
//   2. 이미 만들어 둔 iso2d·iso3d 애셋이 지형으로 읽히는가.
//   3. 세력색을 **곱하기**로 합성하면 지형이 살아남는가.
//      배포본은 불투명 덮어쓰기라 소유된 縣의 지형이 100% 가려진다
//      (web/shared/src/HanMapCanvas.tsx:1190 · provinceMap.ts:823).
//
// 여기 색칠은 실제 국가 소유가 아니라 **郡 소속(실데이터)** 이다. 국가색 표가 붙기 전까지
// 합성 방식만 보이려는 것이고, 없는 수치를 지어내지 않으려고 그렇게 라벨을 단다.

import { useCallback, useMemo, useState } from 'react';
import {
  IsoMap2D, PillTabs, TERRAIN_ASSET_NAME, fitFootprintsInTile, useIsoTileGrid,
  type PlacedCity, type TintMode,
} from '@opensamguk/ui';
import Shell from '../../../components/Shell';
import PageHead from '../../../components/PageHead';
import IsoMap3D from '../../../components/iso/IsoMap3D';

type Track = 'iso3d' | 'iso2d';

const TRACKS = [
  { key: 'iso3d' as const, label: '3D · glTF' },
  { key: 'iso2d' as const, label: '2D · 스프라이트' },
];

const TINTS = [
  { key: 'none' as const, label: '지형만' },
  { key: 'commandery' as const, label: '郡 소속' },
];

const TERRAIN_LABEL: Record<string, string> = {
  sea: '바다', plain: '평지', mountain: '산', river: '강', lake: '호수',
  desert: '사막', plateau: '고원', basin: '분지', hill: '구릉',
};

export default function IsoLabPage() {
  const [track, setTrack] = useState<Track>('iso3d');
  const [tintMode, setTintMode] = useState<TintMode>('none');
  const [tintStrength, setTintStrength] = useState(0.55);
  const [picked, setPicked] = useState<{ col: number; row: number } | null>(null);

  const state = useIsoTileGrid('/api/game/api/map/terrain?mapCode=han-world-v3');
  const data = state.data;

  const onPickTile = useCallback((tile: { col: number; row: number } | null) => setPicked(tile), []);

  const summary = useMemo(() => {
    if (!data) return null;
    const { grid } = data;
    const count = new Array(10).fill(0);
    let flat = 0;
    let slope = 0;
    let cliff = 0;
    let outside = 0;
    for (let i = 0; i < grid.code.length; i += 1) {
      count[grid.code[i]] += 1;
      if (grid.playable[i] === 0) {
        outside += 1;
        continue;
      }
      if (grid.cliff[i]) cliff += 1;
      else if (grid.mask[i] === 0) flat += 1;
      else slope += 1;
    }
    return { count, flat, slope, cliff, outside };
  }, [data]);

  // 랩은 게임 도시가 아니라 지형 응답의 CHGIS 지명(1,138 곳)을 세운다. 게임 번호가
  // 없으므로 id 를 -1 로 두고 집히지 않게 한다 — 없는 번호를 지어내지 않는다.
  const atlasCities = useMemo<PlacedCity[]>(() => {
    const places: PlacedCity[] = (data?.cities ?? []).map((city) => ({
      id: -1,
      name: city.name,
      level: city.level,
      nationId: 0,
      col: city.col,
      row: city.row,
      tileCol: city.col,
      tileRow: city.row,
      drawCol: city.col,
      drawRow: city.row,
      drawScale: 1,
      seat: city.seat,
      isCapital: false,
      exact: true,
    }));
    // 게임창과 같은 규칙으로 칸에 앉힌다 — 랩만 다른 자리에 세우면 눈으로 비교가 안 된다.
    fitFootprintsInTile(places);
    return places;
  }, [data]);

  const detail = useMemo(() => {
    if (!data || !picked) return null;
    const { grid, owner, parentOwner, cities } = data;
    const i = picked.row * grid.cols + picked.col;
    const city = cities.find((entry) => entry.col === picked.col && entry.row === picked.row);
    return {
      terrain: TERRAIN_LABEL[TERRAIN_ASSET_NAME[grid.code[i]]] ?? '알 수 없음',
      playable: grid.playable[i] === 1,
      level: grid.level[i],
      baseHeight: grid.baseHeight[i],
      mask: grid.mask[i],
      cliff: grid.cliff[i] === 1,
      commandery: parentOwner[i] >= 0
        ? (data.commanderyNames[parentOwner[i]] ?? `#${parentOwner[i]}`)
        : null,
      province: owner[i] >= 0 ? owner[i] : null,
      city,
    };
  }, [data, picked]);

  return (
    <Shell>
      <div className="page-content">
        <PageHead
          title="아이소 지도 랩"
          chip={data ? `${data.year}년 · ${data.grid.cols}×${data.grid.rows}` : undefined}
          tabs={<PillTabs tabs={TRACKS} value={track} onChange={setTrack} label="렌더러" />}
        />

        {state.status === 'error' ? (
          <p role="alert" style={{ color: 'var(--rust-2)' }}>지도를 불러오지 못했다: {state.error}</p>
        ) : null}
        {state.status === 'loading' ? <p className="text-muted">지형·고도 불러오는 중…</p> : null}

        {data ? (
          <>
            <div
              style={{
                display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 16,
                margin: '0 0 12px', font: '12px var(--font-sans)',
              }}
            >
              <PillTabs tabs={TINTS} value={tintMode} onChange={setTintMode} label="세력색 합성" />
              <label style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--text-2)' }}>
                곱하기 세기
                <input
                  type="range"
                  min={0}
                  max={1}
                  step={0.05}
                  value={tintStrength}
                  onChange={(e) => setTintStrength(Number(e.target.value))}
                  disabled={tintMode === 'none'}
                  style={{ width: 140 }}
                />
                <span className="os-num" style={{ color: 'var(--muted)', minWidth: '3ch' }}>
                  {tintStrength.toFixed(2)}
                </span>
              </label>
              <span style={{ color: 'var(--muted)' }}>
                끌어서 이동 · 휠로 확대(郡治를 당기면 縣이 나온다) · 눌러서 타일 선택
              </span>
            </div>

            <div
              style={{
                height: 'min(72vh, 720px)', border: '1px solid var(--line)',
                background: 'var(--bg)', overflow: 'hidden',
              }}
            >
              {track === 'iso3d' ? (
                <IsoMap3D
                  data={data}
                  cities={atlasCities}
                  hideCityNames
                  showStats
                  tintMode={tintMode}
                  tintStrength={tintStrength}
                  onPickTile={onPickTile}
                />
              ) : (
                <IsoMap2D
                  data={data}
                  cities={atlasCities}
                  hideCityNames
                  tintMode={tintMode}
                  tintStrength={tintStrength}
                  onPickTile={onPickTile}
                />
              )}
            </div>

            <div
              style={{
                display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
                gap: 16, marginTop: 16,
              }}
            >
              <section>
                <h2 className="os-section-title">선택 타일</h2>
                {detail && picked ? (
                  <dl className="os-kv">
                    <dt>좌표</dt><dd className="os-num">{picked.col}, {picked.row}</dd>
                    <dt>지형</dt>
                    <dd>{detail.terrain}{detail.playable ? '' : ' · 지도 밖(플레이 불가)'}</dd>
                    <dt>DEM 단</dt><dd className="os-num">{detail.level} / 6</dd>
                    <dt>바닥 높이</dt><dd className="os-num">{detail.baseHeight}</dd>
                    <dt>경사 마스크</dt>
                    <dd className="os-num">{detail.mask}{detail.cliff ? ' · 절벽' : ''}</dd>
                    <dt>郡</dt><dd>{detail.commandery ?? '—'}</dd>
                    {detail.city ? (
                      <>
                        <dt>城</dt>
                        <dd>{detail.city.name} ({detail.city.nameCh}) lv{detail.city.level}</dd>
                      </>
                    ) : null}
                  </dl>
                ) : (
                  <p className="text-muted">지도를 눌러 타일을 고르세요.</p>
                )}
              </section>

              <section>
                <h2 className="os-section-title">격자</h2>
                {summary ? (
                  <dl className="os-kv">
                    <dt>평지</dt><dd className="os-num">{summary.flat.toLocaleString()}</dd>
                    <dt>경사</dt><dd className="os-num">{summary.slope.toLocaleString()}</dd>
                    {/* 스프라이트가 못 그리는 타일. 0 이 아니면 실루엣이 어긋나 배경이 샌다. */}
                    <dt>절벽(0이어야 함)</dt>
                    <dd className="os-num">{summary.cliff.toLocaleString()}</dd>
                    {/* 원본 지형 데이터가 없어 ETOPO1 표고에서 지형을 유추한 타일. */}
                    <dt>지도 밖</dt>
                    <dd className="os-num">{summary.outside.toLocaleString()}</dd>
                    {TERRAIN_ASSET_NAME.map((name, code) => (
                      name ? (
                        <span key={name} style={{ display: 'contents' }}>
                          <dt>{TERRAIN_LABEL[name]}</dt>
                          <dd className="os-num">{summary.count[code].toLocaleString()}</dd>
                        </span>
                      ) : null
                    ))}
                  </dl>
                ) : null}
              </section>

              <section>
                <h2 className="os-section-title">고도 출처</h2>
                {data.elevation?.dataset ? (
                  <>
                    <p style={{ margin: '0 0 8px', color: 'var(--text-2)' }}>
                      {data.elevation.dataset.title}
                    </p>
                    <p style={{ margin: '0 0 8px', color: 'var(--muted)', fontSize: 12 }}>
                      {data.elevation.dataset.license} · 받은 날 {data.elevation.dataset.retrieved}
                    </p>
                    <ul style={{ margin: 0, paddingLeft: '1.1em', color: 'var(--muted)', fontSize: 12 }}>
                      {(data.elevation.limitations ?? []).map((line: string) => <li key={line}>{line}</li>)}
                    </ul>
                  </>
                ) : (
                  <p className="text-muted">고도 매니페스트를 못 읽었다.</p>
                )}
              </section>
            </div>

            <p style={{ marginTop: 16, color: 'var(--muted)', fontSize: 12 }}>
              색칠은 <strong>郡 소속</strong>이다. 실제 국가 소유·국가색이 아니다 — 합성 방식(곱하기)만
              보이려는 화면이고, 국가색 표가 붙으면 같은 자리에 그대로 들어간다.
            </p>
          </>
        ) : null}
      </div>
    </Shell>
  );
}
