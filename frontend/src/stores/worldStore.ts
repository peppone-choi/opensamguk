import { create } from "zustand";
import type { WorldState } from "@/types";
import { worldApi } from "@/lib/gameApi";

interface WorldStore {
  worlds: WorldState[];
  currentWorld: WorldState | null;
  loading: boolean;
  fetchWorlds: () => Promise<void>;
  setCurrentWorld: (world: WorldState) => void;
  createWorld: (scenarioCode: string, name?: string) => Promise<WorldState>;
  deleteWorld: (id: number) => Promise<void>;
  resetWorld: (id: number, scenarioCode?: string) => Promise<WorldState>;
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

  createWorld: async (scenarioCode, name) => {
    const { data } = await worldApi.create(scenarioCode, name);
    set((state) => ({ worlds: [...state.worlds, data], currentWorld: data }));
    return data;
  },

  deleteWorld: async (id) => {
    await worldApi.delete(id);
    set((state) => ({
      worlds: state.worlds.filter((w) => w.id !== id),
      currentWorld: state.currentWorld?.id === id ? null : state.currentWorld,
    }));
  },

  resetWorld: async (id, scenarioCode) => {
    const { data } = await worldApi.reset(id, scenarioCode);
    set((state) => ({
      worlds: state.worlds
        .map((w) => (w.id === id ? data : w))
        .concat(state.worlds.some((w) => w.id === id) ? [] : [data]),
      currentWorld: state.currentWorld?.id === id ? data : state.currentWorld,
    }));
    return data;
  },
}));
