'use client';

import { useEffect, useState, type ReactNode } from 'react';
import { PartIcon, type PartIconName } from './PartIcon';
import type { HelpTopicRef, StatusKind } from './types';

type Common = {
  /** 영역 하나(기본) · 화면 전체. */
  readonly scope?: 'region' | 'page';
  readonly className?: string;
};

export type StatusViewProps = Common & (
  | { readonly kind: 'loading'; readonly rows?: number; readonly delayMs?: number }
  | { readonly kind: 'empty'; readonly title: string; readonly body: ReactNode; readonly actions?: ReactNode }
  | { readonly kind: 'error'; readonly title: string; readonly body?: ReactNode; readonly errorCode?: string; readonly onRetry: () => void }
  | { readonly kind: 'denied'; readonly title: string; readonly howTo: ReactNode; readonly helpTopic?: HelpTopicRef; readonly onHelp?: (topicId: string) => void }
  | { readonly kind: 'waiting'; readonly title: string; readonly body?: ReactNode }
  | { readonly kind: 'stale'; readonly lastReceived: string; readonly onReconnect: () => void; readonly title?: string }
  | { readonly kind: 'not-found'; readonly actions?: ReactNode }
  | { readonly kind: 'maintenance'; readonly body?: ReactNode; readonly actions?: ReactNode }
);

/** 보드 States 의 기본 글자. 화면이 넘기지 않은 칸만 채운다. */
export const STATUS_TEXT = {
  loading: '불러오는 중…',
  errorBody: '잠시 뒤 다시 해 보세요. 계속되면 오류 번호를 알려 주세요.',
  retry: '다시 시도',
  howToHead: '이렇게 하면 됩니다',
  waitingBody: '이 화면에 보일 내용을 서버가 아직 주지 않습니다. 준비되면 이 자리에 바로 보입니다.',
  waitingChip: '준비 중',
  staleTitle: '연결이 끊겼습니다',
  reconnect: '지금 다시 잇기',
  notFoundTitle: '찾는 화면이 없습니다',
  notFoundBody: '주소가 바뀌었거나 없어진 화면입니다. 옛 주소는 새 화면으로 저절로 넘어갑니다.',
  maintenanceTitle: '점검 중입니다',
  maintenanceBody: '끝나면 이 화면이 저절로 바뀝니다. 걸어 둔 예약은 그대로 남습니다.',
} as const;

const ICON: Record<Exclude<StatusKind, 'loading'>, { readonly name: PartIconName; readonly tone: string }> = {
  empty: { name: 'list', tone: 'muted' },
  error: { name: 'alert', tone: 'rust' },
  denied: { name: 'lock', tone: 'rust' },
  waiting: { name: 'clock', tone: 'info' },
  stale: { name: 'unplug', tone: 'bronze' },
  'not-found': { name: 'back', tone: 'muted' },
  maintenance: { name: 'tools', tone: 'info' },
};

/**
 * 상태(보드 StatusView, P-X01) — 영역 또는 화면 전체를 대신한다. 빈 것과 실패는 다른 모양이다(빈 = 이유 + 채우는 법,
 * 실패 = 다시 시도 + 오류 번호). 서버 대기 B(입력만 없음)는 여기가 아니라 InputAction NOT_DELIVERED 다.
 */
