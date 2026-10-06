'use client';

// 황실(P-K09) — K8 설계서 §3 P-K09, 보드 V31K8Imperial · MImperial · ImperialStates.
// 서버가 지금 주는 것은 황제 소재지(GET /api/imperial/presence)뿐이다. 그 칸만 실제 값으로 그린다.
//  - 세력과 황실 · 조서 · 인장 · 조정 방침: 관찰자 투영 읽기(계약판 K8-10, C6)가 오기 전까지 서버 대기 A(영역 전체 waiting).
//  - 칭제: 규칙 설계가 없다(K8-15, 등급 D) — 자리 한 칸만 둔다(설계서 P-K11 「지금 할 수 있는 것」).
//  - 섭정 · 조정을 지키는 세력 · 조정 상태는 소재지 응답에 없다 — 값을 짓지 않고 「준비 중」 줄로 둔다(서버 대기 K8-10).
//    보드에 있는 칸을 조용히 빼면 나중에 빠진 것을 아무도 모른다(K0 2026-10-01).
//  - 황통 카드의 지도 조각(왕관 표식)은 지도 층 · crown 아이콘(#1142)이 오면 붙인다 — 그때까지 「준비 중」 칸.
// 황제 이름은 서버가 준 `emperorName`(K8-16, #1150) — 없으면 「이름을 아직 모릅니다」(황제는 있고 이름만 없다, K0 10-01). 城은 지도 미리보기(황제가 있을 때만 받는다),
// 구역은 이번 접속에서 지도를 받았을 때만 안다(K4-21 대기).

import { Chip, KV, Panel, SectionHeader, StatusView, useProvinceName } from '@opensamguk/ui';
import type { ImperialCourtLine } from '@/lib/api/imperial-court';
import { useGameSession } from '@/lib/campaign-session';
import { emperorWhere, useImperialPresence, type ImperialBadge } from '@/lib/imperial';
import { courtSlots, quietLines, type CourtReadState, type CourtSlot } from '@/lib/imperial-court-view';
import { useImperialCourt } from '@/lib/use-imperial-court';
import { useRecordNames, type RecordNames } from '@/lib/records-names';
import styles from './imperial.module.css';

const NO_NAME = '이름을 아직 모릅니다';
const UNKNOWN_CITY = '어느 성';
const CHECKING = '확인 중';

export default function ImperialScreen() {
    const presence = useImperialPresence();
    const { generalId } = useGameSession();
    // court(C6 #1389 · D123)는 황실이 있을 때만 읽는다. 경로가 아직 없으면(404) 서버 대기로 남는다(D124 미리 짓기).
    const hasHouse = presence.state === 'ready' && (presence.view.kind === 'PRESENT' || presence.view.kind === 'VACANT');
    const court = useImperialCourt(generalId, hasHouse);
    const quiet = quietLines(court);

    if (presence.state === 'loading') {
        return <div className={styles.screen}><StatusView kind="loading" rows={3} /></div>;
    }
    if (presence.state === 'error' || presence.view.kind === 'UNAVAILABLE') {
        const code = presence.state === 'error' ? (presence.httpStatus === null ? undefined : `HTTP ${presence.httpStatus}`) : 'STATE_UNAVAILABLE';
        return (
            <div className={styles.screen}>
                <StatusView kind="error" title="황실 정보를 지금 읽을 수 없습니다" body="잠시 뒤 다시 해 보세요." errorCode={code} onRetry={presence.retry} />
            </div>
        );
    }
    const { view } = presence;
    return (
        <div className={styles.screen}>
            {view.kind === 'NO_IMPERIAL_HOUSE' ? (
                <StatusView kind="empty" title="이 천하에는 황실이 없습니다" body="황제와 조정이 없는 시나리오입니다. 지도에도 황제 표식이 나오지 않습니다." />
            ) : (
                <>
                    {view.kind === 'VACANT' ? (
                        <>
                            <QuietLines vacant={quiet.vacant} ended={[]} />
                            <StatusView kind="empty" title="지금 황제가 없습니다" body="제위가 비어 있습니다. 누가 오를지는 황통의 후계 규칙이 정합니다." />
                        </>
                    ) : (
                        <>
                            <Lines badges={view.badges} court={court} />
                            <QuietLines vacant={quiet.vacant} ended={[]} />
                        </>
                    )}
                    <QuietLines vacant={[]} ended={quiet.ended} />
                    <WaitingGrid />
                </>
            )}
            <ClaimCell />
        </div>
    );
}

/** 황통 카드들 — 이 컴포넌트가 그려질 때만(황제가 있을 때만) 이름을 받는다. 조정 · 섭정 · 지키는 세력은 court(D123)가 채운다. */
function Lines({ badges, court }: { readonly badges: readonly ImperialBadge[]; readonly court: CourtReadState }) {
    const names = useRecordNames(null, null); // 城 이름만 쓴다
    const provinceName = useProvinceName();
    return (
        <div className={styles.lines}>
            {badges.map((badge) => {
                const slots = courtSlots(court, badge.lineCode);
                return (
                    <Panel key={badge.lineCode} className={styles.line} aria-label={`황통 — ${badge.lineName}`}>
                        <SectionHeader title={`황통 — ${badge.lineName}`} sub="황통은 여럿일 수 있습니다" />
                        <div className={styles.lineBody}>
                            <div className={styles.mapSlot} data-server-wait="map-layer · crown">
                                <span>지도 표식</span>
                                <span className={styles.wait}>준비 중</span>
                            </div>
                            <KV
                                className={styles.facts}
                                items={[
                                    { k: '황제', v: badge.emperorName ?? NO_NAME },
                                    { k: '있는 곳', v: placeText(names, provinceName, badge) },
                                    { k: '조정', v: slots.court ? <SlotText slot={slots.court} /> : badge.courtCityId === null ? '정하지 않음' : cityName(names, badge.courtCityId) },
                                    { k: '섭정', v: <SlotText slot={slots.regent} /> },
                                    { k: '조정을 지키는 세력', v: <SlotText slot={slots.guardian} /> },
                                    { k: '조정 상태', v: <Waiting row="K8-10" /> },
                                ]}
                            />
                        </div>
                    </Panel>
                );
            })}
        </div>
    );
}

