'use client';

import { useMemo, useState } from 'react';
import { Chip, WorldMapCanvas, Panel, SectionHeader, cityBadgeLabel, type CommanderyVisibility, type IsoCityOverlay } from '@opensamguk/ui';
import { commanderyOfCity } from '@/lib/hwiha-fog';
import { HWIHA_MAP_CODE, HWIHA_PROVINCES_URL, useHwihaWorldMap } from '@/lib/hwiha-map';
import { buildVisibleCorps } from '@/lib/map-corps';
import type { Corps, Sieges, Works } from '@/lib/hwiha-reads';
import { CommanderyNavigator } from './CommanderyNavigator';
import { Empty } from './GameStates';

export interface WarRoomMapProps {
    readonly refreshKey?: unknown;
    readonly homeCityId: number | null;
    readonly visibility: ReadonlyMap<number, CommanderyVisibility> | null;
    readonly onScout?: (commanderyNo: number) => void;
    readonly scoutPending?: boolean;
    readonly scoutable?: ReadonlySet<number>;
    readonly intelAge?: ReadonlyMap<number, number>;
    readonly corps?: readonly Corps[];
    readonly works?: Works | null;
    readonly sieges?: Sieges | null;
}

export default function WarRoomMap({ refreshKey = 0, homeCityId, visibility, onScout, scoutPending, scoutable,
    intelAge, corps, works, sieges }: WarRoomMapProps) {
    const map = useHwihaWorldMap(refreshKey, works, sieges);
    const [focusNo, setFocusNo] = useState<number | null>(null);
    const [hover, setHover] = useState<{ city: IsoCityOverlay; x: number; y: number } | null>(null);
    const ready = map.kind === 'ready' ? map : null;
    const home = useMemo(() => {
        if (!ready || homeCityId == null) return undefined;
        const city = ready.preview.cities.find((entry) => entry.id === homeCityId);
        return commanderyOfCity(ready.commanderies, city?.commanderyName);
    }, [homeCityId, ready]);
    const focus = ready ? ready.commanderies.find((entry) => entry.no === focusNo)
        ?? home ?? ready.commanderies.find((entry) => entry.focusCityId != null) : undefined;
    const focusCityId = focus && home && focus.no === home.no ? homeCityId : focus?.focusCityId ?? null;
    const corpsOverlay = useMemo(() => ready ? buildVisibleCorps(corps, visibility, ready.provinceCenter) : [],
        [corps, ready, visibility]);

    return <Panel style={{ padding: 12 }}>
        <SectionHeader title="천하 형세" sub="구역 단위 · 보이는 만큼만" />
        {map.kind === 'loading' ? <Empty>지도를 불러오는 중입니다.</Empty> : null}
        {map.kind === 'error' ? <Empty>{`지도를 불러오지 못했습니다 — ${map.message}`}</Empty> : null}
        {map.kind === 'unsupported' ? <Empty>{`이 서버의 지도(${map.mapCode})는 휘하 지도(${HWIHA_MAP_CODE})가 아닙니다.`}</Empty> : null}
        {ready && focus ? <>
            <div style={{ position: 'relative', marginTop: 8 }}>
                <WorldMapCanvas key={focus.no} mapCode={HWIHA_MAP_CODE} tiles={ready.tiles}
                    tilesSha256={ready.tilesSha256} provinceMap={ready.provinceMap ?? undefined}
                    provinceUrl={ready.provinceMap ? undefined : HWIHA_PROVINCES_URL}
                    corps={corpsOverlay} cities={ready.cities} administrativeOwnership={ready.administrativeOwnership}
                    sourceSize={ready.sourceSize} markerPositions={ready.markerPositions}
                    currentCityId={focusCityId ?? undefined} initialFocus="current-commandery"
                    showCellGrid showCityFootprint commanderyVisibility={visibility} fogMode="dim"
                    politicalStyle="tint" ariaLabel={`천하 형세 — ${focus.name}`}
                    onCityHover={(city, point) => setHover(city && point ? { city, x: point.x, y: point.y } : null)}
                    style={{ width: '100%', height: 560 }} />
                {hover && <div role="status" style={{ position: 'absolute', zIndex: 3, pointerEvents: 'none',
                    left: hover.x + 12, top: hover.y + 12, padding: '5px 7px', background: 'rgba(12,15,14,0.9)',
                    color: '#fff', fontSize: 12 }}>
                    <strong>{hover.city.commanderyName ? `${hover.city.commanderyName} ${hover.city.name}` : hover.city.name}</strong>
                    {hover.city.cityBadges?.map((badge, index) =>
                        <div key={`${badge.kind}-${index}`}>{cityBadgeLabel(badge)}</div>)}
                </div>}
                <CommanderyNavigator commanderies={ready.commanderies} focus={focus} home={home}
                    onFocus={setFocusNo} visibility={visibility} intelAge={intelAge}
                    scoutable={scoutable} onScout={onScout} scoutPending={scoutPending} />
            </div>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', paddingTop: 10 }}>
                {ready.legend.slice(0, 12).map((entry) => <span key={entry.nationId}
                    style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 12, whiteSpace: 'nowrap' }}>
                    <span aria-hidden style={{ width: 10, height: 10, borderRadius: 2, background: entry.color, display: 'inline-block' }} />
                    {entry.name}<span style={{ color: 'var(--muted)' }}>{entry.cities}</span>
                </span>)}
                {ready.legend.length > 12 ? <Chip>{`외 ${ready.legend.length - 12}개 세력`}</Chip> : null}
                <Chip>무주</Chip>
            </div>
        </> : null}
    </Panel>;
}
