'use client';

// GeneralBasicCard — 플레이어 장수 정보 카드(spec §6.1, 레거시 GeneralBasicCard.vue + generalInfo2 충실 이식).
// 레거시는 generalInfo() + generalInfo2() 두 패널을 그린다. web/game 의 front-info.general 이 실제로
// 싣는 부분집합만 렌더한다(날조 금지). 헤더는 레거시처럼 국가색 배경 + 밝기 기반 자동 글자색.
//
// front-info(FrontGeneralInfo + FrontNationInfo)에서만 렌더 — 추가 fetch 없음.
//
// 렌더 필드(API 보유):
//   generalInfo: name + 관직 + 통/무/지(부상색+통솔본너스) + 명마/무기/서적/도구 + 병종 + 성격 +
//     자금/군량/병사 + 훈련/사기 + 특기(내정/전투) + Lv(경험치막대) + 연령(은퇴색) + 삭턴 +
//     호칭(experience) + 공헌(dedication) + 부상(텍스트+색).
//   generalInfo2: 명성/계급(원시값+색) + 전투/계략/사관 + 승률/승리/패배 + 살상률/사살/피살.
//
// 미렌더(API-BLOCKED, 날조 금지): 통/무/지 *_exp 경험치 막대, turntime/실행 남은시간,
// dex1..5(숙련도 컬럼 부재), refreshScore(general_access_log 부재), troopInfo(부대명 합성),
// defence_train(컬럼 부재), train/atmos bonus(onCalcStat 미배선).

import { formatNumber } from '@/lib/format';
import { formatInjury, nextExpLevelRemain } from '@/lib/utilGame';
import type { FrontGeneralInfo, FrontNationInfo } from '@/lib/types';

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

// 장비/특기/성격/병종 코드를 표시값으로. 레거시 dummyInfo.name='-' 동치: 'None'/null/빈값 → '-'.
function codeText(code?: string | null): string {
    if (!code || code === 'None') return '-';
    return code.startsWith('che_') ? code.slice(4) : code;
}

// API 해석 이름 우선, 부재('-'/null/빈값 포함) 시 raw 코드 표시값으로 폴드백(구버전 API 호환).
function nameOrCode(name: string | null | undefined, code?: string | null): string {
    if (name && name !== '-' && name !== 'None') return name;
    return codeText(code);
}

// 부상 텍스트+색 — PHP generalInfo() injury 분기 동치.
function InjuryLabel({ injury }: { injury: number }) {
    const [text, color] = formatInjury(injury);
    return <span style={{ color }}>{text}</span>;
}

// 연령 색 — PHP generalInfo() age 분기 동치(retirementYear=60 기준).
function AgeLabel({ age }: { age: number }) {
    const retirementYear = 60;
    let color: string;
    if (age < retirementYear * 0.75) {
        color = 'limegreen';
    } else if (age < retirementYear) {
        color = 'yellow';
    } else {
        color = 'red';
    }
    return <span style={{ color }}>{age}세</span>;
}

// Lv + 경험치 막대 — PHP generalInfo() bar(getLevelPer(experience, explevel), 20) 동치.
function LevelBar({ experience, explevel }: { experience: number | null | undefined; explevel: number | null | undefined }) {
    const exp = experience ?? 0;
    const lv = explevel ?? 0;
    const [remain, span] = nextExpLevelRemain(exp, lv);
    const pct = span > 0 ? Math.min(100, Math.max(0, (remain / span) * 100)) : 0;
    return (
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-xs)', width: '100%' }}>
            <span>Lv {lv}</span>
            <div className="mcd-bar" style={{ flex: 1, height: 12 }}>
                <div className="mcd-bar-fill" style={{ width: `${pct}%` }} />
            </div>
        </div>
    );
}

