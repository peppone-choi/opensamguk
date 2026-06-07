'use client';

// CityBasicCard — 플레이어 소재 도시 정보 카드(레거시 CityBasicCard.vue 충실 이식).
// 레거시는 cityNamePanel(국가색, 【지역 | 등급】 도시명) + nationNamePanel(지배 국가/공백지) + 주민/민심/
// 농업/상업/치안/수비/성벽/시세 게이지 + 태수/군사/종사 칸을 그린다. web/game 의 front-info.city
// (FrontCityInfo)가 싣는 now/max 수치만 게이지로 렌더한다(날조 금지). 게이지는 기존 Gauge 컴포넌트
// (레거시 SammoBar 동치) 재사용.
//
// 렌더 필드(API 보유): name + level(등급) + 지배국(nationId↔player nation 매칭 시 국가명/색, 아니면
// 공백지) + 주민/농업/상업/치안/수비/성벽(now/max Gauge) + 민심(trust, barOnly) + 시세(trade % 막대).
//
// 등급(level)=getCityLevelList()[level] 한글명(수/진/관/이/소/중/대/특), 지역(region)=CityConst.regionMap[region]
// 한글명(하북/중원/…/동이)을 서버가 levelName/regionName 으로 해석해 내려준다. 도시 관직(태수4/군사3/종사2)은
// front-info.city.officers(officer_city==이 도시 AND officer_level∈{4,3,2}, PHP officerList GetFrontInfo:504).

import Gauge from './Gauge';
import type { FrontCityInfo, FrontNationInfo } from '@/lib/types';

// NPC 이름 색상 — 레거시 hwe/ts/utilGame/getNPCColor.ts verbatim 포팅(npc_state → CSS color).
function getNPCColor(npc: number): string | undefined {
    if (npc === 6) return 'mediumaquamarine';
    if (npc === 5) return 'darkcyan';
    if (npc === 4) return 'deepskyblue';
    if (npc >= 2) return 'cyan';
    if (npc === 1) return 'skyblue';
    return undefined;
}

function isBrightColor(hex?: string): boolean {
    if (!hex) return false;
    const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
    if (!m) return false;
    const v = parseInt(m[1], 16);
    const r = (v >> 16) & 0xff;
    const g = (v >> 8) & 0xff;
    const b = v & 0xff;
    return (r * 299 + g * 587 + b * 114) / 1000 >= 128;
}

export interface CityBasicCardProps {
    city: FrontCityInfo | null;
    /** 플레이어 국가 — city.nationId 와 일치할 때만 헤더 국가색/국가명에 사용(레거시 nationInfo 패러티). */
    nation: FrontNationInfo | null;
}

export default function CityBasicCard({ city, nation }: CityBasicCardProps) {
    if (!city) {
        return (
            <section className="basic-card city-basic-card ib-city" aria-label="도시 정보">
                <div className="basic-card-name" style={{ backgroundColor: '#333333', color: '#fff' }}>
                    도시
                </div>
                <div className="mcd-empty">배치된 도시가 없습니다.</div>
            </section>
        );
    }

    // 지배국 — city.nationId 가 player nation 과 같으면 그 색/이름, 아니면 공백지(레거시 `!nationInfo.id`).
    const sameNation = nation != null && nation.id === city.nationId && city.nationId !== 0;
    const cityNationColor = sameNation ? nation!.color : '#333333';
    const cityHeaderText = isBrightColor(cityNationColor) ? '#000' : '#fff';
    const nationLabel = sameNation ? `지배 국가 【 ${nation!.name} 】` : city.nationId !== 0 ? '지배 국가' : '공 백 지';

    return (
        <section className="basic-card city-basic-card ib-city" aria-label="도시 정보">
            {/* cityNamePanel — 레거시 【지역 | 등급】 도시명(CityBasicCard.vue:10). 지역/등급 모두 서버 해석
                한글명(regionName=CityConst.regionMap[region], levelName=getCityLevelList()[level]). 부재 시 폴백. */}
            <div className="basic-card-name" style={{ backgroundColor: cityNationColor, color: cityHeaderText }}>
                【{city.regionName ? `${city.regionName} | ` : ''}{city.levelName ?? `Lv.${city.level}`}】 {city.name}
            </div>
            {/* nationNamePanel — 지배 국가/공백지. */}
            <div className="basic-card-name" style={{ backgroundColor: cityNationColor, color: cityHeaderText }}>
                {nationLabel}
            </div>
            <div className="gauge-metrics">
                <Gauge label="주민" now={city.population} max={city.populationMax} />
                {/* 민심(trust) — 막대 cur/100, 텍스트는 레거시대로 단독 숫자(소수 1자리, '%' 없음). */}
                <Gauge label="민심" now={city.trust} max={100} barOnly />
                <Gauge label="농업" now={city.agriculture} max={city.agricultureMax} />
                <Gauge label="상업" now={city.commerce} max={city.commerceMax} />
                <Gauge label="치안" now={city.security} max={city.securityMax} />
                <Gauge label="수비" now={city.defense} max={city.defenseMax} />
                <Gauge label="성벽" now={city.wall} max={city.wallMax} />
                {/* 시세(trade %) — 레거시 tradeBarPercent=(trade-95)*10(0..100 클램프). trade==null(상인 없음)이면
                    막대 없이 텍스트만(분모 없는 단독 표시, 날조 금지). */}
                <div className="mcd-metric gauge-metric">
                    <div className="mcd-metric-head">시세</div>
                    <div className="mcd-metric-body">
                        {city.trade != null && (
                            <div className="mcd-bar">
                                <div
                                    className="mcd-bar-fill"
                                    style={{ width: `${Math.min(100, Math.max(0, (city.trade - 95) * 10))}%` }}
                                />
                            </div>
                        )}
                        <div className="mcd-metric-text">{city.trade != null ? `${city.trade}%` : '상인 없음'}</div>
                    </div>
                </div>
                {/* 도시 관직(태수4/군사3/종사2) — 레거시 CityBasicCard.vue officerList[4/3/2]. 이름색=getNPCColor(npc),
                    빈 슬롯은 '-'(officerList[N]?.name ?? '-'). officers=officer_city==이 도시 AND officer_level∈{4,3,2}. */}
                {([['태수', 4], ['군사', 3], ['종사', 2]] as const).map(([label, lvl]) => {
                    const off = city.officers?.find((o) => o.officerLevel === lvl);
                    return (
                        <div key={label} className="mcd-metric gauge-metric">
                            <div className="mcd-metric-head">{label}</div>
                            <div className="mcd-metric-body">
                                <div className="mcd-metric-text" style={{ color: off ? getNPCColor(off.npc) : undefined }}>
                                    {off?.name ?? '-'}
                                </div>
                            </div>
                        </div>
                    );
                })}
            </div>
        </section>
    );
}
