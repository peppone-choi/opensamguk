// 셸이 찾은 지금 화면 → 도움말 「이 화면」. 셸 레이아웃(모든 게임 화면)이 부르므로 원장 표 · 부품을 import 하지 않는다.
import type { HelpScreen } from './help-screens';

/**
 * 셸이 찾은 지금 화면(nav31 locateScreen — 묶음 · 화면 경로) → 「이 화면」. 묶음에 딸린 화면 중 따로 도움말 목록이 있는 것만 가른다.
 * 기록 · 광장 · 경로 밖(도움말 페이지 · 입장 등)은 `other`(분류로 찾기).
 */
export function helpScreenOf(groupKey: string | null | undefined, screenPath: string | null | undefined): HelpScreen {
    const path = (screenPath ?? '').split('?')[0];
    switch (groupKey) {
        case 'war': return 'war-room';
        case 'retinue': return 'retinue';
        case 'stratagem': return 'stratagem';
        case 'territory':
            if (path.startsWith('territory/county')) return 'county';
            if (path.startsWith('territory/commandery')) return 'commandery';
            return path === 'territory/supply' ? 'supply' : 'territory';
        case 'corps': return path === 'corps/siege' ? 'siege' : path === 'corps/intel' ? 'intel' : 'corps';
        case 'court':
            if (path === 'court/diplomacy') return 'diplomacy';
            if (path === 'court/realm') return 'realm';
            // 참모 제안(K8 #1273)은 보내는 입력이 아직 없다(서버 대기 K8-06) — 조정 결정 목록을 「이 화면」으로 보이지 않는다.
            return path === 'court/proposals' ? 'other' : 'court';
        default: return 'other';
    }
}
