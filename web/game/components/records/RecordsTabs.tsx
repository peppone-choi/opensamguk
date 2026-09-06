'use client';
// 기록 부서(12 아트보드) 화면 사이를 오가는 탭 — 부서 메뉴 「기록」 그룹의 잎(연감·세력일람·장수일람·명장일람·
// 명예의전당·왕조일람·접속량정보·빙의일람)에 전황(/game/world-log) 을 더한 것. 라벨은 메뉴 원천 그대로.
import { usePathname } from 'next/navigation';
import { resolveDeptHref } from '../DeptNav';
import { DEPT_GROUPS } from '../../lib/dept-menu-config';
import { normalizeGamePathname, normalizeLegacyGamePath, useServerId } from '../../lib/serverGameUrl';

export interface RecordsTab {
    readonly label: string;
    readonly href: string;
}

const WORLD_LOG: RecordsTab = { label: '전황', href: '/game/world-log' };

/** 기록 탭 목록(정적) — 부서 메뉴 원천에서 뽑고 전황을 연감 다음에 둔다. */
export function recordsTabs(): RecordsTab[] {
    const group = DEPT_GROUPS.find((g) => g.key === 'records');
    const tabs: RecordsTab[] = [];
    for (const entry of group?.entries ?? []) {
        if (entry.kind === 'menu') tabs.push({ label: entry.item.name, href: normalizeLegacyGamePath(entry.item.url) });
        else if (entry.kind === 'route') tabs.push({ label: entry.label, href: entry.href });
        if (tabs.length === 1) tabs.push(WORLD_LOG);
    }
    return tabs;
}

export default function RecordsTabs() {
    const pathname = usePathname();
    const serverId = useServerId();
    const current = normalizeGamePathname(pathname ?? '', serverId).split('?')[0];
    return (
        <nav className="os-pill-tabs records-tabs" aria-label="기록">
            {recordsTabs().map((tab) => {
                const active = tab.href === current;
                return (
                    <a key={tab.href} href={resolveDeptHref(tab.href, serverId)} aria-current={active ? 'page' : undefined}>
                        {tab.label}
                    </a>
                );
            })}
        </nav>
    );
}