/** court 칸 하나 — 값 · 셈하지 못함 · 서버 대기(K8-10) · 지금 읽을 수 없음. */
function SlotText({ slot }: { readonly slot: CourtSlot }) {
    if (slot.kind === 'value') return <>{slot.text}</>;
    if (slot.kind === 'waiting') return <Waiting row="K8-10" />;
    return <span className={styles.slotMuted}>{slot.kind === 'unavailable' ? '셈하지 못함' : '지금 읽을 수 없음'}</span>;
}

/** 공위 · 종결 황통 줄(D123 ② · ③) — 이름 · 상태만. 조정 · 섭정 · 지키는 세력 칸은 그리지 않는다. */
function QuietLines({ vacant, ended }: { readonly vacant: readonly ImperialCourtLine[]; readonly ended: readonly ImperialCourtLine[] }) {
    if (vacant.length === 0 && ended.length === 0) return null;
    return (
        <ul className={styles.quiet} aria-label={vacant.length > 0 ? '공위인 황통' : '끝난 황통'}>
            {vacant.map((l) => (
                <li key={l.code} className={styles.quietRow}>
                    <span className={styles.quietName}>황통 — {l.name}</span>
                    <Chip>공위</Chip>
                </li>
            ))}
            {ended.map((l) => (
                <li key={l.code} className={styles.quietRow}>
                    <span className={styles.quietName}>{l.name} 황통</span>
                    <span className={styles.quietState}>· 끝남</span>
                    <span className={styles.quietNote}>내력은 연감 · 기록에서</span>
                </li>
            ))}
        </ul>
    );
}

/** 서버가 아직 주지 않는 칸 — 값을 짓지 않고 「준비 중」. 어느 계약판 행을 기다리는지 data-server-wait 에 단다. */
function Waiting({ row }: { readonly row: string }) {
    return <span className={styles.wait} data-server-wait={row}>준비 중</span>;
}

function cityName(names: RecordNames, cityId: number): string {
    if (!names.ready) return CHECKING;
    return names.city(cityId) ?? UNKNOWN_CITY;
}

/** 황제가 선 곳 — 성 안이면 그 城, 성 밖이면 구역(이름을 알 때만), 수역이면 「물 위」. */
function placeText(names: RecordNames, provinceName: (id: string) => string | undefined, badge: ImperialBadge): string {
    const where = emperorWhere(badge);
    if (where === 'ON_WATER') return '물 위';
    if (where === 'IN_CITY') return `${cityName(names, badge.emperorCityId!)} · 성 안`;
    const province = provinceName(badge.emperorNodeId);
    return province ? `${province} · 성 밖` : '성 밖';
}

/** 서버 대기 A — 읽기가 아직 없는 영역은 통째로 waiting(가짜 목록 · 빈 표를 그리지 않는다). 패널에 기다리는 계약판 행(K8-10)을 단다. */
function WaitingGrid() {
    return (
        <div className={styles.waiting}>
            <Panel className={styles.box} data-server-wait="K8-10">
                <SectionHeader title="세력과 황실" sub="황통마다 다릅니다" />
                <StatusView kind="waiting" title="아직 없습니다" body="세력과 황실의 관계는 서버가 아직 주지 않습니다." />
            </Panel>
            <Panel className={styles.box} data-server-wait="K8-10">
                <SectionHeader title="조서" sub="우리에게 보이는 것만" />
                <StatusView kind="waiting" title="아직 없습니다" body="조서는 서버가 아직 주지 않습니다. 준비되면 이 자리에 보입니다." />
            </Panel>
            <Panel className={styles.box} data-server-wait="K8-10">
                <SectionHeader title="인장 · 조정 방침" sub="주인과 보관자는 다릅니다" />
                <StatusView kind="waiting" title="아직 없습니다" body="인장과 조정 방침은 서버가 아직 주지 않습니다." />
            </Panel>
        </div>
    );
}

/** 칭제 — 규칙 설계가 없다. 누가 · 어떤 조건에서 · 무엇이 바뀌는지 정해지면 이 칸에 결정과 조건이 들어온다. */
function ClaimCell() {
    return (
        <Panel className={styles.box} data-server-wait="K8-15">
            <SectionHeader title="칭제" sub="황제를 칭하기" />
            <StatusView kind="waiting" title="아직 정해지지 않았습니다" body="누가, 어떤 조건에서 황제를 칭할 수 있는지는 아직 정해지지 않았습니다. 정해지면 이 자리에 보입니다." />
        </Panel>
    );
}
