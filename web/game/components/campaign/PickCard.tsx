'use client';

import Link from 'next/link';
import { useState, type KeyboardEvent } from 'react';
import { Chip, Gauge, Modal, Portrait, ReasonTooltip } from '@opensamguk/ui';
import { HelpedInputAction } from '@/components/campaign/HelpedInputAction';
import { api } from '@/lib/api';
import { useCampaignRead, type CorpsList, type Visibility } from '@/lib/campaign-reads';
import { countyVision, indicatorRows, specialtyText } from '@/lib/county-view';
import { availabilityOf } from '@/lib/input-availability';
import type { FrontCityInfo } from '@/lib/types';
import { meSubline, pickSubline, pickView, stationedCorps, stationedText, type PickView, type WarRoomPickTarget } from '@/lib/war-room-pick';
import styles from './WarRoomPage.module.css';

export interface PickCardProps {
    /** 고른 城(지도 · 「내 위치」 알약). null 이면 고르지 않았다. */
    readonly pick: WarRoomPickTarget | null;
    /** 내 장수가 선 城(front-info). 성 밖이면 null. */
    readonly home: FrontCityInfo | null;
    readonly myNationId: number | null;
    readonly vision: Visibility | null;
    readonly corps: CorpsList | null;
    /** 현 상세(P-T02) 주소. */
    readonly countyHref: (cityId: number) => string;
    /** 「여기로 명령」 — 이 현을 대상으로 명령 흐름. */
    readonly onCommandHere: (cityId: number) => void;
    /** 「첩보」 — 이 현 군을 대상으로 첩보 흐름(군 id 를 모르면 null — 흐름에서 고른다). */
    readonly onScout: (commanderyId: string | null) => void;
    /** 「내 위치」 알약 — 내 城을 고른다. */
    readonly onPickHome: () => void;
    readonly onClear: () => void;
    /** 내 장수(내 장수 카드 — 내 위치 표지를 누르면). 장수가 없으면 null. */
    readonly me: MeInfo | null;
    /** 다음 개인 턴 시각(12순 첫 순의 시각, 「21:40」). 모르면 null. */
    readonly nextTurnAt: string | null;
    /** 「이번 순에 할 일」 — 순을 정하지 않고 명령 흐름. */
    readonly onDoNow: () => void;
}

export interface MeInfo {
    readonly name: string;
    readonly picture?: string | null;
    readonly imageServer?: number | null;
    readonly nationName: string | null;
    readonly nationColor: string | null;
}

const homeTarget = (home: FrontCityInfo): WarRoomPickTarget => ({ cityId: home.id, city: null, nations: [], provinceRecordId: null });

/** 카드 본문 — 머리 칩 · 소속 · 보급 · 주둔 · 시야 · 특산 · 형편 · 「현 상세 · 첩보 · 여기로 명령」(보드 sel_card). */
function PickBody({ view, target, props }: { readonly view: PickView; readonly target: WarRoomPickTarget; readonly props: PickCardProps }) {
    const county = useCampaignRead((id, signal) => api.campaignCounty(id, view.cityId, signal), [view.cityId]);
    const vision = countyVision(props.vision, view.commanderyName, view.mine);
    const rows = indicatorRows(props.home, view.cityId);
    const stationed = stationedCorps(props.corps, target.provinceRecordId);
    const scoutable = !view.mine && (vision.tier === 'INTEL' || vision.tier === 'FOG');
    const supply = view.supplied == null ? (view.mine ? '?' : '안 보임') : view.supplied ? '이어짐' : '끊김';
    const specialties = county.error ? <span className={styles.errText}>특산을 불러오지 못했습니다</span>
        : !county.data ? <span className={styles.muted}>특산 불러오는 중</span>
        : county.data.status !== 'READY' ? <span className={styles.muted}>지금은 특산을 볼 수 없습니다</span>
        : county.data.specialties.length === 0 ? <span className={styles.muted}>특산 없음</span>
        : county.data.specialties.flatMap((s) => {
            // 남의 현 설계값을 모르는 칩은 그리지 않는다(D40)
            const text = specialtyText(s, view.mine);
            return text == null ? [] : [<Chip key={s.resource}>{`특산 ${text}`}</Chip>];
        });
    return (
        <div className={styles.pickBody}>
            <div className={styles.pickChips}>
                {view.commanderyName ? <Chip>{view.commanderyName}</Chip> : null}
                {view.isSeat ? <Chip>군 치소</Chip> : null}
                {view.isCapital ? <Chip tone="bronze">수도</Chip> : null}
                {view.isPass ? <Chip>관</Chip> : null}
                {view.here ? <Chip tone="bronze">내 위치</Chip> : null}
                {view.supplied === false ? <Chip tone="rust">고립</Chip> : null}
            </div>
            <dl className={styles.pickKv}>
                <div><dt>소속</dt><dd className={styles.pickNation}>{view.ownerColor ? <i aria-hidden="true" style={{ background: view.ownerColor }} /> : null}{view.ownerName}</dd></div>
                <div><dt>보급</dt><dd>{supply}</dd></div>
                <div><dt>주둔</dt><dd>{stationedText(stationed)}</dd></div>
            </dl>
            <div className={styles.pickChips}>
                {vision.tier === 'FULL' ? <Chip tone="moss">지금 보임</Chip> : null}
                {vision.tier === 'INTEL' ? <Chip tone="info">{vision.ageTurns == null ? '첩보로 봄' : `첩보 ${vision.ageTurns}순 전`}</Chip> : null}
                {vision.tier === 'FOG' ? <Chip>안 보임</Chip> : null}
                {specialties}
            </div>
            <div className={styles.pickState}>
                <span className={styles.muted}>현 형편</span>
                {rows ? (
                    <div className={styles.pickGauges} role="group" aria-label="형편 7지표">
                        {rows.map((r) => <Gauge key={r.label} label={r.label} value={r.value} max={r.max} tone={r.tone} />)}
                    </div>
                ) : (
                    <p className={styles.muted}>{view.here ? '형편 수치를 받지 못했습니다.' : '다른 현의 형편 수치는 아직 서버가 주지 않습니다. 현 상세에서 볼 수 있는 것부터 보세요.'}</p>
                )}
            </div>
            <div className={styles.pickActions}>
                <Link href={props.countyHref(view.cityId)} className="os-button">현 상세</Link>
                {scoutable ? <HelpedInputAction inputId="action.scout" availability={availabilityOf('action.scout')} label="첩보" variant="ghost"
                    onAct={() => props.onScout(vision.commanderyId)} /> : null}
                <button type="button" className={`os-button os-button--primary ${styles.pickMain}`} onClick={() => props.onCommandHere(view.cityId)}>여기로 명령</button>
            </div>
        </div>
    );
}

