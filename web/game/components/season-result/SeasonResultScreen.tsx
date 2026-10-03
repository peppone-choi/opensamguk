'use client';

// 시즌 결산(P-H05) — K8 설계서 §3 P-H05, 보드 V31K8Season · MSeason(사용자 승인 D36). 지금은 골격이다(K0 2026-10-01 골격 규칙).
// 시즌 결과 읽기(계약판 K8-14 `GET /api/season`, C4)가 SERVER_DONE 이 아니라 값 칸은 모두 서버 대기다(K0 10-02).
//  - 시즌 결과(통일한 세력 · 기한 종료 · 정해진 때 · 마지막 판도 · 연감) · 주요 인물 · 시즌(시작 · 끝 · 상태) = K8-14.
//    시즌이 끝났는지 모르므로 「끝났습니다」 · 턴 멈춤 띠를 그리지 않는다. 주요 인물을 고르는 기준과 결과 값은 짓지 않는다.
//  - 다음 시즌 안내는 정책 그대로라 지금 보인다: 이월 없음(spec §1) · 계정은 남는다(roadmap 공개 알파 운영 정책).
//  - 그리지 않는 것: 명예의 전당(09-26 결정 6) · 개인 결산 통계 · 시즌 끝 조건(기한 [미정]).
// 모바일은 보드 MSeason 대로 시즌 결과 → 주요 인물 → 다음 시즌이다(시즌 칸은 데스크톱 오른쪽 열에만).

import type { ReactNode } from 'react';
import { Panel, SectionHeader, StatusView } from '@opensamguk/ui';
import styles from './season-result.module.css';

export default function SeasonResultScreen() {
    return (
        <div className={styles.screen}>
            <div className={styles.col}>
                <Panel className={styles.box} aria-label="시즌 결과">
                    <SectionHeader title="시즌 결과" sub="통일한 세력 · 마지막 판도" />
                    <Waiting row="K8-14" title="시즌 결과를 서버가 아직 주지 않습니다" body="준비되면 이 자리에 바로 보입니다." />
                </Panel>
                <Panel className={styles.box} aria-label="주요 인물">
                    <SectionHeader title="주요 인물" sub="서버가 고른 사람" />
                    <Waiting row="K8-14" title="아직 없습니다" body="누구를 고르는지는 서버가 정합니다. 서버가 아직 주지 않습니다." />
                </Panel>
            </div>
            <div className={styles.col}>
                <Panel className={`${styles.box} ${styles.facts}`} aria-label="시즌">
                    <SectionHeader title="시즌" sub="진행 · 통일 · 끝남" />
                    <Waiting row="K8-14" title="아직 없습니다" body="시작 · 끝 · 상태는 서버가 아직 주지 않습니다." />
                </Panel>
                <Panel className={styles.box} aria-label="다음 시즌">
                    <SectionHeader title="다음 시즌" sub="넘어가는 것이 없습니다" />
                    <div className={styles.next}>
                        <p className={styles.nextMain}>통일되거나 시즌이 끝나면 천하를 새로 엽니다. 장수 · 부 · 자원은 다음 시즌으로 넘어가지 않습니다.</p>
                        <p className={styles.nextSub}>계정은 남고, 끝난 시즌의 결과 · 연감 · 기록은 계속 볼 수 있습니다.</p>
                    </div>
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
