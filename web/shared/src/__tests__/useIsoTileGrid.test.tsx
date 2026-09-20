import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { useIsoTileGrid } from '../iso/useIsoTileGrid';
const tiles = { _meta:{cols:4,rows:4,year:190}, terrain:['1111','1111','1111','1111'],owner:[[0,16]],cities:[],provinceRecords:[],parentRegions:[] };
beforeEach(()=>{
 vi.stubGlobal('createImageBitmap',vi.fn(async()=>({width:2,height:2,close(){}})));
 vi.spyOn(HTMLCanvasElement.prototype,'getContext').mockReturnValue({drawImage(){},getImageData:()=>({data:new Uint8ClampedArray(16),width:2,height:2})} as unknown as CanvasRenderingContext2D);
});
afterEach(()=>{cleanup();vi.restoreAllMocks();vi.unstubAllGlobals();document.cookie='sam_server=; max-age=0';});
it('renders without waiting for attribution, then preserves grid identity when it arrives',async()=>{
 let attribution!: (r:Response)=>void;
 const fetcher=vi.fn((url:string)=>url.endsWith('/manifest.json') ? new Promise<Response>(resolve=>{attribution=resolve;})
  :Promise.resolve(url.includes('levels.png') ? new Response(new Uint8Array([1])) : new Response(JSON.stringify(tiles),{headers:{ETag:'"slow-manifest"'}})));
 vi.stubGlobal('fetch',fetcher);
 const hook=renderHook(()=>useIsoTileGrid('/terrain?slow-manifest'));
 await waitFor(()=>expect(hook.result.current.status).toBe('ready'));
 const grid=hook.result.current.data!.grid;
 await act(async()=>{attribution(new Response('{"dataset":{"title":"source"}}'));});
 await waitFor(()=>expect(hook.result.current.data?.elevation?.dataset?.title).toBe('source'));
 expect(hook.result.current.data!.grid).toBe(grid);
});
it('shares active geometry and survives one consumer unmounting',async()=>{
 const fetcher=vi.fn(async(url:string)=>url.endsWith('/manifest.json')?new Response('{}'):url.includes('levels.png')?new Response(new Uint8Array([1])):new Response(JSON.stringify(tiles),{headers:{ETag:'"shared"'}}));
 vi.stubGlobal('fetch',fetcher);
 const a=renderHook(()=>useIsoTileGrid('/terrain?shared')),b=renderHook(()=>useIsoTileGrid('/terrain?shared'));
 a.unmount(); await waitFor(()=>expect(b.result.current).toMatchObject({status:'ready',error:null}));
 expect(fetcher.mock.calls.filter(([url])=>url==='/terrain?shared')).toHaveLength(1);
});
it('revalidates on remount and reuses preparation only within the same server',async()=>{
 const fetcher=vi.fn(async(url:string)=>url.endsWith('/manifest.json')?new Response('{}'):url.includes('levels.png')?new Response(new Uint8Array([1])):new Response(JSON.stringify(tiles),{headers:{ETag:'"remount"'}}));
 vi.stubGlobal('fetch',fetcher);document.cookie='sam_server=a';
 const first=renderHook(()=>useIsoTileGrid('/terrain?remount'));await waitFor(()=>expect(first.result.current.status).toBe('ready'));
 const grid=first.result.current.data!.grid;first.unmount();
 const second=renderHook(()=>useIsoTileGrid('/terrain?remount'));await waitFor(()=>expect(second.result.current.status).toBe('ready'));
 expect(second.result.current.data!.grid).toBe(grid);second.unmount();
 document.cookie='sam_server=b';const third=renderHook(()=>useIsoTileGrid('/terrain?remount'));await waitFor(()=>expect(third.result.current.status).toBe('ready'));
 expect(third.result.current.data!.grid).not.toBe(grid);
 expect(fetcher.mock.calls.filter(([url])=>url==='/terrain?remount')).toHaveLength(3);
});
