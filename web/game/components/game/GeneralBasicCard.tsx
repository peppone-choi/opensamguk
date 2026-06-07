'use client';

// GeneralBasicCard — 플레이어 장수 정보 카드(spec §6.1, 레거시 GeneralBasicCard.vue 충실 이식).
// 레거시 카드는 ~30개 필드(통/무/지 + *_exp 막대, 장비 툴팁, 부대, 벌점 …)를 그리지만 web/game 의
// front-info.general 이 실제로 싣는 부분집합만 렌더한다(날조 금지). 헤더는 레거시처럼 국가색 배경 +
// 밝기 기반 자동 글자색.
//
// front-info(FrontGeneralInfo + FrontNationInfo)에서만 렌더 — 추가 fetch 없음. 부상 색은
// formatInjury 동치(injury>0 → orange).
//
// 렌더 필드(API 보유): name + 관직(officerLevelText) + 통/무/지(부상색) + 명마/무기/서적/도구(코드) +
// 병종(crewTypeId) + 성격(personal) + 자금/군량/병사 + 훈련/사기(train/atmos) + 특기 내정/전투
// (specialDomestic/specialWar) + Lv(explevel) + 연령(age) + 삭턴(killturn) + 호칭(honorText) +
// 공헌(dedLevelText) + 통솔보너스(lbonus) + 소속.
//
// 미렌더(API-BLOCKED, 날조 금지): 통/무/지 *_exp 경험치 막대, 수비(defence_train), 벌점(refreshScore),
// turntime/실행 남은시간, troopInfo(부대). 장비/특기/성격/병종의 한글 표시명도 API가 코드만 주므로
// (iAction getName 인스턴스화 BLOCKED — GetConstController 참조) 코드 표시값으로 렌더한다.

import { formatNumber } from '@/lib/format';
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
// API 가 한글 표시명을 주지 않으므로(iAction getName BLOCKED) 코드의 'che_' 접두만 떼어 노출(날조 아님 —
// 실제 코드값을 그대로 보여줄 뿐). 표시명이 생기면 그때 교체.
function codeText(code?: string | null): string {
    if (!code || code === 'None') return '-';
    return code.startsWith('che_') ? code.slice(4) : code;
}

export interface GeneralBasicCardProps {
    general: FrontGeneralInfo;
    nation: FrontNationInfo | null;
}

export default function GeneralBasicCard({ general, nation }: GeneralBasicCardProps) {
    const nationColor = nation?.color ?? '#333333';
    const headerText = isBrightColor(nationColor) ? '#000' : '#fff';
    const injuryColor = general.injury > 0 ? 'orange' : '#fff';

    // 관직 — 스텁 제거. API 가 PHP-faithful officerLevelText(일반/종사/태수/황제/군주…)를 그대로 준다.
    // officer_level 0 의 '재야'도 그 안에 포함되므로 직접 사용(getOfficerLevelText(0)='재야').
    const officerText = general.officerLevelText ?? (general.officerLevel <= 0 ? '재야' : `${general.officerLevel}급`);

    // 통솔 — 부상색 + 통솔보너스(lbonus>0 이면 +N cyan, 레거시 동치).
    const leadershipCell = (
        <>
            <span style={{ color: injuryColor }}>{general.leadership}</span>
            {(general.lbonus ?? 0) > 0 && <span className="bc-bonus"> +{general.lbonus}</span>}
        </>
    );

    // 특기 — 레거시 "내정 / 전투" 단일 칸(specialDomestic / specialWar).
    const specialCell = `${codeText(general.specialDomestic)} / ${codeText(general.specialWar)}`;

    // 두 칸짜리 일반 행. value 가 ReactNode.
    const rows: { label: string; value: React.ReactNode }[] = [
        { label: '관직', value: officerText },
        { label: '소속', value: nation?.name ?? '재야' },
        { label: '통솔', value: leadershipCell },
        { label: '무력', value: <span style={{ color: injuryColor }}>{general.strength}</span> },
        { label: '지력', value: <span style={{ color: injuryColor }}>{general.intel}</span> },
        { label: '명마', value: codeText(general.horse) },
        { label: '무기', value: codeText(general.weapon) },
        { label: '서적', value: codeText(general.book) },
        { label: '도구', value: codeText(general.item) },
        { label: '병종', value: general.crewTypeId != null ? codeText(String(general.crewTypeId)) : '-' },
        { label: '성격', value: codeText(general.personal) },
        { label: '자금', value: formatNumber(general.gold) },
        { label: '군량', value: formatNumber(general.rice) },
        { label: '병사', value: formatNumber(general.crew) },
        { label: '훈련', value: general.train ?? 0 },
        { label: '사기', value: general.atmos ?? 0 },
        { label: 'Lv', value: general.explevel ?? '-' },
        { label: '연령', value: general.age != null ? `${general.age}세` : '-' },
        { label: '호칭', value: general.honorText ?? '-' },
        { label: '공헌', value: general.dedLevelText ?? '-' },
        { label: '삭턴', value: general.killturn != null ? `${general.killturn} 턴` : '-' },
    ];

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
        </section>
    );
}
