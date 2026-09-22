'use client';

import Link from 'next/link';
import { Chip, Panel, SectionHeader } from '@opensamguk/ui';
import HwihaShell from '../../../components/HwihaShell';
import { HWIHA_INPUT_TABS, HWIHA_SCREENS, hwihaHref, hwihaScreensOfTab } from '../../../lib/hwiha-screens';
import { MOCK_IDENTITY } from '../../../lib/hwiha-mock';

/**
 * 작전실 — 허브. 시안 WarRoom 의 자리다.
 *
 * 지금은 새 시대 화면으로 들어가는 문 역할을 한다. 시안의 작전실 본문(예정 턴·지도·피드)은
 * 아직 옮기지 않았고, 그 자리를 비워 두는 대신 어디로 갈 수 있는지를 정직하게 보여 준다.
 */
export default function WarRoomPage() {
    const unassigned = HWIHA_SCREENS.filter((s) => s.tab === null && s.slug !== 'war-room');
    return (
        <HwihaShell title="작전실" tab={null} identity={MOCK_IDENTITY} showBack={false}>
            <div style={{ padding: 12, display: 'grid', gap: 12 }}>
                <Panel>
                    <SectionHeader title="입력 여섯 가지" sub="정본 설계 §4" />
                    <div style={{ display: 'grid', gap: 10, padding: 12 }}>
                        {HWIHA_INPUT_TABS.map((tab) => {
                            const screens = hwihaScreensOfTab(tab);
                            return (
                                <div key={tab} style={{ display: 'flex', alignItems: 'center', gap: 10, minHeight: 44 }}>
                                    <span style={{ width: 88, fontWeight: 700 }}>{tab}</span>
                                    {screens.length === 0 ? (
                                        <Chip tone="info">아직 화면이 없습니다</Chip>
                                    ) : (
                                        <span style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                                            {screens.map((s) => (
                                                <Link key={s.slug} className="os-button os-button--ghost os-button--sm" href={hwihaHref(s.slug)}>
                                                    {s.title}
                                                </Link>
                                            ))}
                                        </span>
                                    )}
                                </div>
                            );
                        })}
                    </div>
                </Panel>

                <Panel>
                    <SectionHeader title="맥락에서 들어가는 화면" sub="시안이 탭을 지정하지 않았다" />
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', padding: 12 }}>
                        {unassigned.map((s) => (
                            <Link key={s.slug} className="os-button os-button--ghost os-button--sm" href={hwihaHref(s.slug)}>
                                {s.title}
                            </Link>
                        ))}
                    </div>
                </Panel>
            </div>
        </HwihaShell>
    );
}
