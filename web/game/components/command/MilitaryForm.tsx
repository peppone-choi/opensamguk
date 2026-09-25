'use client';
import { useEffect, useRef, useState } from 'react';
import { api } from '../../lib/api';
import { submitCommandAndAwaitResult } from '../../lib/commandSubmit';
import type { HwihaMilitaryActionId, HwihaMilitaryOptions } from '../../lib/types';

export const militaryLabels: Record<HwihaMilitaryActionId, string> = {
    'action.conscript': '징병', 'action.raiseVolunteers': '모병', 'action.train': '훈련',
    'action.boostMorale': '사기진작', 'action.muster': '집합', 'action.demobilize': '소집해제',
};
export function isMilitaryActionId(value: string): value is HwihaMilitaryActionId { return value in militaryLabels; }

export default function HwihaMilitaryForm({inputId,generalId,turnIdx,refreshKey,unavailable,onToast,onClose,onReserved}: {
    inputId:HwihaMilitaryActionId;generalId:number;turnIdx:number;refreshKey?:number;unavailable:boolean;
    onToast:(message:string,type:'success'|'error'|'info')=>void;onClose:()=>void;onReserved?:()=>void;
}) {
    const [data,setData]=useState<HwihaMilitaryOptions|null>(null);
    const [reason,setReason]=useState<string|null>(null);
    const [busy,setBusy]=useState(false);
    const generation=useRef(0);const submitting=useRef(false);
    const validTurn=Number.isInteger(turnIdx)&&turnIdx>=0&&turnIdx<12;
    useEffect(()=>{
        const current=++generation.current;
        submitting.current=false;setBusy(false);setData(null);setReason(null);
        if(!unavailable&&validTurn) api.militaryOptions(inputId,generalId).then(value=>{
            if(generation.current!==current)return;
            if(value.inputId!==inputId){setReason('군사 행동 정보를 확인하지 못했습니다.');return;}
            setData(value);
        }).catch(()=>{if(generation.current===current)setReason('군사 행동 정보를 불러오지 못했습니다.');});
        return ()=>{generation.current=current+1;};
    },[inputId,generalId,turnIdx,refreshKey,unavailable,validTurn]);
    if(unavailable)return <p role="status">본인 장수의 군사 행동만 예약할 수 있습니다.</p>;
    if(!validTurn)return <p role="status">군사 행동은 1~12순에만 예약할 수 있습니다.</p>;
    async function reserve(){
        if(!data?.available||submitting.current)return;
        submitting.current=true;setBusy(true);setReason(null);const current=generation.current;
        try{
            const result=await submitCommandAndAwaitResult(()=>api.command(inputId,{},generalId,turnIdx));
            if(current!==generation.current)return;
            if(result.status==='reserved'||result.status==='applied'){
                onToast(result.status==='reserved'?`${militaryLabels[inputId]} 명령이 예약되었습니다.`:`${militaryLabels[inputId]} 명령이 실행되었습니다.`,'success');
                onReserved?.();onClose();
            }else setReason(result.reason??`${militaryLabels[inputId]}을 예약할 수 없습니다.`);
        }catch(error){if(current===generation.current)setReason(error instanceof Error?error.message:'군사 행동 예약에 실패했습니다.');}
        finally{if(current===generation.current){submitting.current=false;setBusy(false);}}
    }
    return <div className="cmd-form"><h3>{militaryLabels[inputId]}</h3>
        <p>{inputId==='action.muster'?'지휘 중인 부곡을 장수의 현재 省으로 집결시킵니다.':'장수가 현재 서 있는 縣의 도시 소유 병력을 다룹니다.'}</p>
        {!data&&!reason&&<p role="status">군사 행동 정보를 불러오는 중입니다.</p>}
        {data&&!data.available&&<p role="status">{data.reason??'현재 실행할 수 없습니다.'}</p>}
        {data?.available&&<p>{inputId==='action.muster'?`집결 대상 부곡: ${data.gatheringCorps??0}`:`대상 縣: ${data.countyName} · 도시 병력 ${data.troops??0}`}</p>}
        {data?.available&&inputId!=='action.muster'&&<p>
            도시 병력 {data.troops??0} → {data.troopsAfter??data.troops??0} · 호구 {data.populationAfter??'—'} ·
            훈련 {data.training??0} → {data.trainingAfter??data.training??0} ·
            사기 {data.morale??0} → {data.moraleAfter??data.morale??0}
            {(data.grainCost??0)>0?` · 곡물 ${data.grainCost} 소모`:''}
            {(data.moneyCost??0)>0?` · 전 ${data.moneyCost} 소모`:''}
        </p>}
        {reason&&<p role="alert">{reason}</p>}
        <button type="button" className="cmd-submit os-button os-button--primary" disabled={busy||!data?.available} onClick={()=>void reserve()}>
            {busy?'처리 중...':`${militaryLabels[inputId]} 예약`}
        </button>
    </div>;
}
