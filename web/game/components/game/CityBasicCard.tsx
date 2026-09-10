'use client';

// CityBasicCard — 작전실 「도시」 카드(ADR-LITE-049 · 03 아트보드).
// 시안: 32px 국가색 헤더(【지역 | 등급】 도시명 + 우측 지배 국가) → 2열 게이지 8종 → 하단 태수/군사/종사 3칸.
// 데이터 계약은 종전 그대로다 — front-info.city(FrontCityInfo)가 싣는 now/max 만 그린다(날조 금지).
// 시세(trade)는 분모가 없으므로 레거시 tradeBarPercent=(trade-95)*10 을 그대로 쓰고, null 이면 막대 없이
// 「상인 없음」만 쓴다. 도시 관직은 officer_city==이 도시 AND officer_level∈{4,3,2}.

import { Gauge, Panel, SectionHeader, cityDisplayName, normaliseNationColor, rgbCss } from '@opensamguk/ui';
import GeneralName from './GeneralName';
import type { FrontCityInfo } from '@/lib/types';

// 헤더에 칠할 세력색. 지도와 **같은 함수**(normaliseNationColor)를 거친다 — DB 자유 hex 를 그대로
// 칠하면 같은 국가가 지도에선 흐린 청회색, 이 카드에선 형광 파랑(#0000ff)으로 보인다. 시안(03 아트보드)의
// 헤더 색도 #3f6fb5 처럼 눌린 값이지 순색이 아니다.
function headColors(hex?: string | null): { background: string; color: string } {
    const rgb = normaliseNationColor(hex ?? '#333333');
    const luma = 0.299 * rgb.r + 0.587 * rgb.g + 0.114 * rgb.b;
    return { background: rgbCss(rgb), color: luma >= 0.5 ? '#0f120f' : '#ece6d8' };
}

const num = (v: number): string => v.toLocaleString();

export interface CityBasicCardProps {
    city: FrontCityInfo | null;
}

export default function CityBasicCard({ city }: CityBasicCardProps) {
    if (!city) {
        return (
            <Panel className="war-card war-card--city" aria-label="도시 정보">
                <SectionHeader className="war-card__head" title="도시" />
                <div className="war-card__empty">배치된 도시가 없습니다.</div>
            </Panel>
        );
    }

    const head = headColors(city.nationColor);
    // 주인이 없으면 국가표기를 붙이지 않는다 — 공백지에 「공 백 지」라고 적지 않는다(2026-09-10).
    const nationLabel = city.nationId !== 0 ? `지배 국가 【 ${city.nationName ?? '-'} 】` : null;
    const tradePercent = city.trade != null ? Math.min(100, Math.max(0, (city.trade - 95) * 10)) : null;

    return (
        <Panel className="war-card war-card--city" aria-label="도시 정보">
            {/* 시안 03 아트보드의 32px 국가색 sec-h — 배경만 국가색이고 구조는 공유 SectionHeader 다. */}
            <SectionHeader
                className="war-card__head war-card__head--nation war-card__head-right"
                style={{ backgroundColor: head.background, color: head.color }}
                title={`【${city.regionName ? `${city.regionName} | ` : ''}${city.levelName ?? `Lv.${city.level}`}】 ${cityDisplayName({ ...city, nameCh: city.nameCh ?? undefined })}`}
                actions={nationLabel ? <span>{nationLabel}</span> : undefined}
            />

            <div className="war-card__gauges">
                <Gauge label="주민" value={city.population} max={city.populationMax} display={`${num(city.population)} / ${num(city.populationMax)}`} />
                {/* 민심(trust) — 막대는 cur/100, 텍스트는 레거시대로 단독 숫자(소수 1자리, '%' 없음). */}
                <Gauge label="민심" value={city.trust} max={100} tone="bronze" display={city.trust.toLocaleString(undefined, { maximumFractionDigits: 1 })} />
                <Gauge label="농업" value={city.agriculture} max={city.agricultureMax} display={`${num(city.agriculture)} / ${num(city.agricultureMax)}`} />
                <Gauge label="상업" value={city.commerce} max={city.commerceMax} display={`${num(city.commerce)} / ${num(city.commerceMax)}`} />
                <Gauge label="치안" value={city.security} max={city.securityMax} display={`${num(city.security)} / ${num(city.securityMax)}`} />
                <Gauge label="수비" value={city.defense} max={city.defenseMax} tone="rust" display={`${num(city.defense)} / ${num(city.defenseMax)}`} />
                <Gauge label="성벽" value={city.wall} max={city.wallMax} tone="rust" display={`${num(city.wall)} / ${num(city.wallMax)}`} />
                {tradePercent != null ? (
                    <Gauge
                        label="시세"
                        value={tradePercent}
                        max={100}
                        tone="bronze"
                        display={`${city.trade}%`}
                        // 막대는 레거시 (trade-95)*10 이라 aria-valuenow 가 화면 숫자와 다르다 — 읽어줄 값은 실제 시세다.
                        aria-valuetext={`${city.trade}%`}
                    />
                ) : (
                    // 분모가 없으므로 막대를 그리지 않는다 — 없는 최댓값을 지어내지 않는다.
                    <div className="os-gauge war-card__gauge--textonly">
                        <div className="os-gauge__top"><span>시세</span><span className="os-num">상인 없음</span></div>
                    </div>
                )}
            </div>

            <footer className="war-card__officers">
                {([['태수', 4], ['군사', 3], ['종사', 2]] as const).map(([label, lvl]) => {
                    const off = city.officers?.find((o) => o.officerLevel === lvl);
                    return (
                        <div key={label} className="war-card__officer">
                            <span className="war-card__k">{label}</span>
                            <span className="war-card__v">
                                {off ? <GeneralName name={off.name} npcType={off.npc} /> : <span className="war-card__none">-</span>}
                            </span>
                        </div>
                    );
                })}
            </footer>
        </Panel>
    );
}
