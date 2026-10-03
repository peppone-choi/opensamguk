'use client';

import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ReasonTooltip } from '../ReasonTooltip';
import { PartIcon } from './PartIcon';
import { Seg } from './Seg';
import type { TargetCandidate, TargetKind, TargetMarkerState } from './types';

// ---------------------------------------------------------------- 상태(지도 층 · 목록 · 띠가 함께 쓴다)

export interface TargetPickerOptions {
  readonly kind: TargetKind;
  readonly candidates: readonly TargetCandidate[];
  /** 여러 곳 고르기(multi-county). 기본은 kind 가 'multi-county' 일 때만 참. */
  readonly multiple?: boolean;
  readonly initialSelected?: readonly string[];
  /** Esc · 「그만 고르기」. */
  readonly onCancel: () => void;
}

export interface TargetPicker {
  readonly kind: TargetKind;
  readonly multiple: boolean;
  /** 고른 targetId(여러 곳이면 고른 순서). */
  readonly selected: readonly string[];
  /** 지도 표지나 목록 행을 누를 때. 불가 후보는 고르지 않고 false 를 돌려준다(부른 쪽이 사유를 연다). */
  readonly pick: (targetId: string) => boolean;
  readonly clear: () => void;
  readonly cancel: () => void;
  /** 지도 표지 · 목록 행의 상태 — 지도와 목록이 같은 상태 · 같은 사유를 쓴다. */
  readonly markerStateOf: (targetId: string) => TargetMarkerState;
  /** 여러 곳 고르기의 고른 순서(1부터). 안 골랐으면 null. */
  readonly orderOf: (targetId: string) => number | null;
  readonly counts: { readonly available: number; readonly blocked: number };
}

/**
 * 지도 대상 고르기 상태(보드 MapPick · MapModes). 지도 그리기는 K2 지도 층이 `markerStateOf` · `orderOf` 로 하고,
 * 띠(PickBar)와 목록(TargetCandidateList)은 이 부품이 그린다. 고르는 동안 Esc 는 그만 고르기다.
 */
export function useTargetPicker({ kind, candidates, multiple, initialSelected = [], onCancel }: TargetPickerOptions): TargetPicker {
  const isMultiple = multiple ?? kind === 'multi-county';
  const [selected, setSelected] = useState<readonly string[]>(initialSelected);
  const byId = useMemo(() => new Map(candidates.map((c) => [c.targetId, c])), [candidates]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !event.defaultPrevented) onCancel();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onCancel]);

  const pick = useCallback((targetId: string) => {
    const candidate = byId.get(targetId);
    if (!candidate || !candidate.available) return false;
    setSelected((was) => {
      if (!isMultiple) return [targetId];
      return was.includes(targetId) ? was.filter((id) => id !== targetId) : [...was, targetId];
    });
    return true;
  }, [byId, isMultiple]);

  const markerStateOf = useCallback((targetId: string): TargetMarkerState => {
    const candidate = byId.get(targetId);
    if (selected.includes(targetId)) return 'selected';
    if (candidate?.here) return 'here';
    return candidate?.available ? 'ok' : 'no';
  }, [byId, selected]);

  const orderOf = useCallback((targetId: string) => {
    const at = selected.indexOf(targetId);
    return at < 0 ? null : at + 1;
  }, [selected]);

  const counts = useMemo(() => {
    let available = 0;
    for (const c of candidates) if (c.available) available += 1;
    return { available, blocked: candidates.length - available };
  }, [candidates]);

  return {
    kind,
    multiple: isMultiple,
    selected,
    pick,
    clear: () => setSelected([]),
    cancel: onCancel,
    markerStateOf,
    orderOf,
    counts,
  };
}

// ---------------------------------------------------------------- 고르기 띠

export interface PickBarProps {
  /** 예: 「갈 곳 고르기 — 이동 · 04순」 */
  readonly title: string;
  /** 앞말(예: 「지도를 누르거나 오른쪽 목록에서」). 뒤에 「가능 n · 불가 m」 이 붙는다. */
  readonly hint: string;
  readonly counts: { readonly available: number; readonly blocked: number };
  readonly onCancel: () => void;
  /** 모바일 「목록」 단추(목록 시트 열기). 없으면 단추를 그리지 않는다. */
  readonly onShowList?: () => void;
  /** 여러 곳 고르기의 「끝」 같은 오른쪽 단추. */
  readonly done?: ReactNode;
}

/** 고르기 띠 — 무엇을 고르는지 · 후보 수 · 그만(Esc). 지도 맨 위에 둔다. 띠 바탕은 누르기를 먹지 않는다. */
export function PickBar({ title, hint, counts, onCancel, onShowList, done }: PickBarProps) {
  return (
    <div className="os-pickbar" role="region" aria-label={title}>
      <span className="os-pickbar__mark"><PartIcon name="target" /></span>
      <span className="os-pickbar__text">
        <span className="os-pickbar__title">{title}</span>
        <span className="os-pickbar__sub">{`${hint} · 가능 ${counts.available} · 불가 ${counts.blocked}`}</span>
      </span>
      <span className="os-pickbar__actions">
        {done}
        {onShowList ? (
          <button type="button" className="os-button os-button--ghost os-pickbar__list" onClick={onShowList}>목록</button>
        ) : null}
        <button type="button" className="os-button os-button--ghost os-pickbar__cancel" onClick={onCancel} aria-keyshortcuts="Escape">
          <span className="os-pickbar__cancel-long">그만 고르기 <span className="os-pickbar__key">Esc</span></span>
          <span className="os-pickbar__cancel-short">그만</span>
        </button>
      </span>
    </div>
  );
}

