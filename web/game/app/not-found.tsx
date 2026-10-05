import Link from 'next/link';
import { StatusView } from '@opensamguk/ui';

/**
 * 맞는 화면이 없는 주소 전부(/game 안팎) — 진짜 HTTP 404 로 셸 밖에서 그리고 작전실로만 보낸다. 셸 안 404 를 쓰면
 * AuthGate 때문에 200 이 된다(app/game/not-found.tsx 주석). 화면이 직접 notFound() 를 부를 때만 셸 안 404 다.
 */
export default function RootNotFound() {
    return (
        <main>
            <StatusView
                kind="not-found"
                scope="page"
                actions={<Link className="os-button os-button--primary os-status__action" href="/game">작전실로</Link>}
            />
        </main>
    );
}
