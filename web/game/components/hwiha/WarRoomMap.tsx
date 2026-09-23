'use client';

import { useMemo, useState } from 'react';
import { Chip, HanMapCanvas, Panel, SectionHeader, type CommanderyVisibility } from '@opensamguk/ui';
import { HWIHA_DIRECTIONS, commanderyOfCity, neighborInDirection } from '@/lib/hwiha-fog';
import { HWIHA_MAP_CODE, HWIHA_PROVINCES_URL, useHwihaWorldMap } from '@/lib/hwiha-map';
import { HwihaEmpty } from './HwihaStates';

/** 3×3 배치 — 가운데는 「여기」다. */
const ARROW_CELLS = ['NW', 'N', 'NE', 'W', null, 'E', 'SW', 'S', 'SE'] as const;

const ARROW_GLYPH: Record<string, string> = {
    N: '↑', NE: '↗', E: '→', SE: '↘', S: '↓', SW: '↙', W: '←', NW: '↖',
};

const VISIBILITY_LABEL: Record<CommanderyVisibility, string> = {
    FULL: '완전 시야',
    INTEL: '첩보 시야',
    FOG: '안개',
};

const VISIBILITY_TONE: Record<CommanderyVisibility, 'moss' | 'info' | 'rust'> = {
    FULL: 'moss',
    INTEL: 'info',
    FOG: 'rust',
};

export interface WarRoomMapProps {
    /** 내 장수가 선 城. 없으면(재야 이동 중 등) 첫 초점은 지도 기본값이다. */
    readonly homeCityId: number | null;
    /**
     * 군국 번호 → 시야. null 이면 안개가 없다(전부 보인다) — 휘하 규칙 월드가 아니거나 시야 조회가
     * 아직 없을 때다. 안개를 지어내지 않는다.
     */
    readonly visibility: ReadonlyMap<number, CommanderyVisibility> | null;
    /** 안개 군국에서 「첩보 보내기」를 눌렀을 때. 없으면 버튼을 보이지 않는다. */
    readonly onScout?: (commanderyNo: number) => void;
    readonly scoutPending?: boolean;
}

/**
 * 작전실 지도 — 실제 지형·구역·세력. 군국 하나가 화면을 채우는 배율로 열고, 화살표로 이웃 군국에
 * 옮긴다. 칸이 게임 단위이므로 칸 경계선과 성내 칸을 그린다.
 */
