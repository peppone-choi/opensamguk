import { SharedResourceCache } from './mapResourceCache';
const sprites = new SharedResourceCache<HTMLImageElement>(128);

export function acquireMapSprite(url: string) {
  return sprites.acquire(url, signal => new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new Image();
    const cleanup = () => { image.onload = null; image.onerror = null; signal.removeEventListener('abort', abort); };
    const abort = () => { cleanup(); image.src = ''; reject(new DOMException('Aborted', 'AbortError')); };
    image.decoding = 'async';
    image.onload = () => {
      image.decode().then(() => { cleanup(); resolve(image); }, error => { cleanup(); reject(error); });
    };
    image.onerror = () => { cleanup(); reject(new Error(`${url} 를 못 불러왔다`)); };
    signal.addEventListener('abort', abort, { once: true });
    image.src = url;
  }));
}
