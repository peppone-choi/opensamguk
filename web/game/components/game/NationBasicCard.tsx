'use client';

// NationBasicCard — 작전실 「국가」 카드(ADR-LITE-049 · 03 아트보드).
// 시안: 32px 헤더(깃발 + 국가명 + 성향·Lv 부제 + 우측 국력 칩) → 3열 라벨/값 격자.
// 데이터 계약은 종전 그대로다 — front-info.nation(FrontNationInfo)이 싣는 필드만 렌더한다(날조 금지).
// bill/taxRate/diplomaticLimit/prohibitScout/prohibitWar 는 nation.meta UNVERIFIED 라 데몬이 채우기
// 전까지 null → 해당 값은 '-'. 재야(nation==null/id==0)면 본문은 레거시 `!nation.id` 분기대로 '해당 없음'.
// 미렌더(API-BLOCKED): 전략(strategicCmdLimit/impossibleStrategicCommand — 명령엔진 필요).

import { Chip, Flag } from '@opensamguk/ui';
import { formatNumber } from '@/lib/format';
import type { FrontNationInfo } from '@/lib/types';

export interface NationBasicCardProps {
    nation: FrontNationInfo | null;
}

export default function NationBasicCard({ nation }: NationBasicCardProps) {
    const nationColor = nation?.color ?? '#8a8477';
    const has = nation != null && nation.id !== 0;
    const NA = '해당 없음';

    // topChiefs 에서 officer_level 별로 1명 찾기(레거시 topChiefs[12]/topChiefs[11]).
    const chiefOf = (level: number): string => nation?.topChiefs?.find((t) => t.officerLevel === level)?.name ?? '-';

    // 성향 — 레거시 "{name} (pros cons)". pros=info, cons=rust(빈 문자열이면 생략).
    const typeNode: React.ReactNode = (() => {
        if (!has || !nation!.type) return null;
        const t = nation!.type;
        return (
            <>
                성향 {t.name}
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

    // 외교/임관/전쟁 — limit>0 이면 'N턴'(제한), 아니면 '가능'. 금지(scout/war)는 '금지' vs '허가'.
    // meta UNVERIFIED 라 값이 null 이면 '-'(날조 금지).
    const limitCell = (v?: number | null): React.ReactNode => {
        if (v == null) return '-';
        return v > 0 ? <Chip tone="rust" className="war-card__chip">{v.toLocaleString()}턴</Chip> : <Chip tone="moss" className="war-card__chip">가능</Chip>;
    };
    const banCell = (v?: number | null): React.ReactNode => {
        if (v == null) return '-';
        return v ? <Chip tone="rust" className="war-card__chip">금지</Chip> : <Chip tone="moss" className="war-card__chip">허가</Chip>;
    };

    const facts: { k: string; v: React.ReactNode; tone?: 'gold' | 'rice' }[] = [
        { k: nation?.rulerOfficerText ?? '군주', v: chiefOf(12) },
        { k: nation?.deputyOfficerText ?? '군주대리', v: chiefOf(11) },
        // 속령·장수는 각자 자기 그룹에만 의존한다 — 한쪽이 null 이라고 다른 쪽 수치까지 잃지 않는다.
        { k: '속령', v: has && nation!.population ? formatNumber(nation!.population.cityCnt) : NA },
        { k: '장수', v: has && nation!.crew ? formatNumber(nation!.crew.generalCnt) : NA },
        { k: '총 주민', v: has && nation!.population
            ? `${formatNumber(nation!.population.now)} / ${formatNumber(nation!.population.max)}` : NA },
        { k: '총 병사', v: has && nation!.crew
            ? `${formatNumber(nation!.crew.now)} / ${formatNumber(nation!.crew.max)}` : NA },
        { k: '기술력', v: has ? formatNumber(Math.floor(nation!.tech)) : NA },
        { k: '국고', v: has ? formatNumber(nation!.gold) : NA, tone: 'gold' },
        { k: '병량', v: has ? formatNumber(nation!.rice) : NA, tone: 'rice' },
        { k: '지급률 · 세율', v: has
            ? `${nation!.bill != null ? `${nation!.bill}%` : '-'} · ${nation!.taxRate != null ? `${nation!.taxRate}%` : '-'}` : NA },
        { k: '외교', v: has ? limitCell(nation!.diplomaticLimit) : NA },
        { k: '임관', v: has ? banCell(nation!.prohibitScout) : NA },
        { k: '전쟁', v: has ? banCell(nation!.prohibitWar) : NA },
    ];

    return (
        <section className="war-card war-card--nation" aria-label="국가 정보">
            <header className="war-card__head">
                <Flag color={nationColor} size={14} />
                <span className="war-card__title">{nation?.name ?? '재야'}</span>
                {/* 성향이 없어도 Lv 는 보여야 한다 — 부제 두 조각을 따로 건다. */}
                <span className="war-card__sub">
                    {typeNode}
                    {typeNode && ' · '}
                    {has ? `Lv ${nation!.level}` : NA}
                </span>
                <span className="war-card__spacer" />
                <Chip tone="bronze" className="war-card__chip">국력 {has ? formatNumber(nation!.power ?? 0) : NA}</Chip>
            </header>

            <div className="war-card__facts war-card__facts--3">
                {facts.map((f) => (
                    <div key={f.k} className="war-card__fact">
                        <span className="war-card__k">{f.k}</span>
                        <span className={`war-card__v${f.tone ? ` war-card__v--${f.tone}` : ''}`}>{f.v}</span>
                    </div>
                ))}
            </div>
        </section>
    );
}
