// 패널 안 한 줄 상태(로딩 · 빈 · 오류). 오류는 「다시 시도」 단추를 함께 둔다 — 0건과 실패를 가른다(설계서 NS6 · SL5 · NB6).
// K3 공용 부품 StatusView(web/shared/src/parts, front-k3-parts WIP)의 props 부분집합과 같은 모양이다 —
// K3 PR 이 병합되면 이 파일만 StatusView 로 바꾼다(K0 2026-09-30: 같은 부품을 따로 만들지 않는다).
export type StateLineProps =
    | { readonly kind: 'loading'; readonly title: string }
    | { readonly kind: 'empty'; readonly title: string; readonly body?: string }
    | { readonly kind: 'error'; readonly title: string; readonly body?: string; readonly onRetry: () => void };

export default function StateLine(props: StateLineProps) {
    return (
        <div className={`gw31-state gw31-state--${props.kind}`} role={props.kind === 'error' ? 'alert' : 'status'}>
            {props.kind === 'loading' && <span className="spinner gw31-state__spin" aria-hidden="true" />}
            <span>{props.title}</span>
            {props.kind !== 'loading' && props.body && <span className="gw31-state__body">{props.body}</span>}
            {props.kind === 'error' && (
                <button type="button" className="os-button os-button--ghost gw31-state__retry" onClick={props.onRetry}>다시 시도</button>
            )}
        </div>
    );
}
