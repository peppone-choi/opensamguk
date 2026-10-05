// 「서버 대기 → 실제 값」 시험 도우미 — 행 목록 · 대기 단언 · 값이 온 뒤 단언, 그리고 어댑터 고정 자료로 쓰는 예시.
import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { expectServerWait, expectServerWaitGone, serverWaitRows } from '../serverWaitTesting';

/** 예시 부품 — 레인이 고정 자료 어댑터로 만드는 칸과 같은 모양. 값이 없으면 계약판 행을 단 대기 칸, 값이 오면 값. */
function CrownCard({ crown }: { readonly crown?: { readonly name: string } }) {
    return (
        <section aria-label="황실">
            <h2>황실</h2>
            {crown ? <p>황제 {crown.name}</p> : <div data-server-wait="K8-10"><span className="os-status--waiting">준비 중</span></div>}
            <div data-server-wait="K8-15"><span className="os-status--waiting">칭제 준비 중</span></div>
        </section>
    );
}

describe('serverWaitRows', () => {
    it('문서 순서 · 중복 포함 · 빈 값은 빈 문자열, 영역 자신의 표지도 센다', () => {
        const { container } = render(
            <div data-server-wait="A-1">
                <span data-server-wait="B-2" />
                <span data-server-wait="" />
                <span data-server-wait="B-2" />
            </div>,
        );
        expect(serverWaitRows(container)).toEqual(['A-1', 'B-2', '', 'B-2']);
        expect(serverWaitRows(container.firstElementChild!)).toEqual(['A-1', 'B-2', '', 'B-2']);
    });

    it('바깥 이름을 쓰지 않아 Playwright evaluate 로 넘길 수 있다(함수 원문만으로 돈다)', () => {
        const { container } = render(<CrownCard />);
        const standalone = new Function('root', `return (${serverWaitRows.toString()})(root);`) as (root: ParentNode) => string[];
        expect(standalone(container)).toEqual(['K8-10', 'K8-15']);
    });
});

describe('expectServerWait', () => {
    it('기다리는 행이 정확히 맞으면 통과(순서 무관)', () => {
        const { container } = render(<CrownCard />);
        expect(() => expectServerWait(container, ['K8-15', 'K8-10'])).not.toThrow();
    });

    it('빠진 행 · 남는 행 · 빈 행 이름을 적어 던진다', () => {
        const { container } = render(<CrownCard />);
        expect(() => expectServerWait(container, ['K8-10'])).toThrow(/기대하지 않은 서버 대기 표지: K8-15/);
        expect(() => expectServerWait(container, ['K8-10', 'K8-15', 'K8-99'])).toThrow(/서버 대기 표지가 없다: K8-99/);
        const blank = render(<div data-server-wait=" " />);
        expect(() => expectServerWait(blank.container, [])).toThrow(/행 이름이 빈/);
    });
});

describe('expectServerWaitGone', () => {
    it('예시 — 고정 자료로 값이 오면 그 행 표지는 사라지고 값이 대기 칸 밖에 보인다(다른 행은 남아도 된다)', () => {
        const { container, rerender } = render(<CrownCard />);
        expectServerWait(container, ['K8-10', 'K8-15']);
        rerender(<CrownCard crown={{ name: '헌제' }} />);
        expectServerWaitGone(container, ['K8-10'], { value: '헌제' });
        expectServerWait(container, ['K8-15']);
    });

    it('값이 왔는데 표지가 남으면 던진다', () => {
        const Stuck = () => <div data-server-wait="K8-10"><p>황제 헌제</p></div>;
        const { container } = render(<Stuck />);
        expect(() => expectServerWaitGone(container, ['K8-10'])).toThrow(/표지가 남았다: K8-10/);
    });

    it('값이 대기 칸 안에만 있거나 아예 없으면 던진다', () => {
        const inside = render(<div><div data-server-wait="K8-15">헌제</div></div>);
        expect(() => expectServerWaitGone(inside.container, ['K8-10'], { value: '헌제' })).toThrow(/서버 대기 칸 안에만 있다/);
        const none = render(<div><p>황실</p></div>);
        expect(() => expectServerWaitGone(none.container, ['K8-10'], { value: /헌제|영제/ })).toThrow(/보이지 않는다/);
    });

    it('영역 전체가 대기 칸이면 그 안의 글자는 값으로 치지 않는다', () => {
        const { container } = render(<section data-server-wait="K8-09">헌제</section>);
        expect(() => expectServerWaitGone(container.firstElementChild!, ['K8-10'], { value: '헌제' })).toThrow(/안에만 있다/);
    });
});
