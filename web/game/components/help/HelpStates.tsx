'use client';

// 도움말 오류 · 대기 상태 — 오류 종류를 버리지 않고 가른다(설계서 §3.7).
import type { HelpErrorKind } from '@/lib/help';
import { StateBlock } from './HelpBits';
import s from './Help.module.css';

export function LoadFailure({ kind, retry }: { kind: HelpErrorKind; retry?: () => void }) {
    const again = retry ? <button type="button" className={[s.btn, s.primary].join(' ')} onClick={retry}>다시 시도</button> : null;
    switch (kind) {
        case 'NOT_FOUND':
            return <StateBlock title="이 도움말을 찾을 수 없습니다" body="주소가 바뀌었거나 없어진 도움말입니다." />;
        case 'WORLD_UNAVAILABLE':
            return <StateBlock title="서버가 준비 중이라 도움말도 잠시 쉽니다" body="점검이 끝나면 이 자리에서 바로 다시 볼 수 있습니다." action={again} />;
        case 'PROFILE_UNAVAILABLE':
            return <StateBlock title="이 서버는 도움말을 제공하지 않습니다" />;
        case 'AUTH':
            return <StateBlock title="로그인하면 볼 수 있습니다" />;
        case 'BAD_QUERY':
            return <StateBlock title="찾는 말을 다시 적어 주세요" body="두 글자 이상, 여든 글자 이하로 적어 주세요." />;
        default:
            return <StateBlock title="도움말을 불러오지 못했습니다" body="잠시 뒤 다시 해 보세요." action={again} />;
    }
}
