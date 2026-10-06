'use client';

// 다시 보기(P-H03 격자 리플레이) — K5 설계서 §5.3, 보드 V31K5ReplayWait. 계약판 K5-09(C2)는 아직 「요청」이다.
//  - 읽기 모양은 「K10 → C2 리플레이 읽기 모양 제안」(계약판, 2026-10-05)으로 합의를 받는 중이다. 합의 전에는 본문을 읽지 않고
//    서버 대기로만 그린다 — 지금 소유 · 옛 4X-C 계획 리플레이로 다시 보기를 만들지 않는다.
//  - 옛 v1 「양측 6자리」 패널(§5.3 · 보드 V31K5Replay)은 D24 · 계약판 174행으로 폐기됐다 — 본문은 장수별 부곡 목록으로 짓는다.
//  - 전투 번호는 v2 Long(10진 문자열, 1 이상)이다. 아니면 「번호가 올바르지 않습니다」.
// 옛 주소 /game/battle-replay/[id](4X-C 계획 리플레이)의 308 은 본문이 선 뒤 켠다(lib/legacyRoutes.ts ready:false).

import { Panel, SectionHeader, StatusView } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import { isLongString } from '@/lib/battle/protocol';
import styles from './replay.module.css';

export const REPLAY_WAIT_TITLE = '전투가 끝나고 결과가 반영되면 다시 볼 수 있습니다';

/** 주소의 전투 번호 — v2 Long 10진 문자열(1 이상)만 받는다. */
export function replayBattleId(raw: string | null | undefined): string | null {
    if (typeof raw !== 'string') return null;
    const id = decodeURIComponent(raw);
    return isLongString(id, true) ? id : null;
}

function Foot() {
    return (
        <div className={styles.foot}>
            <CampaignLink slug="corps/battle" className="os-button os-button--ghost">군단 › 전투로</CampaignLink>
            <CampaignLink slug="records" className="os-button os-button--ghost">기록으로</CampaignLink>
        </div>
    );
}

export default function ReplayScreen({ rawId }: { readonly rawId: string | null }) {
    const battleId = replayBattleId(rawId);
    if (battleId === null) {
        return (
            <Panel className={styles.panel}>
                <StatusView kind="empty" title="다시 볼 전투 번호가 올바르지 않습니다" body="기록의 전투 목록에서 「다시 보기」로 들어오세요."
                    actions={<CampaignLink slug="records" className="os-button os-button--primary">기록으로</CampaignLink>} />
            </Panel>
        );
    }
    return (
        <Panel className={styles.panel} aria-label="다시 보기">
            <SectionHeader title="다시 보기" sub="끝난 전투를 전장 판 위에서 다시 봅니다" />
            <div data-server-wait="K5-09">
                <StatusView kind="waiting" title={REPLAY_WAIT_TITLE}
                    body="진행 중인 전투의 전체 다시 보기는 결과가 캠페인에 반영된 뒤에 공개합니다. 지금 전투에 참가할 수 있다면 군단 › 전투에서 들어가세요. 다시 보기 자료는 서버가 아직 주지 않습니다." />
            </div>
            <Foot />
        </Panel>
    );
}
