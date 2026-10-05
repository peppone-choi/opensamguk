import Link from 'next/link';
import { StatusView } from '@opensamguk/ui';

/** 게이트웨이의 없는 주소(보드 P-X01 없는 화면) — 로비로 보낸다. 옛 주소는 308 이 먼저 받는다. */
export default function GatewayNotFound() {
    return (
        <main>
            <StatusView
                kind="not-found"
                scope="page"
                actions={<Link className="os-button os-button--primary os-status__action" href="/lobby">로비로</Link>}
            />
        </main>
    );
}
