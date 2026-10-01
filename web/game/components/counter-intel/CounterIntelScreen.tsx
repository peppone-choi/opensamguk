'use client';

// 역정보(P-K06) — K8 설계서 §3 P-K06, 보드 V31K8Misinfo · MMisinfo. 지금은 골격이다(K0 2026-10-01 골격 규칙).
// 시전자 쪽 화면이다 — 내가 건 역정보만 보인다. 피해자 화면에는 「역정보」 · 「가짜」 표식을 내지 않는다(설계서 누출 금지, K6 P-C06과 맞춤).
//  - 내가 건 역정보 목록 · 고른 역정보 = K8-07(시전자 현황 읽기, C5). 서버가 아직 주지 않아 영역 전체 서버 대기다.
//    상대는 세력이 아니라 장수다(victimGeneralId, K0 10-01 22:1x 정정) — 열 이름 「상대 장수」, 소속은 서버 투영을 받아 둘째 줄에 둔다.
//  - 거는 입력(계책 카드)은 계책 덱(P-S01)에서 한다. 카드 이름 · 뜻(반간 · 의병)은 C5 결정 전이라 쓰지 않는다.
//  - 안내 문구는 misinformation-model(S5-6a)의 확정 규칙만 옮긴다: 상대는 가짜인 줄 모른다 · 다시 첩보하면 사라진다 · 싸움 · 보급에 끼지 않는다.
// 모바일은 보드 MMisinfo 대로 목록 → 안내 → 계책 덱 고리다. 고른 역정보 칸은 목록 카드를 누르면 열리는 자리라 목록이 생길 때 붙인다.

import type { ReactNode } from 'react';
import { Panel, SectionHeader, StatusView } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import styles from './counter-intel.module.css';

export default function CounterIntelScreen() {
    return (
        <div className={styles.screen}>
            <Panel className={styles.box} aria-label="내가 건 역정보">
                <SectionHeader title="내가 건 역정보" sub="나에게만 보입니다" />
                <Waiting
                    row="K8-07"
                    title="아직 없습니다"
                    body="상대 장수 · 군국 · 가짜 군세가 보이는 곳 · 남은 순 · 상태는 서버가 아직 주지 않습니다."
                />
            </Panel>
            <div className={styles.col}>
                <Panel className={`${styles.box} ${styles.detail}`} aria-label="고른 역정보">
                    <SectionHeader title="고른 역정보" sub="가짜 군세가 보이는 곳" />
                    <Waiting
                        row="K8-07"
                        title="고를 역정보가 아직 없습니다"
                        body="목록에서 고르면 가짜 군세가 보이는 곳과 남은 순이 이 자리에 보입니다."
                    />
                </Panel>
                <Panel className={styles.box} aria-label="알아 둘 것">
                    <SectionHeader title="알아 둘 것" sub="역정보는 계책 덱의 카드로 겁니다" />
                    <div className={styles.guide}>
                        <p className={styles.guideMain}>상대는 이것이 가짜인 줄 모릅니다. 상대가 그 군국을 다시 첩보하면 사라집니다.</p>
                        <p className={styles.guideSub}>가짜 군세는 싸움 · 보급 길에 끼지 않습니다.</p>
                    </div>
                    <div className={styles.foot}>
                        <CampaignLink slug="stratagem" className="os-button os-button--ghost">계책 덱으로</CampaignLink>
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
