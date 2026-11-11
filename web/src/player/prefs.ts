import { create } from "zustand";

/** Choices kept in this browser: playback, and how text looks. */
interface Prefs {
  /** Bitrate cap in kbps; 0 is the original quality. */
  kbps: number;
  levelling: boolean;
  /** Compare every service for the best copy before playing (slower to start). */
  thorough: boolean;
  /** All text in Lexend Bold, like a phone's Bold text setting. */
  boldText: boolean;
  /** The colours: Aurora (purple and green), Violet, Ocean or Ember. */
  palette: Palette;
  /** Light, dark, or as the system is. */
  scheme: "system" | "light" | "dark";
}

export type Palette = "aurora" | "violet" | "ocean" | "ember";

/** The themes, with the two colours each shows in Settings. */
export const PALETTES: { id: Palette; name: string; swatch: [string, string] }[] = [
  { id: "aurora", name: "Aurora", swatch: ["#6a3fd6", "#12a37c"] },
  { id: "violet", name: "Violet", swatch: ["#5b3fd1", "#8e3f5c"] },
  { id: "ocean", name: "Ocean", swatch: ["#1d5fc9", "#0e8fa3"] },
  { id: "ember", name: "Ember", swatch: ["#b4471a", "#9c3f60"] },
];

const KEY = "samm.prefs";

function load(): Prefs {
  const defaults: Prefs = { kbps: 0, levelling: true, thorough: true, boldText: false, palette: "aurora", scheme: "system" };
  try {
    return { ...defaults, ...JSON.parse(localStorage.getItem(KEY) ?? "{}") };
  } catch {
    return defaults;
  }
}

export const usePrefs = create<Prefs>(load);

/** Bold text: the stylesheet turns every weight to Bold while the page has data-bold. */
const showBold = (on: boolean) => document.documentElement.toggleAttribute("data-bold", on);
showBold(usePrefs.getState().boldText);

/** The theme and the scheme, on the page (the stylesheet picks its colours by them). */
function showTheme(prefs: Prefs) {
  const root = document.documentElement;
  root.dataset.palette = prefs.palette;
  if (prefs.scheme === "system") delete root.dataset.scheme;
  else root.dataset.scheme = prefs.scheme;
}
showTheme(usePrefs.getState());

usePrefs.subscribe((prefs) => {
  showBold(prefs.boldText);
  showTheme(prefs);
  try {
    localStorage.setItem(KEY, JSON.stringify(prefs));
  } catch {
    // Not saved; the choice lasts until the page is closed.
  }
});

export const QUALITY_STEPS = [0, 320, 256, 192, 160, 128, 96, 64];

export const qualityLabel = (kbps: number) => (kbps === 0 ? "Original quality" : `Up to ${kbps} kbps`);
