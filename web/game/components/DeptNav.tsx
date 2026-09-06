'use client';

// 부서 나브(44px) — ADR-LITE-049 S1. 6그룹(작전실·국가 운영·군사·정보·광장·기록)에 20버튼 + 전역 메뉴 14잎.
// 비활성은 숨기지 않고 점선 + 사유 툴팁(OPENSAM-113). 우측: 갱신 · 로비로 · 커뮤니티 ↗.
// 게이팅을 아직 모르면(loading) 중립으로 두고, 서버 정보가 없으면(error) 「서버 정보 없음」만 붙인다 — 권한 사유를 지어내지 않는다.
// 키보드: 그룹 버튼 Enter/Space/ArrowDown 으로 열고 첫 항목에 포커스, 항목 간 ArrowUp/Down·Home/End, Escape 로 닫고 버튼으로 복귀.
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { usePathname } from 'next/navigation';
import { Chip, ReasonTooltip } from '@opensamguk/ui';
import {
    buildDeptGroups,
    evaluateEntry,
    groupHighlight,
    type ControlGating,
    type DeptEntryView,
    type DeptGroup,
    type GatingState,
} from '@/lib/dept-menu-config';
import type { MenuFlagSource, MenuNode } from '@/lib/menu-types';
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
    gatingState?: GatingState;
    global: MenuFlagSource;
    /** 서버 전역 메뉴(GetGlobalMenu). 없으면 v2 픽스처. */
    menu?: MenuNode[];
    /** 세로 목록(모바일 「더보기」 시트). */
    vertical?: boolean;
    onNavigate?: () => void;
    onReload?: () => void;
}

const MENU_ITEM_SELECTOR = '[role="menuitem"]';

function moveFocus(list: HTMLElement | null, from: HTMLElement, delta: number | 'first' | 'last') {
    if (!list) return;
    const items = Array.from(list.querySelectorAll<HTMLElement>(MENU_ITEM_SELECTOR));
    if (items.length === 0) return;
    let index = items.indexOf(from);
    if (delta === 'first') index = 0;
    else if (delta === 'last') index = items.length - 1;
    else index = (index + delta + items.length) % items.length;
    items[index]?.focus();
}

