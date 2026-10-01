import Link from 'next/link';
import { Brand, Chip } from '@opensamguk/ui';

/**
 * 게이트웨이 셸 머리줄 — 로그인 전(설계서 §1.1 · §2.0 T1–T7). 데스크톱 48 · 모바일 56.
 * 오른쪽 단추 하나(「회원가입」 또는 「로그인」). 로그인 · 가입은 소개 판에 큰 워드마크가 있어 머리줄 로고를 끈다
 * (시스템 3.1.4 「로고 한 번」, `logo` 기본 false). `overlay` = 지도 위에 투명하게 뜬다.
 */
export default function PublicHeader({ action, logo = false, overlay = false }: {
    readonly action: 'join' | 'login';
    readonly logo?: boolean;
    readonly overlay?: boolean;
}) {
    return (
        <header className={`gw31-head${overlay ? ' gw31-head--overlay' : ''}`} aria-label="상단바">
            <div className="gw31-head__left">
                {logo && (
                    <Link href="/login" className="gw31-head__brand" aria-label="오픈삼국 — 처음으로">
                        <Brand size="large" />
                    </Link>
                )}
                <Chip tone="bronze">공개 알파</Chip>
            </div>
            <div className="gw31-head__right">
                {action === 'join'
                    ? <Link href="/join" className="os-button os-button--ghost">회원가입</Link>
                    : <Link href="/login" className="os-button os-button--ghost">로그인</Link>}
            </div>
        </header>
    );
}
