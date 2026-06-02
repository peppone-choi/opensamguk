// filterMenu — verbatim port of GlobalMenu.vue / GlobalMenuDropdown.vue filterMenu().
// Identical in both bar and dropdown variants, so it lives here once.
//
// Rules (parity contract):
//  - item: no condShowVar → keep. condShowVar '!x' → keep when globalInfo[x] falsy.
//           condShowVar 'x' → keep when globalInfo[x] truthy. The flag must EXIST on globalInfo
//           (legacy `cond in globalInfo`); otherwise the item is dropped.
//  - multi: recursively filter subMenu. 0 remain → drop. exactly 1 remains → hoist that subitem
//           to parent level. else → keep the multi with filtered subMenu.
//  - split: filter main + subMenu independently. main dropped → drop the split. subMenu empty →
//           render main only (as a plain item). else → keep the split with filtered subMenu.
//  - line: structural; only meaningful inside a subMenu, kept as-is during sub filtering.

import type { MenuFlagSource, MenuItem, MenuNode } from './menu-types';

function showItem(item: MenuItem, global: MenuFlagSource): boolean {
    const cond = item.condShowVar;
    if (!cond) return true;
    const negate = cond.startsWith('!');
    const key = negate ? cond.slice(1) : cond;
    if (!(key in global)) return false;
    const value = global[key];
    return negate ? !value : Boolean(value);
}

// Filter a sublist (multi/split children). Items are gated by condShowVar; lines pass through.
function filterSubMenu(subMenu: MenuNode[], global: MenuFlagSource): MenuNode[] {
    const out: MenuNode[] = [];
    for (const node of subMenu) {
        if (node.type === 'item') {
            if (showItem(node, global)) out.push(node);
        } else if (node.type === 'line') {
            out.push(node);
        }
        // multi/split are never nested as subMenu entries in the spec; ignore if present.
    }
    return out;
}

/**
 * Filter the top-level menu list against the global flag source. Returns the displayable nodes,
 * with multi-hoist and split-collapse applied. A hoisted single subitem becomes a top-level item;
 * a collapsed split becomes its main item.
 */
export function filterMenu(menu: MenuNode[], global: MenuFlagSource): MenuNode[] {
    const out: MenuNode[] = [];

    for (const node of menu) {
        if (node.type === 'item') {
            if (showItem(node, global)) out.push(node);
            continue;
        }

        if (node.type === 'line') {
            out.push(node);
            continue;
        }

        if (node.type === 'multi') {
            const filtered = filterSubMenu(node.subMenu, global);
            const items = filtered.filter((n): n is MenuItem => n.type === 'item');
            if (items.length === 0) continue; // drop empty multi
            if (items.length === 1) {
                out.push(items[0]); // hoist the sole subitem
                continue;
            }
            out.push({ type: 'multi', name: node.name, subMenu: filtered });
            continue;
        }

        // split
        if (!showItem(node.main, global)) continue; // main dropped → drop split
        const filtered = filterSubMenu(node.subMenu, global);
        const items = filtered.filter((n): n is MenuItem => n.type === 'item');
        if (items.length === 0) {
            out.push(node.main); // render main only
            continue;
        }
        out.push({ type: 'split', main: node.main, subMenu: filtered });
    }

    return out;
}
