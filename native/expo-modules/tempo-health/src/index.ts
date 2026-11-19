/**
 * JS side of the tempo-health native module. Implements the `NativeHealthBridge` interface from the
 * Replit app (artifacts/tempo/core/health/types.ts), so wiring it in is one line:
 *
 *   import { createNativeHealthBridge } from 'tempo-health';
 *   const bridge = createNativeHealthBridge();          // undefined in Expo Go / web
 *   new AppleHealthKitAdapter(bridge) / new AndroidHealthConnectAdapter(bridge)
 *
 * In Expo Go or the web preview the native module does not exist; this file then returns undefined
 * and the app falls back to the mock adapter. It never throws at import time.
 */
import { Platform } from 'react-native';

type PermissionStatus = 'notDetermined' | 'authorized' | 'denied' | 'unavailable';
interface HeartRateSample { timestamp: number; bpm: number }
interface SessionLike {
  id: string; name: string; startedAt: number; completedAt: number; calories: number; hrSamples: HeartRateSample[];
}
export interface NativeHealthBridge {
  isAvailable(): Promise<boolean>;
  requestPermissions(scopes: string[]): Promise<PermissionStatus>;
  getPermissionStatus(scopes: string[]): Promise<PermissionStatus>;
  saveWorkout(session: SessionLike): Promise<void>;
  readHeartRate(start: number, end: number): Promise<HeartRateSample[]>;
  readRestingHeartRate(): Promise<number | null>;
}

interface NativeModuleShape {
  isAvailable(): Promise<boolean>;
  requestPermissions(): Promise<boolean>;
  getPermissionStatus(): Promise<'granted' | 'denied' | 'notDetermined' | 'partial' | 'unavailable'>;
  saveWorkout(w: SessionLike): Promise<string>;
  readHeartRate(fromMs: number, toMs: number): Promise<HeartRateSample[]>;
  readRestingHeartRate(): Promise<number | null>;
  sendToWatch(payload: string, mode: 'context' | 'message' | 'queue'): Promise<void>;
  addListener?(event: 'onWatchMessage', cb: (e: { payload: string }) => void): { remove(): void };
}

function loadNative(): NativeModuleShape | undefined {
  if (Platform.OS === 'web') return undefined;
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { requireOptionalNativeModule } = require('expo-modules-core');
    return requireOptionalNativeModule('TempoHealth') ?? undefined;
  } catch {
    return undefined;
  }
}

const native = loadNative();

function mapStatus(s: string): PermissionStatus {
  if (s === 'granted') return 'authorized';
  if (s === 'denied' || s === 'partial') return 'denied';
  if (s === 'unavailable') return 'unavailable';
  return 'notDetermined';
}

/** Only the fields the platform needs leave JS; nothing else from the session is sent native-side. */
function toNativeRecord(s: SessionLike): SessionLike {
  return {
    id: String(s.id).slice(0, 64),
    name: String(s.name).slice(0, 60),
    startedAt: s.startedAt,
    completedAt: s.completedAt,
    calories: Math.max(0, Math.round(s.calories)),
    hrSamples: (s.hrSamples ?? []).filter(x => x.bpm >= 25 && x.bpm <= 250).slice(0, 20_000),
  };
}

export function createNativeHealthBridge(): NativeHealthBridge | undefined {
  if (!native) return undefined;
  return {
    isAvailable: () => native.isAvailable(),
    requestPermissions: async () => (await native.requestPermissions()) ? 'authorized' : mapStatus(await native.getPermissionStatus()),
    getPermissionStatus: async () => mapStatus(await native.getPermissionStatus()),
    saveWorkout: async s => { await native.saveWorkout(toNativeRecord(s)); },
    readHeartRate: (start, end) => native.readHeartRate(start, end),
    readRestingHeartRate: () => native.readRestingHeartRate(),
  };
}

/** Watch sync (WatchConnectivity on iOS, Wearable Data Layer on Android). See native/SYNC_PROTOCOL.md. */
export const watchSync = {
  available: !!native,
  sendTemplates(templates: unknown[]) {
    return native?.sendToWatch(JSON.stringify({ v: 1, kind: 'templates', templates: templates.slice(0, 200) }), 'context');
  },
  sendCommand(action: 'start' | 'pause' | 'resume' | 'skip' | 'back' | 'stop', templateId?: string) {
    return native?.sendToWatch(JSON.stringify({ v: 1, kind: 'command', command: { action, templateId } }), 'message');
  },
  /** Listener receives raw JSON; validate it with the app's zod schemas before storing. */
  onMessage(cb: (json: string) => void): () => void {
    const sub = native?.addListener?.('onWatchMessage', e => {
      if (typeof e?.payload === 'string' && e.payload.length < 512 * 1024) cb(e.payload);
    });
    return () => sub?.remove();
  },
};
