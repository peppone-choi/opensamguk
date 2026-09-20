import { describe, it, expect, vi } from 'vitest';
import { SharedResourceCache } from '../iso/mapResourceCache';
import { ValidatedMapCache } from '../iso/validatedMapCache';
const deferred = <T,>() => { let resolve!: (v:T)=>void; let reject!: (e:Error)=>void; const promise=new Promise<T>((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject}; };
describe('map resources',()=>{
 it('shares concurrent consumers and cancels only after the final release',async()=>{
  const cache=new SharedResourceCache<number>(2);const work=deferred<number>();let signal!:AbortSignal;
  const loader=vi.fn((s:AbortSignal)=>{signal=s;return work.promise;});
  const a=cache.acquire('same',loader),b=cache.acquire('same',loader);await Promise.resolve();
  expect(loader).toHaveBeenCalledTimes(1); a.release();expect(signal.aborted).toBe(false);
  work.resolve(42);expect(await b.promise).toBe(42);b.release();
  const c=cache.acquire('same',loader);expect(await c.promise).toBe(42);c.release();expect(loader).toHaveBeenCalledTimes(1);
 });
 it('does not retain aborted or failed work and permits retries',async()=>{
  const cache=new SharedResourceCache<number>(2);const a=cache.acquire('a',s=>new Promise((_,reject)=>s.addEventListener('abort',()=>reject(new Error('abort')))));
  const failed=expect(a.promise).rejects.toThrow('abort');await Promise.resolve();a.release();await failed;
  const b=cache.acquire('a',async()=>7);expect(await b.promise).toBe(7);b.release();
  const c=cache.acquire('bad',async()=>{throw new Error('bad');});await expect(c.promise).rejects.toThrow('bad');c.release();
  const d=cache.acquire('bad',async()=>9);expect(await d.promise).toBe(9);d.release();
 });
 it('can revalidate new readers while an earlier completed reader remains mounted',async()=>{
  const cache=new SharedResourceCache<number>(0,false);let calls=0;
  const a=cache.acquire('same',async()=>++calls);expect(await a.promise).toBe(1);
  const b=cache.acquire('same',async()=>++calls);expect(await b.promise).toBe(2);
  a.release();b.release();
 });
 it('bounds retained idle entries and supports fresh per-mount requests',async()=>{
  const cache=new SharedResourceCache<number>(1);let calls=0;const loader=async()=>++calls;
  for(const key of ['a','b','a']){const lease=cache.acquire(key,loader);await lease.promise;lease.release();}expect(calls).toBe(3);
  const fresh=new SharedResourceCache<number>(0);
  for(let i=0;i<2;i++){const lease=fresh.acquire('same',loader);await lease.promise;lease.release();}expect(calls).toBe(5);
 });
});
describe('validated geometry',()=>{
 it('revalidates and reuses only the same actual ETag in the same scope',async()=>{
  const fetcher=vi.fn().mockResolvedValueOnce(new Response('{"v":1}',{headers:{ETag:'"one"'}}))
   .mockResolvedValueOnce(new Response(null,{status:304}))
   .mockResolvedValueOnce(new Response('{"v":2}',{headers:{ETag:'"two"'}}))
   .mockResolvedValueOnce(new Response('{"v":3}',{headers:{ETag:'"two"'}}));
  const cache=new ValidatedMapCache<{v:number}>(2,fetcher);const build=vi.fn(async(r:Response)=>r.json());const signal=new AbortController().signal;
  const a=await cache.load('server-a','/terrain?version=x',signal,build);
  expect(await cache.load('server-a','/terrain?version=x',signal,build)).toBe(a);
  expect(fetcher.mock.calls[1][1].headers['If-None-Match']).toBe('"one"');
  expect((await cache.load('server-a','/terrain?version=x',signal,build)).v).toBe(2);
  expect((await cache.load('server-b','/terrain?version=x',signal,build)).v).toBe(3);
  expect(build).toHaveBeenCalledTimes(3);
 });
 it('does not reuse geometry without a response ETag or on a changed URL',async()=>{
  const fetcher=vi.fn(async()=>new Response('{}')); const cache=new ValidatedMapCache<object>(2,fetcher);
  const build=vi.fn(async()=>({})),signal=new AbortController().signal;
  const a=await cache.load('s','/a',signal,build),b=await cache.load('s','/a',signal,build);
  expect(a).not.toBe(b);await cache.load('s','/b',signal,build);expect(build).toHaveBeenCalledTimes(3);
 });
 it('does not publish a completed build after cancellation',async()=>{
  const fetcher=vi.fn(async()=>new Response('{}',{headers:{ETag:'"one"'}}));const cache=new ValidatedMapCache<object>(2,fetcher);
  const controller=new AbortController();const pending=deferred<object>();const task=cache.load('s','/a',controller.signal,()=>pending.promise);
  await Promise.resolve();controller.abort();pending.resolve({});await expect(task).rejects.toThrow();
  const build=vi.fn(async()=>({}));await cache.load('s','/a',new AbortController().signal,build);expect(build).toHaveBeenCalledTimes(1);
 });
});
