'use client';

import Link from 'next/link';
import { Chip, Panel } from '@opensamguk/ui';
import { api } from '@/lib/api';
import { useHwihaRead } from '@/lib/hwiha-reads';
import { hwihaHref } from '@/lib/hwiha-screens';
import { useHwihaSession } from '@/lib/hwiha-session';

/**
 * 「걸려 있는 것」 — 개인 턴을 쓰지 않고 스스로 굴러가는 것들. 지금 조회 API 가 있는 셋만 싣는다:
 * 출병 명령(배치), 발령(조정 결정), 계책 손패(계책). 방침·공사는 입력이 아직 없어 싣지 않는다.
 */
export default function StandingBar() {
    const { serverId, isHwihaWorld, generalId } = useHwihaSession();
    const deploy = useHwihaRead((id) => api.deployOptions(id));
    const dispatches = useHwihaRead((id) => api.dispatchPending(id));
    const hand = useHwihaRead((id, signal) => api.stratagemHand(id, signal));

    if (!isHwihaWorld) return null;
    const open = (dispatches.data?.dispatches ?? []).filter((d) => d.status === 'PENDING');
    // 나에게 온 발령만 내가 응답한다. 내가 낸 발령은 상대의 응답을 기다린다.
    const toMe = open.filter((d) => d.targetId === generalId).length;
    const pending = open.length;
    const order = deploy.data?.order ?? null;
    const cards = hand.data?.status === 'READY' ? hand.data.cards.length : null;

    const items: { key: string; label: string; slug: string }[] = [
        { key: 'deploy', label: order ? `출병 ${order.stop ? `· ${order.stop === 'ENCOUNTER' ? '조우로 멈춤' : order.stop}` : '행군 중'}` : '출병 없음', slug: 'posts' },
        { key: 'dispatch', label: pending ? `발령 진행 ${pending}` : '발령 없음', slug: 'orders' },
        { key: 'hand', label: cards == null ? '계책 덱 —' : `계책 덱 · 손패 ${cards}장`, slug: 'hand' },
    ];

    return (
        <Panel style={{ padding: '8px 12px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                <span style={{ fontSize: 12, color: 'var(--muted)', whiteSpace: 'nowrap' }}>걸려 있는 것</span>
                {items.map((item) => (
                    <Link key={item.key} className="os-button os-button--ghost os-button--sm" href={hwihaHref(item.slug, serverId)}>
                        {item.label}
                    </Link>
                ))}
                {toMe ? <Chip tone="rust">{`내 응답 필요 ${toMe}`}</Chip> : null}
            </div>
        </Panel>
    );
}
