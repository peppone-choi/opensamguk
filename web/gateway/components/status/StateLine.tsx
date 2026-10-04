// 패널 안 상태(로딩 · 빈 · 오류). 오류는 「다시 시도」 단추를 함께 둔다 — 0건과 실패를 가른다(설계서 NS6 · SL5 · NB6).
// 게이트웨이 승인 보드가 상태를 공용 state_* 꼴로 그렸다 — 그래서 공용 StatusView(@opensamguk/ui)로 그린다(CEO 10-05).
// 부르는 곳(약 60곳)은 그대로 두고 이 파일 안만 바꿨다. 불러오는 중의 제목은 화면 읽기용으로 남긴다.
import { StatusView } from '@opensamguk/ui';

export type StateLineProps =
    | { readonly kind: 'loading'; readonly title: string }
    | { readonly kind: 'empty'; readonly title: string; readonly body?: string }
    | { readonly kind: 'error'; readonly title: string; readonly body?: string; readonly onRetry: () => void };

export default function StateLine(props: StateLineProps) {
    if (props.kind === 'loading') {
        return (
            <div className="gw31-state gw31-state--loading" role="status">
                <span className="sr-only">{props.title}</span>
                <StatusView kind="loading" rows={2} />
            </div>
        );
    }
    if (props.kind === 'empty') return <StatusView kind="empty" title={props.title} body={props.body ?? ''} />;
    return <StatusView kind="error" title={props.title} body={props.body} onRetry={props.onRetry} />;
}
