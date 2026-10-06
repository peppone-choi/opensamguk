import PolicyLinks from './PolicyLinks';
import PublicHeader from './PublicHeader';

/**
 * P-G10 개인정보처리방침 · P-G11 이용약관(설계서 §2.6). 본문은 공개 알파 정책 문구(U5)와 함께 사용자 승인을 받는다 —
 * 승인 전에는 문서를 지어내지 않고 「준비 중」과 다룰 절만 보인다. 승인되면 sections 에 본문을 채운다.
 */
export default function PolicyPage({ title, sections }: { readonly title: string; readonly sections: readonly string[] }) {
    return (
        <div className="gw31-page">
            <PublicHeader action="join" logo />
            <main className="gw31-doc">
                <h1 className="gw31-doc__title os-serif">{title}</h1>
                <div className="gw31-doc__waiting" role="status" data-copy-status="pending">
                    <span className="os-chip os-chip--info">준비 중</span>
                    <p>이 문서는 공개 알파 정책 문구가 승인되면 이 자리에 게시합니다.</p>
                </div>
                <section aria-labelledby="doc-toc">
                    <h2 id="doc-toc" className="gw31-doc__h2">다룰 내용</h2>
                    <ol className="gw31-doc__toc">
                        {sections.map((section) => <li key={section}>{section}</li>)}
                    </ol>
                </section>
            </main>
            <footer className="gw31-page__foot"><PolicyLinks /></footer>
        </div>
    );
}