export default function WarRoomMap({ homeCityId, visibility, onScout, scoutPending }: WarRoomMapProps) {
    const map = useHwihaWorldMap();
    const [focusNo, setFocusNo] = useState<number | null>(null);

    const ready = map.kind === 'ready' ? map : null;
    const home = useMemo(() => {
        if (!ready || homeCityId == null) return undefined;
        const city = ready.preview.cities.find((c) => c.id === homeCityId);
        return commanderyOfCity(ready.commanderies, city?.commanderyName);
    }, [homeCityId, ready]);
    const focus = ready
        ? ready.commanderies.find((c) => c.no === focusNo) ?? home ?? ready.commanderies.find((c) => c.focusCityId != null)
        : undefined;
    const focusCityId = focus && home && focus.no === home.no ? homeCityId : focus?.focusCityId ?? null;
    const tier: CommanderyVisibility | null = visibility && focus ? visibility.get(focus.no) ?? 'FOG' : null;

    return (
        <Panel style={{ padding: 12 }}>
            <SectionHeader title="천하 형세" sub="구역 단위 · 보이는 만큼만" />
            {map.kind === 'loading' ? <HwihaEmpty>지도를 불러오는 중입니다.</HwihaEmpty> : null}
            {map.kind === 'error' ? <HwihaEmpty>{`지도를 불러오지 못했습니다 — ${map.message}`}</HwihaEmpty> : null}
            {map.kind === 'unsupported' ? (
                <HwihaEmpty>{`이 서버의 지도(${map.mapCode})는 휘하 지도(${HWIHA_MAP_CODE})가 아닙니다.`}</HwihaEmpty>
            ) : null}
            {ready && focus ? (
                <>
                    <div style={{ position: 'relative', marginTop: 8 }}>
                        <HanMapCanvas
                            key={focus.no}
                            mapCode={HWIHA_MAP_CODE}
                            tiles={ready.tiles}
                            tilesSha256={ready.tilesSha256}
                            provinceUrl={HWIHA_PROVINCES_URL}
                            cities={ready.cities}
                            administrativeOwnership={ready.administrativeOwnership}
                            sourceSize={ready.sourceSize}
                            markerPositions={ready.markerPositions}
                            currentCityId={focusCityId ?? undefined}
                            initialFocus="current-commandery"
                            showCellGrid
                            showCityFootprint
                            commanderyVisibility={visibility}
                            fogMode="dim"
                            politicalStyle="tint"
                            ariaLabel={`천하 형세 — ${focus.name}`}
                            style={{ width: '100%', height: 560 }}
                        />
                        <div
                            style={{
                                position: 'absolute',
                                right: 8,
                                top: 8,
                                display: 'grid',
                                gridTemplateColumns: 'repeat(3, 44px)',
                                gridTemplateRows: 'repeat(3, 44px)',
                                gap: 2,
                                background: 'rgba(12,15,14,0.72)',
                                padding: 4,
                                borderRadius: 4,
                            }}
                        >
                            {ARROW_CELLS.map((cell) => {
                                if (cell === null) {
                                    const atHome = home != null && focus.no === home.no;
                                    return (
                                        <button
                                            key="center"
                                            type="button"
                                            className="os-button os-button--ghost os-button--sm"
                                            style={{ minWidth: 0, padding: 0, fontSize: 11, color: 'var(--bronze)' }}
                                            onClick={home ? () => setFocusNo(home.no) : undefined}
                                            disabled={!home || atHome}
                                            title={home ? `내 자리 — ${home.name}` : '내 자리를 모릅니다'}
                                        >
                                            여기
                                        </button>
                                    );
                                }
                                const dir = HWIHA_DIRECTIONS.find((d) => d.key === cell)!;
                                const next = neighborInDirection(ready.commanderies, focus, dir);
                                return (
                                    <button
                                        key={dir.key}
                                        type="button"
                                        className="os-button os-button--ghost os-button--sm"
                                        style={{ minWidth: 0, padding: 0 }}
                                        onClick={next ? () => setFocusNo(next.no) : undefined}
                                        disabled={!next}
                                        aria-label={next ? `${dir.label} — ${next.name}` : `${dir.label} — 지도 끝`}
                                        title={next ? `${dir.label} — ${next.name}` : `${dir.label} — 지도 끝`}
                                    >
                                        {ARROW_GLYPH[dir.key]}
                                    </button>
                                );
                            })}
                        </div>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, paddingTop: 10, flexWrap: 'wrap' }}>
                        <span style={{ fontWeight: 700, whiteSpace: 'nowrap' }}>{focus.name}</span>
                        {home && focus.no === home.no ? <Chip tone="info">지금 여기</Chip> : null}
                        {tier ? <Chip tone={VISIBILITY_TONE[tier]}>{VISIBILITY_LABEL[tier]}</Chip> : null}
                        {tier === 'FOG' && onScout ? (
                            <button
                                type="button"
                                className="os-button os-button--primary os-button--sm"
                                onClick={() => onScout(focus.no)}
                                disabled={scoutPending}
                            >
                                첩보 보내기
                            </button>
                        ) : null}
                    </div>

                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', paddingTop: 10 }}>
                        {ready.legend.slice(0, 12).map((l) => (
                            <span
                                key={l.nationId}
                                style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 12, whiteSpace: 'nowrap' }}
                            >
                                <span
                                    aria-hidden
                                    style={{ width: 10, height: 10, borderRadius: 2, background: l.color, display: 'inline-block' }}
                                />
                                {l.name}
                                <span style={{ color: 'var(--muted)' }}>{l.cities}</span>
                            </span>
                        ))}
                        {ready.legend.length > 12 ? <Chip>{`외 ${ready.legend.length - 12}개 세력`}</Chip> : null}
                        <Chip>무주</Chip>
                    </div>
                </>
            ) : null}
        </Panel>
    );
}
