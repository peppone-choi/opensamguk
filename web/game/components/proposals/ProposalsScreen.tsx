'use client';

// 참모 제안(P-K05) — K8 설계서 §3 P-K05, 보드 V31K8Proposals · MProposals(사용자 승인 D58 · D59).
// 제안 읽기(계약판 K8-06 `GET /api/retinue/proposals`, 서버 #1408)를 useRetinueProposals 로 읽는다. 서버는 아직 제안을 만드는 producer 가
// 없어 NOT_SEEDED 로 답하고, 행 모양(proposalType · status enum)도 정해지지 않았다.
//  - 경로 없음(404) · NOT_SEEDED → 지금처럼 서버 대기(K8-06). 셈 못 함 · 읽기 실패 → 「읽을 수 없음」(다시 읽기). READY [] → 제안 없음.
//  - 이번 순 제안 목록 · 고른 제안(근거 · 확신 · 기울어진 까닭)은 행이 오면 짓는다. 확신 표시 방식은 서버 식 뒤에 정한다(D58).
//  - 채택 · 고쳐서 채택 · 거부 단추는 그리지 않는다 — 채택은 제안의 입력을 예약 접수 경로로 보내고, 거부 inputId 는 K8-06 결정 대기라
//    입력 원장에 행이 없다(「원장 행 없음 = 그리지 않음」). 원장에 올라오면 보드대로 붙인다.
//  - 안내 문구는 승인 보드 그대로다. 거부 · 만료된 제안은 다시 오지 않는다(D59).
//  - 「회의」(여러 인물 찬반)는 그리지 않는다(D58).
// 모바일은 보드 MProposals 대로 목록 → 안내다. 고른 제안은 카드를 누르면 여는 하단 시트라 목록이 생길 때 붙인다.

import type { ReactNode } from 'react';
import { Panel, SectionHeader, StatusView } from '@opensamguk/ui';
import { useGameSession } from '@/lib/campaign-session';
import { useRetinueProposals, type ProposalsLoad } from '@/lib/use-retinue-proposals';
import styles from './proposals.module.css';

/** 세션 장수로 제안을 읽는다. 세션을 다 읽었는데 장수가 없으면 읽을 장수가 없으니 뼈대에 머물지 않고 「읽을 수 없음」(세션 다시 읽기). */
export default function ProposalsScreen() {
    const { generalId, loading, refresh } = useGameSession();
    const load = useRetinueProposals(generalId);
    return <ProposalsBody load={generalId == null && !loading ? { state: 'unavailable', onReload: refresh } : load} />;
}

const WAITING: ProposalsLoad = { state: 'waiting' };

export function ProposalsBody({ load = WAITING }: { readonly load?: ProposalsLoad }) {
    return (
        <div className={styles.screen}>
            <Panel className={styles.box} aria-label="이번 순 제안">
                <SectionHeader title="이번 순 제안" sub="내 부의 인물이 근거와 함께 올린다 · 나에게만 보인다" />
                {load.state === 'loading' ? <StatusView kind="loading" rows={3} /> : null}
                {load.state === 'waiting' ? (
                    <Waiting
                        row="K8-06"
                        title="이번 순 제안을 서버가 아직 주지 않습니다"
                        body="부의 인물이 순마다 근거와 함께 할 일을 제안합니다. 준비되면 이 자리에 보입니다."
                    />
                ) : null}
                {load.state === 'unavailable' ? (
                    <StatusView kind="unavailable" title="이번 순 제안을 읽을 수 없습니다" onReload={load.onReload} />
                ) : null}
                {load.state === 'empty' ? (
                    <StatusView kind="empty" title="이번 순 제안이 없습니다" body="부의 인물이 올린 제안이 이번 순에는 없습니다." />
                ) : null}
                <p className={styles.foot}>제안은 규칙으로 고른 것입니다 — 인물의 적성 · 성향과 지금 사실을 봅니다.</p>
            </Panel>
            <div className={styles.col}>
                <Panel className={`${styles.box} ${styles.desktopOnly}`} aria-label="고른 제안">
                    <SectionHeader title="고른 제안" sub="근거 · 확신 · 채택 · 고쳐서 채택 · 거부" />
                    <Picked load={load} />
                </Panel>
                <Panel className={styles.box} aria-label="알아 둘 것">
                    <SectionHeader title="알아 둘 것" />
                    <ul className={styles.rules}>
                        <li><span>채택하면 이 명령이 예약 순에 들어갑니다.</span><span>직접 넣은 명령과 같은 검사를 거칩니다.</span></li>
                        <li><span>고쳐서 채택은 명령 흐름에서 인자를 바꿉니다.</span><span>바꾼 뒤에도 같은 검사를 거칩니다.</span></li>
                        <li><span>거부 · 만료된 제안은 다시 오지 않습니다.</span><span>상황이 바뀌면 새 제안으로 옵니다.</span></li>
                    </ul>
                </Panel>
            </div>
        </div>
    );
}

/**
 * 고른 제안이 없을 때 — 서버를 기다리는 상태에서만 서버 대기 표지를 단다. 불러오는 중 · 읽을 수 없음은 왼쪽 패널이 말하므로 여기서는
 * 표지 없이 짧게 둔다(두 패널이 같은 상태를 말하게, 지방 관직 #1397 리뷰와 같은 규칙).
 */
function Picked({ load }: { readonly load: ProposalsLoad }) {
    if (load.state === 'loading') return <StatusView kind="loading" rows={2} />;
    if (load.state === 'waiting') {
        return <Waiting row="K8-06" title="아직 없습니다" body="제안을 고르면 서버가 본 근거와 확신이 여기 보입니다. 서버가 아직 주지 않습니다." />;
    }
    if (load.state === 'empty') return <StatusView kind="empty" title="고를 제안이 없습니다" body="이번 순에 올라온 제안이 생기면 여기서 고를 수 있습니다." />;
    return <p className={styles.note}>제안을 읽은 뒤 여기서 고를 수 있습니다.</p>;
}

function Waiting({ row, title, body }: { readonly row: string; readonly title: string; readonly body: ReactNode }) {
    return (
        <div className={styles.wait} data-server-wait={row}>
            <StatusView kind="waiting" title={title} body={body} />
        </div>
    );
}
