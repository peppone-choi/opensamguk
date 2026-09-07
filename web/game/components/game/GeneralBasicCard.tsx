'use client';

// GeneralBasicCard — 작전실 「장수」 카드(ADR-LITE-049 · 03 아트보드).
// 시안: 120px 초상(국가색 링) + 우측 본문 = 이름 줄(관직·소속·호칭·부상 칩, 우측 Lv·경험) →
// 통·무·지·정·매 5열 막대 → 5열 라벨/값 격자 → 추가정보(전투 기록) 접힘.
// 데이터 계약은 종전 그대로다 — front-info.general(FrontGeneralInfo)이 싣는 필드만 렌더한다(날조 금지).
// 미렌더(API-BLOCKED): turntime/실행 남은시간, dex1..5(숙련도 컬럼 부재).

import { Chip, Portrait } from '@opensamguk/ui';
import { formatNumber } from '@/lib/format';
import { formatInjury, nextExpLevelRemain } from '@/lib/utilGame';
import type { FrontGeneralInfo, FrontNationInfo } from '@/lib/types';
import { ICON_CDN, STAT_UP_THRESHOLD } from '@/lib/constants';

// 장비/특기/성격/병종 코드를 표시값으로. 레거시 dummyInfo.name='-' 동치: 'None'/null/빈값 → '-'.
function codeText(code?: string | null): string {
    if (!code || code === 'None') return '-';
    return code.startsWith('che_') ? code.slice(4) : code;
}

// API 해석 이름 우선, 부재('-'/null/빈값 포함) 시 raw 코드 표시값으로 폴백(구버전 API 호환).
function nameOrCode(name: string | null | undefined, code?: string | null): string {
    if (name && name !== '-' && name !== 'None') return name;
    return codeText(code);
}

/** 연령 톤 — PHP generalInfo() age 분기 동치(retirementYear=60). 은퇴가 가까울수록 경고색. */
function ageTone(age: number): 'moss' | 'bronze' | 'rust' {
    const retirementYear = 60;
    if (age < retirementYear * 0.75) return 'moss';
    if (age < retirementYear) return 'bronze';
    return 'rust';
}

function SignedBonus({ value }: { value: number | null | undefined }) {
    const v = value ?? 0;
    if (v === 0) return null;
    return <span className={v > 0 ? 'bc-bonus' : 'bc-penalty'}> {v > 0 ? `+${v}` : v}</span>;
}

/** 승률/살상률 — PHP generalInfo2() winRate/killRate 동치. */
function ratio(a: number | null | undefined, b: number | null | undefined): string {
    const x = a ?? 0;
    const y = b ?? 0;
    return `${y > 0 ? ((x / y) * 100).toFixed(2) : '0.00'} %`;
}

export interface GeneralBasicCardProps {
    general: FrontGeneralInfo;
    nation: FrontNationInfo | null;
}

