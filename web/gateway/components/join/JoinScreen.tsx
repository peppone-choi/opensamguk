import MapPreview from '@/components/MapPreview';
import PolicyLinks from '@/components/gateway/PolicyLinks';
import PublicHeader from '@/components/gateway/PublicHeader';
import JoinForm from './JoinForm';

/** 지도 위에 떠 있는 판 — 새 지도 이름표가 이 밑에 숨지 않게 피한다(K10 실지도 10-03: 동이 · 서량). */
const MAP_AVOID = '.gw31-join__intro, .gw31-join__card, .gw31-join__foot, .gw31-head__left > *, .gw31-head__right > *';

/**
 * P-G03 가입(설계서 §2.3, 보드 V31K5Join · MJoin). 로그인과 같은 지도 배경(일관성), 서버 현황 패널은 두지 않는다(가입에 집중).
 * 왼쪽 소개 묶음의 계정 안내 · 경고 문장은 로비 각주(LB37 · LB38)와 같은 글이다 — 승인됨 2026-10-01(D18).
 */
export default function JoinScreen({ mapServerId }: { readonly mapServerId: string | null }) {
    return (
        <div className="gw31-join">
            <div className="gw31-join__map">
                {mapServerId
                    ? <MapPreview variant="backdrop" avoidSelector={MAP_AVOID} serverId={mapServerId} />
                    : <div className="gw31-login__terrain" aria-hidden="true" />}
            </div>
            <PublicHeader action="login" overlay />
            <main className="gw31-join__stage">
                <section className="gw31-intro gw31-join__intro" aria-label="계정 안내">
                    <picture>
                        <source type="image/webp" srcSet="/logo-wordmark.webp" />
                        <img className="gw31-intro__wordmark" src="/logo-wordmark.png" alt="오픈삼국" width={360} height={134} decoding="async" fetchPriority="high" />
                    </picture>
                    <p className="gw31-intro__lead" data-copy-status="approved">계정은 한 번 만들면 계속 씁니다. 서버가 새로 시작하면 장수만 다시 만듭니다.</p>
                    <p className="gw31-join__warn" data-copy-status="approved">한 사람이 계정 여러 개를 쓰거나 남의 턴을 대신 넣으면 이용이 막힐 수 있습니다.</p>
                    <p className="gw31-join__note">가입하면 바로 로그인되어 로비로 갑니다. 장수는 서버마다 따로 만듭니다.</p>
                </section>
                <section className="os-panel os-panel--static gw31-join__card" aria-labelledby="join-title">
                    <h1 id="join-title" className="gw31-login__title os-serif">회원 가입</h1>
                    <JoinForm />
                </section>
                <footer className="gw31-join__foot">
                    <PolicyLinks />
                </footer>
            </main>
        </div>
    );
}
