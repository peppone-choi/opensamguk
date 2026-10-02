'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { Chip, Seg, StatusView, plainReadError, useViewportClass, type InputAvailability } from '@opensamguk/ui';
import { api } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import { countyHead, countyPolicy, countyStock, countyVision, countyWorks, indicatorRows, readState } from '@/lib/county-view';
import { availabilityOf } from '@/lib/input-availability';
import type { MapPreviewResponse } from '@/lib/types';
import { Governance, HeadChips, HereActions, Indicators, Section, ServerWaiting, Specialties, StockRow, WorksBlock } from './CountyParts';
import styles from './county.module.css';

export interface CountyScreenProps {
    /** 경로의 현 id. null 이면 「이 현을 찾을 수 없습니다」. */
    readonly cityId: number | null;
    readonly hrefs: {
        /** 영지 화면(view 를 주면 그 칸으로 `?view=placement|policy|work`) — 바꾸기 시트는 거기 있다. */
        readonly territory: (view?: 'placement' | 'policy' | 'work') => string;
        readonly court: string;
        readonly records: string;
        /** 명령 흐름(작전실 `?…`) — 쿼리 부분을 받는다. */
        readonly flow: (query: string) => string;
    };
}

type Pane = 'state' | 'gov' | 'works' | 'people' | 'events';
type PreviewLoad = { readonly kind: 'loading' } | { readonly kind: 'ready'; readonly data: MapPreviewResponse } | { readonly kind: 'error'; readonly code: string | null };

/** 남의 현 입력은 모두 점선 + 보드 문구(보드 V31K4CountyIntel). 서버 사유 코드가 없어 문장만 둔다. */
const NOT_MINE = { available: false, reason: '우리 현이 아닙니다' } as const;

/**
 * 현 상세 본문(P-T02) — 머리(이름 · 칩) · 계절 띠(서버 대기) · 세 칸(형편 440 / 다스림 · 공사 / 사람 · 수비군 · 사건 · 할 일 360).
 * 모바일: 머리 · 칩 · 「형편 · 다스림 · 공사 · 사람 · 사건」 세그먼트 · 아래 「여기로 명령」.
 * 縣 상세 읽기(K4-04)가 오기 전이라 7지표(내 장수가 선 현 말고) · 수비군 · 이 현의 사람 · 최근 사건(K5-07)은 서버 대기다.
 */
