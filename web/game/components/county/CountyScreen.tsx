'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { Seg, StatusView, plainReadError, useViewportClass, type InputAvailability } from '@opensamguk/ui';
import { api } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import { useCountyDetail } from '@/hooks/useCountyDetail';
import { detailIndicatorCells, garrisonRows, gradeLabel, hiddenText, peopleHereRows } from '@/lib/county-detail';
import { countyHead, countyPolicy, countyStock, countyVision, countyWorks, indicatorRows, provinceRecordIdOf, readState } from '@/lib/county-view';
import { availabilityOf } from '@/lib/input-availability';
import type { MapPreviewResponse } from '@/lib/types';
import { Garrison, Governance, HeadChips, HereActions, Indicators, PeopleHere, Section, ServerWaiting, Specialties, StockRow, WorksBlock } from './CountyParts';
import SeasonEventBand from '@/components/season/SeasonEventBand';
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
/** 우리 현인데 군주 · 관할자가 아니면(방침 · 공사 줄 없음) — 보드 V31K4County 「현령 · 군주만」. */
const NOT_CONTROLLER = { available: false, reason: '현령 · 군주만' } as const;

/**
 * 현 상세 본문(P-T02) — 머리(이름 · 칩) · 계절 띠(사건이 있을 때만) · 세 칸(형편 440 / 다스림 · 공사 / 사람 · 수비군 · 사건 · 할 일 360).
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
    // 현 상세 읽기(K4-04) — 늘 부른다. 없거나(404) 실패하면 상세 칸만 서버 대기로 남는다(D124).
    const detail = useCountyDetail(cityId, attempt);
    // 404 는 행정 縣이 아님(서버 대기 그대로). 그 밖의 실패(403 · 409 · 5xx)는 숨기지 않고 실패 줄 · 칸으로 보인다(#1392 리뷰 메모).
    const detailFailed = detail.error != null && detail.errorCode !== '404';
    const detailFailText = detailFailed ? '불러오지 못했습니다 — 위 「다시 읽기」로 다시 읽습니다.' : null;

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
    // 7지표: 현 상세가 주면 그 값, 아니면 내 장수가 선 현(front-info), 둘 다 없으면 서버 대기 · 권한 밖이면 「볼 수 없음」.
    const rows = detailIndicatorCells(detail.data) ?? indicatorRows(frontInfo?.city, city.id);
    const grade = gradeLabel(detail.data);
    const generalName = frontInfo?.general.name ?? '내 장수';

    const mineOr = (inputId: string, own: () => InputAvailability | null) => (head.mine ? own() : availabilityOf(inputId, { options: NOT_MINE }));
    // 우리 현이어도 방침 · 공사 줄이 READY 에 없으면 군주 · 관할자가 아니다 — 바꾸기 · 새 공사는 보드 문구로 점선(V31K4County 「현령 · 군주만」).
    const policyOut = readState(policies) === 'ready' && !policy;
    const workOut = readState(works) === 'ready' && !work;
    const placement = mineOr('placement.assign', () => availabilityOf('placement.assign'));
    const policySet = mineOr('policy.set', () => availabilityOf('policy.set', {
        options: policyOut ? NOT_CONTROLLER : policy ? { available: policy.settable, code: policy.blocked?.code, reason: policy.blocked?.reason } : null,
    }));
    const workStart = mineOr('work.start', () => availabilityOf('work.start', { options: workOut ? NOT_CONTROLLER : null }));
    const scoutable = !head.mine && (vision.tier === 'INTEL' || vision.tier === 'FOG');
    const scoutQuery = `do=action.scout${vision.commanderyId ? `&target=commandery:${vision.commanderyId}` : ''}`;
    // 「여기로 명령」 — 구역 id 를 알면 구역 대상(이동 · 출병 「어디로」를 채운다), 모르면 현 대상(작전실 선택 카드와 같다)
    const province = provinceRecordIdOf(preview.data, city);
    const hereHref = hrefs.flow(province ? `target=province:${province}` : `target=county:${city.id}`);

    const go = (href: string) => router.push(href);
    const state = (
        <>
            {vision.tier === 'INTEL' ? <p className={styles.note}>{`${vision.ageTurns == null ? '첩보로 본' : `${vision.ageTurns}순 전 첩보로 본`} 현입니다 — 지금 값과 다를 수 있습니다.`}</p> : null}
            <Indicators rows={rows} hidden={detailFailText ?? hiddenText(detail.data, '/indicators')} />
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
            <PeopleHere rows={peopleHereRows(detail.data)} hidden={detailFailText ?? hiddenText(detail.data, '/peopleHere')} />
            <Garrison rows={garrisonRows(detail.data)} hidden={detailFailText ?? hiddenText(detail.data, '/garrison')} />
        </>
    );
    const events = (
        <>
            <ServerWaiting row="K5-07" title="최근 사건 — 서버 대기" body="기록의 현 거르기가 오면 이 현 사건만 보입니다." />
            <Link href={hrefs.records} className={styles.link}>기록 전체 보기 →</Link>
        </>
    );
    const here = (
        <HereActions here={head.here} generalName={generalName} hereHref={hereHref}
            scout={scoutable ? availabilityOf('action.scout') : null} onScout={scoutable ? () => go(hrefs.flow(scoutQuery)) : null} />
    );
    const title = <h3 className={`os-serif ${styles.name}`}>{head.name}</h3>;
    // 계절 사건 띠(P-K07, K8 SeasonEventBand) — 그 현에 사건이 있을 때만 그린다. 읽기(K8-08 · K8-EV)가 붙기 전에는 띠가 없다(K4 10-05 합의).
    const season = <SeasonEventBand countyId={cityId} />;
    const retry = county.error || policies.error || works.error || visibility.error || detailFailed ? (
        <div className={styles.errRow} role="status">
            <span className={styles.errText}>일부를 불러오지 못했습니다.</span>
            <button type="button" className="os-button os-button--sm" onClick={() => setAttempt((n) => n + 1)}>다시 읽기</button>
        </div>
    ) : null;

    if (mobile) {
        return (
            <div className={styles.screenMobile}>
                <div className={styles.head}>{title}<HeadChips head={head} vision={vision} grade={grade} /></div>
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
            <div className={styles.head}>{title}<HeadChips head={head} vision={vision} grade={grade} /></div>
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
