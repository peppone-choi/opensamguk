'use client';

// 세력(P-K10) — K8 설계서 §3 P-K10, 보드 V31K8Realm · RealmUnits · MRealm. 옛 「세력 정보」(/game/my-nation)를 대신한다.
// 서버가 지금 주는 것만 실제 값으로 그린다:
//  - 세력 요약(GET /api/nation/summary, 계약판 K4-09 + K8-11): 깃발 · 이름 · 군주 · 수도 · 다스리는 현 · 소속 인물 · 호구 · 병력 · 창고 합.
//    `stockTotal` 은 수도 창고가 아니라 다스리는 모든 城 창고의 합이다(CampaignDirectoryReader) — 라벨도 「창고 합」.
//  - 현 목록(GET /api/counties?scope=NATION): 이름 · 수도 표시 · 한 달 예상 세입(지금 상태로 본 예측, 볼 수 없으면 —).
// 정체성 · 제도 · 편제 전통은 읽기 API 가 없다(계약판 K8-12, C6) — 서버 대기 A. 바꾸는 조작은 그리지 않는다.
// null 은 「볼 수 없거나 권한 밖」이다 — 0 으로 바꾸지 않고 「—」로 적는다(directory-reads 약속).

import { useCallback, useEffect, useState } from 'react';
import { Chip, Flag, KV, Panel, PillTabs, SectionHeader, StatusView } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import { campaignPartialNotice, campaignReadNotice } from '@/components/campaign/GameStates';
import { useTurnRefresh } from '@/hooks/useTurnRefresh';
import { api } from '@/lib/api';
import { useGameSession } from '@/lib/campaign-session';
import type { CountyDirectory, NationSummary } from '@/lib/directory-reads';
import { formatNumber } from '@/lib/format';
import styles from './realm.module.css';

type Load<T> = { readonly state: 'loading' } | { readonly state: 'ready'; readonly data: T } | { readonly state: 'error' };
type TabKey = 'counties' | 'identity' | 'institutions' | 'traditions';

const TABS: readonly { readonly key: TabKey; readonly label: string }[] = [
    { key: 'counties', label: '현 목록' },
    { key: 'identity', label: '정체성' },
    { key: 'institutions', label: '제도' },
    { key: 'traditions', label: '편제 전통' },
];

const DASH = '—';
const num = (n: number | null | undefined) => (n == null ? DASH : formatNumber(n));

function useRead<T>(read: ((signal: AbortSignal) => Promise<T>) | null, seq: number): Load<T> {
    const [load, setLoad] = useState<Load<T>>({ state: 'loading' });
    useEffect(() => {
        if (!read) return;
        const controller = new AbortController();
        setLoad((l) => (l.state === 'ready' ? l : { state: 'loading' }));
        read(controller.signal).then(
            (data) => { if (!controller.signal.aborted) setLoad({ state: 'ready', data }); },
            () => { if (!controller.signal.aborted) setLoad({ state: 'error' }); },
        );
        return () => controller.abort();
    }, [read, seq]);
    return load;
}