export function CountyScreen({ cityId, hrefs }: CountyScreenProps) {
    const viewport = useViewportClass();
    const mobile = viewport === null ? null : viewport === 'mobile';
    const { frontInfo } = useGameSession();
    const router = useRouter();
    const [attempt, setAttempt] = useState(0);
    const [preview, setPreview] = useState<PreviewLoad>({ kind: 'loading' });
    const [pane, setPane] = useState<Pane>('state');
    const county = useCampaignRead((id, s) => (cityId == null ? Promise.resolve(null) : api.campaignCounty(id, cityId, s)), [cityId, attempt]);
    const policies = useCampaignRead((id, s) => api.campaignPolicies(id, s), [attempt]);
    const works = useCampaignRead((id, s) => api.campaignWorks(id, s), [attempt]);
    const warehouses = useCampaignRead((id, s) => api.warehouses(id, s), [attempt]);
    const visibility = useCampaignRead((id, s) => api.campaignVisibility(id, s), [attempt]);

    useEffect(() => {
        const controller = new AbortController();
        setPreview({ kind: 'loading' });
        api.mapPreview(controller.signal).then(
            (data) => setPreview({ kind: 'ready', data }),
            (e: unknown) => { if (!controller.signal.aborted) setPreview({ kind: 'error', code: e instanceof Error ? plainReadError(e.message).code : null }); },
        );
        return () => controller.abort();
    }, [attempt]);

    if (mobile === null || preview.kind === 'loading') return <StatusView kind="loading" rows={6} />;
    if (preview.kind === 'error') {
        return <StatusView kind="error" title="현을 불러오지 못했습니다" errorCode={preview.code ?? undefined} onRetry={() => setAttempt((n) => n + 1)} />;
    }
    const city = cityId == null ? null : preview.data.cities.find((c) => c.id === cityId) ?? null;
    if (!city) {
        return (
            <StatusView kind="empty" title="이 현을 찾을 수 없습니다" body="주소의 현이 이 세계에 없습니다. 영지에서 현을 고르세요."
                actions={<Link href={hrefs.territory()} className="os-button">영지로</Link>} />
        );
    }

    const head = countyHead(city, preview.data.nations, { nationId: frontInfo?.nation?.id ?? null, cityId: frontInfo?.city?.id ?? null });
    const vision = countyVision(visibility.data, head.commanderyName, head.mine);
    const policy = countyPolicy(policies.data, city.id);
    const work = countyWorks(works.data, city.id);
    const stock = countyStock(warehouses.data, city.id, head.mine);
    const rows = indicatorRows(frontInfo?.city, city.id);
    const generalName = frontInfo?.general.name ?? '내 장수';

    const mineOr = (inputId: string, own: () => InputAvailability | null) => (head.mine ? own() : availabilityOf(inputId, { options: NOT_MINE }));
    const placement = mineOr('placement.assign', () => availabilityOf('placement.assign'));
    const policySet = mineOr('policy.set', () => availabilityOf('policy.set', {
        options: policy ? { available: policy.settable, code: policy.blocked?.code, reason: policy.blocked?.reason } : null,
    }));
    const workStart = mineOr('work.start', () => availabilityOf('work.start'));
    const scoutable = !head.mine && (vision.tier === 'INTEL' || vision.tier === 'FOG');
    const scoutQuery = `do=action.scout${vision.commanderyId ? `&target=commandery:${vision.commanderyId}` : ''}`;
    const hereHref = hrefs.flow(`target=county:${city.id}`);

    const go = (href: string) => router.push(href);
    const state = (
        <>
            {vision.tier === 'INTEL' ? <p className={styles.note}>{`${vision.ageTurns == null ? '첩보로 본' : `${vision.ageTurns}순 전 첩보로 본`} 현입니다 — 지금 값과 다를 수 있습니다.`}</p> : null}
            <Indicators rows={rows} />
            <Specialties county={county.data} failed={county.error != null} mine={head.mine} />
            <StockRow stock={stock} />
        </>
    );
    const gov = (
        <Governance policy={policy} state={readState(policies)} mine={head.mine} placement={placement} policySet={policySet} courtHref={hrefs.court}
            onPlacement={() => go(hrefs.territory('placement'))} onPolicy={() => go(hrefs.territory('policy'))} />
    );
    const worksBlock = <WorksBlock works={work} state={readState(works)} mine={head.mine} start={workStart} onStart={() => go(hrefs.territory('work'))} />;
    const people = (
        <>
            <ServerWaiting title="이 현에 있는 사람 · 군단 — 서버 대기" body="이 현에 있는 인물 · 군단 목록은 현 상세 읽기가 오면 보입니다." />
            <ServerWaiting title="수비군 — 서버 대기" body="수비군 병력 · 훈련 · 사기를 주는 읽기가 아직 없습니다." />
        </>
    );
    const events = (
        <>
            <ServerWaiting title="최근 사건 — 서버 대기" body="기록의 현 거르기가 오면 이 현 사건만 보입니다." />
            <Link href={hrefs.records} className={styles.link}>기록 전체 보기 →</Link>
        </>
    );
    const here = (
        <HereActions here={head.here} generalName={generalName} hereHref={hereHref}
            scout={scoutable ? availabilityOf('action.scout') : null} onScout={scoutable ? () => go(hrefs.flow(scoutQuery)) : null} />
    );
    const title = <h3 className={`os-serif ${styles.name}`}>{head.name}</h3>;
    const season = (
        <div className={styles.band} role="status">
            <span>계절 사건 — 이 현에 계절 사건이 나면 여기에 경고와 대응이 보입니다.</span>
            <Chip tone="info">서버 대기</Chip>
        </div>
    );
    const retry = county.error || policies.error || works.error || visibility.error ? (
        <div className={styles.errRow} role="status">
            <span className={styles.errText}>일부를 불러오지 못했습니다.</span>
            <button type="button" className="os-button os-button--sm" onClick={() => setAttempt((n) => n + 1)}>다시 읽기</button>
        </div>
    ) : null;

    if (mobile) {
        return (
            <div className={styles.screenMobile}>
                <div className={styles.head}>{title}<HeadChips head={head} vision={vision} /></div>
                {retry}
                <Seg label="보기" value={pane} onChange={setPane} scroll
                    options={[{ value: 'state', label: '형편' }, { value: 'gov', label: '다스림' }, { value: 'works', label: '공사' }, { value: 'people', label: '사람' }, { value: 'events', label: '사건' }]} />
                {pane === 'state' ? <>{state}{season}</> : pane === 'gov' ? gov : pane === 'works' ? worksBlock : pane === 'people' ? people : events}
                <div className={styles.footBar}>{here}</div>
            </div>
        );
    }
    return (
        <div className={styles.screen}>
            <div className={styles.head}>{title}<HeadChips head={head} vision={vision} /></div>
            {season}
            {retry}
            <div className={styles.columns}>
                <Section title="형편" sub="호구 · 전답 · 시장 · 치안 · 민심 · 방비 · 성벽" className={styles.colLeft}>{state}</Section>
                <div className={styles.colMid}>
                    <Section title="다스림" sub="현령 · 방침">{gov}</Section>
                    <Section title="공사" sub="이 현">{worksBlock}</Section>
                </div>
                <div className={styles.colRight}>
                    <Section title="이 현에 있는 사람 · 군단" label="사람">{people}</Section>
                    <Section title="최근 사건" sub="이 현">{events}</Section>
                    <Section title={scoutable ? '다시 보기 · 명령' : '여기서 할 일'} label="여기서 할 일">{here}</Section>
                </div>
            </div>
        </div>
    );
}
