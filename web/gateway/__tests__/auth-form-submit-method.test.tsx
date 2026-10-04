import { render, waitFor } from '@testing-library/react';
import { renderToString } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

// 로그인 · 가입 폼 제출 방식: 스크립트가 붙기 전 HTML(서버 렌더)도 method="post" 라 네이티브 제출이 값을 주소에 싣지 않는다.
// 붙은 뒤에도 method 는 그대로이고 제출은 화면 코드(onSubmit)가 맡는다.
vi.mock('next/navigation', () => ({
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
    useSearchParams: () => ({ get: () => null }),
}));
vi.mock('@/lib/client', () => ({ login: vi.fn(), register: vi.fn() }));

import LoginForm from '@/components/login/LoginForm';
import JoinForm from '@/components/join/JoinForm';

describe.each([
    ['로그인', LoginForm],
    ['가입', JoinForm],
])('%s 폼 제출 방식', (_name, Form) => {
    it('서버 렌더 HTML: method="post" 이고 비밀번호 · 제출 단추가 그 폼 안에 있다', () => {
        const html = renderToString(<Form />);
        const open = /<form[^>]*>/.exec(html)?.[0] ?? '';
        expect(open).toMatch(/\smethod="post"/);
        const inside = html.slice(html.indexOf(open), html.indexOf('</form>'));
        expect(inside).toMatch(/name="password"/);
        expect(inside).toMatch(/type="submit"/);
    });

    it('하이드레이션 뒤에도 method="post" 그대로다', async () => {
        const { container } = render(<Form />);
        const form = container.querySelector('form');
        await waitFor(() => expect(container.querySelector('button[type="submit"]')).not.toHaveAttribute('aria-disabled', 'true'));
        expect(form).toHaveAttribute('method', 'post');
    });
});
