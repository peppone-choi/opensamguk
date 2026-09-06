'use client';

// 부서 나브(44px) — ADR-LITE-049 S1. 6그룹(작전실·국가 운영·군사·정보·광장·기록)에 20버튼 + 전역 메뉴 14잎.
// 비활성은 숨기지 않고 점선 + 사유 툴팁(OPENSAM-113). 우측: 갱신 · 로비로 · 커뮤니티 ↗.
import { useEffect, useId, useRef, useState } from 'react';
import { usePathname } from 'next/navigation';
import { Chip, ReasonTooltip } from '@opensamguk/ui';
import {
    DEPT_GROUPS,
    evaluateEntry,
    groupHighlight,
    type ControlGating,
    type DeptEntryView,
    type DeptGroup,
} from '@/lib/dept-menu-config';
import type { MenuFlagSource } from '@/lib/menu-types';
import {
    gameChildPath,
    normalizeGamePathname,
    normalizeLegacyGamePath,
    resolveServerGamePath,
    useServerId,
} from '@/lib/serverGameUrl';

const gatewayPublicUrl = process.env.NEXT_PUBLIC_GATEWAY_URL ?? process.env.NEXT_PUBLIC_GATEWAY_ORIGIN;
const gatewayBase = gatewayPublicUrl ? gatewayPublicUrl.replace(/\/$/, '') : '';
export const LOBBY_HREF = `${gatewayBase}/lobby`;
export const COMMUNITY_HREF = `${gatewayBase}/board`;

