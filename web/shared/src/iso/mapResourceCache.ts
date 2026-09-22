export interface ResourceLease<T> { promise: Promise<T>; release(): void }
interface Entry<T> { promise: Promise<T>; controller: AbortController; users: number; settled: boolean }

/** Coalesce active readers; retain only a bounded number of completed idle resources. */
export class SharedResourceCache<T> {
  private entries = new Map<string, Entry<T>>();
  constructor(private readonly idleLimit: number, private readonly reuseSettled = true) {}
  acquire(key: string, load: (signal: AbortSignal) => Promise<T>): ResourceLease<T> {
    const cached = this.entries.get(key);
    let entry = cached && (this.reuseSettled || !cached.settled) ? cached : undefined;
    if (!entry) {
      const controller = new AbortController();
      const created: Entry<T> = { controller, users: 0, settled: false, promise: undefined! };
      created.promise = Promise.resolve().then(() => {
        controller.signal.throwIfAborted(); return load(controller.signal);
      }).then(value => { controller.signal.throwIfAborted(); created.settled = true; return value; }, error => {
        if (this.entries.get(key) === created) this.entries.delete(key);
        throw error;
      });
      this.entries.set(key, created); entry = created;
    }
    const current = entry;
    current.users++;
    this.entries.delete(key); this.entries.set(key, current);
    let released = false;
    return { promise: current.promise, release: () => {
      if (released) return; released = true; current.users--;
      if (current.users === 0 && !current.settled) {
        current.controller.abort();
        if (this.entries.get(key) === current) this.entries.delete(key);
      }
      const idle = [...this.entries].filter(([, value]) => value.users === 0);
      for (const [old] of idle.slice(0, Math.max(0, idle.length - this.idleLimit))) this.entries.delete(old);
    } };
  }
}
