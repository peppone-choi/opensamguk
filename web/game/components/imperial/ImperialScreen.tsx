'use client';

// 황실(P-K09) — K8 설계서 §3 P-K09, 보드 V31K8Imperial · MImperial · ImperialStates.
// 서버가 지금 주는 것은 황제 소재지(GET /api/imperial/presence)뿐이다. 그 칸만 실제 값으로 그린다.
//  - 세력과 황실 · 조서 · 인장 · 조정 방침: 관찰자 투영 읽기(계약판 K8-10, C6)가 오기 전까지 서버 대기 A(영역 전체 waiting).
//  - 칭제: 규칙 설계가 없다(K8-15, 등급 D) — 자리 한 칸만 둔다(설계서 P-K11 「지금 할 수 있는 것」).
//  - 섭정 · 조정을 지키는 세력 · 조정 상태는 소재지 응답에 없다 — 그리지 않는다(짐작 금지).
// 이름은 화면이 가진 자료로만 푼다(기록 화면과 같은 규칙, lib/records-names): 城은 지도 미리보기, 인물은 내 장수뿐(그 밖은 「어느 인물」),
// 구역은 이번 접속에서 지도를 받았을 때만. 지도 미리보기는 황제가 있을 때만 받는다.

import { KV, Panel, SectionHeader, StatusView, useProvinceName } from '@opensamguk/ui';
import { emperorWhere, useImperialPresence, type ImperialBadge } from '@/lib/imperial';
import { useGameSession } from '@/lib/campaign-session';
import { useRecordNames, type RecordNames } from '@/lib/records-names';
import styles from './imperial.module.css';

const UNKNOWN_PERSON = '어느 인물';
const UNKNOWN_CITY = '어느 성';
const CHECKING = '확인 중';

export default function ImperialScreen() {
    const presence = useImperialPresence();

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
                        <StatusView kind="empty" title="지금 황제가 없습니다" body="제위가 비어 있습니다. 누가 오를지는 황통의 후계 규칙이 정합니다." />
                    ) : (
                        <Lines badges={view.badges} />
                    )}
                    <WaitingGrid />
                </>
            )}
            <ClaimCell />
        </div>
    );
}

/** 황통 카드들 — 이 컴포넌트가 그려질 때만(황제가 있을 때만) 이름을 받는다. */
function Lines({ badges }: { readonly badges: readonly ImperialBadge[] }) {
    const session = useGameSession();
    const general = session.frontInfo?.general ?? null;
    const names = useRecordNames(session.generalId ?? null, general?.name ?? null);
    const provinceName = useProvinceName();
    return (
        <div className={styles.lines}>
            {badges.map((badge) => (
                <Panel key={badge.lineCode} className={styles.line} aria-label={`황통 — ${badge.lineName}`}>
                    <SectionHeader title={`황통 — ${badge.lineName}`} sub="황통은 여럿일 수 있습니다" />
                    <KV
                        className={styles.facts}
                        items={[
                            { k: '황제', v: personName(names, badge.emperorGeneralId) },
                            { k: '있는 곳', v: placeText(names, provinceName, badge) },
                            { k: '조정', v: badge.courtCityId === null ? '정하지 않음' : cityName(names, badge.courtCityId) },
                        ]}
                    />
                </Panel>
            ))}
        </div>
    );
}

function personName(names: RecordNames, generalId: number): string {
    return names.general?.(generalId) ?? UNKNOWN_PERSON;
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

/** 서버 대기 A — 읽기가 아직 없는 영역은 통째로 waiting(가짜 목록 · 빈 표를 그리지 않는다). */
function WaitingGrid() {
    return (
        <div className={styles.waiting}>
            <Panel className={styles.box}>
                <SectionHeader title="세력과 황실" sub="황통마다 다릅니다" />
                <StatusView kind="waiting" title="아직 없습니다" body="세력과 황실의 관계는 서버가 아직 주지 않습니다." />
            </Panel>
            <Panel className={styles.box}>
                <SectionHeader title="조서" sub="우리에게 보이는 것만" />
                <StatusView kind="waiting" title="아직 없습니다" body="조서는 서버가 아직 주지 않습니다. 준비되면 이 자리에 보입니다." />
            </Panel>
            <Panel className={styles.box}>
                <SectionHeader title="인장 · 조정 방침" sub="주인과 보관자는 다릅니다" />
                <StatusView kind="waiting" title="아직 없습니다" body="인장과 조정 방침은 서버가 아직 주지 않습니다." />
            </Panel>
        </div>
    );
}

/** 칭제 — 규칙 설계가 없다. 누가 · 어떤 조건에서 · 무엇이 바뀌는지 정해지면 이 칸에 결정과 조건이 들어온다. */
function ClaimCell() {
    return (
        <Panel className={styles.box}>
            <SectionHeader title="칭제" sub="황제를 칭하기" />
            <StatusView kind="waiting" title="아직 정해지지 않았습니다" body="누가, 어떤 조건에서 황제를 칭할 수 있는지는 아직 정해지지 않았습니다. 정해지면 이 자리에 보입니다." />
        </Panel>
    );
}
