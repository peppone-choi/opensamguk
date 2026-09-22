/** URL/version hints never prove identity: revalidate against the server's ETag. */
export class ValidatedMapCache<T> {
  private prepared = new Map<string, { etag: string; value: T }>();
  constructor(private readonly limit: number, private readonly request: typeof fetch = (...args) => fetch(...args)) {}
  async load(scope: string, url: string, signal: AbortSignal, build: (response: Response) => Promise<T>): Promise<T> {
    const key = JSON.stringify([scope, url]);
    const previous = this.prepared.get(key);
    const response = await this.request(url, { signal, cache: 'no-cache',
      headers: previous ? { 'If-None-Match': previous.etag } : {} });
    signal.throwIfAborted();
    if (!response.ok && !(response.status === 304 && previous)) throw new Error(`지형을 못 받았다: ${response.status}`);
    const etag = response.headers.get('ETag');
    if (previous && (response.status === 304 || (etag !== null && etag === previous.etag))) {
      this.prepared.delete(key); this.prepared.set(key, previous); return previous.value;
    }
    const value = await build(response);
    signal.throwIfAborted();
    this.prepared.delete(key);
    if (etag) {
      this.prepared.set(key, { etag, value });
      while (this.prepared.size > this.limit) this.prepared.delete(this.prepared.keys().next().value!);
    }
    return value;
  }
}
