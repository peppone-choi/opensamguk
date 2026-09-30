'use client';

import { useMemo, useState } from 'react';
import {
  TopdownMap,
  type HitResult,
  type MapLayers,
  type TopdownMapHandle,
  type WorldState,
} from '@opensamguk/ui/map/topdown';

const VIEWS: Record<string, 'fit' | { center: { col: number; row: number }; zoom: number }> = {
  fit: 'fit',
  luoyang: { center: { col: 1505.5, row: 933.5 }, zoom: 16 },
  yingchuan: { center: { col: 1569.5, row: 981.5 }, zoom: 16 },
  commandery: { center: { col: 1540, row: 960 }, zoom: 4 },
};

// 시험용 세력: 구역 번호로 대충 나눈다(실제 소유는 /api/map/preview provinceOccupancy)
function demoWorld(provinceCount: number): WorldState {
  const nations = [
    { id: 1, name: '조조', color: '#4f7fbf' },
    { id: 2, name: '원소', color: '#b0569a' },
    { id: 3, name: '손권', color: '#3f8f3a' },
  ];
  const occupancy = Array.from({ length: provinceCount }, (_, provinceIndex) => ({
    provinceIndex,
    nationId: provinceIndex % 7 === 0 ? 0 : 1 + (Math.floor(provinceIndex / 40) % 3),
  }));
  return { nations, occupancy };
}

export default function MapLab({ bakeUrl, kitUrl, view }: { bakeUrl: string; kitUrl: string; view: string }) {
  const [handle, setHandle] = useState<TopdownMapHandle | null>(null);
  const [hit, setHit] = useState<HitResult | null>(null);
  const [layers, setLayers] = useState<MapLayers>({ provinceLines: false, countyLines: false, commanderyLines: false, cityNames: true });
  const world = useMemo(() => demoWorld(1608), []);
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
        <output data-testid="map-lab-hit">{hit ? `${hit.kind} ${hit.id ?? ''} (${hit.cell.col}, ${hit.cell.row})` : '누른 곳 없음'}</output>
      </div>
      <TopdownMap
        source={source}
        world={world}
        layers={layers}
        initialView={VIEWS[view] ?? VIEWS.luoyang}
        onReady={setHandle}
        onSelect={setHit}
        style={{ flex: 1, minHeight: 0 }}
      />
    </main>
  );
}
