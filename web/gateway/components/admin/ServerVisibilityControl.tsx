'use client';
import { Button } from '@opensamguk/ui';
import { useServerVisibility } from '@/lib/hooks/useServerVisibility';

export default function ServerVisibilityControl({ serverId, name }: { readonly serverId: string; readonly name: string }) {
    const { current, busy, message, load, toggle } = useServerVisibility(serverId);
    const blocked = busy ? '처리 중입니다.' : current?.state === 'VERIFYING' ? '서버 검증이 끝난 뒤 공개 상태를 변경할 수 있습니다.' : null;
    const label = current ? busy ? '변경 중…' : current.publiclyVisible ? '비공개로 전환' : '공개로 전환' : '공개 상태 다시 조회';
    return <section aria-label={`${name} 공개 설정`}>
        <p>공개 상태: {current ? current.state === 'VERIFYING' ? '확인 중' : current.publiclyVisible ? '공개' : '비공개' : '확인 필요'}</p>
        {blocked ? <Button disabled reason={blocked}>{label}</Button> : <Button onClick={current ? toggle : load}>{label}</Button>}
        <p>비공개 서버는 서버 목록과 로그인·메인에서 숨겨지고 직접 접근도 차단됩니다.</p>
        {message && <p role="alert">{message}</p>}
    </section>;
}
