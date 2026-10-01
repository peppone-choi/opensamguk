'use client';

import { useMemo, useState, type ReactNode } from 'react';
import { Portrait } from '../Portrait';
import { ReasonTooltip } from '../ReasonTooltip';
import { matchesKoreanName } from './koreanSearch';
import { Seg } from './Seg';
import { StatusView } from './StatusView';
import type { PeopleGroup, PersonOption } from './types';

export const PEOPLE_GROUP_LABEL: Record<PeopleGroup, string> = {
  mine: '내 부',
  nation: '소속 세력',
  rulers: '다른 세력 군주',
  all: '전체',
};
const GROUP_ORDER: readonly PeopleGroup[] = ['mine', 'nation', 'rulers', 'all'];
/** 불러오는 중 · 실패 때의 빈 목록 — 매 렌더 새 배열이면 useMemo 가 매번 다시 돈다. */
const NO_PEOPLE: readonly PersonOption[] = [];

type Load =
  | { readonly state: 'loading' }
  | { readonly state: 'error'; readonly onRetry: () => void; readonly errorCode?: string }
  | { readonly state: 'ready'; readonly people: readonly PersonOption[] };

type Selection =
  | { readonly multiple?: false; readonly selected: number | null; readonly onChange: (generalId: number | null) => void }
  | { readonly multiple: true; readonly selected: readonly number[]; readonly onChange: (generalIds: readonly number[]) => void };

export type PeoplePickerProps = Selection & {
  readonly load: Load;
  /** 처음 여는 묶음. 기본 「전체」. */
  readonly initialGroup?: PeopleGroup;
  /** 빈 묶음일 때 채우는 법(예: 「전체 보기」 단추). */
  readonly emptyAction?: ReactNode;
  /** 목록 이름(스크린리더). 예: 「받는 사람」. */
  readonly label?: string;
  /**
   * 보일 묶음 탭(쓰는 곳마다 다르다). 기본은 4개 전부. 서버가 묶음을 안 주는 곳은 ['all'] — 탭 줄을 그리지 않는다
   * (「내 부 0」 처럼 모르는 것을 0 이라고 말하지 않는다).
   */
  readonly groups?: readonly PeopleGroup[];
};

/**
 * 사람 고르기(보드 People · PeopleMulti) — NPC 포함. 찾기(이름 · 초성) + 묶음 4(내 부 · 소속 세력 · 다른 세력 군주 · 전체).
 * 빈 묶음 · 찾기 없음 · 불러오기 실패를 따로 보인다. 담는 틀(데스크톱 380 패널 · 모바일 가득 시트)은 부른 쪽이 정한다.
 */
