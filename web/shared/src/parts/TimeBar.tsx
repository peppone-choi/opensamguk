'use client';

import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { PartIcon } from './PartIcon';
import type { TimeBarEvent, TimeBarMode, TimeBarSpeed } from './types';

type Common = {
  /** 「지금」 문장(예: 「우리 선봉 하후돈이 적 좌익과 일기토」). */
  readonly nowText: string;
  /** 지금 자리(ms). */
  readonly position: number;
  readonly events: readonly TimeBarEvent[];
  readonly onSeek: (ms: number) => void;
  readonly className?: string;
};

export type TimeBarProps = Common & (
  | {
      readonly mode: 'replay';
      /** 처음 ~ 끝(ms). */
      readonly duration: number;
      readonly playing: boolean;
      readonly onPlayPause: () => void;
      readonly speed: TimeBarSpeed;
      readonly onSpeed: (speed: TimeBarSpeed) => void;
      /** 「결과」 단추. 없으면 그리지 않는다. */
      readonly onResult?: () => void;
    }
  | {
      readonly mode: 'live';
      /** 지나간 데까지(ms) — 실시간은 여기까지만 되감는다. */
      readonly elapsed: number;
      readonly onJumpLive: () => void;
    }
);

const SPEEDS: readonly TimeBarSpeed[] = [0.5, 1, 2];
const HIT = 44;
const STEP_MS = 5000;

/** 사건 표식의 줄 — 누를 영역 44 가 겹치면 다음 줄로 내린다(보드 tb_rows). */
export function layoutEventRows(events: readonly TimeBarEvent[], span: number, trackPx: number): { readonly rows: ReadonlyMap<string, number>; readonly count: number } {
  const rows = new Map<string, number>();
  const last: number[] = [];
  const sorted = [...events].sort((a, b) => a.at - b.at);
  for (const e of sorted) {
    const x = span > 0 ? (trackPx * e.at) / span : 0;
    let row = last.findIndex((prev) => x - prev >= HIT);
    if (row < 0) {
      row = last.length;
      last.push(x);
    } else last[row] = x;
    rows.set(e.id, row);
  }
  return { rows, count: Math.max(1, last.length) };
}

export function formatClock(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
}

/**
 * 시간 막대(보드 TimeBar). replay = 처음 ~ 끝, 빠르기 · 이전/다음 사건 · 결과. live = 지나간 데까지만, 「지금으로」.
 * 사건 표식은 누르면 그 순간으로 — 누를 영역 44, 겹치면 다음 줄. 손잡이 · 막대 그림은 누르기를 먹지 않는다.
 * 데스크톱 한 줄 · 모바일(< 768) 두 줄은 CSS 가 나눈다.
 */
