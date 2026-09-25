'use client';
import {useEffect,useRef,useState} from 'react';
import {api} from '../../lib/api';
import {submitCommandAndAwaitResult} from '../../lib/commandSubmit';
import type {DirectActionActionId,DirectActionOptions} from '../../lib/types';

export const legacyDirectLabels:Record<DirectActionActionId,string>={
    'action.convertProficiency':'숙련전환','action.tradeEquipment':'장비매매',
    'action.tradeGrain':'군량매매','action.transport':'물자조달',
};
export function isLegacyDirectActionId(value:string):value is DirectActionActionId{return value in legacyDirectLabels;}

export default function DirectActionForm({inputId,generalId,turnIdx,refreshKey,unavailable,onToast,onClose,onReserved}:{
    inputId:DirectActionActionId;generalId:number;turnIdx:number;refreshKey?:number;unavailable:boolean;
    onToast:(message:string,type:'success'|'error'|'info')=>void;onClose:()=>void;onReserved?:()=>void;
}){
    const [data,setData]=useState<DirectActionOptions|null>(null);
    const [choiceIndex,setChoiceIndex]=useState(-1);
    const [amount,setAmount]=useState(1);
    const [reason,setReason]=useState<string|null>(null);
    const [busy,setBusy]=useState(false);
    const generation=useRef(0);const submitting=useRef(false);
    const validTurn=Number.isInteger(turnIdx)&&turnIdx>=0&&turnIdx<12;
    useEffect(()=>{
        const current=++generation.current;submitting.current=false;setBusy(false);setData(null);setChoiceIndex(-1);setAmount(1);setReason(null);
        if(!unavailable&&validTurn)api.legacyDirectOptions(inputId,generalId).then(options=>{
            if(generation.current!==current)return;
            setData(options);setChoiceIndex(options.choices.findIndex(choice=>choice.available));
        }).catch(()=>{if(generation.current===current)setReason('직접 행동 정보를 불러오지 못했습니다.');});
        return()=>{generation.current=current+1;};
    },[inputId,generalId,turnIdx,refreshKey,unavailable,validTurn]);
    if(unavailable)return <p role="status">본인 장수의 직접 행동만 예약할 수 있습니다.</p>;
    if(!validTurn)return <p role="status">직접 행동은 1~12순에만 예약할 수 있습니다.</p>;
    const choice=data?.choices[choiceIndex];
    const validAmount=inputId!=='action.transport'||(Number.isInteger(amount)&&amount>=1&&amount<=(choice?.maxAmount??0));
    async function reserve(){
        if(!choice?.available||!validAmount||submitting.current)return;
        submitting.current=true;setBusy(true);setReason(null);const current=generation.current;
        try{
            const args=inputId==='action.transport'?{...choice.arguments,amount}:choice.arguments;
            const result=await submitCommandAndAwaitResult(()=>api.command(inputId,args,generalId,turnIdx));
            if(current!==generation.current)return;
            if(result.status==='reserved'||result.status==='applied'){
                onToast(result.status==='reserved'?`${legacyDirectLabels[inputId]} 명령이 예약되었습니다.`:`${legacyDirectLabels[inputId]} 명령이 실행되었습니다.`,'success');
                onReserved?.();onClose();
            }else setReason(result.reason??`${legacyDirectLabels[inputId]} 명령을 예약할 수 없습니다.`);
        }catch(error){if(current===generation.current)setReason(error instanceof Error?error.message:'직접 행동 예약에 실패했습니다.');}
        finally{if(current===generation.current){submitting.current=false;setBusy(false);}}
    }
    return <div className="cmd-form"><h3>{legacyDirectLabels[inputId]}</h3>
        {!data&&!reason&&<p role="status">직접 행동 정보를 불러오는 중입니다.</p>}
        {data&&<label>선택<select aria-label="거래·전환 선택" value={choiceIndex} onChange={event=>{setChoiceIndex(Number(event.target.value));setAmount(1);}}>
            {data.choices.map((entry,index)=><option key={index} value={index} disabled={!entry.available}>
                {entry.label}{entry.available?'':` — ${entry.reason??'불가'}`}
            </option>)}
        </select></label>}
        {inputId==='action.transport'&&choice?.available&&<label>운반량
            <input aria-label="운반량" type="number" min={1} max={choice.maxAmount??1000} step={1}
                value={amount} onChange={event=>setAmount(Number(event.target.value))}/>
            <small>한 순 최대 {choice.maxAmount??1000}</small>
        </label>}
        {data&&!data.available&&<p role="status">{data.reason??'현재 실행할 수 없습니다.'}</p>}
        {reason&&<p role="alert">{reason}</p>}
        <button type="button" className="cmd-submit os-button os-button--primary" disabled={busy||!choice?.available||!validAmount}
            onClick={()=>void reserve()}>{busy?'처리 중...':`${legacyDirectLabels[inputId]} 예약`}</button>
    </div>;
}
