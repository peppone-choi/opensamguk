import { create } from "zustand";
import type { WorldState } from "@/types";
import { worldApi } from "@/lib/gameApi";

interface WorldStore {
  worlds: WorldState[];
  currentWorld: WorldState | null;
  loading: boolean;
  fetchWorlds: () => Promise<void>;
  setCurrentWorld: (world: WorldState) => void;
  createWorld: (payload: {
    scenarioCode: string;
    name?: string;
    tickSeconds?: number;
    commitSha?: string;
    gameVersion?: string;
  }) => Promise<WorldState>;
  deleteWorld: (id: number) => Promise<void>;
  resetWorld: (id: number, scenarioCode?: string) => Promise<WorldState>;
  activateWorld: (
    id: number,
    payload?: {
      commitSha?: string;
      gameVersion?: string;
      jarPath?: string;
      port?: number;
      javaCommand?: string;
    },
  ) => Promise<void>;
  deactivateWorld: (id: number) => Promise<void>;
  fetchWorld: (id: number) => Promise<void>;
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

  createWorld: async (payload) => {
    const { data } = await worldApi.create(payload);
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

  activateWorld: async (id, payload) => {
    await worldApi.activate(id, payload);
    const { data } = await worldApi.get(id);
    set((state) => ({
      worlds: state.worlds.map((w) => (w.id === id ? data : w)),
      currentWorld: state.currentWorld?.id === id ? data : state.currentWorld,
    }));
  },

  deactivateWorld: async (id) => {
    await worldApi.deactivate(id);
    const { data } = await worldApi.get(id);
    set((state) => ({
      worlds: state.worlds.map((w) => (w.id === id ? data : w)),
      currentWorld: state.currentWorld?.id === id ? data : state.currentWorld,
    }));
  },

  fetchWorld: async (id) => {
    const { data } = await worldApi.get(id);
    set((state) => ({
      worlds: state.worlds.map((w) => (w.id === id ? data : w)),
      currentWorld: state.currentWorld?.id === id ? data : state.currentWorld,
    }));
  },
}));