export function PeoplePicker(props: PeoplePickerProps) {
  const { load, initialGroup = 'all', emptyAction, label = '사람 목록', groups: shownGroups = GROUP_ORDER } = props;
  const tabs = GROUP_ORDER.filter((g) => shownGroups.includes(g));
  const [picked, setGroup] = useState<PeopleGroup>(initialGroup);
  const group = tabs.includes(picked) ? picked : (tabs[0] ?? 'all');
  const [query, setQuery] = useState('');
  const people = load.state === 'ready' ? load.people : NO_PEOPLE;

  const counts = useMemo(() => {
    const out: Record<PeopleGroup, number> = { mine: 0, nation: 0, rulers: 0, all: people.length };
    for (const p of people) for (const g of p.groups) out[g] += 1;
    return out;
  }, [people]);

  const rows = useMemo(() => people
    .filter((p) => group === 'all' || p.groups.includes(group))
    .filter((p) => matchesKoreanName(p.name, query)), [people, group, query]);

  const selectedIds = props.multiple ? props.selected : props.selected === null ? [] : [props.selected];
  const toggle = (generalId: number) => {
    if (props.multiple) {
      props.onChange(props.selected.includes(generalId) ? props.selected.filter((id) => id !== generalId) : [...props.selected, generalId]);
    } else {
      props.onChange(generalId);
    }
  };
  const byId = useMemo(() => new Map(people.map((p) => [p.generalId, p])), [people]);

  let body: ReactNode;
  if (load.state === 'loading') body = <StatusView kind="loading" rows={4} />;
  else if (load.state === 'error') {
    body = (
      <StatusView
        kind="error"
        title={`${label}을 불러오지 못했습니다`}
        body="빈 목록이 아닙니다 — 불러오기가 실패했습니다."
        errorCode={load.errorCode}
        onRetry={load.onRetry}
      />
    );
  } else if (rows.length === 0 && query.trim()) {
    body = <StatusView kind="empty" title={`「${query.trim()}」와 맞는 사람이 없습니다`} body="이름을 줄이거나 초성으로 찾아 보세요." />;
  } else if (rows.length === 0) {
    body = <StatusView kind="empty" title="이 묶음에 사람이 없습니다" body={`${PEOPLE_GROUP_LABEL[group]} 묶음에 든 인물이 없습니다.`} actions={emptyAction} />;
  } else {
    body = (
      <div role="listbox" aria-label={label} aria-multiselectable={props.multiple || undefined} className="os-people__list">
        {rows.map((p) => (
          <PersonRow key={p.generalId} person={p} selected={selectedIds.includes(p.generalId)} multiple={Boolean(props.multiple)} onPick={toggle} />
        ))}
      </div>
    );
  }

  return (
    <div className="os-people">
      <div className="os-people__head">
        {props.multiple ? (
          <div className="os-people__chosen">
            <span className="os-people__count">고른 사람 <b>{props.selected.length}</b></span>
            <button type="button" className="os-button os-button--ghost os-people__clear" onClick={() => props.onChange([])}>
              모두 풀기
            </button>
            <div className="os-people__chips">
              {props.selected.map((id) => {
                const person = byId.get(id);
                const name = person?.name ?? `#${id}`;
                return (
                  <span key={id} className="os-chip os-chip--bronze os-people__chip">
                    {name}
                    <button type="button" className="os-people__chip-x" aria-label={`${name} 빼기`} onClick={() => toggle(id)}>×</button>
                  </span>
                );
              })}
            </div>
          </div>
        ) : null}
        <input
          type="search"
          className="os-input os-people__search"
          placeholder="이름 · 초성으로 찾기 — 예: ㅅㅇ"
          aria-label="이름 · 초성으로 찾기"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        {tabs.length > 1 ? (
          <Seg label="묶음" options={tabs.map((g) => ({ value: g, label: PEOPLE_GROUP_LABEL[g], count: load.state === 'ready' ? counts[g] : null }))}
            value={group} onChange={setGroup} scroll />
        ) : null}
      </div>
      {body}
    </div>
  );
}

function PersonRow({ person: p, selected, multiple, onPick }: {
  readonly person: PersonOption;
  readonly selected: boolean;
  readonly multiple: boolean;
  readonly onPick: (generalId: number) => void;
}) {
  const where = p.location === undefined ? null : p.location === null ? '자리 모름(시야 밖)' : `자리 ${p.location}`;
  const affiliation = p.nation === undefined ? null : p.nation === null ? '재야' : p.nation.name;
  const sub = [affiliation, where].filter(Boolean).join(' · ');
  const content = (
    <>
      {multiple ? <span className={['os-people__box', selected ? 'os-people__box--on' : ''].filter(Boolean).join(' ')} aria-hidden="true" /> : null}
      <Portrait picture={p.picture} imageServer={p.imageServer} size="card-36" alt="" className="os-people__pic" />
      <span className="os-opt__text">
        <span className="os-opt__name-row">
          <span className="os-opt__name">{p.name}</span>
          {p.isHuman ? <span className="os-chip os-chip--info">사람</span> : null}
        </span>
        {sub ? (
          <span className="os-opt__sub">
            {p.nation ? <i className="os-opt__nation" style={{ background: p.nation.color }} aria-hidden="true" /> : null}
            {sub}
          </span>
        ) : null}
      </span>
    </>
  );

  if (p.blockedReason) {
    const reason = p.blockedReason;
    return (
      <ReasonTooltip reason={reason} title={`${p.name} — 고를 수 없습니다`} block>
        {(describedBy) => (
          <button
            type="button"
            role="option"
            aria-selected="false"
            aria-disabled="true"
            aria-describedby={describedBy}
            className="os-opt os-opt--no os-people__row"
            data-general-id={p.generalId}
          >
            {content}
            <span className="os-opt__end"><span className="os-opt__why">{reason}</span></span>
          </button>
        )}
      </ReasonTooltip>
    );
  }

  return (
    <button
      type="button"
      role="option"
      aria-selected={selected}
      className={['os-opt', 'os-people__row', selected ? 'os-opt--sel' : ''].filter(Boolean).join(' ')}
      data-general-id={p.generalId}
      onClick={() => onPick(p.generalId)}
    >
      {content}
      <span className="os-opt__end">{selected && !multiple ? <span className="os-chip os-chip--bronze">고름</span> : null}</span>
    </button>
  );
}
