'use client';

// 게임창의 지도. 3D·2D 아이소 렌더러를 실제 세력색·실제 도시와 함께 쓴다.
//
// 이 컴포넌트가 기존 지도(HanMapCanvas)를 대신한다. 대신하려면 세 가지가 다 있어야 했다.
//
//   1) 세력색 — 縣(owner) 인덱스로 국가색을 찾는다. 색상만 얹으므로 지형 음영이 살아 있다.
//      이음매: 지형 응답 owner[i] = provinceRecords 안 縣 인덱스(0..1523, -1 은 비플레이),
//      /api/map/preview 의 provinceOccupancy[{provinceIndex, nationId}] 가 같은 색인 공간이다
//      (provinceMap.ts:554 가 이미 어긋나면 던진다).
//   2) 城 — 게임 도시 번호를 든 채로 선다. 대조표는 placeGameCities 주석에 있다.
//   3) 눌러서 들어가기 — 城 을 집으면 도시 번호가 그대로 나온다.
//
// 과거 스냅샷(전투 기록 화면)처럼 provinceOccupancy 가 없을 수도 있다. 그때는 그 시점
// 도시의 provinceId → nationId 로 縣 색을 짓는다. 살아 있는 값만 쓰고, 없으면 비운다.

import { useCallback, useMemo, useState } from 'react';
import {
  IsoMap2D,
  PillTabs,
  TERRAIN_ASSET_NAME,
  isOwnedNationVisual,
  placeBattlefields,
  placeGameCities,
  useIsoTileGrid,
  type GameCityInput,
  type IsoBattlefieldMarker,
  type PlacedCity,
  type TintMode,
} from '@opensamguk/ui';
import IsoMap3D from './IsoMap3D';

export type IsoView = 'iso3d' | 'iso2d';

const TERRAIN_LABEL: Record<string, string> = {
  sea: '바다', plain: '평지', mountain: '산', river: '강', lake: '호수',
  desert: '사막', plateau: '고원', basin: '분지', hill: '구릉',
};

const TINTS: { key: TintMode; label: string }[] = [
  { key: 'nation', label: '세력색' },
  { key: 'none', label: '지형만' },
];

const VIEWS: { key: IsoView; label: string }[] = [
  { key: 'iso3d', label: '3D' },
  { key: 'iso2d', label: '2D' },
];

export interface IsoWorldMapNation {
  id: number;
  name: string;
  color: string;
}

export interface IsoWorldMapBattlefield {
  id: string;
  name: string;
  latitude: number;
  longitude: number;
  current?: boolean;
}

export interface IsoWorldMapProps {
  terrainUrl: string;
  cities: readonly GameCityInput[];
  nations: readonly IsoWorldMapNation[];
  /** 게임 좌표계 크기(MapPreviewResponse.width/height). 縣 판정이 없는 도시의 폴백에 쓴다. */
  sourceSize: { width: number; height: number };
  provinceOccupancy?: readonly { provinceIndex: number; nationId: number }[];
  view?: IsoView;
  onViewChange?: (view: IsoView) => void;
  currentCityId?: number | null;
  selectedCityId?: number | null;
  hideCityNames?: boolean;
  /** 城 을 눌렀을 때. 없으면 城 은 집히지 않고 지형 판독만 남는다. */
  onCityActivate?: (city: PlacedCity, activation: { pointerType: string }) => void;
  /**
   * 진행 중 전장. 城 과 달리 위경도로 오므로 지형 응답의 투영식으로 격자에 앉힌다.
   * 마름모로 얹고, 캔버스 위 「전장 선택」 단추 줄도 함께 낸다 — 축소 상태에서
   * 마름모가 작아져도 들어갈 길이 남아야 한다.
   */
  battlefields?: readonly IsoWorldMapBattlefield[];
  onBattlefieldActivate?: (target: IsoWorldMapBattlefield) => void;
  /** 조작 줄과 타일 판독 줄을 감춘다. 로비·기록처럼 보기만 하는 자리용. */
  compact?: boolean;
}

