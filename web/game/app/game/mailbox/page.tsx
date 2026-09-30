'use client';

// 서신(P-Q02) — 개인 · 세력 · 전체 · 요청. K6 설계서 §3.8, 보드 V31K6Mail · MMail.
// 셸(#1107) 전에는 `/game/mailbox` 가 휘하 세션 제공자 밖이라 장수 · 세력을 여기서 한 번 읽는다(옛 메일함과 같은 front-info).
// 머리줄 서신 단추도 이 주소를 연다. 받은 요청은 한 읽기(useRequests)를 요청 탭 배지와 같이 쓴다.
import { useEffect, useState } from 'react';
import { StatusView } from '@opensamguk/ui';
import PageHead from '@/components/PageHead';
import Shell from '@/components/Shell';
import { MailScreen } from '@/components/mail/MailScreen';
import { api } from '@/lib/api';
import type { MailMe } from '@/lib/mail/use-mail';
import { useRequests } from '@/lib/requests';

type MeLoad = { state: 'loading' } | { state: 'error' } | { state: 'none' } | { state: 'ready'; me: MailMe };

export default function MailPage() {
    const [meLoad, setMeLoad] = useState<MeLoad>({ state: 'loading' });
    const [seq, setSeq] = useState(0);
    const [refreshKey, setRefreshKey] = useState(0);

    useEffect(() => {
        let alive = true;
        setMeLoad({ state: 'loading' });
        api.frontInfo()
            .then((info) => {
                if (!alive) return;
                const g = info.general;
                setMeLoad(g.hasGeneral && g.generalId != null ? { state: 'ready', me: { generalId: g.generalId, nationId: g.nationId } } : { state: 'none' });
            })
            .catch(() => { if (alive) setMeLoad({ state: 'error' }); });
        return () => { alive = false; };
    }, [seq]);

    const requests = useRequests(meLoad.state === 'ready' ? meLoad.me.generalId : null, refreshKey);

    return (
        <Shell>
            <PageHead
                title="서신"
                actions={meLoad.state === 'ready' ? (
                    <button type="button" className="os-button os-button--ghost" onClick={() => { setRefreshKey((k) => k + 1); requests.reload(); }}>새로고침</button>
                ) : null}
            />
            {meLoad.state === 'loading' ? <StatusView kind="loading" rows={4} /> : null}
            {meLoad.state === 'error' ? <StatusView kind="error" title="장수 정보를 불러오지 못했습니다" onRetry={() => setSeq((n) => n + 1)} /> : null}
            {meLoad.state === 'none' ? <StatusView kind="empty" title="이 서버에 내 장수가 없습니다" body="장수를 만들면 서신을 주고받을 수 있습니다." /> : null}
            {meLoad.state === 'ready' ? <MailScreen me={meLoad.me} requests={requests} refreshKey={refreshKey} /> : null}
        </Shell>
    );
}
