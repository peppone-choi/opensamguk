'use client';

import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Chip, WorldMapCanvas, Panel, SectionHeader, cityBadgeLabel, commanderyCells, type CommanderyVisibility, type IsoCityOverlay, safeNationColor } from '@opensamguk/ui';
import { bakeCommanderyAnchors, loadBakePlaces, loadBakeProvinceCenters, topdownScreensEnabled, topdownSourceFor, type CellPoint, type MapLayerPanel, type TopdownMapHandle, type TopdownSource } from '@opensamguk/ui/map/topdown';
import { commanderyOfCity } from '@/lib/campaign-fog';
import { CAMPAIGN_MAP_CODE, CAMPAIGN_PROVINCES_URL, useCampaignWorldMap } from '@/lib/campaign-map';
import { buildVisibleCorps, toTopdownCorps } from '@/lib/map-corps';
import { useSupplyLines } from '@/lib/use-supply-lines';
import type { Corps, Sieges, Works } from '@/lib/campaign-reads';
import { CommanderyNavigator } from './CommanderyNavigator';
import { Empty } from './GameStates';
import WarRoomTopdownMap, { type WarRoomMapPick, type WarRoomMyGeneral } from './WarRoomTopdownMap';
import type { MapPreviewCity, MapPreviewNation } from '@/lib/types';
import type { WarRoomMapView } from '@/lib/war-room-map-view';

/** 새 지도에서 고른 城 + 그 城의 미리보기 행 · 세력 목록(틀이 지도 미리보기를 다시 읽지 않게, 선택 카드 K4). */
export interface WarRoomPick extends WarRoomMapPick {
    readonly city: MapPreviewCity;
    readonly nations: readonly MapPreviewNation[];
    /** 그 城이 든 구역의 서버 id(미리보기 provinceOccupancy) — 군단 자리(Corps.provinceId)와 같은 id. 모르면 null. */
    readonly provinceRecordId: string | null;
}

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
    /** 주소로 연 보기(`?view=…&focus=…`, `parseWarRoomMapView`). 새 지도만 듣는다. */
    readonly mapView?: WarRoomMapView;
    /**
     * 부모 상자를 채운다(작전실 재배치 P-W01, K4 — 보드 V31K4WarRoom 지도가 화면 전부). 패널 · 「천하 형세」 머리 · 밑 범례 줄을 그리지 않는다
     * (범례는 새 지도의 범례 단추). 안 넘기면 지금 그대로(패널 · 높이 560).
     */
    readonly fill?: boolean;
    /** 고른 城을 틀이 쥘 때(작전실 선택 카드, K4) — 새 지도만 듣는다. 미리보기에 없는 城은 고르지 않는다(null). */
    readonly pickedCityId?: number | null;
    readonly onPick?: (pick: WarRoomPick | null) => void;
}

/** bake 구역 대표 칸(군단 자리). 새 지도가 아니거나 아직 못 받았으면 null — 그동안 군단을 싣지 않는다. */
function useBakeProvinceCenters(source: TopdownSource | null): readonly (CellPoint | null)[] | null {
    const [loaded, setLoaded] = useState<{ source: TopdownSource; centers: readonly (CellPoint | null)[] } | null>(null);
    useEffect(() => {
        if (!source) return undefined;
        let live = true;
        loadBakeProvinceCenters(source).then(
            (centers) => { if (live) setLoaded({ source, centers }); },
            (error: unknown) => console.warn('[작전실 새 지도] 구역 대표 칸', error),
        );
        return () => { live = false; };
    }, [source]);
    return loaded && loaded.source === source ? loaded.centers : null;
}

type CommanderyAnchors = ReturnType<typeof bakeCommanderyAnchors>;