// 명성/계급 셀 — PHP generalInfo2() experience/dedication bonus 분기 동치.
// bonus=onCalcStat-10000; >0 cyan, <0 magenta, =0 white.
function HonorCell({
    honorText,
    raw,
    bonus,
}: {
    honorText: string | null | undefined;
    raw: number | null | undefined;
    bonus: number | null | undefined;
}) {
    const b = bonus ?? 0;
    const color = b > 0 ? 'cyan' : b < 0 ? 'magenta' : undefined;
    return (
        <span style={{ color }}>
            {honorText ?? '-'} ({raw ?? 0})
        </span>
    );
}

// 승률/살상률 — PHP generalInfo2() winRate/killRate 동치.
function WinRate({ killnum, warnum }: { killnum: number | null | undefined; warnum: number | null | undefined }) {
    const k = killnum ?? 0;
    const w = warnum ?? 0;
    const rate = w > 0 ? ((k / w) * 100).toFixed(2) : '0.00';
    return <span>{rate} %</span>;
}
function KillRate({ killcrew, deathcrew }: { killcrew: number | null | undefined; deathcrew: number | null | undefined }) {
    const k = killcrew ?? 0;
    const d = deathcrew ?? 0;
    const rate = d > 0 ? ((k / d) * 100).toFixed(2) : '0.00';
    return <span>{rate} %</span>;
}

export interface GeneralBasicCardProps {
    general: FrontGeneralInfo;
    nation: FrontNationInfo | null;
}

