import { StatusView } from '@opensamguk/ui';

/** /game 밖의 없는 주소(셸 밖) — 작전실로만 보낸다. 게임 화면 안의 없는 주소는 app/game/not-found.tsx(셸 안)가 받는다. */
export default function RootNotFound() {
    return (
        <main>
            <StatusView
                kind="not-found"
                scope="page"
                actions={<a className="os-button os-button--primary os-status__action" href="/game">작전실로</a>}
            />
        </main>
    );
}
