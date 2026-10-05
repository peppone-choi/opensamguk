// 서버 공개 상태(game-api admission, C1 #1355) — 게임 API 가 이 서버를 일반 요청에 열지 않았는지 가른다(D112 ② · D120).
// 403 SERVER_NOT_PUBLIC = 검증 중(공개 전), 503 SERVER_ADMISSION_UNAVAILABLE = 공개 상태를 확인하지 못함.
// 401 AUTH_REQUIRED 는 로그인 문제라 여기서 다루지 않는다(AuthGate 가 맡는다). 그 밖의 오류도 공개 상태가 아니다.
import { GameHttpError } from './api';

export type Admission = 'not-public' | 'unavailable';

export function admissionOf(error: unknown): Admission | null {
    if (!(error instanceof GameHttpError)) return null;
    if (error.status === 403 && error.code === 'SERVER_NOT_PUBLIC') return 'not-public';
    if (error.status === 503 && error.code === 'SERVER_ADMISSION_UNAVAILABLE') return 'unavailable';
    return null;
}
