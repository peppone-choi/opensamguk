'use client';

// MapCityDetail — the click-detail panel for a map city (spec §6.3 CityBasicCard + legacy
// MapCityDetail.vue). Opens when MapViewer selects a city. Header mirrors CityBasicCard.vue verbatim:
// the city panel `【Region | Level】 City Name` (region omitted gracefully — the map snapshot carries
// no region) and the nation panel `지배 국가 【 Nation 】` / `공 백 지` (nation-colored bg, auto text
// color by brightness). Metrics use the VERBATIM CityBasicCard labels — 주민 / 민심 / 농업 / 상업 / 치안
// / 수비 / 성벽 / 시세 — each cur/max with a bar.
//
// Data sourcing (no fabrication): the map snapshot (MapPreviewCity) only carries {id,name,level,
// nationId}. Full metrics come from the game-api city read (api.city(id)). game-api has no /api/city/{id}
// route yet, so that call may 404 — we catch it and render the header + "상세 정보 없음" gracefully
// (NEVER crash, NEVER fabricate stats). If the parent already has the current city's full stats from
// front-info, it can pass them via `cityDetail` to skip the fetch.

import { useEffect, useState } from 'react';
import { api } from '@/lib/api';
import { formatNumber } from '@/lib/format';
import type { FrontCityInfo, MapPreviewCity } from '@/lib/types';

// Level → 치소 등급 label (legacy cityConstMap.level). lv 4 = 이민족 전용 "이"; 한족 군 치소는 lv 5 "소".
const LEVEL_TEXT: Record<number, string> = {
    1: '진',
    2: '관',
    3: '촌',
    4: '이',
    5: '소',
    6: '중',
    7: '대',
    8: '특',
};

function levelText(level: number): string {
    return LEVEL_TEXT[level] ?? String(level);
}

// isBrightColor (legacy util) — pick black text on a bright nation color, white otherwise.
function isBrightColor(hex: string): boolean {
    const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
    if (!m) return false;
    const v = parseInt(m[1], 16);
    const r = (v >> 16) & 0xff;
    const g = (v >> 8) & 0xff;
    const b = v & 0xff;
    // perceived luminance (Rec. 601)
    return (r * 299 + g * 587 + b * 114) / 1000 >= 128;
}

interface Metric {
    label: string;
    cur: number;
    max?: number;
}

function MetricRow({ m }: { m: Metric }) {
    const pct = m.max && m.max > 0 ? Math.min(100, Math.max(0, (m.cur / m.max) * 100)) : 0;
    return (
        <div className="mcd-metric">
            <div className="mcd-metric-head">{m.label}</div>
            <div className="mcd-metric-body">
                <div className="mcd-bar">
                    <div className="mcd-bar-fill" style={{ width: `${pct}%` }} />
                </div>
                <div className="mcd-metric-text">
                    {m.max != null
                        ? `${formatNumber(m.cur)} / ${formatNumber(m.max)}`
                        : formatNumber(m.cur)}
                </div>
            </div>
        </div>
    );
}

export interface MapCityDetailProps {
    city: MapPreviewCity;
    nationName: string;
    nationColor: string;
    isCurrent: boolean;
    /** Optional full stats (e.g. front-info city for the current city) — skips the api.city fetch. */
    cityDetail?: FrontCityInfo | null;
    onClose?: () => void;
}

export default function MapCityDetail({
    city,
    nationName,
    nationColor,
    isCurrent,
    cityDetail,
    onClose,
}: MapCityDetailProps) {
    const [detail, setDetail] = useState<FrontCityInfo | null>(cityDetail ?? null);
    const [loading, setLoading] = useState(false);
    // unavailable = the read endpoint returned nothing (graceful: header-only render, no fabrication).
    const [unavailable, setUnavailable] = useState(false);

    useEffect(() => {
        // Provided directly → use it. Otherwise attempt the read; tolerate 404 (route may not exist yet).
        if (cityDetail) {
            setDetail(cityDetail);
            setUnavailable(false);
            return;
        }
        let on = true;
        setDetail(null);
        setUnavailable(false);
        setLoading(true);
        api
            .city<FrontCityInfo>(city.id)
            .then((d) => {
                if (on) setDetail(d);
            })
            .catch(() => {
                if (on) setUnavailable(true);
            })
            .finally(() => {
                if (on) setLoading(false);
            });
        return () => {
            on = false;
        };
    }, [city.id, cityDetail]);

    const headerTextColor = isBrightColor(nationColor) ? 'black' : 'white';

    // Build the metric list from whatever the read returned — OMIT any field that is absent (no fabrication).
    const metrics: Metric[] = [];
    if (detail) {
        metrics.push({ label: '주민', cur: detail.population, max: detail.populationMax });
        metrics.push({ label: '민심', cur: detail.trust }); // % (decimal); no max
        metrics.push({ label: '농업', cur: detail.agriculture, max: detail.agricultureMax });
        metrics.push({ label: '상업', cur: detail.commerce, max: detail.commerceMax });
        metrics.push({ label: '치안', cur: detail.security, max: detail.securityMax });
        metrics.push({ label: '수비', cur: detail.defense, max: detail.defenseMax });
        metrics.push({ label: '성벽', cur: detail.wall, max: detail.wallMax });
    }

    return (
        <div className="mcd" aria-label={`${city.name} 도시 정보`}>
            <div className="mcd-header" style={{ backgroundColor: nationColor, color: headerTextColor }}>
                <div className="mcd-city-name">
                    {`【${levelText(city.level)}】 ${city.name}`}
                    {isCurrent && <span className="mcd-current-tag"> · 현재 도시</span>}
                </div>
                {onClose && (
                    <button
                        type="button"
                        className="mcd-close"
                        style={{ color: headerTextColor }}
                        aria-label="닫기"
                        onClick={onClose}
                    >
                        ✕
                    </button>
                )}
            </div>

            <div className="mcd-nation" style={{ backgroundColor: nationColor, color: headerTextColor }}>
                {city.nationId > 0 ? `지배 국가 【 ${nationName} 】` : '공 백 지'}
            </div>

            {loading && (
                <div className="mcd-empty">
                    <div className="spinner" />
                </div>
            )}

            {!loading && detail && (
                <div className="mcd-metrics">
                    {metrics.map((m) => (
                        <MetricRow key={m.label} m={m} />
                    ))}
                    {/* 시세 (trade %) — `상인 없음` when null, never fabricated. */}
                    <div className="mcd-metric">
                        <div className="mcd-metric-head">시세</div>
                        <div className="mcd-metric-body">
                            <div className="mcd-metric-text">
                                {detail.trade != null ? `${detail.trade}%` : '상인 없음'}
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {!loading && !detail && (
                <div className="mcd-empty">
                    {unavailable ? '상세 정보 없음' : '도시 상세 정보를 불러올 수 없습니다.'}
                </div>
            )}
        </div>
    );
}
