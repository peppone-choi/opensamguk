'use client';

// 받은 요청 목록 — useRequests 한 번 + 요청 카드. 서신 「요청」 탭(K6) · 조정 「받은 요청」 띠(K4) · 지난 순 서랍(K4, compact)이 쓴다.
// 자기 데이터로 카드만 쓰려면 RequestCard를 바로 쓰면 된다.
import { StatusView } from '@opensamguk/ui';
import { availabilityOf } from '@/lib/input-availability';
import { findRequest, useRequests, type IncomingRequest, type UseRequests } from '@/lib/requests';
import { RequestCard } from './RequestCard';

export interface IncomingRequestsProps {
    readonly generalId: number | null;
    readonly refreshKey?: number;
    readonly compact?: boolean;
    /** 응답 대기만 보인다(띠 · 서랍). 기본은 응답한 것도 「수락함 · 거절함」으로 남긴다. */
    readonly waitingOnly?: boolean;
    /** 카드 한 장만(지난 순 서랍의 기록 줄 밑 펼침) — requestKey(...)로 만든 키. 응답한 뒤에도 「수락함」으로 남는다. */
    readonly onlyKey?: string;
    /** 이미 가진 useRequests를 넘기면 다시 읽지 않는다(머리줄 배지와 같은 읽기). */
    readonly source?: UseRequests;
    readonly className?: string;
}

export function IncomingRequests(props: IncomingRequestsProps) {
    const own = useRequests(props.source ? null : props.generalId, props.refreshKey);
    const src = props.source ?? own;
    const { load } = src;

    if (load.state === 'loading') return <StatusView kind="loading" rows={2} />;
    if (load.state === 'error') {
        return <StatusView kind="error" title="받은 요청을 불러오지 못했습니다" body="빈 목록이 아닙니다 — 불러오기가 실패했습니다." onRetry={src.reload} />;
    }
    if (props.onlyKey) {
        const one = findRequest(load.requests, props.onlyKey);
        return one
            ? <RequestRow request={one} src={src} compact={props.compact} />
            : <StatusView kind="empty" title="이 요청은 목록에 없습니다" body="이미 끝났거나 취소된 요청입니다." />;
    }
    const rows = props.waitingOnly ? load.requests.filter((r) => r.state === 'waiting') : load.requests;
    return (
        <div className={props.className} style={{ display: 'grid', gap: 8 }} data-testid="incoming-requests">
            {load.partial ? <p role="status" style={{ margin: 0, fontSize: 12, color: 'var(--rust-2)' }}>받은 요청 일부를 불러오지 못했습니다.</p> : null}
            {rows.length === 0
                ? <StatusView kind="empty" title="응답할 요청이 없습니다" body="발령이나 동의 요청이 오면 여기에 보입니다." />
                : rows.map((r) => <RequestRow key={r.key} request={r} src={src} compact={props.compact} />)}
        </div>
    );
}

function RequestRow({ request: r, src, compact }: { request: IncomingRequest; src: UseRequests; compact?: boolean }) {
    const rejection = src.rejected[r.key];
    const availability = rejection ? availabilityOf(r.inputId, { rejected: rejection }) : r.availability;
    return (
        <RequestCard
            kind={r.label}
            from={{ name: r.from.name }}
            what={r.what}
            due={r.due}
            consequence={r.consequence}
            state={r.state}
            compact={compact}
            answer={{
                inputId: r.inputId,
                availability,
                busy: src.busyKey === r.key,
                onAccept: () => void src.respond(r, true),
                onRefuse: () => void src.respond(r, false),
                rejectedOn: rejection ? { side: rejection.side, seq: rejection.seq } : null,
            }}
        />
    );
}

export default IncomingRequests;
