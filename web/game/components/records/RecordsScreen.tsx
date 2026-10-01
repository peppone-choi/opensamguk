'use client';

import { useSearchParams } from 'next/navigation';
import { Fragment, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import {
  Button, Chip, EVENT_KIND_COVERAGE, EVENT_KIND_LABEL, NOT_WRITTEN_NOTE, Panel, RECORD_KIND_SECTION, RECORD_SECTION_LABEL,
  RECORD_SECTION_ORDER, SectionHeader, Seg, StatusView, eventSentence, formatGameDate, useViewportClass,
  type GameEvent, type RecordSection, type SegOption,
} from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import { useGameSession } from '@/lib/campaign-session';
import { useRecordFeeds, type FeedSlot } from '@/lib/records-reads';
import { useRecordNames } from '@/lib/records-names';
import { mergeFeeds, PRIVATE_SECTIONS } from '@/lib/recordsFeed';
import RecordDetail, { detailTitle, sectionOf } from './RecordDetail';
import styles from './records.module.css';

export type RecordsCategory = 'ALL' | RecordSection;

const ALL_SECTIONS: readonly RecordSection[] = [...PRIVATE_SECTIONS, 'WORLD'];
const CATEGORY_OPTIONS: readonly SegOption<RecordsCategory>[] = [
  { value: 'ALL', label: '전체' },
  ...RECORD_SECTION_ORDER.map((section) => ({ value: section, label: RECORD_SECTION_LABEL[section] })),
];

const EMPTY_TITLE: Readonly<Record<RecordsCategory, string>> = {
  ALL: '아직 기록이 없습니다',
  PERSONAL: '아직 개인 행적이 없습니다',
  RETINUE_NATION: '아직 부 · 세력 소식이 없습니다',
  COURT: '아직 조정 공문이 없습니다',
  BATTLE: '아직 전장 보고가 없습니다',
  WORLD: '아직 천하 정세가 없습니다',
};

function isCategory(value: string | null): value is RecordsCategory {
  return value === 'ALL' || (value !== null && (RECORD_SECTION_ORDER as readonly string[]).includes(value));
}

/** 이 분류에서 고를 수 있는 종류(서버가 쓰는 것) · 아직 안 쓰는 종류. 옛 별칭은 둘 다에 넣지 않는다. */
export function kindsOf(category: RecordsCategory): { readonly written: readonly string[]; readonly notWritten: readonly string[] } {
  const kinds = Object.keys(RECORD_KIND_SECTION).filter((kind) => category === 'ALL' || RECORD_KIND_SECTION[kind] === category);
  return {
    written: kinds.filter((kind) => EVENT_KIND_COVERAGE[kind] === 'WRITTEN'),
    notWritten: kinds.filter((kind) => EVENT_KIND_COVERAGE[kind] === 'NOT_WRITTEN'),
  };
}

interface View {
  readonly status: 'loading' | 'ready' | 'error' | 'denied';
  readonly events: readonly GameEvent[];
  readonly more: boolean;
  readonly loadingMore: boolean;
  readonly moreError: boolean;
  readonly fresh: number;
  /** 「전체」에서 막히거나 실패한 분류(천하 정세만 보일 때 알린다). */
  readonly missing: readonly RecordSection[];
  readonly httpStatus: number | null;
}

export function viewOf(category: RecordsCategory, slots: Partial<Record<RecordSection, FeedSlot>>): View {
  if (category !== 'ALL') {
    const slot = slots[category];
    if (!slot) return { status: 'loading', events: [], more: false, loadingMore: false, moreError: false, fresh: 0, missing: [], httpStatus: null };
    return { status: slot.status, events: slot.events, more: slot.cursor !== null, loadingMore: slot.loadingMore,
      moreError: slot.moreError, fresh: slot.fresh.length, missing: [], httpStatus: slot.httpStatus };
  }
  const present = ALL_SECTIONS.map((section) => [section, slots[section]] as const);
  if (present.some(([, slot]) => !slot || slot.status === 'loading')) {
    return { status: 'loading', events: [], more: false, loadingMore: false, moreError: false, fresh: 0, missing: [], httpStatus: null };
  }
  const ready = present.filter(([, slot]) => slot?.status === 'ready').map(([, slot]) => slot as FeedSlot);
  const missing = present.filter(([, slot]) => slot?.status !== 'ready').map(([section]) => section);
  if (ready.length === 0) {
    const worldError = slots.WORLD?.status === 'error';
    return { status: worldError ? 'error' : 'denied', events: [], more: false, loadingMore: false, moreError: false, fresh: 0, missing, httpStatus: null };
  }
  const merged = mergeFeeds(ready);
  return { status: 'ready', events: merged.events, more: merged.more, loadingMore: ready.some((slot) => slot.loadingMore),
    moreError: ready.some((slot) => slot.moreError), fresh: ready.reduce((sum, slot) => sum + slot.fresh.length, 0), missing, httpStatus: null };
}

/** 목록 머리의 기간 「200년 1월 하순 – 3월 중순」(설계서 WL3). 같은 해면 뒤쪽 연도를 뺀다. */
export function dateRange(events: readonly GameEvent[]): string | null {
  if (events.length === 0) return null;
  const newest = events[0].occurredAt;
  const oldest = events[events.length - 1].occurredAt;
  const from = formatGameDate(oldest);
  const to = formatGameDate(newest);
  if (from === to) return from;
  return oldest.year === newest.year ? `${from} – ${to.replace(`${newest.year}년 `, '')}` : `${from} – ${to}`;
}

/** 같은 날짜(연 · 월 · 순)끼리 묶음 머리를 단다. */
function groupByDate(events: readonly GameEvent[]): { readonly date: string; readonly events: readonly GameEvent[] }[] {
  const out: { date: string; events: GameEvent[] }[] = [];
  for (const event of events) {
    const date = formatGameDate(event.occurredAt);
    const last = out[out.length - 1];
    if (last && last.date === date) last.events.push(event);
    else out.push({ date, events: [event] });
  }
  return out;
}

/**
 * P-H01 기록 5분류 피드(설계서 §5.1). 서버가 준 kind + refs 로 알림체 한 줄을 만든다(ADR-LITE-069).
 * 서버 대기: 인물 · 현 · 날짜로 거르기(서버 인자 없음), 전장 보고의 누구 · 어디 · 다시 보기(서버가 지운다), 인물 이름 사전.
 */
export default function RecordsScreen() {
  const session = useGameSession();
  const viewport = useViewportClass();
  const search = useSearchParams();
  const initial = search?.get('section') ?? null;
  const [category, setCategory] = useState<RecordsCategory>(isCategory(initial) ? initial : 'ALL');
  const [kind, setKind] = useState('');
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [sheetOpen, setSheetOpen] = useState(false);
  const listTop = useRef<HTMLDivElement | null>(null);
  const opener = useRef<HTMLButtonElement | null>(null);

  const hasGeneral = session.loading ? null : session.generalId != null;
  const sections = category === 'ALL' ? ALL_SECTIONS : [category];
  const feeds = useRecordFeeds(sections, hasGeneral);
  const general = session.frontInfo?.general;
  const names = useRecordNames(session.generalId, general?.name ?? null);
  const viewer = useMemo(() => ({ generalId: session.generalId }), [session.generalId]);
  const view = viewOf(category, feeds.slots);
  const shown = useMemo(() => (kind ? view.events.filter((event) => event.kind === kind) : view.events), [kind, view.events]);
  const { written, notWritten } = kindsOf(category);
  const wide = viewport === 'desktop';
  const selected = shown.find((event) => event.id === selectedId) ?? (wide ? shown[0] : undefined);

  useEffect(() => { setKind(''); setSelectedId(null); }, [category]);

  const choose = (event: GameEvent, button: HTMLButtonElement) => {
    setSelectedId(event.id);
    if (!wide) { opener.current = button; setSheetOpen(true); }
  };
  const closeSheet = () => { setSheetOpen(false); opener.current?.focus(); };
  const applyFresh = () => {
    feeds.applyFresh(sections);
    listTop.current?.scrollIntoView({ block: 'start' });
  };
  const nationless = general?.hasGeneral === true && general.nationId === 0;

  let body: ReactNode;
  if (view.status === 'loading' || hasGeneral === null) {
    body = <StatusView kind="loading" rows={8} />;
  } else if (view.status === 'error') {
    body = <StatusView kind="error" title="기록을 불러오지 못했습니다" onRetry={() => feeds.retry(sections)} />;
  } else if (view.status === 'denied') {
    body = view.httpStatus === 401
      ? <StatusView kind="denied" title="로그인하면 볼 수 있습니다" howTo="천하 정세는 로그인하지 않아도 볼 수 있습니다." />
      : <StatusView kind="denied" title="이 서버에 내 장수가 없습니다"
          howTo="장수를 만들면 개인 행적 · 부 · 조정 · 전장 기록이 여기에 쌓입니다. 천하 정세는 지금도 볼 수 있습니다." />;
  } else if (shown.length === 0 && !view.more) {
    body = kind
      ? <StatusView kind="empty" title="이 종류의 기록이 없습니다" body="종류를 「전체」로 되돌려 보세요." />
      : <StatusView kind="empty"
          title={category === 'RETINUE_NATION' && nationless ? '소속 세력이 없어 세력 소식이 없습니다' : EMPTY_TITLE[category]}
          body={category === 'RETINUE_NATION' && nationless ? '출사하거나 거병하면 부와 세력의 일이 여기에 쌓입니다.' : '순이 지나면 일어난 일이 여기에 쌓입니다.'} />;
  } else {
    body = (
      <div role="list" aria-label="기록" className={styles.rows}>
        {groupByDate(shown).map((group) => (
          <Fragment key={group.date}>
            <div role="listitem" className={styles.dateHead}>{group.date}</div>
            {group.events.map((event) => {
              const section = sectionOf(event);
              const text = eventSentence(event, names, viewer);
              return (
                <div role="listitem" key={event.id} className={styles.row} data-selected={selected?.id === event.id ? 'true' : 'false'}>
                  <button type="button" className={styles.rowButton} aria-current={selected?.id === event.id ? 'true' : undefined}
                    onClick={(e) => choose(event, e.currentTarget)}>
                    <span className={styles.cat} data-section={section ?? undefined}><i aria-hidden="true" />{section ? RECORD_SECTION_LABEL[section] : '기록'}</span>
                    <span className={styles.rowText} data-unknown={text === null ? 'true' : undefined}>{text ?? '기록을 표시할 수 없습니다.'}</span>
                  </button>
                  {/* 줄 단추 안에 누를 것을 넣지 않는다(K10 3.1.4) — 행동은 줄 옆에 따로. */}
                  {event.kind === 'court.dispatchReceived'
                    ? <span className={styles.rowEnd}><CampaignLink slug="court" query="?tab=orders" className="os-button os-button--ghost os-button--sm">응답하기</CampaignLink></span>
                    : section === 'BATTLE' ? <span className={styles.rowEnd}><Chip tone="info">누구 · 어디 — 서버 준비 중</Chip></span> : null}
                </div>
              );
            })}
          </Fragment>
        ))}
      </div>
    );
  }

  const list = (
    <Panel className={styles.list} aria-label="기록 목록">
      <div ref={listTop} />
      <SectionHeader title="기록" sub={[dateRange(shown), category === 'ALL' ? '다섯 분류 합침' : RECORD_SECTION_LABEL[category]].filter(Boolean).join(' · ')} />
      <div className={styles.tools}>
        <Seg label="기록 분류" options={CATEGORY_OPTIONS} value={category} onChange={setCategory} scroll={viewport === 'mobile'} />
        <div className={styles.toolRow}>
          <label className={styles.kind}>
            종류
            <select className={styles.kindSelect} value={kind} onChange={(e) => setKind(e.target.value)}>
              <option value="">전체</option>
              {written.map((code) => <option key={code} value={code}>{EVENT_KIND_LABEL[code]}</option>)}
            </select>
          </label>
          <Button size="sm" disabled reason="서버가 아직 인물로 거르기를 주지 않습니다.">인물로 거르기</Button>
          <Button size="sm" disabled reason="서버가 아직 날짜로 가기를 주지 않습니다.">날짜로 가기</Button>
        </div>
        {kind ? <p className={styles.note}>불러온 기록 안에서만 거릅니다. 더 오래된 것은 「더 보기」로 불러옵니다.</p> : null}
        {notWritten.length > 0 ? (
          <p className={styles.note}>{NOT_WRITTEN_NOTE}: {notWritten.map((code) => EVENT_KIND_LABEL[code]).join(' · ')}</p>
        ) : null}
        {category === 'BATTLE' ? <p className={styles.note}>지금은 서버가 전장 보고의 누구 · 어디 · 다시 보기를 보내지 않아 종류만 적습니다.</p> : null}
        {category === 'ALL' && view.status === 'ready' && view.missing.length > 0 ? (
          hasGeneral ? (
            <div className={styles.toolRow}>
              <p className={styles.note} role="status">{`불러오지 못한 분류(${view.missing.map((s) => RECORD_SECTION_LABEL[s]).join(' · ')})는 빼고 합쳤습니다.`}</p>
              <Button size="sm" onClick={() => feeds.retry(view.missing)}>다시 시도</Button>
            </div>
          ) : <p className={styles.note}>이 서버에 내 장수가 없어 천하 정세만 보입니다.</p>
        ) : null}
      </div>
      {view.fresh > 0 ? <div className={styles.band}><Button size="sm" block onClick={applyFresh}>{`새 기록 ${view.fresh}건 — 맨 위로`}</Button></div> : null}
      {body}
      {view.status === 'ready' && view.more ? (
        <div className={styles.more}>
          {view.loadingMore
            ? <Button block disabled reason="기록을 더 불러오는 중입니다.">더 보기</Button>
            : <Button block onClick={() => feeds.loadMore(sections)}>더 보기</Button>}
          {view.moreError ? <p className={styles.note} role="alert">더 불러오지 못했습니다. 다시 눌러 보세요.</p> : null}
        </div>
      ) : null}
    </Panel>
  );

  return (
    <div className={styles.screen}>
      {list}
      {wide ? (
        <Panel className={styles.detail} aria-label="고른 기록">
          <SectionHeader title="고른 기록" sub={selected ? detailTitle(selected) : '줄을 누르면 여기에 보입니다'} />
          {selected ? <RecordDetail event={selected} names={names} viewer={viewer} /> : null}
        </Panel>
      ) : null}
      {!wide && sheetOpen && selected ? (
        <div className={styles.sheetLayer}>
          <button type="button" className={styles.scrim} aria-label="고른 기록 닫기" onClick={closeSheet} />
          <section className={styles.sheet} role="dialog" aria-modal="true" aria-label={detailTitle(selected)}
            onKeyDown={(event) => { if (event.key === 'Escape') closeSheet(); }}>
            <div className={styles.sheetHead}>
              <span className={styles.sheetTitle}>{detailTitle(selected)}</span>
              <Button size="sm" onClick={closeSheet} autoFocus>닫기</Button>
            </div>
            <div className={styles.sheetBody}>
              <RecordDetail event={selected} names={names} viewer={viewer} />
            </div>
          </section>
        </div>
      ) : null}
    </div>
  );
}
