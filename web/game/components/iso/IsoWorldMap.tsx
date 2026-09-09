'use client';

// 게임창(천하 지도)에 들어가는 아이소 지형 보기. 3D·2D 렌더러를 실제 세력색과 함께 쓴다.
//
// 랩(/game/iso-lab)과 다른 점은 색의 출처 하나다. 랩은 郡 소속을 황금각으로 돌려 칠하지만
// 여기서는 진짜 국가색을 쓴다. 이음매는 縣 인덱스다.
//   지형 응답 owner[i]  = provinceRecords 안 縣 인덱스(0..1523, -1 은 비플레이)
//   /api/map/preview    = provinceOccupancy[{provinceIndex, nationId}] + nations[{id, color}]
// 두 provinceIndex 가 같은 색인 공간이라는 것은 provinceMap.ts:554 가 이미 검사한다
// (provinces[owner.provinceIndex].id !== owner.provinceRecordId 면 던진다).
//
// 城 선택은 붙이지 않는다. 지형 응답의 city.id 는 CHGIS 계열 식별자(85377)이고 게임
// 도시 번호와 다른 공간이라, 대조표 없이 이으면 엉뚱한 도시를 여는 거짓말이 된다.

import { useCallback, useEffect, useMemo, useState } from 'react';
import { PillTabs, TERRAIN_ASSET_NAME } from '@opensamguk/ui';
import IsoMap3D, { type TintMode } from './IsoMap3D';
import IsoMap2D from './IsoMap2D';
import { useIsoTileGrid } from './useIsoTileGrid';
import { api } from '../../lib/api';

export type IsoView = 'iso3d' | 'iso2d';

const TERRAIN_LABEL: Record<string, string> = {
  sea: '바다', plain: '평지', mountain: '산', river: '강', lake: '호수',
  desert: '사막', plateau: '고원', basin: '분지', hill: '구릉',
};

const TINTS = [
  { key: 'nation' as const, label: '세력색' },
  { key: 'none' as const, label: '지형만' },
];

export interface IsoWorldMapProps {
  view: IsoView;
  /** 턴이 바뀌면 올려 준다. 세력색만 다시 받는다 — 지형·고도는 정적이라 그대로 둔다. */
  refreshKey?: number;
}

interface NationPaint {
  colorByProvince: Record<number, string>;
  nameByProvince: Record<number, string>;
}

export default function IsoWorldMap({ view, refreshKey = 0 }: IsoWorldMapProps) {
  const state = useIsoTileGrid('/api/game/api/map/terrain?mapCode=han-world-v3');
  const [paint, setPaint] = useState<NationPaint | null>(null);
  const [paintError, setPaintError] = useState<string | null>(null);
  const [tintMode, setTintMode] = useState<TintMode>('nation');
  const [picked, setPicked] = useState<{ col: number; row: number } | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    api.mapPreview(controller.signal)
      .then((preview) => {
        const colorByNation = new Map(preview.nations.map((nation) => [nation.id, nation]));
        const colorByProvince: Record<number, string> = {};
        const nameByProvince: Record<number, string> = {};
        for (const owner of preview.provinceOccupancy ?? []) {
          const nation = colorByNation.get(owner.nationId);
          if (!nation) continue;
          colorByProvince[owner.provinceIndex] = nation.color;
          nameByProvince[owner.provinceIndex] = nation.name;
        }
        setPaint({ colorByProvince, nameByProvince });
        setPaintError(null);
      })
      .catch(() => {
        if (controller.signal.aborted) return;
        setPaintError('세력색을 불러오지 못했습니다. 지형만 그립니다.');
      });
    return () => controller.abort();
  }, [refreshKey]);

  const onPickTile = useCallback(
    (tile: { col: number; row: number } | null) => setPicked(tile),
    [],
  );

  const detail = useMemo(() => {
    if (!state.data || !picked) return null;
    const { grid, owner, parentOwner, commanderyNames } = state.data;
    const i = picked.row * grid.cols + picked.col;
    return {
      terrain: TERRAIN_LABEL[TERRAIN_ASSET_NAME[grid.code[i]]] ?? '알 수 없음',
      level: grid.level[i],
      commandery: parentOwner[i] >= 0 ? (commanderyNames[parentOwner[i]] ?? null) : null,
      nation: owner[i] >= 0 ? (paint?.nameByProvince[owner[i]] ?? null) : null,
    };
  }, [paint, picked, state.data]);

  if (state.status === 'error') {
    return <p role="alert" className="map-rail__msg page-error">지형을 불러오지 못했습니다: {state.error}</p>;
  }
  if (!state.data) {
    return <p className="map-rail__msg">지형과 고도를 불러오는 중입니다.</p>;
  }

  return (
    <div className="iso-stage">
      <div className="iso-stage__bar">
        <PillTabs tabs={TINTS} value={tintMode} onChange={setTintMode} label="세력색" />
        <span className="text-muted">끌어서 이동 · 휠로 확대(郡治를 당기면 縣이 나온다)</span>
        {paintError ? <span className="page-error">{paintError}</span> : null}
      </div>
      <div className="iso-stage__canvas">
        {view === 'iso3d' ? (
          <IsoMap3D
            data={state.data}
            tintMode={tintMode}
            tintStrength={0.55}
            nationColorByOwner={paint?.colorByProvince}
            onPickTile={onPickTile}
          />
        ) : (
          <IsoMap2D
            data={state.data}
            tintMode={tintMode}
            tintStrength={0.55}
            nationColorByOwner={paint?.colorByProvince}
            onPickTile={onPickTile}
          />
        )}
      </div>
      <dl className="iso-stage__readout">
        <dt>지형</dt>
        <dd>{detail ? detail.terrain : '—'}</dd>
        <dt>표고 단</dt>
        <dd className="os-num">{detail ? `${detail.level} / 6` : '—'}</dd>
        <dt>郡</dt>
        <dd>{detail?.commandery ?? '—'}</dd>
        <dt>세력</dt>
        <dd>{detail?.nation ?? '—'}</dd>
      </dl>
    </div>
  );
}
