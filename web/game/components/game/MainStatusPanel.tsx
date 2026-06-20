'use client';

import type { FrontInfoResponse } from '@/lib/types';

export default function MainStatusPanel({ frontInfo }: { frontInfo: FrontInfoResponse }) {
    const onlineNations = frontInfo.global.onlineNations ?? '';
    const onlineGen = frontInfo.nation?.onlineGen;
    const notice = frontInfo.nation?.notice ?? '';

    return (
        <section className="main-status" aria-label="메인 상태">
            <div className="main-status-row main-status-online-nations">접속중인 국가: {onlineNations}</div>
            <div className="main-status-row main-status-online-users">
                【 접속자 】 {onlineGen ?? ''}
            </div>
            <div className="main-status-row main-status-notice">
                <div className="main-status-notice-head">【 국가방침 】</div>
                <div className="main-status-notice-body">{notice}</div>
            </div>
        </section>
    );
}
