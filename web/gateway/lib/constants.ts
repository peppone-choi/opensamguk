// 게이트웨이 verbatim 한글 라벨 + 상수 (F0 패러티 스펙 §3~§6 기준).
// 렌더되는 텍스트는 레거시(devsam/core)와 byte-parity — 마케팅 카피 등 임의 문구 금지.
// 예외: BRAND는 의도적 리브랜딩(devsam "삼국지 모의전투 HiDCHe" → "오픈삼국") — 사용자 결정 divergence.

export const BRAND = '오픈삼국';

// 게임 서버(web/game) 진입 URL. 배포 시 NEXT_PUBLIC_GAME_URL로 덮어쓴다.
export const GAME_URL = process.env.NEXT_PUBLIC_GAME_URL ?? 'http://localhost:3001';

// 이미지 자산 CDN 베이스 — opensamguk-images(jsDelivr 미러). 배포 시 NEXT_PUBLIC_IMAGE_CDN으로 덮어쓴다.
export const IMAGE_CDN_BASE =
    process.env.NEXT_PUBLIC_IMAGE_CDN ?? 'https://cdn.jsdelivr.net/gh/peppone-choi/opensamguk-images';

// 로비 맵 프리뷰의 추상 게임맵 베이스 자산 경로.
export const MAP_CDN = `${IMAGE_CDN_BASE}/game/map`;

export const AUTH_LABELS = {
    loginTitle: '로그인',
    joinTitle: '회원 가입',
    username: '계정명',
    password: '비밀번호',
    passwordConfirm: '비밀번호 확인',
    nickname: '별명',
    email: '이메일',
    loginBtn: '로그인',
    registerBtn: '회원가입',
    logout: '로 그 아 웃',
    toJoin: '계정이 없으신가요? 회원가입',
    toLogin: '이미 계정이 있으신가요? 로그인',
    // 검증/에러 (verbatim, login.ts/join.ts/AuthService)
    emptyUsername: '유저명을 입력해주세요',
    emptyPassword: '비밀번호를 입력해주세요',
    loginFail: '아이디나 비밀번호가 올바르지 않습니다.',
    loginForbidden: '현재는 로그인이 금지되어있습니다!',
    passwordMismatch: '비밀번호가 일치하지 않습니다',
    registerSuccess: '회원 등록되었습니다.',
    // 가입 필드 제약 (AuthDto.kt; backend = grand truth)
    usernameRule: '3~50자',
    passwordRule: '6자 이상',
    usernameTooShort: (n: number) => `${n}글자 이상 입력하셔야 합니다`,
    usernameTooLong: (n: number) => `${n}자를 넘을 수 없습니다`,
    passwordTooShort: (n: number) => `비밀번호는 적어도 ${n}글자 이상이어야 합니다`,
} as const;

// 푸터 링크 (legacy index.php) — F0에선 inert placeholder
export const FOOTER_LINKS = ['개인정보처리방침', '이용약관'] as const;

export const LOBBY_LABELS = {
    serverSelect: '서 버 선 택',
    colServer: '서 버',
    colInfo: '정 보',
    colCharacter: '캐 릭 터',
    colSelect: '선 택',
    enter: '입장',
    accountSection: '계 정 관 리',
    accountManage: '비밀번호 & 전콘 & 탈퇴',
    admin: '관리',
    // 캐릭터 셀 상태 (legacy entrance.php)
    unregistered: '- 미 등 록 -',
    registerClosed: '- 장수 등록 마감 -',
    createGeneral: '장수생성',
    possessGeneral: '장수빙의',
    selectGeneral: '장수선택',
    closed: '- 폐 쇄 중 -',
    preparing: '- 준 비 중 -', // 백엔드/현황은 떴으나 입장(인게임 라우팅) 미완 — 입장 비활성
} as const;

// 각주 (legacy entrance.php, verbatim)
export const LOBBY_FOOTNOTES = [
    '★ 1명이 2개 이상의 계정을 사용하거나 타 유저의 턴을 대신 입력하는 것이 적발될 경우 차단 될 수 있습니다.',
    '계정은 한번 등록으로 계속 사용합니다. 각 서버 리셋시 캐릭터만 새로 생성하면 됩니다.',
] as const;

// 서버 상태 → 뱃지 라벨/색 (legacy isUnited/상태)
export const SERVER_STATUS: Record<string, { label: string; cls: string }> = {
    running: { label: '§이벤트 진행중§', cls: 'status-jade' }, // isUnited=1
    unified: { label: '§천하통일§', cls: 'status-gold' }, // =2
    ended: { label: '§이벤트 종료§', cls: 'status-muted' }, // =3
    preopen: { label: '-가오픈 중-', cls: 'status-gold' },
    closed: { label: '- 폐 쇄 중 -', cls: 'status-muted' },
};

// 경쟁중 N국 (running, 미통일)
export const competingLabel = (nationCount: number): string => `<${nationCount}국 경쟁중>`;

// 타임라인 (legacy entrance.php, verbatim 템플릿)
export const timelineYear = (year: number, month: number, scenario: string): string =>
    `서기 ${year}년 ${month}월 (${scenario})`;
export const timelineUsers = (userCnt: number, maxUserCnt: number, npcCnt: number, turnTerm: number): string =>
    `유저 : ${userCnt} / ${maxUserCnt}명 NPC : ${npcCnt}명 (${turnTerm}분 턴 서버)`;
