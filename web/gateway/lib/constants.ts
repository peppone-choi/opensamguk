// 게이트웨이 한글 라벨 + 상수. 화면 문구는 v3.1 설계(2026-09-30 전체 승인, K5 설계서 §2)를 따른다 — 쉬운 말.
// 로그인 · 가입 오류는 설계서 LG12 · J11 의 쉬운 말로 바꿨다. 서버가 보낸 거절 문장은 받은 그대로 보인다.
// LOBBY_LABELS 는 아직 옛 Topbar(계정 · 커뮤니티 · 운영 콘솔)가 쓴다 — 그 화면들을 다시 지을 때 MemberHeader 로 바꾸고 지운다.

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
    emptyNickname: '별명을 입력해주세요',
    loginFail: '계정명이나 비밀번호가 맞지 않습니다.',
    passwordMismatch: '비밀번호가 일치하지 않습니다',
    // 가입 필드 제약 (AuthDto.kt; backend = grand truth)
    usernameRule: '3~50자',
    nicknameRule: '2~20자, 다른 유저와 겹칠 수 없음',
    passwordRule: '6자 이상',
    usernameTooShort: (n: number) => `${n}글자 이상 입력하셔야 합니다`,
    usernameTooLong: (n: number) => `${n}자를 넘을 수 없습니다`,
    passwordTooShort: (n: number) => `비밀번호는 적어도 ${n}글자 이상이어야 합니다`,
} as const;

// 바닥 정책 링크 이름(가입 화면이 아직 쓴다 — 가입 재구현 P-G03 에서 PolicyLinks 로 바꾼다).
export const FOOTER_LINKS = ['개인정보처리방침', '이용약관'] as const;

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
