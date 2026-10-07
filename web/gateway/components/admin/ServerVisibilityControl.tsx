'use client';
import { useServerVisibility } from '@/lib/hooks/useServerVisibility';

export default function ServerVisibilityControl({ serverId, name }: { readonly serverId: string; readonly name: string }) {
    const { current, busy, message, load, toggle } = useServerVisibility(serverId);
    return <section aria-label={`${name} 공개 설정`}>
        <p>공개 상태: {current ? current.state === 'VERIFYING' ? '확인 중' : current.publiclyVisible ? '공개' : '비공개' : '확인 필요'}</p>
        {current ? <button type="button" className="btn-secondary" disabled={busy || current.state === 'VERIFYING'} onClick={toggle}>
            {busy ? '변경 중…' : current.publiclyVisible ? '비공개로 전환' : '공개로 전환'}
        </button> : <button type="button" className="btn-secondary" disabled={busy} onClick={load}>공개 상태 다시 조회</button>}
        <p>비공개 서버는 서버 목록과 로그인·메인에서 숨겨지고 직접 접근도 차단됩니다.</p>
        {message && <p role="alert">{message}</p>}
    </section>;
}
