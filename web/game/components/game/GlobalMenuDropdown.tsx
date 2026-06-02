'use client';

// GlobalMenuDropdown — column-layout replica of GlobalMenu for the mobile `외부 메뉴` dropup
// (spec §8.1, port of GlobalMenuDropdown.vue). Same filterMenu + click semantics; renders as a
// CSS `columns` list: multi = disabled header + indented subitems, split = linked header + indented,
// line = <hr class="dropdown-divider">. Default 3 columns.

import { filterMenu } from '@/lib/menu-filter';
import type { MenuFlagSource, MenuItem, MenuNode } from '@/lib/menu-types';

interface GlobalMenuDropdownProps {
    menu: MenuNode[];
    global: MenuFlagSource;
    columns?: number;
    onReqCall?: (url: string) => void;
}

function handleClick(item: MenuItem, onReqCall?: (url: string) => void): boolean {
    if (item.funcCall) {
        onReqCall?.(item.url);
        return true;
    }
    if (!item.url) return true;
    if (!item.newTab) return false;
    window.open(item.url);
    return true;
}

function ItemLink({
    item,
    sub,
    onReqCall,
}: {
    item: MenuItem;
    sub?: boolean;
    onReqCall?: (url: string) => void;
}) {
    return (
        <a
            className={`dropdown-item${sub ? ' subItem' : ''}${item.newTab ? ' open-window' : ''}`}
            href={item.url}
            target={item.newTab ? '_blank' : undefined}
            rel={item.newTab ? 'noopener noreferrer' : undefined}
            onClick={(e) => {
                if (handleClick(item, onReqCall)) e.preventDefault();
            }}
        >
            {item.name}
        </a>
    );
}

export default function GlobalMenuDropdown({
    menu,
    global,
    columns = 3,
    onReqCall,
}: GlobalMenuDropdownProps) {
    const filtered = filterMenu(menu, global);

    return (
        <ul
            className="dropdown-menu dropdown-menu-start gm-columns"
            style={{ ['--gm-columns' as string]: String(columns) } as React.CSSProperties}
        >
            {filtered.map((node, idx) => {
                if (node.type === 'item') {
                    return (
                        <li key={idx}>
                            <ItemLink item={node} onReqCall={onReqCall} />
                        </li>
                    );
                }
                if (node.type === 'multi') {
                    return (
                        <li key={idx} className="gm-group">
                            <span className="dropdown-item disabled">{node.name}</span>
                            {node.subMenu.map((sub, i) =>
                                sub.type === 'item' ? (
                                    <ItemLink key={i} item={sub} sub onReqCall={onReqCall} />
                                ) : (
                                    <hr key={i} className="dropdown-divider" />
                                ),
                            )}
                            <hr className="dropdown-divider" />
                        </li>
                    );
                }
                if (node.type === 'split') {
                    return (
                        <li key={idx} className="gm-group">
                            <ItemLink item={node.main} onReqCall={onReqCall} />
                            {node.subMenu.map((sub, i) =>
                                sub.type === 'item' ? (
                                    <ItemLink key={i} item={sub} sub onReqCall={onReqCall} />
                                ) : (
                                    <hr key={i} className="dropdown-divider" />
                                ),
                            )}
                            <hr className="dropdown-divider" />
                        </li>
                    );
                }
                return null;
            })}
        </ul>
    );
}
