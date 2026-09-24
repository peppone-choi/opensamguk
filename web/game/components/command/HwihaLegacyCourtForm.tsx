'use client';
import {useEffect,useRef,useState} from 'react';
import {api} from '../../lib/api';
import {submitCommandAndAwaitResult} from '../../lib/commandSubmit';
import type {HwihaLegacyCourtId,HwihaLegacyCourtOptions} from '../../lib/types';

const labels:Record<HwihaLegacyCourtId,string>={
    'court.releaseCorps':'부대 탈퇴 지시','court.diplomacy':'물자 원조',
    'court.abandonCounty':'현 포기','court.institution':'기술 연구',
    'court.moveCapital':'천도','court.confiscate':'몰수',
    'court.nonAggression':'불가침 제의','court.declareWar':'선전포고',
    'court.offerPeace':'종전 제의','court.breakNonAggression':'불가침 파기 제의',
};
const ids=Object.keys(labels) as HwihaLegacyCourtId[];

export default function HwihaLegacyCourtForm({generalId,refreshKey=0,onReserved}:{generalId:number;refreshKey?:number;onReserved?:()=>void}){
    const [inputId,setInputId]=useState<HwihaLegacyCourtId>('court.releaseCorps');
    const [options,setOptions]=useState<HwihaLegacyCourtOptions|null>(null);
    const [index,setIndex]=useState(-1);
    const [amount,setAmount]=useState(1);
    const [message,setMessage]=useState<string|null>(null);
    const [busy,setBusy]=useState(false);
    const [tracking,setTracking]=useState<string|null>(null);
    const generation=useRef(0);const submitting=useRef(false);
    useEffect(()=>{
        const current=++generation.current;setOptions(null);setIndex(-1);setAmount(1);setMessage(null);
        api.legacyCourtOptions(inputId,generalId).then(value=>{
            if(generation.current!==current)return;
            setOptions(value);setIndex(value.choices.findIndex(choice=>choice.available));
        }).catch(()=>{if(generation.current===current)setMessage('조정 선택지를 불러오지 못했습니다.');});
        return()=>{generation.current=current+1;};
    },[inputId,generalId,refreshKey]);
    useEffect(()=>{
        if(!tracking)return;
        let active=true;let timer:ReturnType<typeof setTimeout>;
        const check=async()=>{
            try{
                const result=await api.commandResult(tracking);
                if(!active)return;
                if(result.status==='RESOLVED'){
                    setMessage(result.ok?'조정 결정이 실행되었습니다.':result.reason??'결정을 실행할 수 없습니다.');
                    setTracking(null);onReserved?.();return;
                }
            }catch{/* accepted request remains queued */}
            if(active)timer=setTimeout(check,3000);
        };
        timer=setTimeout(check,3000);
        return()=>{active=false;clearTimeout(timer);};
    },[tracking,onReserved]);
    const choice=options?.choices[index];
    const resource=inputId==='court.confiscate'||inputId==='court.diplomacy';
    const validAmount=!resource||(Number.isInteger(amount)&&amount>0&&amount<=(choice?.maxAmount??0));
    async function submit(){
        if(!choice?.available||!validAmount||submitting.current||tracking)return;
        submitting.current=true;setBusy(true);setMessage(null);
        let acceptedId:string|null=null;
        try{
            const args=resource?{...choice.arguments,amount}:choice.arguments;
            const result=await submitCommandAndAwaitResult(async()=>{
                const accepted=await api.courtLegacy(inputId,generalId,args);
                if('requestId' in accepted&&typeof accepted.requestId==='string')acceptedId=accepted.requestId;
                return accepted;
            });
            if(result.status==='rejected')setMessage(result.reason??'결정을 접수할 수 없습니다.');
            else if(result.status==='applied'){setMessage('조정 결정이 실행되었습니다.');onReserved?.();}
            else{setMessage('접수되었습니다. 결정권자의 다음 개인 턴에 실행합니다.');setTracking(acceptedId);onReserved?.();}
        }catch(error){
            if(acceptedId){setTracking(acceptedId);setMessage('접수된 결정의 결과를 확인하고 있습니다.');}
            else setMessage(error instanceof Error?error.message:'조정 결정을 접수할 수 없습니다.');
        }finally{submitting.current=false;setBusy(false);}
    }
    return <section aria-labelledby="legacy-court-title">
        <h3 id="legacy-court-title">조정 결정</h3>
        <label>명령<select className="os-inset" aria-label="조정 명령" value={inputId}
            onChange={event=>setInputId(event.target.value as HwihaLegacyCourtId)} disabled={busy||!!tracking}>
            {ids.map(id=><option key={id} value={id}>{labels[id]}</option>)}
        </select></label>
        {options&&<label>대상<select className="os-inset" aria-label="조정 대상" value={index}
            onChange={event=>{setIndex(Number(event.target.value));setAmount(1);}} disabled={busy||!!tracking}>
            {options.choices.map((row,i)=><option key={i} value={i} disabled={!row.available}>
                {row.label}{row.available?'':` — ${row.reason??'실행 불가'}`}
            </option>)}
        </select></label>}
        {resource&&choice?.available&&<label>수량<input aria-label="조정 수량" type="number" min={1}
            max={choice.maxAmount??undefined} step={1} value={amount} disabled={busy||!!tracking}
            onChange={event=>setAmount(Number(event.target.value))}/></label>}
        {!options&&!message&&<p role="status">조정 선택지를 불러오는 중입니다.</p>}
        {options&&!options.available&&<p role="status">{options.reason??'현재 실행할 수 없습니다.'}</p>}
        {message&&<p role="status">{message}</p>}
        <button className="os-button os-button--primary" type="button"
            disabled={busy||!!tracking||!choice?.available||!validAmount} onClick={()=>void submit()}>
            {busy?'접수 중…':`${labels[inputId]} 접수`}
        </button>
    </section>;
}
