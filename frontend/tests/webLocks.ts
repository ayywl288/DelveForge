import { vi } from "vitest";

// 同名锁排队、不同名锁独立；ifAvailable 在竞争时返回 null。
export function installWebLocks() {
  const held = new Set<string>();
  const queues = new Map<string, (() => void)[]>();
  const request = vi.fn(async (name: string, options: { ifAvailable?: boolean },
    callback: (lock: { name: string } | null) => unknown) => {
    if (held.has(name)) {
      if (options.ifAvailable) return callback(null);
      await new Promise<void>(resolve => {
        const queue = queues.get(name) ?? [];
        queue.push(resolve); queues.set(name, queue);
      });
    } else held.add(name);
    try { return await callback({ name }); }
    finally {
      const next = queues.get(name)?.shift();
      if (next) next(); else held.delete(name);
    }
  });
  Object.defineProperty(navigator, "locks", { configurable: true, value: { request } });
  return { held, request };
}
