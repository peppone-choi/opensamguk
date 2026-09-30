import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import { resetMapSpriteCache } from './src/WorldMapCanvas';
import { resetProvinceNames } from './src/provinceNames';
import { resetSharedProvinceIdentityMaps } from './src/provinceMap';

// 지도판이 모듈에 담아 두는 省 지도 · 城 그림은 테스트끼리 나눠 쓰지 않는다.
afterEach(() => {
  cleanup();
  resetSharedProvinceIdentityMaps();
  resetMapSpriteCache();
  resetProvinceNames();
});