/** 「장수 상세」 — 인물 상세(P-R03) 화면이 생기기 전까지 사유 있는 비활성(점선 + 누르면 사유). 보드 칸은 빼지 않는다(K0 10-03). */
const PERSON_DETAIL_WAIT = '장수 상세 화면은 아직 준비 중입니다.';

/**
 * 내 장수 카드 본문(보드 me_card) — 자리 · 귀환 성 · 다음 개인 턴 · 「이번 순에 할 일 · 장수 상세」(보드 단추 둘 그대로 — 현 상세는 城 카드에).
 * 자리는 지금 성 안만 안다(성 밖 · 군단과 함께 · 이동 중은 서버 U-04). 귀환 성은 읽기가 없어 값 자리에 「서버 대기」.
 */
function MeBody({ view, props }: { readonly view: PickView | null; readonly props: PickCardProps }) {
    return (
        <div className={styles.pickBody}>
            <dl className={styles.pickKv}>
                <div><dt>자리</dt><dd>{view ? '성 안' : '성 밖'}</dd></div>
                <div><dt>귀환 성</dt><dd className={styles.muted}>서버 대기</dd></div>
                <div><dt>다음 개인 턴</dt><dd className="os-mono">{props.nextTurnAt ?? '—'}</dd></div>
            </dl>
            <div className={styles.pickActions}>
                <button type="button" className={`os-button os-button--primary ${styles.pickMain}`} onClick={props.onDoNow}>이번 순에 할 일</button>
                <ReasonTooltip reason={PERSON_DETAIL_WAIT} className="os-ia">
                    {(describedBy) => (
                        <>
                            <button type="button" className="os-button os-ia__button os-button--ghost os-button--disabled" aria-disabled="true" aria-describedby={describedBy}>장수 상세</button>
                            <span className="os-ia__why" aria-hidden="true">준비 중</span>
                        </>
                    )}
                </ReasonTooltip>
            </div>
        </div>
    );
}

function MeHead({ me, sub, onClose }: { readonly me: MeInfo; readonly sub: string; readonly onClose: () => void }) {
    return (
        <div className={styles.meHead}>
            <Portrait picture={me.picture ?? null} imageServer={me.imageServer ?? null} size="card-36" alt=""
                ring={me.nationColor ? { color: me.nationColor, reason: 'self' } : undefined} />
            <div className={styles.meName}>
                <h2 className={`os-serif ${styles.pickName}`}>{me.name}</h2>
                <span className={styles.muted}>{sub}</span>
            </div>
            <button type="button" className={`os-button os-button--sm ${styles.push}`} onClick={onClose}>닫기</button>
        </div>
    );
}

function CardHead({ title, onClose }: { readonly title: string; readonly onClose: () => void }) {
    return (
        <div className={styles.pickHead}>
            <h2 className={`os-serif ${styles.pickName}`}>{title}</h2>
            <button type="button" className={`os-button os-button--sm ${styles.push}`} onClick={onClose}>닫기</button>
        </div>
    );
}