// ---------------------------------------------------------------- 후보 목록

export interface TargetCandidateListProps {
  readonly picker: TargetPicker;
  readonly candidates: readonly TargetCandidate[];
  /** 묶음 탭(예: ['내 영지', '이웃']). 「전체」 는 끝에 늘 붙는다. */
  readonly groups?: readonly string[];
  /** 목록 이름(스크린리더). */
  readonly label?: string;
  readonly rowHeight?: 44 | 48 | 52;
}

const ALL = '전체';

/**
 * 후보 목록(보드 TargetCandidateList) — 가능 · 불가를 같이 보이고 가까운 순으로 늘어놓는다. 「가능만」 은 기본 꺼짐.
 * 불가 행은 행 전체가 사유를 여는 단추(aria-disabled)이고 사유는 점선 꼬리표로 보인다 — 행 안에 단추를 넣지 않는다.
 */
export function TargetCandidateList({ picker, candidates, groups = [], label = '후보 목록', rowHeight = 48 }: TargetCandidateListProps) {
  const tabs = useMemo(() => [...groups.filter((g) => g !== ALL), ALL], [groups]);
  const [group, setGroup] = useState(ALL);
  const [onlyAvailable, setOnlyAvailable] = useState(false);

  const rows = useMemo(() => {
    const inGroup = group === ALL ? candidates : candidates.filter((c) => c.groups?.includes(group));
    const shown = onlyAvailable ? inGroup.filter((c) => c.available) : inGroup;
    return [...shown].sort((a, b) => (a.distanceCells ?? Infinity) - (b.distanceCells ?? Infinity));
  }, [candidates, group, onlyAvailable]);

  return (
    <div className="os-cands">
      <div className="os-cands__head">
        <Seg label="후보 묶음" options={tabs.map((t) => ({ value: t, label: t }))} value={group} onChange={setGroup} scroll />
        <label className="os-check">
          <input type="checkbox" checked={onlyAvailable} onChange={(event) => setOnlyAvailable(event.target.checked)} />
          <span>가능만</span>
        </label>
      </div>
      <div role="listbox" aria-label={label} aria-multiselectable={picker.multiple || undefined} className="os-cands__list">
        {rows.length === 0 ? <span className="os-cands__none">이 묶음에 후보가 없습니다</span> : null}
        {rows.map((c) => <CandidateRow key={c.targetId} candidate={c} picker={picker} height={rowHeight} />)}
      </div>
    </div>
  );
}

function CandidateRow({ candidate: c, picker, height }: { readonly candidate: TargetCandidate; readonly picker: TargetPicker; readonly height: number }) {
  const state = picker.markerStateOf(c.targetId);
  const order = picker.orderOf(c.targetId);
  const distance = c.distanceCells === undefined ? null : <span className="os-opt__dist">{`${c.distanceCells}칸`}</span>;
  const lead = <span className={`os-opt__dot os-opt__dot--${c.here ? 'here' : c.available ? 'ok' : 'no'}`} aria-hidden="true" />;
  const text = (
    <span className="os-opt__text">
      <span className="os-opt__name">{c.name}</span>
      {c.sub ? <span className="os-opt__sub"><span className="os-opt__sub-text">{c.sub}</span></span> : null}
    </span>
  );

  if (!c.available) {
    const reason = c.reason?.trim() || '사유를 받지 못했습니다';
    return (
      <ReasonTooltip reason={reason} code={c.reasonCode} title={`${c.name} — 고를 수 없습니다`} block>
        {(describedBy) => (
          <button
            type="button"
            role="option"
            aria-selected="false"
            aria-disabled="true"
            aria-describedby={describedBy}
            className="os-opt os-opt--no"
            style={{ minHeight: height }}
            data-target-id={c.targetId}
          >
            {lead}{text}
            <span className="os-opt__end">{distance}<span className="os-opt__why">{reason}</span></span>
          </button>
        )}
      </ReasonTooltip>
    );
  }

  return (
    <button
      type="button"
      role="option"
      aria-selected={state === 'selected'}
      className={['os-opt', state === 'selected' ? 'os-opt--sel' : ''].filter(Boolean).join(' ')}
      style={{ minHeight: height }}
      data-target-id={c.targetId}
      onClick={() => picker.pick(c.targetId)}
    >
      {lead}{text}
      <span className="os-opt__end">
        {c.here ? <span className="os-chip os-chip--bronze">지금 자리</span> : distance}
        {order !== null && picker.multiple ? <span className="os-opt__order">{order}</span> : null}
        {!c.here && state !== 'selected' ? <span className="os-chip os-chip--moss">가능</span> : null}
        {state === 'selected' && !picker.multiple ? <span className="os-chip os-chip--bronze">고름</span> : null}
      </span>
    </button>
  );
}
