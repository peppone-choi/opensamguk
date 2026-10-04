'use client';

// 주변 세계(P-K08) — 외교(P-K02, K6 DiplomacyPanel)의 「주변 세계」 탭 내용. K8 설계서 §3 P-K08, 보드 V31K8Frontier · MFrontier.
// 지금은 골격이다(K0 2026-10-01 골격 규칙). 행위자 · 관계 · 맞닿은 현 읽기(계약판 K8-09, C5)가 없어 영역 전체 서버 대기다.
// 화면 상태는 셋을 나눈다(서버 필드 이름은 K8-09가 확정된 뒤 읽기에서 옮긴다 — 여기서 짓지 않는다):
//  - waiting     읽기가 아직 없다(지금).
//  - unavailable 서버는 답했지만 접촉 원장이 없다 — 「자료 없음」(D29, StatusView unavailable). 빈 READY 로 그리지 않는다(C5 · K8 대조 39행).
//  - empty       접경한 주변 세계가 없다(내륙 세력).
// 행위자 카드 · 상세(관계 · 맞닿은 현 · 일어난 일 · 사자)는 읽기가 생기면 붙인다. 일어난 일은 frontier 읽기가 아니라 기록(K5)에서 온다.

import { StatusView } from '@opensamguk/ui';
import styles from './frontier.module.css';

export type FrontierLoad =
    | { readonly state: 'waiting' }
    | { readonly state: 'unavailable'; readonly onReload: () => void }
    | { readonly state: 'empty' };

const WAITING: FrontierLoad = { state: 'waiting' };

export function FrontierTab({ load = WAITING }: { readonly load?: FrontierLoad }) {
    return (
        <div className={styles.tab}>
            {load.state === 'waiting' ? (
                <div className={styles.wait} data-server-wait="K8-09">
                    <StatusView
                        kind="waiting"
                        title="주변 세계 준비 중"
                        body="침입 · 조공 · 내속 · 교역을 하는 지도 밖 세력과 우리의 관계, 맞닿은 현은 서버가 아직 주지 않습니다."
                    />
                </div>
            ) : null}
            {load.state === 'unavailable' ? (
                <StatusView kind="unavailable" title="주변 세계를 읽을 수 없습니다" onReload={load.onReload} />
            ) : null}
            {load.state === 'empty' ? (
                <StatusView kind="empty" title="접경한 주변 세계가 없습니다" body="변경 현과 맞닿은 세력에만 나옵니다." />
            ) : null}
            <p className={styles.note}>주변 세계는 지도 밖 세력입니다. 거점은 차지할 수 있지만 세력으로 세우지 않고, 침입 · 조공 · 내속 · 교역은 사건과 외교로 일어납니다.</p>
        </div>
    );
}
