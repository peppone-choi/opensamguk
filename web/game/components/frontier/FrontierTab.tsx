'use client';

// 주변 세계(P-K08) — 외교(P-K02, K6 DiplomacyPanel)의 「주변 세계」 탭 내용. K8 설계서 §3 P-K08, 보드 V31K8Frontier · MFrontier.
// 읽기(계약판 K8-09, 서버 #1407 GET /api/frontier)는 FrontierWorld 가 받아 load 로 넘긴다(lib/use-frontier). 화면 상태:
//  - loading     읽는 중.
//  - waiting     경로가 아직 없다(배포 전, 404) — 서버 대기(K8-09).
//  - unavailable 서버는 답했지만 접촉 원천이 없거나(NOT_SEEDED) 셈하지 못했다 — 「자료 없음」(D29, StatusView unavailable).
//                빈 READY 로 그리지 않는다(C5 · K8 대조 39행).
//  - empty       확인된 무접촉 — 접경한 주변 세계가 없다(내륙 세력, READY []).
// 행위자 카드 · 상세(관계 · 맞닿은 현 · 일어난 일 · 사자)는 서버가 행을 내면 붙인다. 일어난 일은 frontier 읽기가 아니라 기록(K5)에서 온다.

import { StatusView } from '@opensamguk/ui';
import type { FrontierLoad } from '@/lib/use-frontier';
import styles from './frontier.module.css';

export type { FrontierLoad } from '@/lib/use-frontier';

const WAITING: FrontierLoad = { state: 'waiting' };

export function FrontierTab({ load = WAITING }: { readonly load?: FrontierLoad }) {
    return (
        <div className={styles.tab}>
            {load.state === 'loading' ? <StatusView kind="loading" rows={3} /> : null}
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
