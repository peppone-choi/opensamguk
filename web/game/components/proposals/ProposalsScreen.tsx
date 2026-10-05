'use client';

// 참모 제안(P-K05) — K8 설계서 §3 P-K05, 보드 V31K8Proposals · MProposals(사용자 승인 D58 · D59). 지금은 골격이다(K0 2026-10-01 골격 규칙).
// 제안 읽기(계약판 K8-06 `GET /api/retinue/proposals` → v2 P-5 필드 + inputId · argsDraft, C5)가 서버에 없어 영역 전체 서버 대기다.
//  - 이번 순 제안 목록 · 고른 제안(근거 · 확신 · 기울어진 까닭) = K8-06. 확신 표시 방식은 서버 식 뒤에 정한다(D58).
//  - 채택 · 고쳐서 채택 · 거부 단추는 그리지 않는다 — 채택은 제안의 입력을 예약 접수 경로로 보내고, 거부 inputId 는 K8-06 결정 대기라
//    입력 원장에 행이 없다(「원장 행 없음 = 그리지 않음」). 원장에 올라오면 보드대로 붙인다.
//  - 안내 문구는 승인 보드 그대로다. 거부 · 만료된 제안은 다시 오지 않는다(D59).
//  - 「회의」(여러 인물 찬반)는 그리지 않는다(D58).
// 모바일은 보드 MProposals 대로 목록 → 안내다. 고른 제안은 카드를 누르면 여는 하단 시트라 목록이 생길 때 붙인다.

import type { ReactNode } from 'react';
import { Panel, SectionHeader, StatusView } from '@opensamguk/ui';
import styles from './proposals.module.css';

export default function ProposalsScreen() {
    return (
        <div className={styles.screen}>
            <Panel className={styles.box} aria-label="이번 순 제안">
                <SectionHeader title="이번 순 제안" sub="내 부의 인물이 근거와 함께 올린다 · 나에게만 보인다" />
                <Waiting
                    row="K8-06"
                    title="이번 순 제안을 서버가 아직 주지 않습니다"
                    body="부의 인물이 순마다 근거와 함께 할 일을 제안합니다. 준비되면 이 자리에 보입니다."
                />
                <p className={styles.foot}>제안은 규칙으로 고른 것입니다 — 인물의 적성 · 성향과 지금 사실을 봅니다.</p>
            </Panel>
            <div className={styles.col}>
                <Panel className={`${styles.box} ${styles.desktopOnly}`} aria-label="고른 제안">
                    <SectionHeader title="고른 제안" sub="근거 · 확신 · 채택 · 고쳐서 채택 · 거부" />
                    <Waiting row="K8-06" title="아직 없습니다" body="제안을 고르면 서버가 본 근거와 확신이 여기 보입니다. 서버가 아직 주지 않습니다." />
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

function Waiting({ row, title, body }: { readonly row: string; readonly title: string; readonly body: ReactNode }) {
    return (
        <div className={styles.wait} data-server-wait={row}>
            <StatusView kind="waiting" title={title} body={body} />
        </div>
    );
}
