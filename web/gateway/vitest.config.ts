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
        // e2e/ 는 Playwright spec 이다(*.spec.ts 라 vitest 기본 규칙이 집는다).
        exclude: [...configDefaults.exclude, 'e2e/**'],
    },
});
