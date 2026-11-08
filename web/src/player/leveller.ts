/**
 * Evens out loudness between songs from different services, like the Lite app does. A song whose
 * source knows its loudness (Jellyfin's normalization gain) gets that fixed gain. Anything else
 * is levelled by ear: a slowly moving measure of how loud the music is (silence doesn't count)
 * sets the gain, which moves by at most a couple of dB a second. A limiter stops boosts clipping.
 */
const TARGET_DB = -16;
const KNOWN_GAIN_OFFSET_DB = 2; // Jellyfin's gain aims at -18 LUFS
const MIN_GAIN_DB = -12;
const MAX_GAIN_DB = 6;
const START_LEVEL_DB = -14;
const SILENCE_DB = -45;
const TIME_CONSTANT_S = 8;
const MAX_DB_PER_SECOND = 2;
const TICK_S = 0.25;

const dbToPower = (db: number) => Math.pow(10, db / 10);
const powerToDb = (power: number) => 10 * Math.log10(Math.max(power, 1e-12));
const dbToAmplitude = (db: number) => Math.pow(10, db / 20);
const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

export class Leveller {
  private ctx: AudioContext | null = null;
  private gain: GainNode | null = null;
  private analyser: AnalyserNode | null = null;
  private limiter: DynamicsCompressorNode | null = null;
  private buffer: Float32Array<ArrayBuffer> | null = null;
  private timer: number | null = null;
  private knownGainDb: number | null = null;
  private gainDb = 0;
  private meanSquare = dbToPower(START_LEVEL_DB);
  enabled = true;

  constructor(private readonly audio: HTMLAudioElement) {}

  /** Connects the player to the audio graph; must first run during a click (browsers' rule). */
  async start() {
    if (!this.ctx) {
      const Context = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
      if (!Context) return;
      try {
        const ctx = new Context();
        const source = ctx.createMediaElementSource(this.audio);
        const analyser = ctx.createAnalyser();
        analyser.fftSize = 2048;
        const gain = ctx.createGain();
        const limiter = ctx.createDynamicsCompressor();
        limiter.threshold.value = -1;
        limiter.knee.value = 0;
        limiter.ratio.value = 20;
        limiter.attack.value = 0.002;
        limiter.release.value = 0.1;
        source.connect(analyser);
        analyser.connect(gain);
        gain.connect(limiter);
        limiter.connect(ctx.destination);
        this.ctx = ctx;
        this.analyser = analyser;
        this.gain = gain;
        this.limiter = limiter;
        this.buffer = new Float32Array(new ArrayBuffer(analyser.fftSize * 4));
        this.timer = window.setInterval(() => this.tick(), TICK_S * 1000);
      } catch {
        // No Web Audio here: songs just play unlevelled.
        return;
      }
    }
    if (this.ctx.state === "suspended") await this.ctx.resume().catch(() => undefined);
  }

  /** A new song: [gainDb] is its known loudness gain, if its source has one. */
  newSong(gainDb: number | undefined) {
    this.knownGainDb = gainDb ?? null;
    if (this.knownGainDb != null) {
      this.gainDb = clamp(this.knownGainDb + KNOWN_GAIN_OFFSET_DB, MIN_GAIN_DB, MAX_GAIN_DB);
      this.apply(0.05);
    }
  }

  setEnabled(enabled: boolean) {
    this.enabled = enabled;
    if (!enabled) {
      this.gainDb = 0;
      this.apply(0.1);
    }
  }

  private tick() {
    if (!this.enabled || !this.analyser || !this.buffer || this.audio.paused) return;
    let target: number;
    if (this.knownGainDb != null) {
      target = clamp(this.knownGainDb + KNOWN_GAIN_OFFSET_DB, MIN_GAIN_DB, MAX_GAIN_DB);
      this.gainDb = target;
    } else {
      this.analyser.getFloatTimeDomainData(this.buffer);
      let sum = 0;
      for (let i = 0; i < this.buffer.length; i++) sum += this.buffer[i] * this.buffer[i];
      const power = sum / this.buffer.length;
      if (power > dbToPower(SILENCE_DB)) {
        this.meanSquare += (power - this.meanSquare) * (1 - Math.exp(-TICK_S / TIME_CONSTANT_S));
      }
      target = clamp(TARGET_DB - powerToDb(this.meanSquare), MIN_GAIN_DB, MAX_GAIN_DB);
      const step = MAX_DB_PER_SECOND * TICK_S;
      this.gainDb += clamp(target - this.gainDb, -step, step);
    }
    this.apply(TICK_S);
  }

  private apply(seconds: number) {
    if (!this.ctx || !this.gain) return;
    this.gain.gain.setTargetAtTime(dbToAmplitude(this.gainDb), this.ctx.currentTime, seconds / 3);
  }

  get currentGainDb() {
    return this.gainDb;
  }

  dispose() {
    if (this.timer != null) clearInterval(this.timer);
    this.limiter?.disconnect();
  }
}
