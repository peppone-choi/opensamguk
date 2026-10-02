'use client';

import { useEffect, useMemo, useState } from 'react';
import { Chip, WorldMapCanvas, Panel, SectionHeader, cityBadgeLabel, type CommanderyVisibility, type IsoCityOverlay } from '@opensamguk/ui';
import { topdownScreensEnabled, topdownSourceFor, type MapLayerPanel, type TopdownMapHandle } from '@opensamguk/ui/map/topdown';
import { commanderyOfCity } from '@/lib/campaign-fog';
import { CAMPAIGN_MAP_CODE, CAMPAIGN_PROVINCES_URL, useCampaignWorldMap } from '@/lib/campaign-map';
import { buildVisibleCorps, toTopdownCorps } from '@/lib/map-corps';
import type { Corps, Sieges, Works } from '@/lib/campaign-reads';
import { CommanderyNavigator } from './CommanderyNavigator';
import { Empty } from './GameStates';
import WarRoomTopdownMap, { type WarRoomMyGeneral } from './WarRoomTopdownMap';

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
    /**
     * 새 지도(탑다운) handle — 화면 틀(K4)이 城 목록 · 검색에서 고르면 `focusCity(id)`로 지도를 그 城으로 옮기고 고른다.
     * 옛 지도이거나 새 지도가 아직 없으면 null.
     */
    readonly onMapHandle?: (handle: TopdownMapHandle | null) => void;
    /** 새 지도의 레이어 · 범례 판을 틀이 쥘 때(K4 하단 시트와 하나만 열기). 안 넘기면 지도가 스스로 연다. */
    readonly layerPanel?: MapLayerPanel | null;
    readonly onLayerPanelChange?: (open: MapLayerPanel | null) => void;
    /** 내 장수(새 지도 내 위치 표지의 초상 · 링). 장수가 없으면 넘기지 않는다. */
    readonly myGeneral?: WarRoomMyGeneral;
    /** 화면 틀이 지도를 덮은 폭(서랍 · 하단 시트, K4). */
    readonly myLocationInset?: { readonly left?: number; readonly bottom?: number };
}

export default function WarRoomMap({ refreshKey = 0, homeCityId, visibility, onScout, scoutPending, scoutable,
    intelAge, corps, works, sieges, onMapHandle, layerPanel, onLayerPanelChange, myGeneral, myLocationInset }: WarRoomMapProps) {
    const map = useCampaignWorldMap(refreshKey, works, sieges);
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
    const topdownCorps = useMemo(() => toTopdownCorps(corpsOverlay, new Map((corps ?? []).map((row) => [row.corpsId, row.ageTurns]))),
        [corpsOverlay, corps]);
    // 새 지도는 교체 스위치가 켜져 있고 서버가 bakeId를 줄 때만(둘 중 하나라도 없으면 옛 지도 그대로)
    const bakeId = ready?.preview.topdownBakeId;
    const topdown = useMemo(() => (topdownScreensEnabled() ? topdownSourceFor(bakeId) : null), [bakeId]);
    // 서버 원문(영어 · 상태 코드)과 지도 코드는 화면에 싣지 않고 콘솔에만 남긴다
    const errorDetail = map.kind === 'error' ? map.message : map.kind === 'unsupported' ? `mapCode=${map.mapCode}` : null;
    useEffect(() => {
        if (errorDetail) console.warn('[작전실 지도]', errorDetail);
    }, [errorDetail]);

    return <Panel style={{ padding: 12 }}>
        <SectionHeader title="천하 형세" sub="구역 단위 · 보이는 만큼만" />
        {map.kind === 'loading' ? <Empty>지도를 불러오는 중입니다.</Empty> : null}
        {map.kind === 'error' ? <Empty>지도를 불러오지 못했습니다. 잠시 뒤 다시 열어 주세요.</Empty> : null}
        {map.kind === 'unsupported' ? <Empty>이 서버 지도는 아직 작전실에서 열 수 없습니다.</Empty> : null}
        {ready && focus ? <>
            <div style={{ position: 'relative', marginTop: 8 }}>
                {topdown ? <WarRoomTopdownMap source={topdown} preview={ready.preview} homeCityId={homeCityId}
                    focusCityId={focusCityId} ariaLabel={`천하 형세 — ${focus.name}`} legend={ready.legend} onMapHandle={onMapHandle}
                    layerPanel={layerPanel} onLayerPanelChange={onLayerPanelChange} corps={topdownCorps}
                    myGeneral={myGeneral} myLocationInset={myLocationInset} /> : <WorldMapCanvas key={focus.no} mapCode={CAMPAIGN_MAP_CODE} tiles={ready.tiles}
                    tilesSha256={ready.tilesSha256} provinceMap={ready.provinceMap ?? undefined}
                    provinceUrl={ready.provinceMap ? undefined : CAMPAIGN_PROVINCES_URL}
                    corps={corpsOverlay} cities={ready.cities} administrativeOwnership={ready.administrativeOwnership}
                    sourceSize={ready.sourceSize} markerPositions={ready.markerPositions}
                    currentCityId={homeCityId ?? undefined} cameraFocusCityId={focusCityId ?? undefined}
                    initialFocus="current-commandery"
                    showCellGrid showCityFootprint commanderyVisibility={visibility} fogMode="dim"
                    politicalStyle="tint" ariaLabel={`천하 형세 — ${focus.name}`}
                    onCityHover={(city, point) => setHover(city && point ? { city, x: point.x, y: point.y } : null)}
                    style={{ width: '100%', height: 560 }} />}
                {hover && <div role="status" style={{ position: 'absolute', zIndex: 3, pointerEvents: 'none',
                    left: hover.x + 12, top: hover.y + 12, padding: '5px 7px', background: 'rgba(12,15,14,0.9)',
                    color: '#fff', fontSize: 12 }}>
                    <strong>{hover.city.commanderyName ? `${hover.city.commanderyName} ${hover.city.name}` : hover.city.name}</strong>
                    {hover.city.cityBadges?.map((badge, index) =>
                        <div key={`${badge.kind}-${index}`}>{cityBadgeLabel(badge)}</div>)}
                </div>}
                {/* 새 지도는 자유 끌기 · 「내 위치로」가 郡 화살표를 대신한다 — 화살표 칸은 옛 지도에만, 시야 · 첩보 줄은 둘 다 */}
                <CommanderyNavigator commanderies={ready.commanderies} focus={focus} home={home}
                    onFocus={setFocusNo} visibility={visibility} intelAge={intelAge}
                    scoutable={scoutable} onScout={onScout} scoutPending={scoutPending} arrows={!topdown} />
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
