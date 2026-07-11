// gateway-api 인증 계약 (AuthDto.kt 미러).

export interface User {
    id: number;
    username: string;
    email: string | null;
    nickname: string | null;
    role: string; // "USER" | "ADMIN"
    picture: string | null;
    imageServer: number;
}

export interface AuthResponse {
    accessToken: string;
    refreshToken: string;
    user: User;
}
