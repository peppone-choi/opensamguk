'use client';
import { useEffect, useRef, useState } from 'react';
import { api } from '../../lib/api';
import { submitCommandAndAwaitResult } from '../../lib/commandSubmit';
import type { FieldActionId, FieldOptions } from '../../lib/types';

export const fieldLabels: Record<FieldActionId, string> = {
    'action.farm': '농지개간', 'action.commerce': '상업투자', 'action.fortify': '수비강화',
    'action.repairWall': '성벽보수', 'action.security': '치안강화', 'action.settle': '정착장려',
    'action.selectResidents': '주민선정', 'action.tour': '순행',
};
export function isFieldActionId(value: string): value is FieldActionId { return value in fieldLabels; }

export default function FieldForm({inputId,generalId,turnIdx,refreshKey,unavailable,onToast,onClose,onReserved}: {
    inputId:FieldActionId;generalId:number;turnIdx:number;refreshKey?:number;unavailable:boolean;
    onToast:(message:string,type:'success'|'error'|'info')=>void;onClose:()=>void;onReserved?:()=>void;
}) {
    const [data,setData]=useState<FieldOptions|null>(null);
    const [reason,setReason]=useState<string|null>(null);
    const [busy,setBusy]=useState(false);
    const generation=useRef(0);const submitting=useRef(false);
    const validTurn=Number.isInteger(turnIdx)&&turnIdx>=0&&turnIdx<12;
    useEffect(()=>{
        const current=++generation.current;
        submitting.current=false;setBusy(false);setData(null);setReason(null);
        if(!unavailable&&validTurn) api.fieldOptions(inputId,generalId).then(value=>{
            if(generation.current!==current)return;
            if(value.inputId!==inputId){setReason('현장 행동 정보를 확인하지 못했습니다.');return;}
            setData(value);
        }).catch(()=>{if(generation.current===current)setReason('현장 행동 정보를 불러오지 못했습니다.');});
        return ()=>{generation.current=current+1;};
    },[inputId,generalId,turnIdx,refreshKey,unavailable,validTurn]);
    if(unavailable)return <p role="status">본인 장수의 현장 행동만 예약할 수 있습니다.</p>;
    if(!validTurn)return <p role="status">현장 행동은 1~12순에만 예약할 수 있습니다.</p>;
    async function reserve(){
        if(!data?.available||submitting.current)return;
        submitting.current=true;setBusy(true);setReason(null);const current=generation.current;
        try{
            const result=await submitCommandAndAwaitResult(()=>api.command(inputId,{},generalId,turnIdx));
            if(current!==generation.current)return;
            if(result.status==='reserved'||result.status==='applied'){
                onToast(result.status==='reserved'?`${fieldLabels[inputId]} 명령이 예약되었습니다.`:`${fieldLabels[inputId]} 명령이 실행되었습니다.`,'success');
                onReserved?.();onClose();
            }else setReason(result.reason??`${fieldLabels[inputId]}을 예약할 수 없습니다.`);
        }catch(error){if(current===generation.current)setReason(error instanceof Error?error.message:'현장 행동 예약에 실패했습니다.');}
        finally{if(current===generation.current){submitting.current=false;setBusy(false);}}
    }
    return <div className="cmd-form"><h3>{fieldLabels[inputId]}</h3>
        <p>장수가 현재 서 있는 縣에 한 순의 효과가 적용됩니다. 해당 縣의 방침·공사 효과와 합산됩니다.</p>
        {!data&&!reason&&<p role="status">현장 행동 정보를 불러오는 중입니다.</p>}
        {data&&!data.available&&<p role="status">{data.reason??'현재 실행할 수 없습니다.'}</p>}
        {data?.available&&<p>현재 縣: {data.countyName} (실행 순에 위치·창고 재판정)</p>}
        {reason&&<p role="alert">{reason}</p>}
        <button type="button" className="cmd-submit os-button os-button--primary" disabled={busy||!data?.available} onClick={()=>void reserve()}>
            {busy?'처리 중...':`${fieldLabels[inputId]} 예약`}
        </button>
    </div>;
}