/**
 * 데스크톱 — 지도 왼쪽 위 「내 위치」 알약(누르면 내 城을 고른다) + 고른 城 카드(지도 오른쪽 위 떠 있음 320, 보드 sel_card).
 * 카드는 고르기만 따라간다 — 지도에서 城 · 깃발 · 내 위치 표지를 누르면 열리고, 빈 땅 · Esc · 「닫기」면 닫힌다. 지도 조작을 가로채지 않게 지도 가운데를 비운다.
 */
export function PickPillDesktop({ pick, home, onPickHome, onClear }: Pick<PickCardProps, 'pick' | 'home' | 'onPickHome' | 'onClear'>) {
    // 성 밖(군단 · 이동 중)이면 고를 내 城이 없다 — 알약을 두지 않는다(내 위치 표지도 서버 U-04 대기).
    if (!home) return null;
    const homePicked = pick != null && pick.cityId === home.id;
    return (
        <button type="button" className={styles.herePill} aria-expanded={homePicked} aria-label={`내 위치 — ${home.name}`}
            onClick={() => (homePicked ? onClear() : onPickHome())}>
            <span className={`os-serif ${styles.hereName}`}>{home.name}</span>
            <Chip tone="bronze">내 위치</Chip>
        </button>
    );
}

export function PickCardDesktop(props: PickCardProps) {
    const view = props.pick ? pickView(props.pick, props.home, props.myNationId) : null;
    const onKeyDown = (event: KeyboardEvent<HTMLElement>) => {
        if (event.key !== 'Escape') return;
        event.stopPropagation();
        props.onClear();
    };
    if (props.pick?.me && props.me) {
        return (
            <section className={styles.pickCard} aria-label={`내 장수 — ${props.me.name}`} data-testid="war-room-pick" onKeyDown={onKeyDown}>
                <MeHead me={props.me} sub={meSubline(props.me.nationName, view)} onClose={props.onClear} />
                <MeBody view={view} props={props} />
            </section>
        );
    }
    if (!props.pick || !view) return null;
    return (
        <section className={styles.pickCard} aria-label={`고른 현 — ${view.name}`} data-testid="war-room-pick" onKeyDown={onKeyDown}>
            <CardHead title={view.name} onClose={props.onClear} />
            <PickBody view={view} target={props.pick} props={props} />
        </section>
    );
}

/**
 * 모바일 — 지도 아래 선택 알약(보드 V31K4MWarRoom: 이름 · 군 치소 · 「내 위치」 칩). 고르지 않았으면 내 城, 고르면 그 城.
 * 지도에서 고르면 알약만 바뀐다(시트를 저절로 열지 않는다 — 지도 끌기 · 핀치를 막지 않게). 알약을 누르면 하단 시트에 같은 카드, 「×」는 고르기를 푼다.
 */
export function PickPillMobile(props: PickCardProps) {
    const [open, setOpen] = useState(false);
    const target = props.pick ?? (props.home ? homeTarget(props.home) : null);
    const view = target ? pickView(target, props.home, props.myNationId) : null;
    const meCard = props.pick?.me === true && props.me != null;
    const picked = props.pick != null && (view != null || meCard);
    const name = meCard ? props.me!.name : view ? view.name : '성 밖';
    const label = meCard ? `내 장수 — ${name}` : picked ? `고른 현 — ${name}` : `내 위치 — ${name}`;
    const sub = meCard ? meSubline(props.me!.nationName, view) : view ? pickSubline(view) : '';
    return (
        <>
            <div className={styles.pickPillRow}>
                <button type="button" className={styles.herePillMobile} aria-expanded={open} aria-label={label}
                    data-testid={picked ? 'war-room-pick' : undefined} onClick={() => setOpen(true)}>
                    <span className={`os-serif ${styles.hereName}`}>{name}</span>
                    {sub ? <span className={styles.muted}>{sub}</span> : null}
                    {!meCard && view?.isPass ? <Chip>관</Chip> : null}
                    {meCard || view?.here ? <Chip tone="bronze">내 위치</Chip> : null}
                </button>
                {picked ? <button type="button" className={`os-button ${styles.pickClear}`} aria-label={`고르기 풀기 — ${name}`} onClick={props.onClear}>×</button> : null}
            </div>
            {open ? (
                <Modal ariaLabel={label} onClose={() => setOpen(false)} overlayClassName={styles.sheetBottom}>
                    <div className={styles.sheet}>
                        {meCard ? <MeHead me={props.me!} sub={sub} onClose={() => setOpen(false)} /> : <CardHead title={name} onClose={() => setOpen(false)} />}
                        {meCard ? (
                            <MeBody view={view} props={{ ...props, onDoNow: () => { setOpen(false); props.onDoNow(); } }} />
                        ) : view && target ? (
                            <PickBody view={view} target={target} props={{
                                ...props,
                                onCommandHere: (cityId) => { setOpen(false); props.onCommandHere(cityId); },
                                onScout: (id) => { setOpen(false); props.onScout(id); },
                            }} />
                        ) : <p className={styles.muted}>장수가 성에 있지 않습니다.</p>}
                    </div>
                </Modal>
            ) : null}
        </>
    );
}
