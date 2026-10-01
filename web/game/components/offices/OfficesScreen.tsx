'use client';

// 관직 · 봉신(P-K03 · P-K04) — K8 설계서 §3 P-K03 · P-K04, 보드 V31K8Offices · OfficesLord · MOffices · OfficesStates · Vassals · MVassal*.
// 지금은 골격이다(설계서 §2.2 ① 「GET 도 원장 행도 없음 → 영역 전체 서버 대기 A」, K0 2026-10-01 골격 규칙).
//  - 보드의 칸은 숨기지 않고 서버 대기로 둔다. 칸마다 기다리는 계약판 행을 data-server-wait 에 단다(화면 글자는 쉬운 말).
//    관할 · 앉은 사람 = K8-03(GET /api/court/local-offices), 받은 임명 · 봉신 제안 = K8-02(C5 #1151 GET /api/court/offers),
//    추천 · 자칭 · 중앙 관직 = K8-05(C6), 봉신 계약 = K8-04(GET /api/court/vassals), 받은 원군 요청 = K8-17(K0 10-01 신설).
//  - 입력 단추(court.appoint · dismiss · foundVassal · amendVassal · endVassal)는 입력 원장 행이 없어 그리지 않는다(「원장 행 없음 = 그리지 않음」).
// 관직 · 봉신은 세력 안의 일이라 재야(nationId 0)면 「세력에 속해야 관직이 있습니다」만 보인다.

import { useState, type ReactNode } from 'react';
import { Chip, Panel, PillTabs, SectionHeader, StatusView } from '@opensamguk/ui';
import { useGameSession } from '@/lib/campaign-session';
import styles from './offices.module.css';

type TabKey = 'local' | 'claims' | 'central' | 'vassals';

const TABS: readonly { readonly key: TabKey; readonly label: string }[] = [
    { key: 'local', label: '지방 관직' },
    { key: 'claims', label: '추천 · 자칭' },
    { key: 'central', label: '중앙 관직' },
    { key: 'vassals', label: '봉신' },
];

export default function OfficesScreen() {
    const { frontInfo } = useGameSession();
    const [tab, setTab] = useState<TabKey>('local');
    if ((frontInfo?.general.nationId ?? 0) === 0) {
        return (
            <div className={styles.screen}>
                <StatusView kind="empty" title="세력에 속해야 관직이 있습니다" body="주공에게 출사하면 그 세력의 관직과 봉신이 여기 보입니다." />
            </div>
        );
    }
    return (
        <div className={styles.screen}>
            <PillTabs<TabKey> className={styles.tabs} label="관직 · 봉신 보기" tabs={TABS} value={tab} onChange={setTab} />
            <div role="tabpanel" aria-label={TABS.find((t) => t.key === tab)!.label} className={styles.panel}>
                {tab === 'local' ? <LocalOffices /> : null}
                {tab === 'claims' ? (
                    <Waiting
                        row="K8-05"
                        title="추천 · 자칭 기록이 아직 없습니다"
                        body="추천 → 심의 → 결과, 자칭과 추인의 기록이 이 자리에 보입니다. 서버가 아직 주지 않습니다."
                    />
                ) : null}
                {tab === 'central' ? (
                    <Waiting
                        row="K8-05"
                        title="중앙 관직이 아직 없습니다"
                        body="삼공 · 구경 · 상서 · 장군호는 황실 조서를 받아들여야 생깁니다. 서버가 아직 주지 않습니다."
                    />
                ) : null}
                {tab === 'vassals' ? <Vassals /> : null}
            </div>
        </div>
    );
}

/** 지방 관직 — 왼쪽 관할과 앉은 사람, 오른쪽 고른 관할 · 받은 임명 제안(보드 V31K8Offices). */
function LocalOffices() {
    return (
        <div className={styles.split}>
            <Panel className={styles.box} aria-label="관할과 앉은 사람">
                <SectionHeader title="관할과 앉은 사람" sub="주 → 군국 → 현" />
                <div className={styles.legend} aria-label="상태 풀이">
                    <Chip tone="moss">실권 있음</Chip>
                    <Chip tone="rust">명목</Chip>
                    <Chip tone="info">부임 전</Chip>
                    <Chip tone="info">수락 대기</Chip>
                    <Chip>공석</Chip>
                    <span className={styles.note}>현령은 배치 · 발령으로 정합니다</span>
                </div>
                <Waiting row="K8-03" title="관직 정보가 아직 없습니다" body="관할마다 누가 앉았고 실제로 다스리는지는 서버가 아직 주지 않습니다. 준비되면 이 자리에 보입니다." />
            </Panel>
            <div className={styles.col}>
                <Panel className={styles.box} aria-label="고른 관할">
                    <SectionHeader title="고른 관할" sub="앉은 사람 · 실효 판정 · 이 자리로 할 수 있는 것" />
                    <Waiting row="K8-03" title="아직 없습니다" body="관할을 고르면 앉은 사람과 실제로 다스리는지가 여기 보입니다. 서버가 아직 주지 않습니다." />
                </Panel>
                <Panel className={styles.box} aria-label="받은 임명 제안">
                    <SectionHeader title="받은 임명 제안" sub="응답은 장수 행동을 쓰지 않습니다" />
                    <Waiting row="K8-02" title="아직 없습니다" body="받은 임명 제안은 서버가 아직 주지 않습니다." />
                </Panel>
            </div>
        </div>
    );
}

/** 봉신 — 봉신 계약 목록 · 받은 봉신 제안 · 받은 원군 요청(보드 V31K8Vassals · MVassalSide). */
function Vassals() {
    return (
        <div className={`${styles.split} ${styles.splitVassals}`}>
            <Panel className={styles.box} aria-label="봉신 계약">
                <SectionHeader title="봉신 계약" sub="같은 세력의 봉신 주공" />
                <Waiting row="K8-04" title="아직 없습니다" body="봉신 계약 — 봉토 · 상납 · 원군 · 자치 · 외교권 — 은 서버가 아직 주지 않습니다." />
            </Panel>
            <div className={styles.col}>
                <Panel className={styles.box} aria-label="받은 봉신 제안">
                    <SectionHeader title="받은 봉신 제안" sub="동의해야 맺어집니다" />
                    <Waiting row="K8-02" title="아직 없습니다" body="받은 봉신 제안은 서버가 아직 주지 않습니다." />
                </Panel>
                <Panel className={styles.box} aria-label="받은 원군 요청">
                    <SectionHeader title="받은 원군 요청" sub="봉신의 의무" />
                    <Waiting row="K8-17" title="아직 없습니다" body="받은 원군 요청과 그 응답(수락 · 지연 · 줄여 보냄 · 거절)은 서버가 아직 주지 않습니다." />
                </Panel>
            </div>
        </div>
    );
}

/** 서버 대기 A — 영역 전체 waiting. 기다리는 계약판 행을 data-server-wait 에 단다. */
function Waiting({ row, title, body }: { readonly row: string; readonly title: string; readonly body: ReactNode }) {
    return (
        <div className={styles.wait} data-server-wait={row}>
            <StatusView kind="waiting" title={title} body={body} />
        </div>
    );
}