export function StatusView(props: StatusViewProps) {
  const { scope = 'region', className = '' } = props;
  const root = ['os-status', `os-status--${props.kind}`, scope === 'page' ? 'os-status--page' : '', className].filter(Boolean).join(' ');

  if (props.kind === 'loading') return <Loading className={root} rows={props.rows ?? 3} delayMs={props.delayMs ?? 300} />;

  const icon = ICON[props.kind];
  let title: ReactNode;
  let body: ReactNode = null;
  let extra: ReactNode = null;
  let actions: ReactNode = null;
  let role: 'status' | 'alert' = 'status';

  switch (props.kind) {
    case 'empty':
      ({ title, body } = props);
      actions = props.actions;
      break;
    case 'error':
      title = props.title;
      body = props.body ?? STATUS_TEXT.errorBody;
      role = 'alert';
      extra = props.errorCode ? <ErrorCode code={props.errorCode} /> : null;
      actions = <button type="button" className="os-button os-button--primary os-status__action" onClick={props.onRetry}>{STATUS_TEXT.retry}</button>;
      break;
    case 'denied':
      title = props.title;
      body = <><span className="os-status__howto-head">{STATUS_TEXT.howToHead}</span><br />{props.howTo}</>;
      if (props.helpTopic) {
        const topic = props.helpTopic;
        const onHelp = props.onHelp;
        actions = (
          <a
            className="os-button os-button--ghost os-status__action"
            href={`?help=${encodeURIComponent(topic.id)}`}
            onClick={(event) => { if (onHelp) { event.preventDefault(); onHelp(topic.id); } }}
          >
            도움말 — {topic.title}
          </a>
        );
      }
      break;
    case 'waiting':
      title = props.title;
      body = props.body ?? STATUS_TEXT.waitingBody;
      extra = <span className="os-chip os-chip--info">{STATUS_TEXT.waitingChip}</span>;
      break;
    case 'stale':
      title = props.title ?? STATUS_TEXT.staleTitle;
      body = `마지막으로 받은 자료를 보이는 중입니다(${props.lastReceived}). 다시 이어지면 저절로 새로 고칩니다.`;
      actions = <button type="button" className="os-button os-button--ghost os-status__action" onClick={props.onReconnect}>{STATUS_TEXT.reconnect}</button>;
      break;
    case 'not-found':
      title = STATUS_TEXT.notFoundTitle;
      body = STATUS_TEXT.notFoundBody;
      actions = props.actions;
      break;
    case 'maintenance':
      title = STATUS_TEXT.maintenanceTitle;
      body = props.body ?? STATUS_TEXT.maintenanceBody;
      actions = props.actions;
      break;
  }

  return (
    <div className={root} role={role}>
      <span className={`os-status__icon os-status__icon--${icon.tone}`}><PartIcon name={icon.name} /></span>
      <span className="os-status__title">{title}</span>
      {body ? <span className="os-status__body">{body}</span> : null}
      {extra}
      {actions ? <div className="os-status__actions">{actions}</div> : null}
    </div>
  );
}

function Loading({ className, rows, delayMs }: { readonly className: string; readonly rows: number; readonly delayMs: number }) {
  // 0.3초 안에 끝나는 불러오기는 뼈대를 보이지 않는다(보드 States 「0.3초 넘을 때만」).
  const [shown, setShown] = useState(delayMs <= 0);
  useEffect(() => {
    if (delayMs <= 0) return undefined;
    const timer = window.setTimeout(() => setShown(true), delayMs);
    return () => window.clearTimeout(timer);
  }, [delayMs]);
  return (
    <div className={className} aria-busy="true" role="status">
      {shown ? (
        <>
          {Array.from({ length: rows }, (_, i) => (
            <span key={i} className="os-status__skel-row" aria-hidden="true">
              <span className="os-status__skel os-status__skel--pic" />
              <span className={`os-status__skel os-status__skel--${i % 2 ? 'short' : 'long'}`} />
              <span className="os-status__skel os-status__skel--end" />
            </span>
          ))}
          <span className="os-status__loading-text">{STATUS_TEXT.loading}</span>
        </>
      ) : <span className="os-visually-hidden">{STATUS_TEXT.loading}</span>}
    </div>
  );
}

function ErrorCode({ code }: { readonly code: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      type="button"
      className="os-button os-button--ghost os-status__code"
      aria-label={`오류 번호 ${code} 복사`}
      onClick={() => {
        void navigator.clipboard?.writeText(code).then(() => setCopied(true), () => setCopied(false));
      }}
    >
      <PartIcon name="copy" size={16} />
      <span className="os-status__mono">오류 번호 {code}</span>
      {copied ? <span className="os-status__copied">복사함</span> : null}
    </button>
  );
}
