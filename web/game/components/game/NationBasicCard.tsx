'use client';

// NationBasicCard — 플레이어 국가 정보 카드(spec §6.2, 레거시 NationBasicCard.vue 충실 이식).
// 레거시 카드는 성향/군주·참모/총주민/총병사/국고/병량/지급률/세율/속령/장수/국력/기술력/전략/외교/임관/
// 전쟁 을 그린다. web/game 의 front-info.nation(FrontNationInfo, W3 enrich)이 싣는 필드만 렌더한다
// (날조 금지). 헤더는 레거시처럼 국가색 배경 + 밝기 기반 자동 글자색. 재야(nation==null/ id==0)면 본문은
// 레거시 `!nation.id` 분기대로 '해당 없음'.
//
// 렌더 필드(API 보유): name + 성향(type{name,pros,cons}) + 군주/군주대리(topChiefs) + 총주민(population
// now/max) + 총병사(crew now/max) + 국고/병량 + 지급률(bill)/세율(taxRate) + 속령(cityCnt)/장수
// (generalCnt) + 국력(power) + 기술력(tech) + 외교(diplomaticLimit)/임관(prohibitScout)/전쟁(prohibitWar).
//
// 미렌더(API-BLOCKED, 날조 금지): 전략(strategicCmdLimit/impossibleStrategicCommand — 명령엔진 필요).
// 또한 bill/taxRate/diplomaticLimit/prohibitScout/prohibitWar 는 nation.meta UNVERIFIED 라 데몬이
// 채우기 전까지 null → 해당 행은 null-safe 로 렌더(부재 시 '-', 값 날조 없음).

import { formatNumber } from '@/lib/format';
import type { FrontNationInfo } from '@/lib/types';

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

export interface NationBasicCardProps {
    nation: FrontNationInfo | null;
}

export default function NationBasicCard({ nation }: NationBasicCardProps) {
    const nationColor = nation?.color ?? '#333333';
    const headerText = isBrightColor(nationColor) ? '#000' : '#fff';
    const has = nation != null && nation.id !== 0;
    const NA = '해당 없음';

    // topChiefs 에서 officer_level 별로 1명 찾기(레거시 topChiefs[12]/topChiefs[11]).
    const chiefOf = (level: number): string => {
        const c = nation?.topChiefs?.find((t) => t.officerLevel === level);
        return c?.name ?? '-';
    };

    // 성향 — 레거시 "{name} (pros cons)". pros=cyan, cons=magenta(빈 문자열이면 생략).
    const typeCell: React.ReactNode = (() => {
        if (!has || !nation!.type) return NA;
        const t = nation!.type;
        return (
            <>
                {t.name}
                {(t.pros || t.cons) && (
                    <>
                        {' ('}
                        {t.pros && <span className="bc-pros">{t.pros}</span>}
                        {t.cons && <span className="bc-cons">{t.cons}</span>}
                        {')'}
                    </>
                )}
            </>
        );
    })();

    // 외교/임관/전쟁 — 레거시: limit>0 면 'N턴'(red), 아니면 '가능'(limegreen) / 금지(scout/war): red '금지' vs limegreen '허가'.
    // 단 meta UNVERIFIED 라 값이 null 이면 '-'(날조 금지).
    const limitCell = (v?: number | null): React.ReactNode => {
        if (v == null) return '-';
        return v > 0 ? <span className="bc-no">{v.toLocaleString()}턴</span> : <span className="bc-ok">가능</span>;
    };
    const banCell = (v?: number | null): React.ReactNode => {
        if (v == null) return '-';
        return v ? <span className="bc-no">금지</span> : <span className="bc-ok">허가</span>;
    };

    // 레거시 그리드 라벨/값 순서 그대로(전략 행만 BLOCKED 로 제외).
    const rows: { label: string; value: React.ReactNode }[] = [
        { label: '성향', value: typeCell },
        { label: '군주', value: chiefOf(12) },
        { label: '군주대리', value: chiefOf(11) },
        { label: '총 주민', value: has && nation!.population
            ? `${formatNumber(nation!.population.now)} / ${formatNumber(nation!.population.max)}` : NA },
        { label: '총 병사', value: has && nation!.crew
            ? `${formatNumber(nation!.crew.now)} / ${formatNumber(nation!.crew.max)}` : NA },
        { label: '국고', value: has ? formatNumber(nation!.gold) : NA },
        { label: '병량', value: has ? formatNumber(nation!.rice) : NA },
        { label: '지급률', value: has ? (nation!.bill != null ? `${nation!.bill}%` : '-') : NA },
        { label: '세율', value: has ? (nation!.taxRate != null ? `${nation!.taxRate}%` : '-') : NA },
        { label: '속령', value: has && nation!.population ? formatNumber(nation!.population.cityCnt) : NA },
        { label: '장수', value: has && nation!.crew ? formatNumber(nation!.crew.generalCnt) : NA },
        { label: '국력', value: has ? formatNumber(nation!.power ?? 0) : NA },
        { label: '기술력', value: has ? formatNumber(Math.floor(nation!.tech)) : NA },
        { label: '외교', value: has ? limitCell(nation!.diplomaticLimit) : NA },
        { label: '임관', value: has ? banCell(nation!.prohibitScout) : NA },
        { label: '전쟁', value: has ? banCell(nation!.prohibitWar) : NA },
    ];

    return (
        <section className="basic-card nation-basic-card ib-nation" aria-label="국가 정보">
            <div className="basic-card-name" style={{ backgroundColor: nationColor, color: headerText }}>
                {nation?.name ?? '재야'}
            </div>
            <div className="basic-card-grid">
                {rows.map((r) => (
                    <div key={r.label} className="basic-card-row">
                        <div className="basic-card-head">{r.label}</div>
                        <div className="basic-card-body">{r.value}</div>
                    </div>
                ))}
            </div>
        </section>
    );
}
