'use client';

import { useMemo, useState } from 'react';
import {
  TopdownMap,
  type HitResult,
  type MapLayers,
  type MyLocation,
  type TopdownMapHandle,
  type WorldState,
} from '@opensamguk/ui/map/topdown';

// 시험용 내 위치: 洛陽 성 안(실제 자료는 계약판 U-04)
const ME: MyLocation = { cell: { col: 1505, row: 933 }, state: 'IN_CITY', nationColor: '#4f7fbf', portrait: null, name: '하후돈' };

const VIEWS: Record<string, 'fit' | { center: { col: number; row: number }; zoom: number }> = {
  fit: 'fit',
  luoyang: { center: { col: 1505.5, row: 933.5 }, zoom: 16 },
  yingchuan: { center: { col: 1569.5, row: 981.5 }, zoom: 16 },
  commandery: { center: { col: 1540, row: 960 }, zoom: 4 },
};

// 시험용 세력: 구역 번호로 대충 나눈다(실제 소유는 /api/map/preview provinceOccupancy)
function demoWorld(provinceCount: number, pick: boolean): WorldState {
  const nations = [
    { id: 1, name: '조조', color: '#4f7fbf' },
    { id: 2, name: '원소', color: '#b0569a' },
    { id: 3, name: '손권', color: '#3f8f3a' },
  ];
  const occupancy = Array.from({ length: provinceCount }, (_, provinceIndex) => ({
    provinceIndex,
    nationId: provinceIndex % 7 === 0 ? 0 : 1 + (Math.floor(provinceIndex / 40) % 3),
  }));
  // 대상 고르기 시험: 구역 300–339를 후보로, 셋째마다 불가
  const candidates = new Map<number, boolean>();
  for (let index = 300; index < 340; index += 1) candidates.set(index, index % 3 !== 0);
  return { nations, occupancy, pick: pick ? { candidates } : undefined, selectedProvinces: pick ? new Set([313]) : undefined };
}

function initialView(view: string, center?: string, zoom?: string) {
  const [col, row] = (center ?? '').split(',').map(Number);
  if (Number.isFinite(col) && Number.isFinite(row)) return { center: { col, row }, zoom: Number(zoom) || 16 };
  return VIEWS[view] ?? VIEWS.luoyang;
}

export default function MapLab({ bakeUrl, kitUrl, view, center, zoom }: {
  bakeUrl: string; kitUrl: string; view: string; center?: string; zoom?: string;
}) {
  const [handle, setHandle] = useState<TopdownMapHandle | null>(null);
  const [hit, setHit] = useState<HitResult | null>(null);
  const [layers, setLayers] = useState<MapLayers>({ provinceLines: false, countyLines: false, commanderyLines: false, cityNames: true });
  const [pick, setPick] = useState(false);
  const [showMe, setShowMe] = useState(true);
  const world = useMemo(() => demoWorld(1608, pick), [pick]);
  const source = useMemo(() => ({ bakeUrl, kitUrl }), [bakeUrl, kitUrl]);
  const toggle = (key: keyof MapLayers) => setLayers((current) => ({ ...current, [key]: !current[key] }));
  return (
    <main style={{ height: '100vh', display: 'flex', flexDirection: 'column', background: '#0c0f0e', color: '#ece6d8' }}>
      <div style={{ display: 'flex', gap: 8, padding: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <strong>지도 시험(탑다운)</strong>
        {(['ju', 'commandery', 'county'] as const).map((level) => (
          <button key={level} type="button" style={{ minHeight: 44, minWidth: 44 }} onClick={() => handle?.setLevel(level)}>
            {{ ju: '주', commandery: '군', county: '현' }[level]}
          </button>
        ))}
        <button type="button" style={{ minHeight: 44, minWidth: 44 }} onClick={() => handle?.zoomStep(1)}>+</button>
        <button type="button" style={{ minHeight: 44, minWidth: 44 }} onClick={() => handle?.zoomStep(-1)}>−</button>
        {(['provinceLines', 'countyLines', 'commanderyLines', 'cityNames'] as const).map((key) => (
          <label key={key} style={{ display: 'inline-flex', gap: 4, alignItems: 'center', minHeight: 44 }}>
            <input type="checkbox" checked={layers[key]} onChange={() => toggle(key)} />
            {{ provinceLines: '구역 경계', countyLines: '현 경계', commanderyLines: '군 경계', cityNames: '도시 이름' }[key]}
          </label>
        ))}
        <label style={{ display: 'inline-flex', gap: 4, alignItems: 'center', minHeight: 44 }}>
          <input type="checkbox" checked={pick} onChange={() => setPick((v) => !v)} />
          대상 고르기(시험)
        </label>
        <label style={{ display: 'inline-flex', gap: 4, alignItems: 'center', minHeight: 44 }}>
          <input type="checkbox" checked={showMe} onChange={() => setShowMe((v) => !v)} />
          내 위치(시험)
        </label>
        <button type="button" style={{ minHeight: 44 }} onClick={() => handle?.centerOn(ME.cell, 16)}>내 위치로</button>
        <output data-testid="map-lab-hit">{hit ? `${hit.kind} ${hit.id ?? ''} (${hit.cell.col}, ${hit.cell.row})` : '누른 곳 없음'}</output>
      </div>
      <TopdownMap
        source={source}
        world={world}
        layers={layers}
        initialView={initialView(view, center, zoom)}
        onReady={setHandle}
        onSelect={setHit}
        me={showMe ? ME : null}
        minimap
        style={{ flex: 1, minHeight: 0 }}
      />
    </main>
  );
}
