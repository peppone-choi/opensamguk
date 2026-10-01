'use client';

import { Button, EVENT_FACT_LABEL, KV, eventFactText, eventSentence, formatGameDate, recordSection, RECORD_SECTION_LABEL,
  type EventNames, type EventViewer, type GameEvent, type KVItem } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import { NAV31 } from '@/lib/nav31';
import RecordMap from './RecordMap';
import styles from './records.module.css';

const UNREADABLE = '기록을 표시할 수 없습니다.';

/** 자세히 칸의 역할 이름. 요청 · 군단 · 리플레이 id 는 사람이 읽을 이름이 아니라 적지 않는다. */
const REF_LABEL: Readonly<Record<string, string>> = {
  CITY: '현',
  NATION: '세력',
  FROM_NATION: '이전 주인',
  TO_NATION: '새 주인',
  ROAD_FORT: '보루',
  ACTOR: '행동한 사람',
  PERSON: '인물',
  ISSUER: '보낸 사람',
  TARGET: '받는 사람',
};
const GENERAL_ROLES = new Set(['ACTOR', 'PERSON', 'ISSUER', 'TARGET']);
const NATION_ROLES = new Set(['NATION', 'FROM_NATION', 'TO_NATION']);

function numeric(value: number | string): number | undefined {
  if (typeof value === 'number') return value;
  return value.trim() !== '' && Number.isFinite(Number(value)) ? Number(value) : undefined;
}

export function refItems(event: GameEvent, names: EventNames): KVItem[] {
  const items: KVItem[] = [];
  for (const [role, label] of Object.entries(REF_LABEL)) {
    const value = event.refs[role];
    if (value === undefined) continue;
    const id = numeric(value);
    let text: string;
    if (role === 'CITY') text = (id != null && names.city(id)) || '어느 현';
    else if (NATION_ROLES.has(role)) text = id === 0 ? '주인 없음' : (id != null && names.nation(id)) || '어느 세력';
    else if (role === 'ROAD_FORT') text = (typeof value === 'string' && names.roadFort?.(value)) || '어느 보루';
    else if (GENERAL_ROLES.has(role)) text = (id != null && names.general?.(id)) || '어느 인물';
    else continue;
    items.push({ k: label, v: text });
  }
  for (const [role, label] of Object.entries(EVENT_FACT_LABEL)) {
    const value = event.facts[role];
    if (value === undefined) continue;
    const text = eventFactText(role, value);
    if (text !== null) items.push({ k: label, v: text });
  }
  return items;
}

/** 새 경로 화면이 들어왔는지 — 들어왔으면 그 경로, 아니면 null(점선 단추 + 사유). 옛 삼모 화면으로는 보내지 않는다. */
function builtPath(path: string): string | null {
  for (const group of NAV31) for (const screen of group.screens) if (screen.path === path) return screen.built ? screen.path : null;
  return null;
}

function ScreenLink({ path, label }: { readonly path: string; readonly label: string }) {
  const href = builtPath(path);
  if (href === null) return <Button size="sm" disabled reason={`${label} 화면은 아직 준비 중입니다.`}>{label}</Button>;
  const [slug, query = ''] = href.split('?');
  return <CampaignLink slug={slug} query={query ? `?${query}` : ''} className="os-button os-button--ghost os-button--sm">{label}</CampaignLink>;
}

export function sectionOf(event: GameEvent) {
  return recordSection(event.kind) ?? (event.section in RECORD_SECTION_LABEL ? event.section as keyof typeof RECORD_SECTION_LABEL : null);
}

/** 고른 기록 — 지도(현이 있을 때) · 문장 · 자세히 · 가는 곳. 데스크톱 오른쪽 칸과 모바일 시트가 같은 내용을 쓴다. */
export default function RecordDetail({ event, names, viewer }: { readonly event: GameEvent; readonly names: EventNames; readonly viewer: EventViewer }) {
  const sentence = eventSentence(event, names, viewer) ?? UNREADABLE;
  const cityRef = event.refs.CITY;
  const cityId = cityRef === undefined ? undefined : numeric(cityRef);
  const cityName = cityId != null ? names.city(cityId) : undefined;
  const items = refItems(event, names);
  const unresolved = items.some((item) => typeof item.v === 'string' && item.v.startsWith('어느 '));
  const hasNation = ['NATION', 'TO_NATION'].some((role) => { const v = event.refs[role]; return v !== undefined && numeric(v) !== 0; });
  return (
    <div className={styles.detail}>
      {cityId != null && cityName ? <RecordMap cityId={cityId} label={cityName} /> : null}
      <div className={styles.detailBody}>
        <p className={styles.sentence}>{sentence}</p>
        {items.length > 0 ? <KV items={items} /> : null}
        <div className={styles.actions}>
          {event.kind === 'court.dispatchReceived' ? <CampaignLink slug="court" query="?tab=orders" className="os-button os-button--sm os-button--primary">응답하기</CampaignLink> : null}
          {cityId != null ? <ScreenLink path="territory/county" label="현 상세" /> : null}
          {hasNation ? <ScreenLink path="court/realm" label="세력" /> : null}
        </div>
        {unresolved ? <p className={styles.note}>이름을 모르는 인물 · 장소는 「어느 인물」 · 「어느 현」으로 적습니다. 서버의 이름 사전이 준비되면 풀립니다.</p> : null}
        {sectionOf(event) === 'BATTLE' ? <p className={styles.note}>지금은 서버가 전장 보고의 누구 · 어디 · 다시 보기를 보내지 않습니다.</p> : null}
      </div>
    </div>
  );
}

export function detailTitle(event: GameEvent): string {
  const section = sectionOf(event);
  return `${section ? RECORD_SECTION_LABEL[section] : '기록'} · ${formatGameDate(event.occurredAt)}`;
}
