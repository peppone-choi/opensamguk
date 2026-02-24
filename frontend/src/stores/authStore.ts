import { create } from "zustand";
import api from "@/lib/api";

interface User {
  id: number;
  loginId: string;
  displayName: string;
  role: string;
  picture?: string;
  oauthProviders?: import("@/types").OAuthProviderInfo[];
}

interface LoginResult {
  otpRequired?: boolean;
}

interface AuthState {
  user: User | null;
  token: string | null;
  isAuthenticated: boolean;
  isInitialized: boolean;
  login: (loginId: string, password: string) => Promise<LoginResult | void>;
  loginWithToken: (token: string) => Promise<void>;
  loginWithOtp: (
    loginId: string,
    password: string,
    otpCode: string,
  ) => Promise<void>;
  loginWithOAuth: (
    provider: string,
    code: string,
    redirectUri: string,
  ) => Promise<void>;
  register: (
    loginId: string,
    displayName: string,
    password: string,
    agreements?: { terms: boolean; privacy: boolean },
  ) => Promise<void>;
  registerWithOAuth: (
    provider: string,
    code: string,
    redirectUri: string,
    displayName: string,
    agreements?: { terms: boolean; privacy: boolean },
  ) => Promise<void>;
  logout: () => void;
  initAuth: () => void;
}

function parseTokenUser(token: string): User {
  const payload = JSON.parse(atob(token.split(".")[1]));
  return {
    id: payload.userId,
    loginId: payload.sub,
    displayName: payload.displayName,
    role: payload.role || "USER",
  };
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  token: null,
  isAuthenticated: false,
  isInitialized: false,

  login: async (loginId, password) => {
    const { data } = await api.post("/auth/login", { loginId, password });
    if (data.otpRequired) {
      return { otpRequired: true };
    }
    localStorage.setItem("token", data.token);
    const user = { ...data.user, role: parseTokenUser(data.token).role };
    set({ user, token: data.token, isAuthenticated: true });
  },

  loginWithToken: async (token: string) => {
    // Validate token by calling a lightweight endpoint
    try {
      const { data } = await api.post("/auth/token-login", { token });
      const newToken = data.token ?? token;
      localStorage.setItem("token", newToken);
      const user = parseTokenUser(newToken);
      set({ user, token: newToken, isAuthenticated: true });
    } catch {
      // If no token-login endpoint, try parsing token directly
      const user = parseTokenUser(token);
      // Check expiry
      const payload = JSON.parse(atob(token.split(".")[1]));
      if (payload.exp && payload.exp * 1000 < Date.now()) {
        throw new Error("Token expired");
      }
      set({ user, token, isAuthenticated: true });
    }
  },

  loginWithOtp: async (loginId, password, otpCode) => {
    const { data } = await api.post("/auth/login", {
      loginId,
      password,
      otpCode,
    });
    localStorage.setItem("token", data.token);
    const user = { ...data.user, role: parseTokenUser(data.token).role };
    set({ user, token: data.token, isAuthenticated: true });
  },

  loginWithOAuth: async (provider, code, redirectUri) => {
    const { data } = await api.post("/auth/oauth/login", {
      provider,
      code,
      redirectUri,
    });
    localStorage.setItem("token", data.token);
    const user = parseTokenUser(data.token);
    set({ user, token: data.token, isAuthenticated: true });
  },

  register: async (loginId, displayName, password, agreements) => {
    const { data } = await api.post("/auth/register", {
      loginId,
      displayName,
      password,
      ...(agreements ? { agreeTerms: agreements.terms, agreePrivacy: agreements.privacy } : {}),
    });
    localStorage.setItem("token", data.token);
    const user = { ...data.user, role: parseTokenUser(data.token).role };
    set({ user, token: data.token, isAuthenticated: true });
  },

  registerWithOAuth: async (
    provider,
    code,
    redirectUri,
    displayName,
    agreements,
  ) => {
    const { data } = await api.post("/auth/oauth/register", {
      provider,
      code,
      redirectUri,
      displayName,
      ...(agreements ? { agreeTerms: agreements.terms, agreePrivacy: agreements.privacy } : {}),
    });
    localStorage.setItem("token", data.token);
    const user = parseTokenUser(data.token);
    set({ user, token: data.token, isAuthenticated: true });
  },

  logout: () => {
    localStorage.removeItem("token");
    set({ user: null, token: null, isAuthenticated: false });
  },

  initAuth: () => {
    if (typeof window !== "undefined") {
      const token = localStorage.getItem("token");
      if (token) {
        try {
          const user = parseTokenUser(token);
          // Check expiry
          const payload = JSON.parse(atob(token.split(".")[1]));
          if (payload.exp && payload.exp * 1000 < Date.now()) {
            localStorage.removeItem("token");
            set({ token: null, isAuthenticated: false, isInitialized: true });
            return;
          }
          set({
            token,
            isAuthenticated: true,
            isInitialized: true,
            user,
          });
        } catch {
          localStorage.removeItem("token");
          set({ token: null, isAuthenticated: false, isInitialized: true });
        }
      } else {
        set({ isInitialized: true });
      }
    }
  },
}));
