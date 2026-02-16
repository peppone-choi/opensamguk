import { create } from "zustand";
import type { WorldState } from "@/types";
import { worldApi } from "@/lib/gameApi";

interface WorldStore {
  worlds: WorldState[];
  currentWorld: WorldState | null;
  loading: boolean;
  fetchWorlds: () => Promise<void>;
  setCurrentWorld: (world: WorldState) => void;
  createWorld: (scenarioCode: string) => Promise<WorldState>;
}

export const useWorldStore = create<WorldStore>((set) => ({
  worlds: [],
  currentWorld: null,
  loading: false,

  fetchWorlds: async () => {
    set({ loading: true });
    try {
      const { data } = await worldApi.list();
      set({ worlds: data });
    } finally {
      set({ loading: false });
    }
  },

  setCurrentWorld: (world) => set({ currentWorld: world }),

  createWorld: async (scenarioCode) => {
    const { data } = await worldApi.create(scenarioCode);
    set((state) => ({ worlds: [...state.worlds, data], currentWorld: data }));
    return data;
  },
}));
