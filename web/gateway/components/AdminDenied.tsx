import Link from 'next/link';
import StateLine from '@/components/status/StateLine';

/** 운영 콘솔 「권한 없음」(설계서 §2.0 G3) — 말없이 로비로 보내지 않고 권한 없음 상태 + 로비 고리. 화면 래퍼와 서버 래퍼가 함께 쓴다. */
export default function AdminDenied() {
    return (
        <div className="center-screen gw31-denied">
            <StateLine kind="empty" title="운영자만 볼 수 있습니다" body="운영 콘솔은 운영자 계정으로 들어와야 합니다." />
            <Link className="os-button os-button--ghost" href="/lobby">로비로</Link>
        </div>
    );
}
