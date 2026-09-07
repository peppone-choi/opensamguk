import type { HTMLAttributes } from 'react';
import { parseLogTokens, type LogSegment } from './logTokens';

export type LogTextProps = Omit<HTMLAttributes<HTMLSpanElement>, 'children'> & {
  readonly text: string | null | undefined;
};

function segmentClass(s: LogSegment): string | undefined {
  const parts = [
    s.tone ? `os-log__${s.tone}` : '',
    s.small ? 'os-log__small' : '',
    s.bold ? 'os-log__b' : '',
    s.failed ? 'os-log__failed' : '',
  ].filter(Boolean);
  return parts.length ? parts.join(' ') : undefined;
}

/**
 * 게임 로그 한 줄 — devsam 토큰(<C>●</> <Y>이름</> …)을 팔레트 색 span 으로 그린다.
 * innerHTML 을 쓰지 않는다: 토큰 밖 문자는 전부 텍스트 노드다.
 */
export function LogText({ text, className = '', ...props }: LogTextProps) {
  const segments = parseLogTokens(text);
  return (
    <span className={`os-log ${className}`.trim()} {...props}>
      {segments.map((s, i) => {
        const cls = segmentClass(s);
        if (!cls && !s.color) return s.text;
        return (
          <span key={i} className={cls} style={s.color ? { color: s.color } : undefined}>{s.text}</span>
        );
      })}
    </span>
  );
}
