"use client";

import { useCallback, useEffect, useRef, useState } from "react";

type SoundType = "turnComplete" | "battleStart" | "newMessage" | "notification";

const FREQUENCIES: Record<SoundType, { freq: number[]; duration: number[]; type: OscillatorType }> = {
  turnComplete: { freq: [523, 659, 784], duration: [100, 100, 200], type: "sine" },
  battleStart: { freq: [220, 330, 440, 330, 220], duration: [80, 80, 80, 80, 120], type: "square" },
  newMessage: { freq: [880, 1047], duration: [80, 120], type: "sine" },
  notification: { freq: [660, 880], duration: [100, 150], type: "triangle" },
};

const STORAGE_KEY = "opensam:sound-enabled";

function getStoredEnabled(): boolean {
  if (typeof window === "undefined") return true;
  const stored = localStorage.getItem(STORAGE_KEY);
  return stored === null ? true : stored === "true";
}

let sharedCtx: AudioContext | null = null;

function getAudioContext(): AudioContext | null {
  if (typeof window === "undefined") return null;
  if (!sharedCtx) {
    try {
      sharedCtx = new AudioContext();
    } catch {
      return null;
    }
  }
  if (sharedCtx.state === "suspended") {
    sharedCtx.resume().catch(() => {});
  }
  return sharedCtx;
}

function playSynthSound(soundType: SoundType) {
  const ctx = getAudioContext();
  if (!ctx) return;

  const config = FREQUENCIES[soundType];
  let offset = ctx.currentTime;

  for (let i = 0; i < config.freq.length; i++) {
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = config.type;
    osc.frequency.value = config.freq[i];
    gain.gain.value = 0.15;

    const dur = config.duration[i] / 1000;
    gain.gain.setValueAtTime(0.15, offset);
    gain.gain.exponentialRampToValueAtTime(0.001, offset + dur);

    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start(offset);
    osc.stop(offset + dur);
    offset += dur;
  }
}

/** Global sound effect system with on/off toggle persisted to localStorage */
export function useSoundEffects() {
  const [enabled, setEnabled] = useState(true);
  const enabledRef = useRef(true);

  useEffect(() => {
    const val = getStoredEnabled();
    setEnabled(val);
    enabledRef.current = val;
  }, []);

  const toggleSound = useCallback(() => {
    setEnabled((prev) => {
      const next = !prev;
      enabledRef.current = next;
      localStorage.setItem(STORAGE_KEY, String(next));
      // Resume audio context on user interaction
      if (next) getAudioContext();
      return next;
    });
  }, []);

  const playSound = useCallback((type: SoundType) => {
    if (!enabledRef.current) return;
    playSynthSound(type);
  }, []);

  return { soundEnabled: enabled, toggleSound, playSound };
}

/** Singleton play function for use outside React components */
export function playSoundEffect(type: SoundType) {
  if (!getStoredEnabled()) return;
  playSynthSound(type);
}
