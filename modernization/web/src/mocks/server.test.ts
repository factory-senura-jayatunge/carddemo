import { describe, expect, it } from 'vitest';
import { createMockServer, memoryStore, type MockSettings } from './server';

const settings: MockSettings = { latencyMs: 0, batchWindowOpen: false, uiSwitches: {}, authorizationInstalled: false };

const signOn = (server: ReturnType<typeof createMockServer>, userId: string, password: string) =>
  server.handle(
    new Request(`${window.location.origin}/api/auth/sign-on`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId, password }),
    }),
  );

describe('contract mock', () => {
  it('@DEF-SGN-002 counts every one of overlapping failed sign-ons', async () => {
    const server = createMockServer(memoryStore(), () => settings);
    const answers = await Promise.all(Array.from({ length: 30 }, (_, i) => signOn(server, 'USER0003', `WRONG${i}`)));
    expect(answers.map((a) => a.status)).toEqual(Array<number>(30).fill(401));
    // 30 failures reach the per-address limit; 5 of them have locked USER0003.
    expect((await signOn(server, 'USER0003', 'PASSWORD')).status).toBe(429);
  });
});