function EntryRow({ view, serverId, onNavigate, onKeyNav }: { view: DeptEntryView; serverId: string | undefined; onNavigate?: () => void; onKeyNav: (e: KeyboardEvent<HTMLElement>) => void }) {
    if (view.hidden) return null;
    const href = resolveDeptHref(view.href, serverId);
    if (!view.enabled) {
        return (
            <li role="none">
                <ReasonTooltip reason={view.reason ?? '사용 불가'} className="dept-nav__entry-wrap">
                    <span className="dept-nav__entry dept-nav__entry--disabled" role="menuitem" aria-disabled="true" tabIndex={-1} onKeyDown={onKeyNav}>
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
                tabIndex={-1}
                href={href}
                target={view.newTab ? '_blank' : undefined}
                rel={view.newTab ? 'noopener noreferrer' : undefined}
                onClick={onNavigate}
                onKeyDown={onKeyNav}
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
    gatingState,
    global,
    serverId,
    open,
    vertical,
    onToggle,
    onClose,
    onNavigate,
    current,
}: {
    group: DeptGroup;
    gating: ControlGating | null;
    gatingState: GatingState;
    global: MenuFlagSource;
    serverId: string | undefined;
    open: boolean;
    vertical: boolean;
    onToggle: () => void;
    onClose: () => void;
    onNavigate?: () => void;
    current: boolean;
}) {
    const id = useId();
    const buttonRef = useRef<HTMLButtonElement>(null);
    const listRef = useRef<HTMLUListElement>(null);
    const single = group.entries.length === 1 && group.entries[0].kind === 'route';
    const highlight = groupHighlight(group, gating, global);

    // 열리면 첫 항목에 포커스(세로 모드는 전부 펼쳐져 있으므로 제외).
    useEffect(() => {
        if (open && !vertical) moveFocus(listRef.current, listRef.current as unknown as HTMLElement, 'first');
    }, [open, vertical]);

    if (single) {
        const view = evaluateEntry(group.entries[0], gating, global, gatingState);
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
    const onKeyNav = (e: KeyboardEvent<HTMLElement>) => {
        if (e.key === 'ArrowDown') { e.preventDefault(); moveFocus(listRef.current, e.currentTarget, 1); }
        else if (e.key === 'ArrowUp') { e.preventDefault(); moveFocus(listRef.current, e.currentTarget, -1); }
        else if (e.key === 'Home') { e.preventDefault(); moveFocus(listRef.current, e.currentTarget, 'first'); }
        else if (e.key === 'End') { e.preventDefault(); moveFocus(listRef.current, e.currentTarget, 'last'); }
        else if (e.key === 'Escape' && !vertical) { e.preventDefault(); onClose(); buttonRef.current?.focus(); }
    };
    return (
        <div className="dept-nav__group">
            <button
                ref={buttonRef}
                type="button"
                className={`os-nav-item${open || current ? ' os-nav-item--on' : ''}`}
                aria-haspopup={vertical ? undefined : 'menu'}
                aria-expanded={vertical ? undefined : open}
                aria-controls={id}
                onClick={vertical ? undefined : onToggle}
                onKeyDown={(e) => {
                    if (vertical) return;
                    if (e.key === 'ArrowDown' && !open) { e.preventDefault(); onToggle(); }
                }}
            >
                {group.label}
                {highlight && (
                    <Chip tone="bronze" style={{ height: 16, fontSize: 10, padding: '0 5px' }}>
                        진행
                    </Chip>
                )}
                {!vertical && <span aria-hidden="true" className="dept-nav__caret">▾</span>}
            </button>
            <ul ref={listRef} id={id} role="menu" aria-label={group.label} className="dept-nav__menu" hidden={!open}>
                {group.entries.map((entry, i) => (
                    <EntryRow key={i} view={evaluateEntry(entry, gating, global, gatingState)} serverId={serverId} onNavigate={onNavigate} onKeyNav={onKeyNav} />
                ))}
            </ul>
        </div>
    );
}

export default function DeptNav({ gating, gatingState = gating ? 'ready' : 'loading', global, menu, vertical = false, onNavigate, onReload }: DeptNavProps) {
    const serverId = useServerId();
    const pathname = usePathname();
    const normalized = normalizeGamePathname(pathname ?? '', serverId);
    const [openKey, setOpenKey] = useState<string | null>(null);
    const rootRef = useRef<HTMLElement>(null);
    const groups = useMemo(() => buildDeptGroups(menu), [menu]);

    useEffect(() => {
        if (!openKey) return;
        const onDown = (e: MouseEvent) => {
            if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpenKey(null);
        };
        window.addEventListener('mousedown', onDown);
        return () => window.removeEventListener('mousedown', onDown);
    }, [openKey]);

    // 현재 경로가 속한 그룹(하이라이트용). 작전실은 /game 정확히.
    const currentGroup = groups.find((g) =>
        g.entries.some((e) => {
            const v = evaluateEntry(e, gating, global, gatingState);
            const target = normalizeLegacyGamePath(v.href).split(/[?#]/)[0];
            return target === normalized;
        }),
    );

    return (
        <nav ref={rootRef} className={`dept-nav${vertical ? ' dept-nav--vertical' : ''}`} aria-label="부서 메뉴">
            <div className="dept-nav__groups">
                {groups.map((group) => (
                    <GroupMenu
                        key={group.key}
                        group={group}
                        gating={gating}
                        gatingState={gatingState}
                        global={global}
                        serverId={serverId}
                        open={vertical || openKey === group.key}
                        vertical={vertical}
                        onToggle={() => setOpenKey((k) => (k === group.key ? null : group.key))}
                        onClose={() => setOpenKey(null)}
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