export function TimeBar(props: TimeBarProps) {
  const { nowText, position, events, onSeek, className = '' } = props;
  const span = props.mode === 'replay' ? props.duration : props.elapsed;
  const end = props.mode === 'replay' ? props.duration : props.elapsed;
  const visible = useMemo(() => (props.mode === 'replay' ? events : events.filter((e) => e.at <= end)), [props.mode, events, end]);

  const track = useRef<HTMLDivElement>(null);
  const [trackPx, setTrackPx] = useState(820);
  useEffect(() => {
    const el = track.current;
    if (!el || typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver(([entry]) => setTrackPx(Math.max(1, entry.contentRect.width)));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  const layout = useMemo(() => layoutEventRows(visible, span, trackPx), [visible, span, trackPx]);

  const clamp = (ms: number) => Math.min(end, Math.max(0, ms));
  const pct = (ms: number) => (span > 0 ? (100 * ms) / span : 0);
  const seekFromPointer = (event: PointerEvent<HTMLDivElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    if (rect.width <= 0) return;
    onSeek(clamp(((event.clientX - rect.left) / rect.width) * span));
  };
  const onKey = (event: KeyboardEvent<HTMLDivElement>) => {
    const next = { ArrowLeft: position - STEP_MS, ArrowRight: position + STEP_MS, Home: 0, End: end }[event.key];
    if (next === undefined) return;
    event.preventDefault();
    onSeek(clamp(next));
  };
  const jump = (dir: -1 | 1) => {
    const sorted = [...visible].sort((a, b) => a.at - b.at);
    const target = dir < 0 ? [...sorted].reverse().find((e) => e.at < position - 1) : sorted.find((e) => e.at > position + 1);
    if (target) onSeek(target.at);
  };

  const height = HIT * layout.count + 14;
  const lineY = HIT * layout.count + 2;
  const trackEl = (
    <div
      ref={track}
      className="os-timebar__track"
      role="slider"
      tabIndex={0}
      aria-label="시간"
      aria-valuemin={0}
      aria-valuemax={Math.round(end / 1000)}
      aria-valuenow={Math.round(position / 1000)}
      aria-valuetext={formatClock(position)}
      style={{ height }}
      onPointerDown={(event) => {
        if ((event.target as HTMLElement).closest('.os-timebar__event')) return;
        event.currentTarget.setPointerCapture?.(event.pointerId);
        seekFromPointer(event);
      }}
      onPointerMove={(event) => { if (event.buttons === 1) seekFromPointer(event); }}
      onKeyDown={onKey}
    >
      <i className="os-timebar__rail" style={{ top: lineY, right: `${100 - pct(end)}%` }} aria-hidden="true" />
      <i className="os-timebar__fill" style={{ top: lineY, width: `${pct(position)}%` }} aria-hidden="true" />
      {visible.map((e) => (
        <button
          key={e.id}
          type="button"
          className={`os-timebar__event os-timebar__event--${e.tone ?? 'bronze'}`}
          style={{ left: `${pct(e.at)}%`, top: HIT * (layout.rows.get(e.id) ?? 0) }}
          aria-label={`${e.label} — ${Math.round(pct(e.at))}% 지점으로`}
          onClick={() => onSeek(e.at)}
        >
          <i aria-hidden="true" />
        </button>
      ))}
      <i className="os-timebar__thumb" style={{ left: `${pct(position)}%`, top: lineY - 6 }} aria-hidden="true" />
    </div>
  );

  const now = (
    <div className="os-timebar__now">
      <span className="os-timebar__now-head">지금</span>
      <span className="os-timebar__now-text">{nowText}</span>
    </div>
  );

  if (props.mode === 'replay') {
    const controls = (
      <span className="os-timebar__controls">
        <button type="button" className="os-timebar__ibtn" aria-label="이전 사건" onClick={() => jump(-1)}><PartIcon name="prev" /></button>
        <button
          type="button"
          className="os-timebar__ibtn os-timebar__ibtn--main"
          aria-label={props.playing ? '멈춤' : '재생'}
          aria-pressed={props.playing}
          onClick={props.onPlayPause}
        >
          <PartIcon name={props.playing ? 'pause' : 'play'} />
        </button>
        <button type="button" className="os-timebar__ibtn" aria-label="다음 사건" onClick={() => jump(1)}><PartIcon name="next" /></button>
      </span>
    );
    const tail = (
      <span className="os-timebar__tail">
        <span className="os-seg os-timebar__speed" role="radiogroup" aria-label="빠르기">
          {SPEEDS.map((s) => (
            <button
              key={s}
              type="button"
              role="radio"
              aria-checked={s === props.speed}
              className={['os-seg__item', s === props.speed ? 'os-seg__item--on' : ''].filter(Boolean).join(' ')}
              onClick={() => props.onSpeed(s)}
            >
              {`${s}×`}
            </button>
          ))}
        </span>
        {props.onResult ? <button type="button" className="os-button os-button--ghost os-timebar__btn" onClick={props.onResult}>결과</button> : null}
      </span>
    );
    return (
      <div className={['os-timebar', 'os-timebar--replay', className].filter(Boolean).join(' ')} role="group" aria-label="시간 막대">
        {now}
        <div className="os-timebar__row">
          {controls}
          {trackEl}
          <span className="os-timebar__clock">{`${formatClock(position)} / ${formatClock(props.duration)}`}</span>
          {tail}
        </div>
      </div>
    );
  }

  return (
    <div className={['os-timebar', 'os-timebar--live', className].filter(Boolean).join(' ')} role="group" aria-label="시간 막대">
      {now}
      <div className="os-timebar__row">
        <span className="os-timebar__controls"><span className="os-chip os-chip--rust os-timebar__live"><i aria-hidden="true" />실시간</span></span>
        {trackEl}
        <span className="os-timebar__clock">{`${formatClock(props.elapsed)} 지남`}</span>
        <span className="os-timebar__tail">
          <button type="button" className="os-button os-button--ghost os-timebar__btn" onClick={props.onJumpLive}>지금으로</button>
        </span>
      </div>
    </div>
  );
}

export type { TimeBarMode };
