'use client';
import {useEffect,useRef,useState} from 'react';
import {api} from '../../lib/api';
import {submitCommandAndAwaitResult} from '../../lib/commandSubmit';
import type {HwihaPoliticalActionId,HwihaPoliticalOption,HwihaPoliticalConsentOption} from '../../lib/types';

export const politicalLabels:Record<HwihaPoliticalActionId,string>={
    'action.resign':'하야','action.rise':'거병','action.foundState':'건국',
    'action.independence':'독립','action.dissolve':'세력 해산',
    'action.abdicate':'선양','action.oath':'결의',
};
export function isPoliticalActionId(value:string):value is HwihaPoliticalActionId{return value in politicalLabels;}

export default function HwihaPoliticalForm({inputId,generalId,turnIdx,refreshKey,unavailable,onToast,onClose,onReserved}:{
    inputId:HwihaPoliticalActionId;generalId:number;turnIdx:number;refreshKey?:number;unavailable:boolean;
    onToast:(message:string,type:'success'|'error'|'info')=>void;onClose:()=>void;onReserved?:()=>void;
}){
    const [data,setData]=useState<HwihaPoliticalOption|null>(null);
    const [consents,setConsents]=useState<HwihaPoliticalConsentOption[]>([]);
    const [targetId,setTargetId]=useState<number|null>(null);
    const [reason,setReason]=useState<string|null>(null);
    const [busy,setBusy]=useState(false);
    const generation=useRef(0);const submitting=useRef(false);
    const validTurn=Number.isInteger(turnIdx)&&turnIdx>=0&&turnIdx<12;
    useEffect(()=>{
        const current=++generation.current;submitting.current=false;setBusy(false);setData(null);setReason(null);setConsents([]);setTargetId(null);
        if(!unavailable&&validTurn)api.politicalOptions(generalId).then(options=>{
            if(generation.current!==current)return;
            const option=options.find(entry=>entry.inputId===inputId);
            if(!option){setReason('정치 행동 정보를 확인하지 못했습니다.');return;}
            setData(option);setTargetId(option.targets?.find(entry=>entry.available)?.generalId??null);
        }).catch(()=>{if(generation.current===current)setReason('정치 행동 정보를 불러오지 못했습니다.');});
        if(inputId==='action.abdicate'||inputId==='action.oath')api.politicalConsentOptions(generalId).then(options=>{
            if(generation.current===current)setConsents(options.filter(entry=>entry.inputId===inputId&&entry.available));
        }).catch(()=>{if(generation.current===current)setReason('동의 대상 정보를 불러오지 못했습니다.');});
        return()=>{generation.current=current+1;};
    },[inputId,generalId,turnIdx,refreshKey,unavailable,validTurn]);
    if(unavailable)return <p role="status">본인 장수의 정치 행동만 예약할 수 있습니다.</p>;
    if(!validTurn)return <p role="status">정치 행동은 1~12순에만 예약할 수 있습니다.</p>;
    async function reserve(){
        if(!data?.available||submitting.current)return;
        if((inputId==='action.abdicate'||inputId==='action.oath')&&targetId==null)return;
        submitting.current=true;setBusy(true);setReason(null);const current=generation.current;
        try{
            const args=targetId==null?{}:{targetGeneralId:targetId};
            const result=await submitCommandAndAwaitResult(()=>api.command(inputId,args,generalId,turnIdx));
            if(current!==generation.current)return;
            if(result.status==='reserved'||result.status==='applied'){
                onToast(result.status==='reserved'?`${politicalLabels[inputId]} 명령이 예약되었습니다.`:`${politicalLabels[inputId]} 명령이 실행되었습니다.`,'success');
                onReserved?.();onClose();
            }else setReason(result.reason??`${politicalLabels[inputId]} 명령을 예약할 수 없습니다.`);
        }catch(error){if(current===generation.current)setReason(error instanceof Error?error.message:'정치 행동 예약에 실패했습니다.');}
        finally{if(current===generation.current){submitting.current=false;setBusy(false);}}
    }
    async function respond(issuerGeneralId:number,accepted:boolean){
        if(submitting.current||!(inputId==='action.abdicate'||inputId==='action.oath'))return;
        submitting.current=true;setBusy(true);setReason(null);const current=generation.current;
        try{
            const result=await submitCommandAndAwaitResult(()=>api.courtPoliticalConsent(generalId,{issuerGeneralId,inputId,accepted}));
            if(current!==generation.current)return;
            if(result.status==='reserved'||result.status==='applied'){
                onToast(accepted?'동의를 전달했습니다.':'거절을 전달했습니다.','success');onReserved?.();onClose();
            }else setReason(result.reason??'동의 응답을 보낼 수 없습니다.');
        }catch(error){if(current===generation.current)setReason(error instanceof Error?error.message:'동의 응답에 실패했습니다.');}
        finally{if(current===generation.current){submitting.current=false;setBusy(false);}}
    }
    return <div className="cmd-form"><h3>{politicalLabels[inputId]}</h3>
        <p>{inputId==='action.resign'?'현재 섬기는 세력을 떠나 휘하와 함께 재야가 됩니다.':
            inputId==='action.rise'?'현재 무주 縣에서 새 세력을 일으킵니다.':
            inputId==='action.foundState'?'기존 세력을 국가로 선포합니다.':
            inputId==='action.independence'?'현재 縣을 기반으로 섬기던 세력에서 독립합니다.':
            inputId==='action.abdicate'?'수락한 같은 세력 장수에게 주공 자리를 넘깁니다.':
            inputId==='action.oath'?'현재 省의 수락한 장수와 결의합니다.':
            '본인 세력을 해산하고 소유한 縣과 장수를 재야로 돌립니다.'}</p>
        {(inputId==='action.abdicate'||inputId==='action.oath')&&<>
            <label>대상 장수<select aria-label="대상 장수" value={targetId??''} onChange={e=>setTargetId(Number(e.target.value)||null)}>
                <option value="">선택</option>{data?.targets?.map(target=><option key={target.generalId} value={target.generalId} disabled={!target.available}>{target.name}{target.available?'':` — ${target.reason??'불가'}`}</option>)}
            </select></label>
            {consents.length>0&&<section aria-label="동의 요청"><p>본인이 대상인 제안에 응답</p>{consents.map(entry=><div key={`${entry.inputId}-${entry.issuerGeneralId}`}>
                <span>{entry.issuerName}{entry.accepted===true?' (수락함)':entry.accepted===false?' (거절함)':''}</span>
                <button type="button" disabled={busy} onClick={()=>void respond(entry.issuerGeneralId,true)}>수락</button>
                <button type="button" disabled={busy} onClick={()=>void respond(entry.issuerGeneralId,false)}>거절</button>
            </div>)}</section>}
        </>}
        {!data&&!reason&&<p role="status">정치 행동 정보를 불러오는 중입니다.</p>}
        {data&&!data.available&&<p role="status">{data.reason??'현재 실행할 수 없습니다.'}</p>}
        {reason&&<p role="alert">{reason}</p>}
        <button type="button" className="cmd-submit os-button os-button--primary" disabled={busy||!data?.available||((inputId==='action.abdicate'||inputId==='action.oath')&&targetId==null)}
            onClick={()=>void reserve()}>{busy?'처리 중...':`${politicalLabels[inputId]} 예약`}</button>
    </div>;
}
