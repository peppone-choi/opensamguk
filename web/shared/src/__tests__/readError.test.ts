import { describe, expect, it } from 'vitest';
import { plainReadError } from '../readError';

describe('plainReadError — 서버 읽기 실패를 쉬운 말 한 문장으로', () => {
  it.each([
    ['503: Service Unavailable', 'error', '서버가 잠시 응답하지 않습니다. 잠시 뒤 다시 해 보세요.', '503'],
    ['502: Bad Gateway', 'error', '서버가 잠시 응답하지 않습니다. 잠시 뒤 다시 해 보세요.', '502'],
    ['504: Gateway Timeout', 'error', '서버 응답이 늦습니다. 잠시 뒤 다시 해 보세요.', '504'],
    ['500: Internal Server Error', 'error', '서버에서 문제가 생겼습니다. 잠시 뒤 다시 해 보세요.', '500'],
    ['401: Unauthorized', 'denied', '로그인이 필요합니다. 다시 로그인해 주세요.', '401'],
    ['403: Forbidden', 'denied', '이 내용을 볼 권한이 없습니다.', '403'],
    ['404: Not Found', 'not-found', '찾는 내용이 없습니다.', '404'],
    ['409: Conflict', 'error', '요청을 처리하지 못했습니다.', '409'],
  ])('%s → %s', (raw, kind, text, code) => {
    expect(plainReadError(raw)).toEqual({ kind, text, code });
  });

  it('화면 문장에는 상태 코드도 영어 원문도 없다', () => {
    for (const raw of ['503: Service Unavailable', '401: Unauthorized', '500: Internal Server Error', 'TypeError: Failed to fetch', 'Unexpected token < in JSON']) {
      const { text } = plainReadError(raw);
      expect(text, raw).not.toMatch(/[A-Za-z]/);
      expect(text, raw).not.toMatch(/\d{3}/);
    }
  });

  it('네트워크 오류는 「서버에 닿지 않습니다」', () => {
    expect(plainReadError('TypeError: Failed to fetch').text).toBe('서버에 닿지 않습니다. 인터넷 연결을 확인하고 다시 해 보세요.');
    expect(plainReadError('Load failed').code).toBeNull();
  });

  it('서버가 준 한국어 상세는 그대로(마침표만 맞춘다), 영어 상세 · 빈 값은 「잠시 뒤 다시 해 보세요.」', () => {
    expect(plainReadError('장수가 이미 행동 중입니다')).toEqual({ kind: 'error', text: '장수가 이미 행동 중입니다.', code: null });
    expect(plainReadError('권한이 없습니다.').text).toBe('권한이 없습니다.');
    expect(plainReadError('Unexpected token < in JSON').text).toBe('잠시 뒤 다시 해 보세요.');
    expect(plainReadError(null).text).toBe('잠시 뒤 다시 해 보세요.');
  });
});
