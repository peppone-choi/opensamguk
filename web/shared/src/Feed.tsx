import type { HTMLAttributes, ReactNode } from 'react';

export type FeedProps = HTMLAttributes<HTMLDivElement>;

export function Feed({ className = '', ...props }: FeedProps) {
  return <div className={`os-feed ${className}`.trim()} role="list" {...props} />;
}

export type FeedItemProps = Omit<HTMLAttributes<HTMLDivElement>, 'children'> & {
  /** 좌측 32px 슬롯(초상 아이콘·깃발). */
  readonly leading?: ReactNode;
  readonly who?: ReactNode;
  readonly what: ReactNode;
  readonly when?: ReactNode;
};

export function FeedItem({ leading, who, what, when, className = '', ...props }: FeedItemProps) {
  return (
    <div className={`os-feed-item ${className}`.trim()} role="listitem" {...props}>
      <span className="os-feed-item__leading">{leading}</span>
      <span>
        {who != null && <span className="os-feed-item__who">{who} </span>}
        <span className="os-feed-item__what">{what}</span>
        {when != null && <div className="os-feed-item__when">{when}</div>}
      </span>
    </div>
  );
}
