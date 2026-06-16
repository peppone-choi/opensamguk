'use client';

// GlobalMenu — server-driven menu spine (spec §4). Port of GlobalMenu.vue.
// Consumes the GetMenuResponse union, filters it against the front-info `global` flag block,
// and renders a responsive grid (desktop ~8 / mobile ~4). multi/split render as click-to-open
// inline dropdowns. Click handling mirrors menuClick(): funcCall → reqCall(url); newTab → window.open.

import { useState } from 'react';
import { filterMenu } from '@/lib/menu-filter';
import { gameChildPath, resolveServerGamePath } from '@/lib/serverGameUrl';
import type { MenuFlagSource, MenuItem, MenuNode } from '@/lib/menu-types';

interface GlobalMenuProps {
    menu: MenuNode[];
    global: MenuFlagSource;
    desktopRowSize?: number;
    mobileRowSize?: number;
    // funcCall items emit here (API-driven actions); GameChrome wires the handler.
    onReqCall?: (url: string) => void;
}

function isHighlighted(item: MenuItem, global: MenuFlagSource): boolean {
    const v = item.condHighlightVar;
    return v ? Boolean(global[v]) : false;
}

function resolveMenuUrl(url: string, serverId: string | undefined): string {
    if (!serverId) return url;
    if (!url.startsWith('/game') || url.startsWith('/game/') === false && url !== '/game') return url;
    return resolveServerGamePath(undefined, serverId, '/game', gameChildPath(url));
}

// Resolve a click the same way the legacy menuClick() does. Returns true if navigation was handled
// (caller should preventDefault); false → let the default <a> nav proceed.
function handleClick(item: MenuItem, onReqCall?: (url: string) => void): boolean {
    if (item.funcCall) {
        onReqCall?.(item.url);
        return true;
    }
    if (!item.url) return true;
    if (!item.newTab) return false; // default nav
    window.open(item.url);
    return true;
}

function MenuButton({
    item,
    global,
    serverId,
    onReqCall,
}: {
    item: MenuItem;
    global: MenuFlagSource;
    serverId: string | undefined;
    onReqCall?: (url: string) => void;
}) {
    const highlight = isHighlighted(item, global);
    const url = resolveMenuUrl(item.url, serverId);
    return (
        <a
            className={`global-menu-btn${highlight ? ' highlight' : ''}`}
            href={url}
            target={item.newTab ? '_blank' : undefined}
            rel={item.newTab ? 'noopener noreferrer' : undefined}
            onClick={(e) => {
                if (handleClick({ ...item, url }, onReqCall)) e.preventDefault();
            }}
        >
            {item.name}
        </a>
    );
}

function MenuDropdown({
    label,
    mainItem,
    subMenu,
    global,
    serverId,
    onReqCall,
}: {
    label: string;
    mainItem?: MenuItem; // present for split (main is itself navigable)
    subMenu: MenuNode[];
    global: MenuFlagSource;
    serverId: string | undefined;
    onReqCall?: (url: string) => void;
}) {
    const [open, setOpen] = useState(false);
    return (
        <div className={`global-menu-group${open ? ' open' : ''}`} onMouseLeave={() => setOpen(false)}>
            {mainItem ? (
                <a
                    className="global-menu-btn global-menu-split-main"
                    href={resolveMenuUrl(mainItem.url, serverId)}
                    target={mainItem.newTab ? '_blank' : undefined}
                    rel={mainItem.newTab ? 'noopener noreferrer' : undefined}
                    onClick={(e) => {
                        if (handleClick(mainItem, onReqCall)) e.preventDefault();
                    }}
                >
                    {label}
                </a>
            ) : (
                <button type="button" className="global-menu-btn" onClick={() => setOpen((o) => !o)}>
                    {label} ▾
                </button>
            )}
            {mainItem && (
                <button
                    type="button"
                    className="global-menu-btn global-menu-split-toggle"
                    aria-label="Toggle Dropdown"
                    onClick={() => setOpen((o) => !o)}
                >
                    ▾
                </button>
            )}
            {open && (
                <ul className="global-menu-dropdown">
                    {subMenu.map((sub, i) =>
                        sub.type === 'line' ? (
                            <li key={i}>
                                <hr className="dropdown-divider" />
                            </li>
                        ) : sub.type === 'item' ? (
                            <li key={i}>
                                <a
                                    className="dropdown-item"
                                    href={resolveMenuUrl(sub.url, serverId)}
                                    target={sub.newTab ? '_blank' : undefined}
                                    rel={sub.newTab ? 'noopener noreferrer' : undefined}
                                    onClick={(e) => {
                                        if (handleClick(sub, onReqCall)) e.preventDefault();
                                        setOpen(false);
                                    }}
                                >
                                    {sub.name}
                                </a>
                            </li>
                        ) : null,
                    )}
                </ul>
            )}
        </div>
    );
}

export default function GlobalMenu({
    menu,
    global,
    desktopRowSize = 8,
    mobileRowSize = 4,
    onReqCall,
}: GlobalMenuProps) {
    const filtered = filterMenu(menu, global);
    const serverId = (global?.serverId as string | undefined) || undefined;

    return (
        <nav
            className="global-menu"
            aria-label="전역 메뉴"
            style={
                {
                    '--gm-desktop-cols': desktopRowSize,
                    '--gm-mobile-cols': mobileRowSize,
                } as React.CSSProperties
            }
        >
            {filtered.map((node, idx) => {
                if (node.type === 'item') {
                    return (
                        <MenuButton
                            key={idx}
                            item={node}
                            global={global}
                            serverId={serverId}
                            onReqCall={onReqCall}
                        />
                    );
                }
                if (node.type === 'multi') {
                    return (
                        <MenuDropdown
                            key={idx}
                            label={node.name}
                            subMenu={node.subMenu}
                            global={global}
                            serverId={serverId}
                            onReqCall={onReqCall}
                        />
                    );
                }
                if (node.type === 'split') {
                    return (
                        <MenuDropdown
                            key={idx}
                            label={node.main.name}
                            mainItem={node.main}
                            subMenu={node.subMenu}
                            global={global}
                            serverId={serverId}
                            onReqCall={onReqCall}
                        />
                    );
                }
                return null; // bare top-level line — not rendered in the bar
            })}
        </nav>
    );
}
