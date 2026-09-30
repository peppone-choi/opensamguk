// 게이트웨이 한글 라벨 + 상수. 화면 문구는 v3.1 설계(2026-09-30 전체 승인, K5 설계서 §2)를 따른다 — 쉬운 말.
// 로그인 · 가입 오류는 설계서 LG12 · J11 의 쉬운 말로 바꿨다. 서버가 보낸 거절 문장은 받은 그대로 보인다.
// 삼모 원문(LOBBY_LABELS · LOBBY_FOOTNOTES)은 로비 재구현(P-G04)에서 바꾼다.

export const BRAND = '오픈삼국';

// 게임 서버(web/game) 진입 URL. 배포 시 NEXT_PUBLIC_GAME_URL로 덮어쓴다.
export const GAME_URL = process.env.NEXT_PUBLIC_GAME_URL ?? 'http://localhost:3001';

// 이미지 자산 CDN 베이스 — opensamguk-images(jsDelivr 미러). 배포 시 NEXT_PUBLIC_IMAGE_CDN으로 덮어쓴다.
export const IMAGE_CDN_BASE =
    process.env.NEXT_PUBLIC_IMAGE_CDN ?? 'https://cdn.jsdelivr.net/gh/peppone-choi/opensamguk-images';

// 로비 맵 프리뷰의 추상 게임맵 베이스 자산 경로.
export const MAP_CDN = `${IMAGE_CDN_BASE}/game/map`;

// 도시 상태/성/수도 아이콘 경로 (IMAGE_CDN_BASE 하위 game). MapViewer(web/game)와 동일 불변식 — 둘 다 CDN 단일 출처.
export const ICON_CDN = `${IMAGE_CDN_BASE}/game`;

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
    // 검증/에러 — 설계서 LG12 쉬운 말. 서버 거절 문장(AuthService)은 받은 그대로 보인다.
    emptyUsername: '계정명을 입력하세요',
    emptyPassword: '비밀번호를 입력하세요',
    emptyNickname: '별명을 입력하세요',
    loginFail: '계정명이나 비밀번호가 맞지 않습니다.',
    passwordMismatch: '비밀번호가 서로 다릅니다.',
    // 가입 칸 오류 — 설계서 J11 쉬운 말. 별명은 별명 문구로 따로 쓴다(계정명 문구를 빌려 쓰지 않는다).
    usernameTooShort: (n: number) => `계정명은 ${n}자 이상이어야 합니다`,
    usernameTooLong: (n: number) => `계정명은 ${n}자를 넘을 수 없습니다`,
    passwordTooShort: (n: number) => `비밀번호는 ${n}자 이상이어야 합니다`,
    nicknameTooShort: (n: number) => `별명은 ${n}자 이상이어야 합니다`,
    nicknameTooLong: (n: number) => `별명은 ${n}자를 넘을 수 없습니다`,
} as const;

// 가입 칸 제약(gateway-api AuthDto.kt — 서버가 최종 판정) · 입력칸 아래 도움말.
export const JOIN_RULES = {
    usernameMin: 3,
    usernameMax: 50,
    passwordMin: 6,
    nicknameMin: 2,
    nicknameMax: 20,
    usernameHelp: '3~50자',
    passwordHelp: '6자 이상',
    nicknameHelp: '2~20자, 다른 사람과 겹칠 수 없습니다.',
} as const;


export const LOBBY_LABELS = {
    // 상단바(ADR-LITE-049)
    navLobby: '로비',
    navBoard: '게시판',
    navAccount: '계정',
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
    closed: '- 폐 쇄 중 -',
    preparing: '- 준 비 중 -', // 백엔드/현황은 떴으나 입장(인게임 라우팅) 미완 — 입장 비활성
} as const;

// 각주 (legacy entrance.php, verbatim)
export const LOBBY_FOOTNOTES = [
    '★ 1명이 2개 이상의 계정을 사용하거나 타 유저의 턴을 대신 입력하는 것이 적발될 경우 차단 될 수 있습니다.',
    '계정은 한번 등록으로 계속 사용합니다. 각 서버 리셋시 캐릭터만 새로 생성하면 됩니다.',
] as const;