/** 상대 href(/game/…, 레거시 .php, 외부 http, #앵커)를 현재 서버 경로로 해석한다. */
export function resolveDeptHref(href: string, serverId: string | undefined): string {
    if (/^https?:\/\//i.test(href) || href.startsWith('#')) return href;
    const hashIndex = href.indexOf('#');
    const hash = hashIndex >= 0 ? href.slice(hashIndex) : '';
    const noHash = hashIndex >= 0 ? href.slice(0, hashIndex) : href;
    const normalized = normalizeLegacyGamePath(noHash);
    const isGame = normalized === '/game' || normalized.startsWith('/game/') || normalized.startsWith('/game?');
    if (!serverId || !isGame) return `${normalized}${hash}`;
    return `${resolveServerGamePath(undefined, serverId, '/game', gameChildPath(normalized))}${hash}`;
}

export interface DeptNavProps {
    gating: ControlGating | null;
    global: MenuFlagSource;
    /** 세로 목록(모바일 「더보기」 시트). */
    vertical?: boolean;
    onNavigate?: () => void;
    onReload?: () => void;
}

function EntryRow({ view, serverId, onNavigate }: { view: DeptEntryView; serverId: string | undefined; onNavigate?: () => void }) {
    if (view.hidden) return null;
    const href = resolveDeptHref(view.href, serverId);
    if (!view.enabled) {
        return (
            <li role="none">
                <ReasonTooltip reason={view.reason ?? '사용 불가'} className="dept-nav__entry-wrap">
                    <span className="dept-nav__entry dept-nav__entry--disabled" role="menuitem" aria-disabled="true" tabIndex={0}>
                        {view.label}
                    </span>
                </ReasonTooltip>
            </li>
        );
    }
    return (
        <li role="none">
            <a
                className={`dept-nav__entry${view.highlight ? ' dept-nav__entry--highlight' : ''}`}
                role="menuitem"
                href={href}
                target={view.newTab ? '_blank' : undefined}
                rel={view.newTab ? 'noopener noreferrer' : undefined}
                onClick={onNavigate}
            >
                <span>{view.label}</span>
                {view.newTab && <span aria-hidden="true" className="dept-nav__ext">↗</span>}
            </a>
        </li>
    );
}

function GroupMenu({
    group,
    gating,
    global,
    serverId,
    open,
    onToggle,
    onNavigate,
    current,
}: {
    group: DeptGroup;
    gating: ControlGating | null;
    global: MenuFlagSource;
    serverId: string | undefined;
    open: boolean;
    onToggle: () => void;
    onNavigate?: () => void;
    current: boolean;
}) {
    const id = useId();
    const single = group.entries.length === 1 && group.entries[0].kind === 'route';
    const highlight = groupHighlight(group, gating, global);
    if (single) {
        const view = evaluateEntry(group.entries[0], gating, global);
        return (
            <a
                className={`os-nav-item${current ? ' os-nav-item--on' : ''}`}
                href={resolveDeptHref(view.href, serverId)}
                aria-current={current ? 'page' : undefined}
                onClick={onNavigate}
            >
                {group.label}
            </a>
        );
    }
    return (
        <div className="dept-nav__group">
            <button
                type="button"
                className={`os-nav-item${open || current ? ' os-nav-item--on' : ''}`}
                aria-haspopup="menu"
                aria-expanded={open}
                aria-controls={id}
                onClick={onToggle}
            >
                {group.label}
                {highlight && (
                    <Chip tone="bronze" style={{ height: 16, fontSize: 10, padding: '0 5px' }}>
                        진행
                    </Chip>
                )}
                <span aria-hidden="true" className="dept-nav__caret">▾</span>
            </button>
            <ul id={id} role="menu" aria-label={group.label} className="dept-nav__menu" hidden={!open}>
                {group.entries.map((entry, i) => (
                    <EntryRow key={i} view={evaluateEntry(entry, gating, global)} serverId={serverId} onNavigate={onNavigate} />
                ))}
            </ul>
        </div>
    );
}

export default function DeptNav({ gating, global, vertical = false, onNavigate, onReload }: DeptNavProps) {
    const serverId = useServerId();
    const pathname = usePathname();
    const normalized = normalizeGamePathname(pathname ?? '', serverId);
    const [openKey, setOpenKey] = useState<string | null>(null);
    const rootRef = useRef<HTMLElement>(null);

    useEffect(() => {
        if (!openKey) return;
        const onKey = (e: KeyboardEvent) => {
            if (e.key === 'Escape') setOpenKey(null);
        };
        const onDown = (e: MouseEvent) => {
            if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpenKey(null);
        };
        window.addEventListener('keydown', onKey);
        window.addEventListener('mousedown', onDown);
        return () => {
            window.removeEventListener('keydown', onKey);
            window.removeEventListener('mousedown', onDown);
        };
    }, [openKey]);

    // 현재 경로가 속한 그룹(하이라이트용). 작전실은 /game 정확히.
    const currentGroup = DEPT_GROUPS.find((g) =>
        g.entries.some((e) => {
            const v = evaluateEntry(e, gating, global);
            const target = normalizeLegacyGamePath(v.href).split(/[?#]/)[0];
            return target === normalized;
        }),
    );

    return (
        <nav ref={rootRef} className={`dept-nav${vertical ? ' dept-nav--vertical' : ''}`} aria-label="부서 메뉴">
            <div className="dept-nav__groups">
                {DEPT_GROUPS.map((group) => (
                    <GroupMenu
                        key={group.key}
                        group={group}
                        gating={gating}
                        global={global}
                        serverId={serverId}
                        open={vertical || openKey === group.key}
                        onToggle={() => setOpenKey((k) => (k === group.key ? null : group.key))}
                        onNavigate={() => {
                            setOpenKey(null);
                            onNavigate?.();
                        }}
                        current={currentGroup?.key === group.key}
                    />
                ))}
            </div>
            <div className="dept-nav__actions">
                <button type="button" className="os-button os-button--ghost os-button--sm" onClick={onReload ?? (() => window.location.reload())}>
                    갱신
                </button>
                <a className="os-button os-button--ghost os-button--sm" href={LOBBY_HREF}>
                    로비로
                </a>
                <a className="os-button os-button--ghost os-button--sm dept-nav__community" href={COMMUNITY_HREF}>
                    커뮤니티 ↗
                </a>
            </div>
        </nav>
    );
}
