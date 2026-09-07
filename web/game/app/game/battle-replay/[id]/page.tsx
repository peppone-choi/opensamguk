'use client';
// Phase 4X-C 10 「리플레이」 — /game/battle-replay/{id}.
import { useParams } from 'next/navigation';
import Shell from '../../../../components/Shell';
import PageHead from '../../../../components/PageHead';
import BattleReplayPlayer from '../../../../components/game/BattleReplayPlayer';

export default function BattleReplayPage() {
    const params = useParams<{ id: string }>();
    const id = Number(params?.id);
    return (
        <Shell>
            <div className="page-content">
                <PageHead title="리플레이" />
                {Number.isFinite(id) && id > 0 ? (
                    <BattleReplayPlayer id={id} battleCenterHref="../battle-center" operationHref="../my-nation#operations" />
                ) : (
                    <p className="text-muted">리플레이 번호가 없습니다.</p>
                )}
            </div>
        </Shell>
    );
}
