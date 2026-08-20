/// <reference types="vitest" />
// Vitest 설정 — web/game 프론트엔드 컴포넌트/순수로직 테스트 러너.
// jsdom 환경 + React Testing Library. tsconfig 의 `@/*` 경로 별칭을 그대로 매핑해
// 컴포넌트가 import 하는 `@/lib/*`를 테스트에서도 동일하게 해석한다.
// 라이브 백엔드 불필요 — 모든 네트워크(fetch)는 테스트에서 모킹한다.
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

export default defineConfig({
    plugins: [react()],
    resolve: {
        alias: {
            '@': resolve(__dirname, '.'),
            '@opensamguk/ui': resolve(__dirname, '../shared/src/index.ts'),
        },
    },
    test: {
        environment: 'jsdom',
        globals: true,
        setupFiles: ['./vitest.setup.ts'],
        // 테스트 파일은 __tests__ 디렉터리에만 둔다(소스 스캔 오염 방지).
        include: ['**/__tests__/**/*.{test,spec}.{ts,tsx}'],
        // e2e/playwright 스펙은 라이브 스택이 필요하므로 단위 러너에서 제외(QA 이관).
        exclude: ['**/node_modules/**', '**/e2e/**', '**/*.e2e.{ts,tsx}'],
    },
});
