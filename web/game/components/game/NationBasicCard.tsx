'use client';

// NationBasicCard — the player's nation info card (spec §6.2, legacy NationBasicCard.vue). The legacy card
// carries 성향/관직/주민/병사/국고/병량/지급률/세율/속령/장수/국력/기술력/전략/외교/임관/전쟁 — most of which
// are NOT in web/game's front-info.nation (FrontNationInfo only has id/name/color/level/gold/rice/tech/
// capitalCityId). We render the VERBATIM labels for the fields we have and OMIT the rest (NO fabrication).
// Header uses the nation color (auto text color by brightness) exactly like the .vue. When the player is
// factionless (`nation == null`), the body shows `해당 없음` like the legacy `!nation.id` branch.

import { formatNumber } from '@/lib/format';
import type { FrontNationInfo } from '@/lib/types';

function isBrightColor(hex?: string): boolean {
    if (!hex) return false;
    const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
    if (!m) return false;
    const v = parseInt(m[1], 16);
    const r = (v >> 16) & 0xff;
    const g = (v >> 8) & 0xff;
    const b = v & 0xff;
    return (r * 299 + g * 587 + b * 114) / 1000 >= 128;
}

export interface NationBasicCardProps {
    nation: FrontNationInfo | null;
}

export default function NationBasicCard({ nation }: NationBasicCardProps) {
    const nationColor = nation?.color ?? '#333333';
    const headerText = isBrightColor(nationColor) ? '#000' : '#fff';
    const has = nation != null && nation.id !== 0;

    const rows: { label: string; value: React.ReactNode }[] = [
        { label: '국고', value: has ? formatNumber(nation!.gold) : '해당 없음' },
        { label: '병량', value: has ? formatNumber(nation!.rice) : '해당 없음' },
        { label: '기술력', value: has ? formatNumber(Math.floor(nation!.tech)) : '해당 없음' },
    ];

    return (
        <section className="basic-card nation-basic-card" aria-label="국가 정보">
            <div className="basic-card-name" style={{ backgroundColor: nationColor, color: headerText }}>
                {nation?.name ?? '재야'}
            </div>
            <div className="basic-card-grid">
                {rows.map((r) => (
                    <div key={r.label} className="basic-card-row">
                        <div className="basic-card-head">{r.label}</div>
                        <div className="basic-card-body">{r.value}</div>
                    </div>
                ))}
            </div>
        </section>
    );
}
