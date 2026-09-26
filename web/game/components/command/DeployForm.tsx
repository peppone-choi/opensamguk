'use client';
import { useEffect, useRef, useState } from 'react';
import { api } from '../../lib/api';
import { submitCommandAndAwaitResult } from '../../lib/commandSubmit';
import type { DeployOptions } from '../../lib/types';

export default function DeployForm({generalId,turnIdx,refreshKey,unavailable,onToast,onClose,onReserved}: {
    generalId:number; turnIdx:number; refreshKey?:number; unavailable:boolean;
    onToast:(message:string,type:'success'|'error'|'info')=>void; onClose:()=>void; onReserved?:()=>void;
}) {
    const [data,setData]=useState<DeployOptions|null>(null);
    const [selected,setSelected]=useState<number[]>([]);
    const [destination,setDestination]=useState('');
    const [reason,setReason]=useState<string|null>(null);
    const [busy,setBusy]=useState(false);
    const generation=useRef(0); const submitting=useRef(false);
    const stopLabels: Record<string,string>={ARRIVED:'도착',ENCOUNTER:'조우 중단',EDGE_BLOCKED:'통행로 폐쇄',ENCOUNTER_UNAVAILABLE:'진입 상태 확인 불가',BUDGET_EXHAUSTED:'행군 중'};
    const validTurn=Number.isInteger(turnIdx)&&turnIdx>=0&&turnIdx<12;
    useEffect(()=>{
        const current=++generation.current;
        submitting.current=false;setBusy(false);setData(null);setSelected([]);setDestination('');setReason(null);
        if(!unavailable&&validTurn) api.deployOptions(generalId).then(value=>{
            if(generation.current!==current)return;
            if(value.maxReservedTurns!==12){setReason('출병 정보를 확인하지 못했습니다.');return;}
            setData(value);
        }).catch(()=>{if(generation.current===current)setReason('출병 정보를 불러오지 못했습니다.');});
        return ()=>{generation.current++;};
    },[generalId,turnIdx,refreshKey,unavailable,validTurn]);
    if(unavailable)return <p role="status">본인 출병만 한 건씩 예약할 수 있습니다.</p>;
    if(!validTurn)return <p role="status">출병은 1~12순에만 예약할 수 있습니다.</p>;
    const eligible=!!data?.available&&!data.order&&selected.length>0&&selected.every(id=>data.bugoks.some(b=>b.id===id&&b.available))&&data.destinations.some(d=>d.provinceId===destination);
    async function reserve(){
        if(!eligible||submitting.current)return;
        submitting.current=true;setBusy(true);setReason(null);const current=generation.current;
        try{
            const result=await submitCommandAndAwaitResult(()=>api.command('action.deploy',{bugokIds:[...selected].sort((a,b)=>a-b),destinationProvinceId:destination},generalId,turnIdx));
            if(current!==generation.current)return;
            if(result.status==='reserved'||result.status==='applied'){
                onToast(result.status==='reserved'?'출병 명령이 예약되었습니다.':'출병 명령이 실행되었습니다.','success');onReserved?.();onClose();
            }else setReason(result.reason??'출병을 예약할 수 없습니다.');
        }catch(error){if(current===generation.current)setReason(error instanceof Error?error.message:'출병 예약에 실패했습니다.');}
        finally{if(current===generation.current){submitting.current=false;setBusy(false);}}
    }
    return <div className="cmd-form"><h3>출병</h3><p>본인이 지휘할 부대와 목적지를 선택합니다. 실행할 때 조건을 다시 확인합니다.</p>
        {!data&&!reason&&<p role="status">출병 정보를 불러오는 중입니다.</p>}
        {data?.order&&<p role="status">현재 출병 명령: {data.order.orderId} · 목적지 {data.destinations.find(d=>d.provinceId===data.order?.destinationProvinceId)?.name??data.order.destinationProvinceId}{data.order.stop?` · ${stopLabels[data.order.stop]??'상태 확인 필요'}`:''}. 출전 중에는 새 출병을 예약할 수 없습니다.</p>}
        {data&&!data.available&&<p role="status">{data.reason??'현재 출병할 수 없습니다.'}</p>}
        {data&&<><fieldset disabled={busy||!data.available||!!data.order}><legend>출병 부대</legend>
            {data.bugoks.length===0&&<p>출병할 수 있는 부대가 없습니다.</p>}
            {data.bugoks.map(b=><label key={b.id}><input type="checkbox" checked={selected.includes(b.id)} disabled={!b.available} onChange={e=>setSelected(ids=>e.target.checked?[...ids,b.id]:ids.filter(id=>id!==b.id))}/>{b.name} · {b.troops.toLocaleString()}명{!b.available?` — ${b.reason??'출병 불가'}`:''}</label>)}
        </fieldset><label>출병 목적지<select className="os-inset" aria-label="출병 목적지" value={destination} disabled={busy||!data.available||!!data.order} onChange={e=>setDestination(e.target.value)}><option value="">선택하세요</option>{data.destinations.map(d=><option key={d.provinceId} value={d.provinceId}>{d.name}</option>)}</select></label></>}
        {reason&&<p role="alert">{reason}</p>}
        <button type="button" className="cmd-submit os-button os-button--primary" disabled={busy||!eligible} onClick={()=>void reserve()}>{busy?'처리 중...':'출병 예약'}</button>
    </div>;
}
