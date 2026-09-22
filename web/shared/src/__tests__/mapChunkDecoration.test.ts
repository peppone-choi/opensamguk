import {expect,it,vi} from 'vitest';
import {MapChunkCache} from '../iso/mapChunkCache';
it('reuses terrain and a single composite allocation across political revisions',()=>{
 const canvases:HTMLCanvasElement[]=[];
 const cache=new MapChunkCache(()=>{const c={width:0,height:0,getContext:()=>({setTransform(){},fillRect(){},drawImage(){}})} as unknown as HTMLCanvasElement;canvases.push(c);return c;});
 const terrain=vi.fn(()=>1),paint=vi.fn(()=>0),view={width:1000,height:600,panX:0,panY:0,scale:.02,dpr:1};
 let frame=cache.get(view,terrain)!;while(frame.pending)frame=cache.get(view,terrain)!;
 const terrainCalls=terrain.mock.calls.length,revision={};
 for(const chunk of frame.surfaces){const image=cache.decorate(chunk,revision,paint);expect(cache.decorate(chunk,revision,paint)).toBe(image);}
 const paints=paint.mock.calls.length;
 for(const chunk of frame.surfaces)cache.decorate(chunk,{},paint);
 expect(paint).toHaveBeenCalledTimes(paints*2);expect(terrain).toHaveBeenCalledTimes(terrainCalls);
 expect(canvases.reduce((sum,c)=>sum+c.width*c.height,0)).toBeLessThanOrEqual(8_388_608);
 cache.dispose();expect(canvases.every(c=>c.width===0&&c.height===0)).toBe(true);
});
