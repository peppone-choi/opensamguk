'use client';

import { Chip, Panel, SectionHeader } from '@opensamguk/ui';
import GameShell from '@/components/GameShell';
import { Empty, campaignReadNotice } from '@/components/campaign/GameStates';
import { api } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';

/** 카드 종류별 한 줄 설명 — 계책 카드 카탈로그 초안의 사용 방식만 옮긴다(수치 없음). */
const CARD_HINT: Record<string, { kind: string; text: string; cost: string }> = {
    FORTIFY: { kind: '대응', text: '공격받으면 방비가 오르고, 대신 쌀을 더 쓴다.', cost: '쌀' },
    INSIGHT: { kind: '대응', text: '상대 계책 한 장을 무효로 한다.', cost: '금' },
};

/**
 * 계책 덱 — 시안 Hand(시안 제목 「계책 손패」, 2026-09-23 사용자 결정으로 「계책 덱」).
 *
 * 손패는 소유 장수만 본다(`GET /api/commands/stratagem-hand`). 공급은 개인 턴의 드로우 단계에서
 * 일어나고, 조회는 카드를 만들지 않는다. 카드 쓰기(예약·공개·비용)는 아직 서버에 없어 비활성이다.
 */
export default function HandPage() {
    const read = useCampaignRead((id, signal) => api.stratagemHand(id, signal));
    const hand = read.data;
    const notice = campaignReadNotice(read, hand?.status);

    return (
        <GameShell title="계책 덱" tab="계책">
            <div style={{ padding: 12, display: 'grid', gap: 12 }}>
                <Panel style={{ padding: 12 }}>
                    <SectionHeader
                        title="손패"
                        sub="자기 턴마다 한 장 뽑는다"
                        actions={hand?.status === 'READY' ? <Chip tone="bronze">{`${hand.cards.length} / ${hand.handLimit}`}</Chip> : null}
                    />
                    {notice ? <Empty>{notice}</Empty> : null}
                    {!notice && hand?.status === 'NOT_READY' ? (
                        <Empty>아직 첫 손패를 받지 않았습니다. 다음 개인 턴에 처음 뽑습니다.</Empty>
                    ) : null}
                    {hand?.status === 'READY' && hand.cards.length === 0 ? <Empty>손패가 비었습니다.</Empty> : null}
                    {hand?.status === 'READY' && hand.cards.length > 0 ? (
                        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 12, paddingTop: 8 }}>
                            {hand.cards.map((card) => {
                                const hint = CARD_HINT[card.type];
                                return (
                                    <div
                                        key={card.instanceId}
                                        style={{
                                            border: '1px solid var(--bronze)',
                                            borderRadius: 4,
                                            padding: 12,
                                            display: 'grid',
                                            gap: 8,
                                            background: 'var(--panel)',
                                        }}
                                    >
                                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 6 }}>
                                            <span style={{ fontFamily: 'var(--font-serif)', fontSize: 18, fontWeight: 900 }}>{card.label}</span>
                                            {hint ? <Chip tone="info">{hint.kind}</Chip> : null}
                                        </div>
                                        {hint ? <p style={{ margin: 0, fontSize: 13, color: 'var(--text-2)' }}>{hint.text}</p> : null}
                                        {hint ? (
                                            <span style={{ fontSize: 12, color: 'var(--muted)' }}>
                                                비용 <Chip tone="bronze">{hint.cost}</Chip>
                                            </span>
                                        ) : null}
                                        <button
                                            type="button"
                                            className="os-button os-button--ghost os-button--sm"
                                            disabled={!hand.canUse}
                                            title={hand.canUse ? undefined : '카드 쓰기 입력이 아직 없습니다'}
                                        >
                                            쓰기
                                        </button>
                                    </div>
                                );
                            })}
                        </div>
                    ) : null}
                </Panel>
            </div>
        </GameShell>
    );
}
