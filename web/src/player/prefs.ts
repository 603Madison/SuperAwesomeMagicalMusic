import { create } from "zustand";

/** Playback choices, kept in this browser. */
interface Prefs {
  /** Bitrate cap in kbps; 0 is the original quality. */
  kbps: number;
  levelling: boolean;
  /** Compare every service for the best copy before playing (slower to start). */
  thorough: boolean;
}

const KEY = "samm.prefs";

function load(): Prefs {
  const defaults: Prefs = { kbps: 0, levelling: true, thorough: true };
  try {
    return { ...defaults, ...JSON.parse(localStorage.getItem(KEY) ?? "{}") };
  } catch {
    return defaults;
  }
}

export const usePrefs = create<Prefs>(load);

usePrefs.subscribe((prefs) => {
  try {
    localStorage.setItem(KEY, JSON.stringify(prefs));
  } catch {
    // Not saved; the choice lasts until the page is closed.
  }
});

export const QUALITY_STEPS = [0, 320, 256, 192, 160, 128, 96, 64];

export const qualityLabel = (kbps: number) => (kbps === 0 ? "Original quality" : `Up to ${kbps} kbps`);