export default function RealmScreen() {
    const { generalId } = useGameSession();
    const [seq, setSeq] = useState(0);
    const reload = useCallback(() => setSeq((n) => n + 1), []);
    useTurnRefresh(reload);
    const readSummary = useCallback((signal: AbortSignal) => api.nationSummary(generalId!, signal), [generalId]);
    const readCounties = useCallback((signal: AbortSignal) => api.counties(generalId!, 'NATION', null, signal), [generalId]);
    const summary = useRead<NationSummary>(generalId == null ? null : readSummary, seq);
    const counties = useRead<CountyDirectory>(generalId == null ? null : readCounties, seq);
    const [tab, setTab] = useState<TabKey>('counties');

    if (summary.state === 'loading') return <div className={styles.screen}><StatusView kind="loading" rows={3} /></div>;
    if (summary.state === 'error') {
        return <div className={styles.screen}><StatusView kind="error" title="세력 정보를 지금 읽을 수 없습니다" body="잠시 뒤 다시 해 보세요." onRetry={reload} /></div>;
    }
    const s = summary.data;
    if (s.status === 'NO_NATION') {
        return (
            <div className={styles.screen}>
                <StatusView kind="empty" title="소속 세력이 없습니다" body={campaignReadNotice({ loading: false, error: null }, s.status)} />
            </div>
        );
    }
    if (!s.nation || (s.status !== 'READY' && s.status !== 'PARTIAL')) {
        return (
            <div className={styles.screen}>
                <StatusView kind="error" title="세력 정보를 지금 읽을 수 없습니다" body={campaignReadNotice({ loading: false, error: null }, s.status) ?? '잠시 뒤 다시 해 보세요.'} errorCode={s.status} onRetry={reload} />
            </div>
        );
    }
    const capitalName = s.capitalCityId == null || counties.state !== 'ready'
        ? null
        : counties.data.counties.find((c) => c.cityId === s.capitalCityId)?.name ?? null;

    return (
        <div className={styles.screen}>
            <Panel className={styles.band} aria-label="세력 요약">
                <div className={styles.who}>
                    <Flag color={s.nation.color} size={28} label={`${s.nation.name} 깃발`} />
                    <div className={styles.names}>
                        <h3 className={styles.name}>{s.nation.name}</h3>
                        <span className={styles.sub}>
                            군주 {s.lord?.name ?? DASH} · 수도 {s.capitalCityId == null ? '없음' : capitalName ?? '확인 중'}
                        </span>
                    </div>
                </div>
                <KV
                    className={styles.facts}
                    items={[
                        { k: '다스리는 현', v: num(s.countyCount) },
                        { k: '소속 인물', v: num(s.retinueCount) },
                        { k: '호구', v: num(s.population) },
                        { k: '병력', v: s.troops == null ? DASH : `성 ${num(s.troops.city)} · 부곡 ${num(s.troops.bugok)}` },
                        { k: '창고 합', v: s.stockTotal == null ? DASH : `금 ${num(s.stockTotal.money)} · 쌀 ${num(s.stockTotal.grain)}` },
                    ]}
                />
                <CampaignLink slug="territory/supply" className={`os-button os-button--ghost ${styles.supply}`}>창고망 보기</CampaignLink>
                {campaignPartialNotice(s.status) ? <p className={styles.notice} role="note">{campaignPartialNotice(s.status)}</p> : null}
            </Panel>

            <PillTabs<TabKey> className={styles.tabs} label="세력 보기" tabs={TABS} value={tab} onChange={setTab} />
            <div role="tabpanel" aria-label={TABS.find((t) => t.key === tab)!.label} className={styles.panel}>
                {tab === 'counties' ? <CountyList load={counties} capitalCityId={s.capitalCityId} onRetry={reload} /> : null}
                {tab === 'identity' ? (
                    <StatusView kind="waiting" title="정체성은 아직 없습니다" body="세력의 성격(누구에게 정당한가 · 통치 단계 · 조직망)은 결정과 행동으로 천천히 바뀝니다. 서버가 아직 주지 않습니다." />
                ) : null}
                {tab === 'institutions' ? (
                    <StatusView kind="waiting" title="제도 확산이 아직 없습니다" body="제도를 세력이 정하고, 담당관이 시범 군현에서 시행하고, 넓혀 가는 흐름이 여기 보입니다. 서버가 아직 주지 않습니다." />
                ) : null}
                {tab === 'traditions' ? (
                    <StatusView kind="waiting" title="편제 전통은 아직 없습니다" body="실명 · 지역 부대와 우리가 편성할 수 있는지는 서버가 아직 주지 않습니다. 준비되면 이 자리에 보입니다." />
                ) : null}
            </div>
        </div>
    );
}

function CountyList({ load, capitalCityId, onRetry }: { readonly load: Load<CountyDirectory>; readonly capitalCityId: number | null; readonly onRetry: () => void }) {
    if (load.state === 'loading') return <StatusView kind="loading" rows={4} />;
    if (load.state === 'error') return <StatusView kind="error" title="현 목록을 지금 읽을 수 없습니다" body="잠시 뒤 다시 해 보세요." onRetry={onRetry} />;
    const d = load.data;
    const notice = campaignReadNotice({ loading: false, error: null }, d.status);
    if (notice) return <StatusView kind="error" title="현 목록을 지금 읽을 수 없습니다" body={notice} errorCode={d.status} onRetry={onRetry} />;
    if (d.counties.length === 0) return <StatusView kind="empty" title="다스리는 현이 없습니다" body="현을 얻으면 이 목록에 보입니다." />;
    // 수도를 맨 위에, 나머지는 서버 순서 그대로.
    const rows = [...d.counties].sort((a, b) => Number(b.cityId === capitalCityId) - Number(a.cityId === capitalCityId));
    return (
        <Panel className={styles.counties}>
            <SectionHeader title="다스리는 현" sub={`${formatNumber(d.counties.length)}곳 · 한 달 예상 세입은 지금 상태로 본 예측`} />
            {campaignPartialNotice(d.status) ? <p className={styles.notice} role="note">{campaignPartialNotice(d.status)}</p> : null}
            <ul className={styles.rows} aria-label="다스리는 현">
                {rows.map((c) => (
                    <li key={c.cityId} className={styles.row}>
                        <span className={styles.county}>{c.name}</span>
                        {c.cityId === capitalCityId ? <Chip tone="bronze">수도</Chip> : null}
                        <span className={styles.income}>{c.income == null ? DASH : `금 ${formatNumber(c.income.money)} · 쌀 ${formatNumber(c.income.grain)}`}</span>
                    </li>
                ))}
            </ul>
        </Panel>
    );
}
