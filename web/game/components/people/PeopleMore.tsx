'use client';

import { PEOPLE_PAGE_LIMIT } from '@/lib/directory-reads';
import styles from './people.module.css';

export interface PeopleMoreProps {
    readonly hasMore: boolean;
    readonly loading: boolean;
    /** 다음 쪽 실패 — 받은 목록은 그대로 두고 여기만 알린다. */
    readonly moreError: string | null;
    readonly onMore: () => void;
}

/**
 * 표 아래 줄(보드 「50명 더 보기」 — 무한 스크롤 대신 누르기, 키보드 · 스크린리더) + 자리 열 안내.
 * 다음 쪽 실패는 한 줄로 알리고(서버 원문 「403: Forbidden」 같은 글자는 보이지 않는다) 같은 단추로 다시 받는다.
 */
export function PeopleMore({ hasMore, loading, moreError, onMore }: PeopleMoreProps) {
    return (
        <div className={styles.more}>
            {hasMore ? (
                <button type="button" className="os-button" aria-busy={loading || undefined} onClick={() => { if (!loading) onMore(); }}>
                    {moreError ? '다시 받기' : `${PEOPLE_PAGE_LIMIT}명 더 보기`}
                </button>
            ) : null}
            {moreError ? <span className={styles.warn} role="alert" data-error={moreError}>다음 인물을 불러오지 못했습니다. 받은 목록은 그대로입니다.</span> : null}
            <span className={styles.muted}>자리 열은 서버가 아직 주지 않습니다. 채워지는 대로 보입니다.</span>
        </div>
    );
}
