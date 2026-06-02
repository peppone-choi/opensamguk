'use client';

// GeneralBasicCard — the player's general info card (spec §6.1, legacy GeneralBasicCard.vue). The legacy
// card has ~30 fields (exp bars, equip tooltips, troop, 벌점 …); web/game's front-info.general carries
// only the W1 subset, so we render the VERBATIM legacy labels for the fields we actually have and OMIT
// the rest (NO fabrication). Header uses the nation color (auto text color by brightness) like the .vue.
//
// Rendered from front-info (FrontGeneralInfo + FrontNationInfo) — no extra fetch. injury color mirrors
// formatInjury (0 = healthy white).

import { formatNumber } from '@/lib/format';
import type { FrontGeneralInfo, FrontNationInfo } from '@/lib/types';

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

// officer-level → 관직 text (legacy officerLevelText); minimal map, full table is const-driven backend-side.
function officerLevelText(level: number): string {
    return level <= 0 ? '재야' : `관직 ${level}급`;
}

export interface GeneralBasicCardProps {
    general: FrontGeneralInfo;
    nation: FrontNationInfo | null;
}

export default function GeneralBasicCard({ general, nation }: GeneralBasicCardProps) {
    const nationColor = nation?.color ?? '#333333';
    const headerText = isBrightColor(nationColor) ? '#000' : '#fff';
    const injuryColor = general.injury > 0 ? 'orange' : '#fff';

    const rows: { label: string; value: React.ReactNode }[] = [
        { label: '통솔', value: <span style={{ color: injuryColor }}>{general.leadership}</span> },
        { label: '무력', value: <span style={{ color: injuryColor }}>{general.strength}</span> },
        { label: '지력', value: <span style={{ color: injuryColor }}>{general.intel}</span> },
        { label: '소속', value: nation?.name ?? '재야' },
        { label: '관직', value: officerLevelText(general.officerLevel) },
        { label: '자금', value: formatNumber(general.gold) },
        { label: '군량', value: formatNumber(general.rice) },
        { label: '병사', value: formatNumber(general.crew) },
    ];

    return (
        <section className="basic-card general-basic-card" aria-label="장수 정보">
            <div className="basic-card-name" style={{ backgroundColor: nationColor, color: headerText }}>
                {general.name ?? '-'}
                {general.injury > 0 && <span style={{ color: 'orange' }}> 【 부상 】</span>}
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