export default function IsoWorldMap({
  terrainUrl,
  cities,
  nations,
  sourceSize,
  provinceOccupancy,
  view: controlledView,
  onViewChange,
  currentCityId = null,
  selectedCityId = null,
  hideCityNames = false,
  onCityActivate,
  battlefields,
  onBattlefieldActivate,
  compact = false,
}: IsoWorldMapProps) {
  const state = useIsoTileGrid(terrainUrl);
  const [ownView, setOwnView] = useState<IsoView>('iso3d');
  const [tintMode, setTintMode] = useState<TintMode>('nation');
  const [picked, setPicked] = useState<{ col: number; row: number } | null>(null);
  // 마우스를 얹은 城. 눌러야 나오는 게 아니라 얹으면 나온다.
  const [hover, setHover] = useState<{ city: PlacedCity; x: number; y: number } | null>(null);

  const view = controlledView ?? ownView;
  const setView = useCallback((next: IsoView) => {
    setOwnView(next);
    onViewChange?.(next);
  }, [onViewChange]);

  const nationById = useMemo(
    () => new Map(nations.map((nation) => [nation.id, { name: nation.name, color: nation.color }])),
    [nations],
  );

  const placed = useMemo(
    () => (state.data
      ? placeGameCities(cities, state.data, { sourceSize, nations: nationById })
      : []),
    [cities, nationById, sourceSize, state.data],
  );

  // 縣 → 국가색. 서버가 판정한 provinceOccupancy 가 정본이고, 없으면 그 시점 도시에서 짓는다.
  const paint = useMemo(() => {
    const colorByProvince: Record<number, string> = {};
    const nameByProvince: Record<number, string> = {};
    const put = (provinceIndex: number, nationId: number) => {
      const nation = nationById.get(nationId);
      // 城 과 같은 규칙이다 — 소유가 확실할 때만 칠한다(placeGameCities 참조).
      if (!isOwnedNationVisual(nationId, nation?.color) || provinceIndex < 0) return;
      colorByProvince[provinceIndex] = nation.color;
      nameByProvince[provinceIndex] = nation.name;
    };
    if (provinceOccupancy && provinceOccupancy.length > 0) {
      for (const owner of provinceOccupancy) put(owner.provinceIndex, owner.nationId);
    } else {
      for (const city of cities) {
        if (city.provinceId != null) put(city.provinceId, city.nationId);
      }
    }
    return { colorByProvince, nameByProvince };
  }, [cities, nationById, provinceOccupancy]);

  const fields = useMemo(
    () => (state.data && battlefields && battlefields.length > 0
      ? placeBattlefields(
        battlefields,
        state.data.projection,
        { cols: state.data.sourceCols, rows: state.data.sourceRows },
        state.data.grid,
      )
      : []),
    [battlefields, state.data],
  );
  const byFieldId = useMemo(
    () => new Map((battlefields ?? []).map((field) => [field.id, field])),
    [battlefields],
  );
  const onPickBattlefield = useCallback((marker: IsoBattlefieldMarker) => {
    const target = byFieldId.get(marker.id);
    if (target) onBattlefieldActivate?.(target);
  }, [byFieldId, onBattlefieldActivate]);

  const onHoverCity = useCallback((city: PlacedCity | null, at: { x: number; y: number }) => {
    setHover(city ? { city, x: at.x, y: at.y } : null);
  }, []);

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
      nation: owner[i] >= 0 ? (paint.nameByProvince[owner[i]] ?? null) : null,
    };
  }, [paint, picked, state.data]);

  if (state.status === 'error') {
    return <p role="alert" className="iso-stage__msg page-error">지형을 불러오지 못했습니다: {state.error}</p>;
  }
  if (!state.data) {
    return <p className="iso-stage__msg">지형과 고도를 불러오는 중입니다.</p>;
  }

  const shared = {
    data: state.data,
    tintMode,
    tintStrength: 0.55,
    nationColorByOwner: paint.colorByProvince,
    cities: placed,
    hideCityNames,
    currentCityId,
    selectedCityId,
    onPickTile,
    onPickCity: onCityActivate,
    onHoverCity,
    battlefields: fields,
    onPickBattlefield: onBattlefieldActivate ? onPickBattlefield : undefined,
  };

  return (
    <div className={`iso-stage${compact ? ' iso-stage--compact' : ''}`}>
      {!compact && (
        <div className="iso-stage__bar">
          {/* 보기 전환을 밖에서 쥐고 있으면(지도 페이지 머리) 여기 탭은 중복이다. */}
          {controlledView === undefined && (
            <PillTabs tabs={VIEWS} value={view} onChange={setView} label="지도 보기" />
          )}
          <PillTabs tabs={TINTS} value={tintMode} onChange={setTintMode} label="세력색" />
          <span className="text-muted">
            끌어서 이동 · 휠로 확대(郡治를 당기면 縣이 나온다) · 城을 눌러 도시로
          </span>
        </div>
      )}
      <div className="iso-stage__canvas">
        {view === 'iso3d' ? <IsoMap3D {...shared} /> : <IsoMap2D {...shared} />}
        {hover && (
          <div className="iso-tip" role="status" style={{ left: hover.x + 14, top: hover.y + 14 }}>
            <b>{hover.city.name}</b>
            <span>
              {hover.city.nationName ?? '재야'}
              {hover.city.isCapital ? ' · 수도' : ''}
            </span>
          </div>
        )}
        {fields.length > 0 && (
          <div className="iso-stage__fields" role="group" aria-label="전장 선택">
            {fields.map((field) => (
              <button
                key={field.id}
                type="button"
                aria-label={`${field.name} 전장 선택`}
                onClick={() => onPickBattlefield(field)}
              >
                ◇ {field.name}{field.current ? ' · 주둔' : ''}
              </button>
            ))}
          </div>
        )}
      </div>
      {!compact && (
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
      )}
    </div>
  );
}
