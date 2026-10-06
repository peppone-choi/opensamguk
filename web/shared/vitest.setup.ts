import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import { resetProvinceNames } from './src/provinceNames';

// 지도 훅이 모듈에 담아 두는 구역 이름은 테스트끼리 나눠 쓰지 않는다.
afterEach(() => {
  cleanup();
  resetProvinceNames();
});