/**
 * 새 지도의 군국 표 재료: bake 장소 표의 군국 이름 · 대표 칸(지도와 같은 캐시라 요청이 늘지 않는다).
 * 새 지도는 옛 지형을 받지 않으니(미리보기만) 군국 표도 여기서 만든다. 못 받으면 null — 시야 · 첩보 줄만 빠진다(지도는 스스로 알린다).
 */
function useBakeCommanderyAnchors(source: TopdownSource | null): CommanderyAnchors | null {
    const [loaded, setLoaded] = useState<{ source: TopdownSource; anchors: CommanderyAnchors } | null>(null);
    useEffect(() => {
        if (!source) return undefined;
        let live = true;
        loadBakePlaces(source).then(
            (places) => { if (live) setLoaded({ source, anchors: bakeCommanderyAnchors(places) }); },
            (error: unknown) => console.warn('[작전실 새 지도] 군국 표', error),
        );
        return () => { live = false; };
    }, [source]);
    return loaded && loaded.source === source ? loaded.anchors : null;
}

export default function WarRoomMap({ refreshKey = 0, homeCityId, visibility, onScout, scoutPending, scoutable,
    intelAge, corps, works, sieges, onMapHandle, layerPanel, onLayerPanelChange, myGeneral, myLocationInset, mapView, fill = false, pickedCityId, onPick }: WarRoomMapProps) {
    const map = useCampaignWorldMap(refreshKey, works, sieges);
    const [focusNo, setFocusNo] = useState<number | null>(null);
    const [hover, setHover] = useState<{ city: IsoCityOverlay; x: number; y: number } | null>(null);
    // 옛 지도판은 'ready'(지형까지), 새 지도는 'preview'(미리보기만 — useCampaignWorldMap이 같은 규칙으로 멈춘다)
    const ready = map.kind === 'ready' ? map : null;
    const shown = map.kind === 'ready' || map.kind === 'preview' ? map : null;
    const preview = shown?.preview ?? null;
    // 새 지도는 교체 스위치가 켜져 있고 서버가 bakeId를 줄 때만(둘 중 하나라도 없으면 옛 지도 그대로)
    const bakeId = preview?.topdownBakeId;
    const topdown = useMemo(() => (topdownScreensEnabled() ? topdownSourceFor(bakeId) : null), [bakeId]);
    const bakeAnchors = useBakeCommanderyAnchors(topdown);
    const commanderies = useMemo(() => {
        if (!topdown) return ready?.commanderies ?? null;
        return preview && bakeAnchors ? commanderyCells(bakeAnchors, preview) : null;
    }, [bakeAnchors, preview, ready, topdown]);
    const home = useMemo(() => {
        if (!preview || !commanderies || homeCityId == null) return undefined;
        const city = preview.cities.find((entry) => entry.id === homeCityId);
        return commanderyOfCity(commanderies, city?.commanderyName);
    }, [commanderies, homeCityId, preview]);
    const focus = commanderies ? commanderies.find((entry) => entry.no === focusNo)
        ?? home ?? commanderies.find((entry) => entry.focusCityId != null) : undefined;
    // 새 지도는 장소 표(군국 표)를 기다리지 않고 미리보기에 있는 내 城을 초점으로 연다 — 늦은 장소 표 규칙은 지도가 지킨다
    const homeInPreview = homeCityId != null && preview != null && preview.cities.some((entry) => entry.id === homeCityId);
    const focusCityId = (topdown && homeInPreview) || (focus && home && focus.no === home.no) ? homeCityId : focus?.focusCityId ?? null;
    const corpsOverlay = useMemo(() => ready ? buildVisibleCorps(corps, visibility, ready.provinceCenter) : [],
        [corps, ready, visibility]);
    // 새 지도의 군단 자리는 bake 개관 격자의 구역 대표 칸이다. 옛 省 식별 PNG(ready.provinceCenter)는 운영에서
    // 24.7MB라 16MiB 상한으로 버려져 군단이 하나도 서지 못했다. 서버 구역 id → bake 구역 번호는 미리보기 provinceOccupancy가 잇는다.
    const bakeCenters = useBakeProvinceCenters(topdown);
    // 보급선 층(K4-06) — 새 지도를 그릴 때만 창고 연결을 읽는다(옛 지도에는 그 층이 없다)
    const supply = useSupplyLines(topdown != null, refreshKey);
    const topdownCorps = useMemo(() => {
        if (!preview || !bakeCenters) return [];
        const indexById = new Map((preview.provinceOccupancy ?? []).map((entry) => [entry.provinceRecordId, entry.provinceIndex]));
        const center = (provinceId: string) => {
            const index = indexById.get(provinceId);
            return index == null ? undefined : bakeCenters[index] ?? undefined;
        };
        return toTopdownCorps(buildVisibleCorps(corps, visibility, center), new Map((corps ?? []).map((row) => [row.corpsId, row.ageTurns])));
    }, [bakeCenters, corps, preview, visibility]);
    // 새 지도는 미리보기만 받는다(#1231) — 고른 城의 행 · 세력 · 구역 id 도 미리보기에서
    const pick = onPick && preview ? (next: WarRoomMapPick | null) => {
        const city = next ? preview.cities.find((entry) => entry.id === next.cityId) : undefined;
        if (!next || !city) { onPick(null); return; }
        const province = city.provinceId == null ? undefined
            : (preview.provinceOccupancy ?? []).find((entry) => entry.provinceIndex === city.provinceId);
        onPick({ ...next, city, nations: preview.nations, provinceRecordId: province?.provinceRecordId ?? null });
    } : undefined;
    // 서버 원문(영어 · 상태 코드)과 지도 코드는 화면에 싣지 않고 콘솔에만 남긴다
    const errorDetail = map.kind === 'error' ? map.message : map.kind === 'unsupported' ? `mapCode=${map.mapCode}` : null;
    useEffect(() => {
        if (errorDetail) console.warn('[작전실 지도]', errorDetail);
    }, [errorDetail]);

    const Frame = fill ? FillFrame : PanelFrame;
    // 꽉 찬 지도(작전실)에서는 상태 한 줄을 가운데에 둔다 — 위쪽은 칩 줄(모바일 머리줄 · 지난 순 · 층 실패)이 떠 있어 가린다(보드 V31K4MWarRoom).
    const State = fill ? FillState : Empty;
    return <Frame>
        {map.kind === 'loading' ? <State>지도를 불러오는 중입니다.</State> : null}
        {map.kind === 'error' ? <State>지도를 불러오지 못했습니다. 잠시 뒤 다시 열어 주세요.</State> : null}
        {map.kind === 'unsupported' ? <State>이 서버 지도는 아직 작전실에서 열 수 없습니다.</State> : null}
        {shown && (topdown || focus) ? <>
            <div style={{ position: 'relative', ...(fill ? { height: '100%' } : { marginTop: 8 }) }}>
                {topdown ? <WarRoomTopdownMap source={topdown} preview={shown.preview} homeCityId={homeCityId}
                    focusCityId={focusCityId} ariaLabel={focus ? `천하 형세 — ${focus.name}` : '천하 형세'} legend={shown.legend} onMapHandle={onMapHandle}
                    layerPanel={layerPanel} onLayerPanelChange={onLayerPanelChange} corps={topdownCorps} visibility={visibility} supply={supply}
                    myGeneral={myGeneral} myLocationInset={myLocationInset} initialView={mapView} fill={fill}
                    pickedCityId={pick ? pickedCityId ?? null : undefined} onPick={pick} /> : ready && focus ? <WorldMapCanvas key={focus.no} mapCode={CAMPAIGN_MAP_CODE} tiles={ready.tiles}
                    tilesSha256={ready.tilesSha256} provinceMap={ready.provinceMap ?? undefined}
                    provinceUrl={ready.provinceMap ? undefined : CAMPAIGN_PROVINCES_URL}
                    corps={corpsOverlay} cities={ready.cities} administrativeOwnership={ready.administrativeOwnership}
                    sourceSize={ready.sourceSize} markerPositions={ready.markerPositions}
                    currentCityId={homeCityId ?? undefined} cameraFocusCityId={focusCityId ?? undefined}
                    initialFocus="current-commandery"
                    showCellGrid showCityFootprint commanderyVisibility={visibility} fogMode="dim"
                    politicalStyle="tint" ariaLabel={`천하 형세 — ${focus.name}`}
                    onCityHover={(city, point) => setHover(city && point ? { city, x: point.x, y: point.y } : null)}
                    style={{ width: '100%', height: fill ? '100%' : 560 }} /> : null}
                {hover && <div role="status" style={{ position: 'absolute', zIndex: 3, pointerEvents: 'none',
                    left: hover.x + 12, top: hover.y + 12, padding: '5px 7px', background: 'rgba(12,15,14,0.9)',
                    color: '#fff', fontSize: 12 }}>
                    <strong>{hover.city.commanderyName ? `${hover.city.commanderyName} ${hover.city.name}` : hover.city.name}</strong>
                    {hover.city.cityBadges?.map((badge, index) =>
                        <div key={`${badge.kind}-${index}`}>{cityBadgeLabel(badge)}</div>)}
                </div>}
                {/* 새 지도는 자유 끌기 · 「내 위치로」가 郡 화살표를 대신한다 — 화살표 칸은 옛 지도에만, 시야 · 첩보 줄은 둘 다(fill 이면 지도 위 겹층) */}
                {commanderies && focus ? <CommanderyNavigator commanderies={commanderies} focus={focus} home={home}
                    onFocus={setFocusNo} visibility={visibility} intelAge={intelAge}
                    scoutable={scoutable} onScout={onScout} scoutPending={scoutPending} arrows={!topdown}
                    // fill 상자는 지도가 높이를 다 쓴다 — 정보 줄(시야 · 「첩보 보내기」)을 흐름 배치로 두면 상자 밖으로 밀려 잘린다(#1232 리뷰). 지도 위 겹층으로
                    overlayInfo={fill} /> : null}
            </div>
            {fill ? null : <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', paddingTop: 10 }}>
                {shown.legend.slice(0, 12).map((entry) => <span key={entry.nationId}
                    style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 12, whiteSpace: 'nowrap' }}>
                    <span aria-hidden style={{ width: 10, height: 10, borderRadius: 2, background: safeNationColor(entry.color), display: 'inline-block' }} />
                    {entry.name}<span style={{ color: 'var(--muted)' }}>{entry.cities}</span>
                </span>)}
                {shown.legend.length > 12 ? <Chip>{`외 ${shown.legend.length - 12}개 세력`}</Chip> : null}
                <Chip>무주</Chip>
            </div>}
        </> : null}
    </Frame>;
}

function PanelFrame({ children }: { readonly children: ReactNode }) {
    return <Panel style={{ padding: 12 }}><SectionHeader title="천하 형세" sub="구역 단위 · 보이는 만큼만" />{children}</Panel>;
}

/** 꽉 찬 지도의 상태 한 줄 — 지도 가운데(위 칩 줄 · 아래 엿보기 시트를 피한다). 누르기는 지도로 지나간다. */
function FillState({ children }: { readonly children: ReactNode }) {
    return (
        <div data-map-state style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16, pointerEvents: 'none' }}>
            <Empty>{children}</Empty>
        </div>
    );
}

/** 작전실 지도 상자 — 부모 높이를 채우고, 불러오는 중 · 실패 문구는 그 상자 안에 둔다. */
function FillFrame({ children }: { readonly children: ReactNode }) {
    return <div data-testid="war-room-map-fill" style={{ position: 'relative', height: '100%', background: 'var(--inset)' }}>{children}</div>;
}
