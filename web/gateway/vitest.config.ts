import path from 'node:path';
import { defineConfig } from 'vitest/config';

export default defineConfig({
    esbuild: { jsx: 'automatic' },
    resolve: {
        alias: {
            '@': path.resolve(__dirname),
            '@opensamguk/ui': path.resolve(__dirname, '../shared/src/index.ts'),
        },
    },
    test: {
        environment: 'jsdom',
        setupFiles: ['./vitest.setup.ts'],
    },
});