export default function GeneralBasicCard({ general, nation }: GeneralBasicCardProps) {
    const nationColor = nation?.color ?? '#333333';
    const headerText = isBrightColor(nationColor) ? '#000' : '#fff';
    const injuryColor = general.injury > 0 ? 'orange' : '#fff';

    const officerText = general.officerLevelText ?? (general.officerLevel <= 0 ? '재야' : `${general.officerLevel}급`);

    const leadershipCell = (
        <>
            <span style={{ color: injuryColor }}>{general.leadership}</span>
            {(general.lbonus ?? 0) > 0 && <span className="bc-bonus"> +{general.lbonus}</span>}
        </>
    );

    const specialCell = `${nameOrCode(general.specialDomesticName, general.specialDomestic)} / ${nameOrCode(general.specialWarName, general.specialWar)}`;

    // generalInfo 패널 행들.
    const rows: { label: string; value: React.ReactNode }[] = [
        { label: '관직', value: officerText },
        { label: '소속', value: nation?.name ?? '재야' },
        { label: '통솔', value: leadershipCell },
        { label: '무력', value: <span style={{ color: injuryColor }}>{general.strength}</span> },
        { label: '지력', value: <span style={{ color: injuryColor }}>{general.intel}</span> },
        { label: '정치', value: general.politics ?? '-' },
        { label: '매력', value: general.charm ?? '-' },
        { label: '명마', value: nameOrCode(general.horseName, general.horse) },
        { label: '무기', value: nameOrCode(general.weaponName, general.weapon) },
        { label: '서적', value: nameOrCode(general.bookName, general.book) },
        { label: '도구', value: nameOrCode(general.itemName, general.item) },
        { label: '병종', value: nameOrCode(general.crewTypeName, general.crewTypeId != null ? String(general.crewTypeId) : null) },
        { label: '성격', value: nameOrCode(general.personalName, general.personal) },
        { label: '자금', value: formatNumber(general.gold) },
        { label: '군량', value: formatNumber(general.rice) },
        { label: '병사', value: formatNumber(general.crew) },
        { label: '훈련', value: general.train ?? 0 },
        { label: '사기', value: general.atmos ?? 0 },
        { label: 'Lv', value: <LevelBar experience={general.experience} explevel={general.explevel} /> },
        { label: '연령', value: general.age != null ? <AgeLabel age={general.age} /> : '-' },
        { label: '호칭', value: general.honorText ?? '-' },
        { label: '공헌', value: general.dedLevelText ?? '-' },
        { label: '삭턴', value: general.killturn != null ? `${general.killturn} 턴` : '-' },
        { label: '부상', value: <InjuryLabel injury={general.injury} /> },
    ];

    // generalInfo2 패널 — 명성/계급/전투/계략/사관/승률/승리/패배/살상률/사살/피살.
    // API에 war stat이 없으면(null) 해당 행 전체를 숨긴다 — 날조 금지.
    const hasWarStats =
        general.warnum != null ||
        general.killnum != null ||
        general.deathnum != null ||
        general.firenum != null ||
        general.killcrew != null ||
        general.deathcrew != null;

    return (
        <section className="basic-card general-basic-card ib-general" aria-label="장수 정보">
            <div className="basic-card-name" style={{ backgroundColor: nationColor, color: headerText }}>
                {general.name ?? '-'}
                {general.injury > 0 && <span style={{ color: 'orange' }}> 【 부상 】</span>}
            </div>
            <div className="basic-card-grid">
                {/* 특기 — head 없는 전폭 행(레거시 단일 칸 "내정 / 전투"). */}
                <div className="basic-card-row">
                    <div className="basic-card-head">특기</div>
                    <div className="basic-card-body">{specialCell}</div>
                </div>
                {rows.map((r) => (
                    <div key={r.label} className="basic-card-row">
                        <div className="basic-card-head">{r.label}</div>
                        <div className="basic-card-body">{r.value}</div>
                    </div>
                ))}
            </div>

            {/* generalInfo2 — 추가 정보 패널 (war stats 있을 때만 렌더). */}
            {hasWarStats && (
                <div className="basic-card-grid" style={{ marginTop: 'var(--space-md)' }}>
                    <div className="basic-card-row" style={{ gridColumn: '1 / -1' }}>
                        <div
                            className="basic-card-head"
                            style={{
                                textAlign: 'center',
                                background: 'var(--bg-elevated)',
                                fontWeight: 700,
                            }}
                        >
                            추 가 정 보
                        </div>
                    </div>
                    <div className="basic-card-row">
                        <div className="basic-card-head">명성</div>
                        <div className="basic-card-body">
                            <HonorCell honorText={general.honorText} raw={general.experience} bonus={0} />
                        </div>
                    </div>
                    <div className="basic-card-row">
                        <div className="basic-card-head">계급</div>
                        <div className="basic-card-body">
                            <HonorCell honorText={general.dedLevelText} raw={general.dedication} bonus={0} />
                        </div>
                    </div>
                    <div className="basic-card-row">
                        <div className="basic-card-head">전투</div>
                        <div className="basic-card-body">{general.warnum ?? 0}</div>
                    </div>
                    <div className="basic-card-row">
                        <div className="basic-card-head">계략</div>
                        <div className="basic-card-body">{general.firenum ?? 0}</div>
                    </div>
                    <div className="basic-card-row">
                        <div className="basic-card-head">사관</div>
                        <div className="basic-card-body">{general.belong ?? 0}년</div>
                    </div>
                    <div className="basic-card-row">
                        <div className="basic-card-head">승률</div>
                        <div className="basic-card-body">
                            <WinRate killnum={general.killnum} warnum={general.warnum} />
                        </div>
                    </div>
                    <div className="basic-card-row">
                        <div className="basic-card-head">승리</div>
                        <div className="basic-card-body">{general.killnum ?? 0}</div>
                    </div>
                    <div className="basic-card-row">
                        <div className="basic-card-head">패배</div>
                        <div className="basic-card-body">{general.deathnum ?? 0}</div>
                    </div>
                    <div className="basic-card-row">
                        <div className="basic-card-head">살상률</div>
                        <div className="basic-card-body">
                            <KillRate killcrew={general.killcrew} deathcrew={general.deathcrew} />
                        </div>
                    </div>
                    <div className="basic-card-row">
                        <div className="basic-card-head">사살</div>
                        <div className="basic-card-body">{formatNumber(general.killcrew ?? 0)}</div>
                    </div>
                    <div className="basic-card-row">
                        <div className="basic-card-head">피살</div>
                        <div className="basic-card-body">{formatNumber(general.deathcrew ?? 0)}</div>
                    </div>
                </div>
            )}
        </section>
    );
}
