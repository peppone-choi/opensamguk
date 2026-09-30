import Link from 'next/link';

/** 바닥 정책 링크(설계서 LG18 · LG19 · J14). 문서 본문은 공개 알파 정책 문구(U5)와 함께 승인된다 — 페이지는 P-G10 · P-G11. */
export default function PolicyLinks({ className = '' }: { readonly className?: string }) {
    return (
        <nav className={`gw31-policy ${className}`.trim()} aria-label="정책">
            <Link href="/privacy">개인정보처리방침</Link>
            <Link href="/terms">이용약관</Link>
        </nav>
    );
}
