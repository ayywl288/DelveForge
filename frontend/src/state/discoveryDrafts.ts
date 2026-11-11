// 原文只存当前标签页的 sessionStorage；已接受消息仍完全来自服务器。
export const discoveryDraftKey = "delveforge.discovery.drafts.v1";
export const draftTextLimit = 12_000;
const lifetime = 24 * 60 * 60 * 1000;
const validId = (value: unknown): value is string => typeof value === "string" && /^[a-zA-Z0-9_-]{1,160}$/.test(value);
export interface DraftAttempt {
  requestId: string;
  text: string;
  state: "failed" | "uncertain";
  createdAt: number;
}
export interface DiscoveryDraft {
  composer: string;
  attempts: DraftAttempt[];
  updatedAt: number;
}
export type DiscoveryDrafts = Record<string, DiscoveryDraft>;

export function readDiscoveryDrafts(): DiscoveryDrafts {
  try {
    const raw: unknown = JSON.parse(sessionStorage.getItem(discoveryDraftKey) ?? "{}");
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) return {};
    const entries: [string, DiscoveryDraft][] = [];
    for (const [id, item] of Object.entries(raw)) {
      if (!validId(id) || !item || typeof item !== "object") continue;
      const value = item as DiscoveryDraft;
      if (typeof value.composer !== "string" || value.composer.length > draftTextLimit || !Array.isArray(value.attempts) ||
        !Number.isFinite(value.updatedAt) || value.updatedAt < Date.now() - lifetime || value.updatedAt > Date.now()) continue;
      const attempts = value.attempts.filter(attempt => attempt && validId(attempt.requestId) &&
        typeof attempt.text === "string" && attempt.text.length <= draftTextLimit &&
        ["failed", "uncertain"].includes(attempt.state)).slice(-4).map(attempt => ({ ...attempt,
          createdAt: Number.isFinite(attempt.createdAt) && attempt.createdAt <= Date.now()
            ? attempt.createdAt : value.updatedAt })).filter(attempt => attempt.createdAt >= Date.now() - lifetime);
      entries.push([id, { composer: value.composer, attempts, updatedAt: value.updatedAt }]);
    }
    return Object.fromEntries(entries.sort((a, b) => a[1].updatedAt - b[1].updatedAt).slice(-5));
  } catch {
    return {};
  }
}

export function writeDiscoveryDrafts(drafts: DiscoveryDrafts) {
  try {
    sessionStorage.setItem(discoveryDraftKey, JSON.stringify(drafts));
    return true;
  } catch {
    return false;
  }
}
