// 그림 캐시 상한 — 가장 오래 안 쓴 것부터 버린다(Map 의 넣은 순서를 쓴다).

/** Sprite cache limit for battle board pictures (unit sprites · tinted pack frames). */
export const BATTLE_SPRITE_CACHE_LIMIT = 512;

export class LruCache<K, V> {
  private readonly items = new Map<K, V>();

  constructor(readonly limit: number = BATTLE_SPRITE_CACHE_LIMIT) {
    if (!Number.isInteger(limit) || limit < 1) throw new Error('LruCache: limit must be a positive integer');
  }

  get size(): number {
    return this.items.size;
  }

  get(key: K): V | undefined {
    const value = this.items.get(key);
    if (value === undefined) return undefined;
    this.items.delete(key);
    this.items.set(key, value);
    return value;
  }

  set(key: K, value: V): void {
    this.items.delete(key);
    this.items.set(key, value);
    while (this.items.size > this.limit) this.items.delete(this.items.keys().next().value as K);
  }

  clear(): void {
    this.items.clear();
  }
}
