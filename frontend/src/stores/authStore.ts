import { create } from "zustand";
import api from "@/lib/api";

interface User {
  id: number;
  loginId: string;
  displayName: string;
}

interface AuthState {
  user: User | null;
  token: string | null;
  isAuthenticated: boolean;
  login: (loginId: string, password: string) => Promise<void>;
  register: (
    loginId: string,
    displayName: string,
    password: string,
  ) => Promise<void>;
  logout: () => void;
  initAuth: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  token: null,
  isAuthenticated: false,

  login: async (loginId, password) => {
    const { data } = await api.post("/auth/login", { loginId, password });
    localStorage.setItem("token", data.token);
    set({ user: data.user, token: data.token, isAuthenticated: true });
  },

  register: async (loginId, displayName, password) => {
    const { data } = await api.post("/auth/register", {
      loginId,
      displayName,
      password,
    });
    localStorage.setItem("token", data.token);
    set({ user: data.user, token: data.token, isAuthenticated: true });
  },

  logout: () => {
    localStorage.removeItem("token");
    set({ user: null, token: null, isAuthenticated: false });
  },

  initAuth: () => {
    if (typeof window !== "undefined") {
      const token = localStorage.getItem("token");
      if (token) {
        set({ token, isAuthenticated: true });
        try {
          const payload = JSON.parse(atob(token.split(".")[1]));
          set({
            user: {
              id: payload.userId,
              loginId: payload.sub,
              displayName: payload.displayName,
            },
          });
        } catch {
          localStorage.removeItem("token");
          set({ token: null, isAuthenticated: false });
        }
      }
    }
  },
}));
