import path from 'node:path';
import { configDefaults, defineConfig } from 'vitest/config';

export default defineConfig({
    esbuild: { jsx: 'automatic' },
    resolve: {
        dedupe: ['next', 'react', 'react-dom'],
        // 패키지 루트와 하위 경로(./map/topdown · ./battle 등 package.json exports)를 둘 다 소스로 잇는다.
        // 문자열 별칭 하나면 '@opensamguk/ui/map/topdown' 이 index.ts/map/topdown 으로 풀려 깨진다(web/game 과 같은 규칙).
        alias: [
            { find: '@', replacement: path.resolve(__dirname) },
            { find: /^@opensamguk\/ui$/, replacement: path.resolve(__dirname, '../shared/src/index.ts') },
            { find: /^@opensamguk\/ui\/(.+)$/, replacement: `${path.resolve(__dirname, '../shared/src')}/$1` },
        ],
    },
    test: {
        environment: 'jsdom',
        setupFiles: ['./vitest.setup.ts'],
        // e2e/ 는 Playwright spec 이다(*.spec.ts 라 vitest 기본 규칙이 집는다).
        exclude: [...configDefaults.exclude, 'e2e/**'],
    },
});
