/// <reference types="vitest" />
// @opensamguk/ui 단위 테스트 — jsdom + React Testing Library. 앱 테스트는 각 앱에서 돈다.
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    include: ['src/__tests__/**/*.{test,spec}.{ts,tsx}'],
  },
});
