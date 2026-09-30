import path from 'node:path';
import { configDefaults, defineConfig } from 'vitest/config';

export default defineConfig({
    esbuild: { jsx: 'automatic' },
    resolve: {
        dedupe: ['next', 'react', 'react-dom'],
        alias: {
            '@': path.resolve(__dirname),
            '@opensamguk/ui': path.resolve(__dirname, '../shared/src/index.ts'),
        },
    },
    test: {
        environment: 'jsdom',
        setupFiles: ['./vitest.setup.ts'],
        // Playwright 스모크(e2e/)는 vitest 가 돌리지 않는다 — playwright.config.ts(K3 게이트웨이 틀)가 돌린다.
        exclude: [...configDefaults.exclude, 'e2e/**'],
    },
});
