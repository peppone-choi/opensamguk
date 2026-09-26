'use client';
import { useEffect, useRef, useState } from 'react';
import { api } from '../../lib/api';
import { submitCommandAndAwaitResult } from '../../lib/commandSubmit';
import type { TravelActionId, TravelOptions } from '../../lib/types';

const labels: Record<TravelActionId,string> = {
    'action.move':'이동', 'action.forcedMarch':'강행', 'action.return':'귀환',
};

export default function TravelForm({inputId,generalId,turnIdx,refreshKey,unavailable,onToast,onClose,onReserved}: {
    inputId:TravelActionId; generalId:number;turnIdx:number;refreshKey?:number;unavailable:boolean;
    onToast:(message:string,type:'success'|'error'|'info')=>void;onClose:()=>void;onReserved?:()=>void;
}) {
    const [data,setData]=useState<TravelOptions|null>(null);
    const [destination,setDestination]=useState('');
    const [reason,setReason]=useState<string|null>(null);
    const [busy,setBusy]=useState(false);
    const generation=useRef(0);const submitting=useRef(false);
    const validTurn=Number.isInteger(turnIdx)&&turnIdx>=0&&turnIdx<12;
    const returning=inputId==='action.return';
    useEffect(()=>{
        const current=++generation.current;
        submitting.current=false;setBusy(false);setData(null);setDestination('');setReason(null);
        if(!unavailable&&validTurn) api.travelOptions(inputId,generalId).then(value=>{
            if(generation.current!==current)return;
            if(value.inputId!==inputId){setReason('이동 정보를 확인하지 못했습니다.');return;}
            setData(value);
        }).catch(()=>{if(generation.current===current)setReason('이동 정보를 불러오지 못했습니다.');});
        return ()=>{generation.current=current+1;};
    },[inputId,generalId,turnIdx,refreshKey,unavailable,validTurn]);
    if(unavailable)return <p role="status">본인 장수의 이동만 예약할 수 있습니다.</p>;
    if(!validTurn)return <p role="status">이동은 1~12순에만 예약할 수 있습니다.</p>;
    const eligible=!!data?.available&&(returning||data.destinations.some(row=>row.provinceId===destination&&row.available));
    async function reserve(){
        if(!eligible||submitting.current)return;
        submitting.current=true;setBusy(true);setReason(null);const current=generation.current;
        try{
            const args=returning?{}:{destinationProvinceId:destination};
            const result=await submitCommandAndAwaitResult(()=>api.command(inputId,args,generalId,turnIdx));
            if(current!==generation.current)return;
            if(result.status==='reserved'||result.status==='applied'){
                onToast(result.status==='reserved'?`${labels[inputId]} 명령이 예약되었습니다.`:`${labels[inputId]} 명령이 실행되었습니다.`,'success');
                onReserved?.();onClose();
            }else setReason(result.reason??`${labels[inputId]}을 예약할 수 없습니다.`);
        }catch(error){if(current===generation.current)setReason(error instanceof Error?error.message:'이동 예약에 실패했습니다.');}
        finally{if(current===generation.current){submitting.current=false;setBusy(false);}}
    }
    return <div className="cmd-form"><h3>{labels[inputId]}</h3>
        <p>{returning?'발령된 근무 城이 속한 省으로 돌아갑니다.':inputId==='action.forcedMarch'?'한 순에 최대 45km를 이동합니다. 실제 거리 30km당 개인 피로가 10 오르고 사기가 5 내려갑니다.':'한 순에 최대 30km를 이동합니다.'} 실행 시 통행과 위치를 다시 확인합니다.</p>
        {!data&&!reason&&<p role="status">이동 정보를 불러오는 중입니다.</p>}
        {data&&!data.available&&<p role="status">{data.reason??'현재 이동할 수 없습니다.'}</p>}
        {data&&returning&&data.destinations[0]&&<p>귀환지: {data.destinations[0].name}</p>}
        {data&&!returning&&<label>목적 省<select className="os-inset" aria-label="목적 省" value={destination} disabled={busy||!data.available} onChange={event=>setDestination(event.target.value)}>
            <option value="">선택하세요</option>
            {data.destinations.map(row=><option key={row.provinceId} value={row.provinceId} disabled={!row.available}>{row.name}{row.available?'':` — ${row.reason??'이동 불가'}`}</option>)}
        </select></label>}
        {reason&&<p role="alert">{reason}</p>}
        <button type="button" className="cmd-submit os-button os-button--primary" disabled={busy||!eligible} onClick={()=>void reserve()}>{busy?'처리 중...':`${labels[inputId]} 예약`}</button>
    </div>;
}
