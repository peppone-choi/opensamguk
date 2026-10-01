'use client';

// 천하 형세(P-H04) — K8 설계서 §3 P-H04, 보드 V31K8Unification · MUnification. 지금은 골격이다(K0 2026-10-01 골격 규칙).
// 통일 규칙은 사용자 결정 그대로다: 190년 漢 13州 · 郡國 전부를 쥔다 AND 칭제, 호구 문턱 없음, 郡國 밖 거점은 세지 않는다.
// 서버가 아직 주지 않는 칸은 숨기지 않고 서버 대기로 둔다. 기다리는 계약판 행을 data-server-wait 에 단다(화면 글자는 쉬운 말).
//  - 주마다 모두 쥔 세력 · 세력별 진척 · 내 몫 = K8-13(통일 판정 스냅숏, C4).
//  - 「쥔다」의 뜻 = 미정(설계 제안 「그 郡의 모든 육지 구역을 지배」가 확정 전) — 짓지 않는다.
//  - 칭제 = K8-15(칭제 규칙 설계, C4 · C6).
// 13州는 지도 州 층과 같은 데이터 키(JU_NAMES)를 쓰고, 찍기 직전에만 화면 이름으로 바꾼다(juDisplayName — 사용자 결정 D25: 사례 · 양주 · 서량).
// 「지도에서 보기 — 주 경계」는 보드 그대로 두되, 작전실 지도를 州 보기로 여는 주소가 아직 없어 사유가 있는 비활성이다(K0 10-02).
// 주소(작전실 `?view=ju`)가 들어오면 이 단추를 켠다.

import type { ReactNode } from 'react';
import { Icon, JU_NAMES, Panel, ReasonTooltip, SectionHeader, StatusView, juDisplayName } from '@opensamguk/ui';
import styles from './unification.module.css';

export default function UnificationScreen() {
    return (
        <div className={styles.screen}>
            <Panel className={styles.zhou} aria-label="13주">
                <SectionHeader title="13주" sub="주마다 모두 쥔 세력" />
                <ul className={styles.grid} aria-label="13주">
                    {JU_NAMES.map((key) => (
                        <li key={key} className={styles.tile} data-ju={key}>
                            <span className={styles.tileName}>{juDisplayName(key)}</span>
                            <WaitChip row="K8-13" />
                        </li>
                    ))}
                    <li className={styles.legend}>190년 한의 13주 · 군국만 셉니다. 군국 밖 거점은 세지 않습니다.</li>
                </ul>
                <div className={styles.foot}>
                    <ReasonTooltip title={MAP_NOT_READY.title} reason={MAP_NOT_READY.reason}>
                        <button type="button" className="os-button os-button--ghost os-button--disabled" aria-disabled="true">
                            <Icon name="war-room" />지도에서 보기 — 주 경계
                        </button>
                    </ReasonTooltip>
                </div>
            </Panel>
            <div className={styles.col}>
                <Panel className={styles.box} aria-label="통일 조건">
                    <SectionHeader title="통일 조건" sub="둘 다 이루면 시즌이 끝납니다" />
                    <div className={styles.conds}>
                        <div className={styles.cond}>
                            <span className={styles.condTitle}>① 13주 · 군국을 모두 쥔다</span>
                            <span className={styles.condBody}>「쥔다」의 뜻은 아직 정해지지 않았습니다.</span>
                        </div>
                        <div className={styles.cond}>
                            <span className={styles.condTitle}>② 칭제</span>
                            <span className={styles.condBody}>칭제 규칙은 아직 정해지지 않았습니다.</span>
                            <WaitChip row="K8-15" />
                        </div>
                    </div>
                </Panel>
                <Panel className={styles.box} aria-label="세력">
                    <SectionHeader title="세력" sub="통일에 얼마나 가까운가" />
                    <Waiting row="K8-13" title="아직 없습니다" body="세력마다 쥔 군국 수 · 모두 쥔 주 · 칭제 여부는 서버가 아직 주지 않습니다." />
                </Panel>
                <Panel className={styles.box} aria-label="내 몫">
                    <SectionHeader title="내 몫" sub="내가 속한 세력이 쥔 군국" />
                    <Waiting row="K8-13" title="아직 없습니다" body="우리 세력이 쥔 군국과 모두 쥔 주는 서버가 아직 주지 않습니다." />
                </Panel>
            </div>
        </div>
    );
}

const MAP_NOT_READY = {
    title: '지도 주소 준비 중',
    reason: '작전실 지도를 주 경계 보기로 바로 여는 주소가 아직 없습니다. 준비되면 이 단추로 열립니다.',
} as const;

function WaitChip({ row }: { readonly row: string }) {
    return <span className={styles.wait} data-server-wait={row}>준비 중</span>;
}

function Waiting({ row, title, body }: { readonly row: string; readonly title: string; readonly body: ReactNode }) {
    return (
        <div className={styles.waitBox} data-server-wait={row}>
            <StatusView kind="waiting" title={title} body={body} />
        </div>
    );
}
