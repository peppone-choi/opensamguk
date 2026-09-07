'use client';
// 05 천하 지도(ADR-LITE-049) — 중앙 캔버스(HanMapCanvas 픽셀·좌표 불변) + 우측 360 레일.
// 레일: 선택 도시(MapCityDetail 이 /api/city/{id} 를 자체 조회) · 세력 현황(중원정보 /api/diplomacy/conflict —
// 국가·성·장수·국력·관계) · 부대(/api/troops) · 중원 정세(/api/world-log, world-log/page.tsx 와 같은 LogText 렌더).
// 원천이 없는 항목(이동 중 부대 경로·경로 미리보기·레이어 전환)은 그리지 않는다(수치 날조 금지).
import { useCallback, useEffect, useState } from 'react';
import { Chip, Flag, LogText, SectionHeader, type ChipTone, type IsoCityOverlay } from '@opensamguk/ui';
import Shell from '../../../components/Shell';
import PageHead from '../../../components/PageHead';
import MapViewer from '../../../components/game/MapViewer';
import MapCityDetail from '../../../components/game/MapCityDetail';
import GeneralName from '../../../components/game/GeneralName';
import { api } from '../../../lib/api';
import type { WorldLogResponse } from '../../../lib/api';
import type { DiplomacyConflictResponse, TroopInfo } from '../../../types/game';
import { useServerGameUrl } from '../../../lib/serverGameUrl';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';

// 외교 상태 코드 → 라벨(중원정보 /game/global-diplomacy 범례 그대로: 교전 ★ / 선포 ▲ / 통상 ㆍ / 불가침 @).
const RELATION: Record<number, { label: string; tone: ChipTone }> = {
    0: { label: '교전', tone: 'rust' },
    1: { label: '선포', tone: 'rust' },
    2: { label: '통상', tone: 'neutral' },
    7: { label: '불가침', tone: 'moss' },
};

function relationOf(conflict: DiplomacyConflictResponse, nationId: number): { label: string; tone: ChipTone } | null {
    if (conflict.myNationID === 0) return null;
    if (conflict.myNationID === nationId) return { label: '내 소속', tone: 'bronze' };
    const code = conflict.diplomacyList[conflict.myNationID]?.[nationId];
    return code == null ? null : (RELATION[code] ?? null);
}