export default function GeneralBasicCard({ general, nation }: GeneralBasicCardProps) {
    const officerText = general.officerLevelText ?? (general.officerLevel <= 0 ? '재야' : `${general.officerLevel}급`);
    const [injuryText] = formatInjury(general.injury);
    const injured = general.injury > 0;

    // 통·무·지·정·매 — 막대는 값/100, 값 옆에 onCalcStat 보정치(있을 때만). 부상 시 값이 붉어진다.
    const stats = [
        { label: '통솔', value: general.leadership, bonus: general.leadershipBonus ?? general.lbonus, exp: general.leadershipExp },
        { label: '무력', value: general.strength, bonus: general.strengthBonus, exp: general.strengthExp },
        { label: '지력', value: general.intel, bonus: general.intelBonus, exp: general.intelExp },
        { label: '정치', value: general.politics ?? 0, bonus: general.politicsBonus, exp: general.politicsExp },
        { label: '매력', value: general.charm ?? 0, bonus: general.charmBonus, exp: general.charmExp },
    ];

    const [expRemain, expSpan] = nextExpLevelRemain(general.experience ?? 0, general.explevel ?? 0);
    const expPct = expSpan > 0 ? Math.min(100, Math.max(0, (expRemain / expSpan) * 100)) : 0;

    const crewTypeName = nameOrCode(general.crewTypeName, general.crewTypeId != null ? String(general.crewTypeId) : null);
    const crewTypeIcon = general.crewTypeId != null && general.crewTypeId >= 0 ? `${ICON_CDN}/crewtype${general.crewTypeId}.png` : null;

    const facts: { k: string; v: React.ReactNode; tone?: 'gold' | 'rice'; wide?: boolean }[] = [
        {
            k: '병종',
            v: (
                <span className="war-card__crewtype">
                    {crewTypeIcon && (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img src={crewTypeIcon} alt="" onError={(e) => { e.currentTarget.style.display = 'none'; }} />
                    )}
                    {crewTypeName}
                </span>
            ),
        },
        { k: '병사', v: formatNumber(general.crew) },
        { k: '훈련', v: general.train ?? 0 },
        { k: '사기', v: general.atmos ?? 0 },
        { k: '삭턴', v: general.killturn != null ? `${general.killturn} 턴` : '-' },
        { k: '명마', v: nameOrCode(general.horseName, general.horse) },
        { k: '무기', v: nameOrCode(general.weaponName, general.weapon) },
        { k: '서적', v: nameOrCode(general.bookName, general.book) },
        { k: '도구', v: nameOrCode(general.itemName, general.item) },
        { k: '성격', v: nameOrCode(general.personalName, general.personal) },
        { k: '자금', v: formatNumber(general.gold), tone: 'gold' },
        { k: '군량', v: formatNumber(general.rice), tone: 'rice' },
        { k: '공헌', v: general.dedLevelText ?? '-' },
        {
            k: '특기',
            wide: true,
            v: `${nameOrCode(general.specialDomesticName, general.specialDomestic)} · ${nameOrCode(general.specialWarName, general.specialWar)}`,
        },
    ];

    // generalInfo2 — war stat 이 하나도 없으면(null) 추가정보 블록 자체를 숨긴다(날조 금지).
    const hasWarStats =
        general.warnum != null || general.killnum != null || general.deathnum != null ||
        general.firenum != null || general.killcrew != null || general.deathcrew != null;

    const warFacts: { k: string; v: React.ReactNode }[] = [
        { k: '명성', v: `${general.honorText ?? '-'} (${general.experience ?? 0})` },
        { k: '계급', v: `${general.dedLevelText ?? '-'} (${general.dedication ?? 0})` },
        { k: '전투', v: general.warnum ?? 0 },
        { k: '계략', v: general.firenum ?? 0 },
        { k: '사관', v: `${general.belong ?? 0}년` },
        { k: '승률', v: ratio(general.killnum, general.warnum) },
        { k: '승리', v: general.killnum ?? 0 },
        { k: '패배', v: general.deathnum ?? 0 },
        { k: '살상률', v: ratio(general.killcrew, general.deathcrew) },
        { k: '사살', v: formatNumber(general.killcrew ?? 0) },
        { k: '피살', v: formatNumber(general.deathcrew ?? 0) },
    ];

    return (
        <section className="war-card war-card--general" aria-label="장수 정보">
            <Portrait
                picture={general.picture}
                imageServer={general.imageServer}
                size="card-126"
                alt={general.name ?? '장수'}
                ring={nation?.color ? { color: nation.color, reason: 'self' } : null}
                frameClassName="war-card__portrait"
            />

            <div className="war-card__general-body">
                <div className="war-card__general-head">
                    <span className="war-card__name">{general.name ?? '-'}</span>
                    {general.age != null && (
                        <span className={`war-card__age war-card__age--${ageTone(general.age)}`}>연령 {general.age}세</span>
                    )}
                    <Chip tone="bronze" className="war-card__chip">관직 {officerText}</Chip>
                    <Chip className="war-card__chip">소속 {nation?.name ?? '재야'}</Chip>
                    <Chip className="war-card__chip">호칭 {general.honorText ?? '-'}</Chip>
                    <Chip tone={injured ? 'rust' : 'moss'} className="war-card__chip">{injured ? injuryText : '부상 없음'}</Chip>
                    <span className="war-card__spacer" />
                    <span className="war-card__lv">
                        Lv <b className="os-num">{general.explevel ?? 0}</b> · 경험 <b className="os-num">{formatNumber(general.experience ?? 0)}</b>
                        <span className="war-card__lvbar" aria-hidden="true"><i style={{ width: `${expPct}%` }} /></span>
                    </span>
                </div>

                <div className="war-card__stats">
                    {stats.map((s) => (
                        <div
                            key={s.label}
                            className={`war-card__stat${injured ? ' war-card__stat--injured' : ''}`}
                            role="meter"
                            aria-label={s.label}
                            aria-valuenow={s.value}
                            aria-valuemin={0}
                            aria-valuemax={100}
                        >
                            <span className="war-card__stat-top">
                                <span className="war-card__k">{s.label}</span>
                                <b className="os-num">{s.value}<SignedBonus value={s.bonus} /></b>
                            </span>
                            <span className="war-card__stat-bar"><i style={{ width: `${Math.min(100, Math.max(0, s.value))}%` }} /></span>
                            {/* 능력 경험(다음 상승까지) — 분모가 있는 값만 얇은 두 번째 막대로. */}
                            {s.exp != null && (
                                <span className="war-card__stat-exp" title={`${s.label} 경험`}>
                                    <i style={{ width: `${Math.min(100, Math.max(0, (s.exp / STAT_UP_THRESHOLD) * 100))}%` }} />
                                </span>
                            )}
                        </div>
                    ))}
                </div>

                <div className="war-card__facts war-card__facts--5">
                    {facts.map((f) => (
                        <div key={f.k} className={`war-card__fact${f.wide ? ' war-card__fact--wide' : ''}`}>
                            <span className="war-card__k">{f.k}</span>
                            <span className={`war-card__v${f.tone ? ` war-card__v--${f.tone}` : ''}`}>{f.v}</span>
                        </div>
                    ))}
                </div>

                {hasWarStats && (
                    <details className="war-card__extra">
                        <summary>추가정보</summary>
                        <div className="war-card__facts war-card__facts--5">
                            {warFacts.map((f) => (
                                <div key={f.k} className="war-card__fact">
                                    <span className="war-card__k">{f.k}</span>
                                    <span className="war-card__v">{f.v}</span>
                                </div>
                            ))}
                        </div>
                    </details>
                )}
            </div>
        </section>
    );
}
