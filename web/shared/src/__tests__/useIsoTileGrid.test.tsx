import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
let useIsoTileGrid: typeof import('../iso/useIsoTileGrid').useIsoTileGrid;
const tiles = { _meta:{cols:768,rows:669,year:190}, terrain:Array(669).fill('1'.repeat(768)),owner:[[0,768*669]],cities:[],provinceRecords:[],parentRegions:[] };
beforeEach(async()=>{
 vi.resetModules();
 ({useIsoTileGrid}=await import('../iso/useIsoTileGrid'));
 vi.stubGlobal('createImageBitmap',vi.fn(async()=>({width:384,height:334,close(){}})));
 vi.spyOn(HTMLCanvasElement.prototype,'getContext').mockReturnValue({drawImage(){},getImageData:()=>({data:new Uint8ClampedArray(384*334*4),width:384,height:334})} as unknown as CanvasRenderingContext2D);
});
afterEach(()=>{cleanup();vi.restoreAllMocks();vi.unstubAllGlobals();document.cookie='sam_server=; max-age=0';});
it('renders without waiting for attribution, then preserves grid identity when it arrives',async()=>{
 let attribution!: (r:Response)=>void;
 const fetcher=vi.fn((url:string)=>/manifest(?:-legacy)?\.json$/.test(url) ? new Promise<Response>(resolve=>{attribution=resolve;})
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
 const fetcher=vi.fn(async(url:string)=>/manifest(?:-legacy)?\.json$/.test(url)?new Response('{}'):url.includes('levels.png')?new Response(new Uint8Array([1])):new Response(JSON.stringify(tiles),{headers:{ETag:'"shared"'}}));
 vi.stubGlobal('fetch',fetcher);
 const a=renderHook(()=>useIsoTileGrid('/terrain?shared')),b=renderHook(()=>useIsoTileGrid('/terrain?shared'));
 a.unmount(); await waitFor(()=>expect(b.result.current).toMatchObject({status:'ready',error:null}));
 expect(fetcher.mock.calls.filter(([url])=>url==='/terrain?shared')).toHaveLength(1);
});
it('revalidates on remount and reuses preparation only within the same server',async()=>{
 const fetcher=vi.fn(async(url:string)=>/manifest(?:-legacy)?\.json$/.test(url)?new Response('{}'):url.includes('levels.png')?new Response(new Uint8Array([1])):new Response(JSON.stringify(tiles),{headers:{ETag:'"remount"'}}));
 vi.stubGlobal('fetch',fetcher);document.cookie='sam_server=a';
 const first=renderHook(()=>useIsoTileGrid('/terrain?remount'));await waitFor(()=>expect(first.result.current.status).toBe('ready'));
 const grid=first.result.current.data!.grid;first.unmount();
 const second=renderHook(()=>useIsoTileGrid('/terrain?remount'));await waitFor(()=>expect(second.result.current.status).toBe('ready'));
 expect(second.result.current.data!.grid === grid).toBe(true);second.unmount();
 document.cookie='sam_server=b';const third=renderHook(()=>useIsoTileGrid('/terrain?remount'));await waitFor(()=>expect(third.result.current.status).toBe('ready'));
 expect(third.result.current.data!.grid === grid).toBe(false);
 expect(fetcher.mock.calls.filter(([url])=>url==='/terrain?remount')).toHaveLength(3);
});
it('selects separate DEM and attribution assets when switching between saved and expanded worlds',async()=>{
 const expanded={...tiles,_meta:{...tiles._meta,cols:864,rows:843},terrain:Array(843).fill('1'.repeat(864)),owner:[[0,864*843]]};
 vi.stubGlobal('createImageBitmap',vi.fn(async(blob:Blob)=>({width:blob.type==='image/legacy'?384:432,height:blob.type==='image/legacy'?334:421,close(){}})));
 vi.mocked(HTMLCanvasElement.prototype.getContext).mockReturnValue({drawImage(){},getImageData:(_x:number,_y:number,width:number,height:number)=>({data:new Uint8ClampedArray(width*height*4),width,height})} as unknown as CanvasRenderingContext2D);
 let newAttribution!: (r:Response)=>void;
 const fetcher=vi.fn(async(url:string)=>{
  if(url.endsWith('/manifest-legacy.json'))return new Response('{"dataset":{"title":"legacy"}}');
  if(url.endsWith('/manifest.json'))return new Promise<Response>(resolve=>{newAttribution=resolve;});
  if(url.includes('levels.png'))return new Response(new Uint8Array([1]),{headers:{'Content-Type':url.includes('legacy')?'image/legacy':'image/current'}});
  return new Response(JSON.stringify(url.includes('expanded')?expanded:tiles),{headers:{ETag:url}});
 });
 vi.stubGlobal('fetch',fetcher);
 const hook=renderHook(({url})=>useIsoTileGrid(url),{initialProps:{url:'/terrain?legacy'}});
 await waitFor(()=>expect(hook.result.current.data?.elevation?.dataset?.title).toBe('legacy'));
 expect(hook.result.current.data!.grid.cols).toBe(384);
 hook.rerender({url:'/terrain?expanded'});
 await waitFor(()=>expect(hook.result.current.data?.sourceCols).toBe(864));
 expect(hook.result.current.data!.grid.cols).toBe(432);
 expect(hook.result.current.data!.elevation).toBeNull();
 await act(async()=>{newAttribution(new Response('{"dataset":{"title":"expanded"}}'));});
 await waitFor(()=>expect(hook.result.current.data?.elevation?.dataset?.title).toBe('expanded'));
 for(const url of ['/map/elevation/han-world-v3-legacy-levels.png','/map/elevation/han-world-v3-levels.png'])expect(fetcher.mock.calls.filter(([u])=>u===url)).toHaveLength(1);
});
