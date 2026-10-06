'use client';

import { api } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import { allZero, standingTiles, type StandingTile, type TileValue } from '@/lib/standing-tiles';
import CampaignLink from './CampaignLink';
import styles from './WarRoomPage.module.css';

function shown(value: TileValue): { readonly main: string; readonly sub?: string; readonly tone: 'bronze' | 'rust' | 'muted' } {
    switch (value.kind) {
        case 'loading': return { main: '—', tone: 'muted' };
        case 'error': return { main: '?', sub: '못 읽음', tone: 'rust' };
        case 'unavailable': return { main: '—', sub: '볼 수 없음', tone: 'muted' };
        default: return { main: value.value, sub: value.sub, tone: value.alert ? 'rust' : 'bronze' };
    }
}

function Tile({ tile }: { readonly tile: StandingTile }) {
    const v = shown(tile.value);
    const text = v.sub ? `${tile.label} · ${v.sub}` : tile.label;
    return (
        <CampaignLink slug={tile.slug} className={`${styles.tile} ${v.tone === 'rust' ? styles.tileAlert : ''}`}
            aria-label={`${tile.label} ${v.main}${v.sub ? ` · ${v.sub}` : ''}`}>
            <span className={`os-mono ${styles.tileValue} ${styles[`tone_${v.tone}`]}`}>{v.main}</span>
            <span className={styles.tileLabel}>{text}</span>
        </CampaignLink>
    );
}

/**
 * 맡겨 둔 일 6칸(보드 V31K4WarRoom STANDING6, 3 × 2 · 칸 52) — 출병 · 배치 · 방침 · 공사 · 계책 · 발령. 누르면 그 화면으로.
 * 발령 응답이 필요하거나 공사가 멈췄으면 rust. 칸마다 0 이면 「배치 · 방침은 영지에서」.
 * 공사는 작전실이 지도 표지용으로 이미 읽은 것을 받는다(같은 읽기를 두 번 하지 않는다).
 */
export default function StandingGrid({ works }: { readonly works: { readonly data: import('@/lib/campaign-reads').Works | null; readonly error: string | null } }) {
    const { generalId } = useGameSession();
    const deploy = useCampaignRead((id) => api.deployOptions(id));
    const posts = useCampaignRead((id, signal) => api.campaignPosts(id, signal));
    const policies = useCampaignRead((id, signal) => api.campaignPolicies(id, signal));
    const hand = useCampaignRead((id, signal) => api.stratagemHand(id, signal));
    const dispatches = useCampaignRead((id) => api.dispatchPending(id));
    const tiles = standingTiles({ deploy, posts, policies, works, hand, dispatches }, generalId);
    return (
        <div className={styles.standingGrid}>
            <div className={styles.tiles}>
                {tiles.map((tile) => <Tile key={tile.key} tile={tile} />)}
            </div>
            {allZero(tiles) ? <p className={styles.muted}>걸어 둔 일이 없습니다. 배치 · 방침은 영지에서 겁니다.</p> : null}
        </div>
    );
}