export default function GameMapPage() {
    const cityBaseHref = useServerGameUrl('city');
    const [mapName, setMapName] = useState<string | null>(null);
    const [mapError, setMapError] = useState<string | null>(null);
    const [logData, setLogData] = useState<WorldLogResponse | null>(null);
    const [logLoading, setLogLoading] = useState(true);
    const [logError, setLogError] = useState<string | null>(null);
    const [conflict, setConflict] = useState<DiplomacyConflictResponse | null>(null);
    const [conflictError, setConflictError] = useState<string | null>(null);
    const [troops, setTroops] = useState<TroopInfo[] | null>(null);
    const [troopsError, setTroopsError] = useState<string | null>(null);
    const [selected, setSelected] = useState<IsoCityOverlay | null>(null);

    // background=true(턴 갱신)면 로딩 문구를 다시 띄우지 않는다(OPENSAM-196).
    const fetchLog = useCallback(async (background = false) => {
        if (!background) setLogLoading(true);
        try {
            const result = await api.worldLog();
            setLogData(result);
            setLogError(null);
        } catch {
            setLogError('전황 데이터를 불러올 수 없습니다.');
        } finally {
            if (!background) setLogLoading(false);
        }
    }, []);

    // 레일 원천 둘은 서로 독립 — 하나가 실패해도 다른 하나는 그린다.
    const fetchRail = useCallback(async () => {
        const [c, t] = await Promise.allSettled([api.diplomacyConflict(), api.troops()]);
        if (c.status === 'fulfilled' && c.value?.result !== false) {
            setConflict(c.value);
            setConflictError(null);
        } else {
            setConflictError('세력 현황을 불러올 수 없습니다.');
        }
        if (t.status === 'fulfilled' && t.value?.result !== false) {
            setTroops(Array.isArray(t.value.troops) ? t.value.troops : []);
            setTroopsError(null);
        } else {
            setTroopsError('부대 정보를 불러올 수 없습니다.');
        }
    }, []);

    useEffect(() => {
        fetchLog();
        fetchRail();
    }, [fetchLog, fetchRail]);

    useEffect(() => {
        let active = true;
        api.gameConst()
            .then((result) => {
                if (!active) return;
                setMapName(result.mapName);
                setMapError(null);
            })
            .catch(() => {
                if (active) setMapError('지도 설정을 확인할 수 없습니다.');
            });
        return () => { active = false; };
    }, []);

    // MapViewer는 refreshKey prop 변경 시 조용히 자체 재조회한다(리마운트 아님) — OPENSAM-196.
    const [mapRefreshKey, setMapRefreshKey] = useState(0);
    useTurnRefresh(() => {
        fetchLog(true);
        fetchRail();
        setMapRefreshKey((k) => k + 1);
    });

    const onCityPick = useCallback((city: IsoCityOverlay) => setSelected(city), []);

    const entries = logData?.entries ?? [];
    const nations = conflict?.nations ?? [];

    return (
        <Shell>
            <div className="page-wide">
                <PageHead title="세계 지도" />
                <div className="map-page">
                    <div className="map-page__stage">
                        {mapError && <p role="alert" className="map-rail__msg" style={{ color: 'var(--rust)' }}>{mapError}</p>}
                        {!mapError && mapName === null && <p className="map-rail__msg">지도 설정을 불러오는 중입니다.</p>}
                        {!mapError && mapName !== null && (
                            <MapViewer
                                live
                                showMe={1}
                                refreshKey={mapRefreshKey}
                                selectedCityId={selected?.id ?? null}
                                onCityPick={onCityPick}
                            />
                        )}
                        {/* 범례 — HanMapCanvas 가 실제로 그리는 표식만(영토 tint · 수도 점 #ffd84f · 재해 점 #b72f2f · 선택 강조). */}
                        <div className="map-legend" aria-label="범례">
                            <div className="map-legend__row"><span className="map-legend__swatch" style={{ background: 'var(--bronze)', opacity: 0.6 }} />영토 tint · 국가색</div>
                            <div className="map-legend__row"><span className="map-legend__glyph" style={{ color: '#ffd84f' }}>●</span>수도</div>
                            <div className="map-legend__row"><span className="map-legend__glyph" style={{ color: '#b72f2f' }}>●</span>재해 · 사건</div>
                            <div className="map-legend__row"><span className="map-legend__swatch" style={{ border: '1px solid var(--focus)', height: 10, width: 10 }} />선택 도시</div>
                        </div>
                    </div>
                    <aside className="map-rail" aria-label="지도 정보">
                        <p className="map-rail__hint">도시를 클릭하면 해당 도시 정보를 볼 수 있습니다.</p>
                        {selected && (
                            <section className="map-rail__city" aria-label="선택 도시">
                                <MapCityDetail
                                    city={{
                                        id: selected.id,
                                        name: selected.name,
                                        level: selected.level,
                                        nationId: selected.nationId,
                                        x: selected.x,
                                        y: selected.y,
                                        state: selected.state ?? 0,
                                        supply: selected.supply ?? true,
                                        isCapital: selected.isCapital ?? false,
                                    }}
                                    nationName={selected.nationName ?? '공 백 지'}
                                    nationColor={selected.nationColor ?? '#2c342f'}
                                    isCurrent={false}
                                    onClose={() => setSelected(null)}
                                />
                                <div className="map-rail__city-actions">
                                    <a className="os-button os-button--sm" href={`${cityBaseHref}?id=${encodeURIComponent(String(selected.id))}`}>도시 정보</a>
                                </div>
                            </section>
                        )}

                        {/* ── 세력 현황 — 중원정보(diplomacy/conflict) 국가·성·장수·국력·관계 ── */}
                        <SectionHeader title="세력 현황" sub={conflict ? `${nations.length}국` : undefined} />
                        {conflictError && <p role="alert" className="map-rail__msg" style={{ color: 'var(--rust)' }}>{conflictError}</p>}
                        {!conflictError && conflict === null && <p className="map-rail__msg">불러오는 중...</p>}
                        {conflict && nations.length === 0 && <p className="map-rail__msg">세력이 없습니다.</p>}
                        {conflict && nations.length > 0 && (
                            <div className="os-table-wrap">
                                <table className="os-table os-table--nowrap map-rail__nations">
                                    <thead>
                                        <tr>
                                            <th scope="col">국가</th>
                                            <th scope="col" className="os-num-col">성</th>
                                            <th scope="col" className="os-num-col">장수</th>
                                            <th scope="col" className="os-num-col">국력</th>
                                            <th scope="col">관계</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {nations.map((n) => {
                                            const rel = relationOf(conflict, n.nation);
                                            const mine = conflict.myNationID === n.nation;
                                            return (
                                                <tr key={n.nation} className={mine ? 'is-mine' : undefined}>
                                                    <td><span className="map-rail__nation"><Flag color={n.color} />{mine ? <b>{n.name}</b> : n.name}</span></td>
                                                    <td className="os-num os-num-col">{n.cities.length}</td>
                                                    <td className="os-num os-num-col">{n.gennum}</td>
                                                    <td className="os-num os-num-col">{n.power.toLocaleString()}</td>
                                                    <td>{rel ? <Chip tone={rel.tone}>{rel.label}</Chip> : <span className="text-muted">-</span>}</td>
                                                </tr>
                                            );
                                        })}
                                    </tbody>
                                </table>
                            </div>
                        )}

                        {/* ── 부대 — /api/troops 부대명·부대장·소재·인원(초상 원천 없음 → 아이콘 없이) ── */}
                        <SectionHeader title="부대" tone="info" sub={troops ? `${troops.length}` : undefined} />
                        {troopsError && <p role="alert" className="map-rail__msg" style={{ color: 'var(--rust)' }}>{troopsError}</p>}
                        {!troopsError && troops === null && <p className="map-rail__msg">불러오는 중...</p>}
                        {troops && troops.length === 0 && <p className="map-rail__msg">편성된 부대가 없습니다.</p>}
                        {troops && troops.map((t) => (
                            <div key={t.troopLeader} className="map-troop">
                                <div>
                                    <b>{t.name}</b>{' '}
                                    <span className="map-troop__meta">
                                        <GeneralName name={t.leaderName} npcType={t.leaderNpc} />
                                        {t.leaderCityName ? ` · 【${t.leaderCityName}】` : ''}
                                    </span>
                                </div>
                                <span className="os-num">{t.memberCount}명</span>
                            </div>
                        ))}

                        {/* ── 중원 정세 — devsam PageCachedMap.vue cachedMap.history[] 대응 ── */}
                        <SectionHeader title="중원 정세" tone="rust" sub={logData ? `${entries.length}` : undefined} />
                        <div className="map-rail__feed">
                            {logLoading && <p className="map-rail__msg">로딩 중...</p>}
                            {logError && <p role="alert" className="map-rail__msg" style={{ color: 'var(--rust)' }}>{logError}</p>}
                            {!logLoading && !logError && entries.length === 0 && <p className="map-rail__msg">기록이 없습니다.</p>}
                            {!logLoading && !logError && entries.map((item) => (
                                // text 는 서버 패러티 로그 원문(색/태그 토큰) — 연감·작전실과 같은 LogText 렌더.
                                <div key={item.id} className="map-rail__row"><LogText text={item.text} /></div>
                            ))}
                        </div>
                    </aside>
                </div>
            </div>
        </Shell>
    );
}
