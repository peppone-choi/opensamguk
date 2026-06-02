// Server-driven GlobalMenu union — mirrors the legacy `@/defs/API/Global` GetMenuResponse and the
// game-api GlobalMenuResponse (lib/types.ts MenuNode). The server emits a flat list of nodes; the
// client filters them against the front-info `global` block (condShowVar / condHighlightVar) before
// rendering. Discriminated on `type` so filterMenu can narrow without casts.

export interface MenuItem {
    type: 'item';
    name: string;
    url: string;
    funcCall?: string; // present → emit reqCall(url), preventDefault (API-driven action)
    icon?: string;
    newTab?: boolean;
    condHighlightVar?: string; // highlight when globalInfo[var] truthy
    condShowVar?: string; // '!x' = show when falsy; 'x' = show when truthy; absent = always
}

export interface MenuLine {
    type: 'line';
}

export interface MenuMulti {
    type: 'multi';
    name: string;
    subMenu: MenuNode[];
}

export interface MenuSplit {
    type: 'split';
    main: MenuItem;
    subMenu: MenuNode[];
}

export type MenuNode = MenuItem | MenuLine | MenuMulti | MenuSplit;

export interface GetMenuResponse {
    result: boolean;
    version?: number;
    menu: MenuNode[];
}

// The shape filterMenu reads condShowVar/condHighlightVar against. The legacy reads `globalInfo`
// (GetFrontInfoResponse.global); our W1 front-info `global` is narrower, so we accept an open record
// and look up the named flag dynamically (truthy test only — matches the legacy semantics exactly).
export type MenuFlagSource = Record<string, unknown>;
