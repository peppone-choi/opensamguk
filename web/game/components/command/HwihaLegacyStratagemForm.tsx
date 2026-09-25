'use client';
import {useEffect,useRef,useState} from 'react';
import {api} from '../../lib/api';
import {submitCommandAndAwaitResult} from '../../lib/commandSubmit';
import type {HwihaLegacyStratagemId,HwihaLegacyStratagemOptions} from '../../lib/types';

const labels:Record<HwihaLegacyStratagemId,string>={
    'stratagem.play':'유언','stratagem.steal':'탈취','stratagem.sabotage':'파괴',
    'stratagem.fire':'화계','stratagem.lastStand':'필사즉생','stratagem.mobilizePeople':'백성동원',
    'stratagem.flood':'수공','stratagem.falseReport':'허보','stratagem.raiseMilitia':'의병모집',
    'stratagem.provokeRivalry':'이호경식','stratagem.raid':'급습','stratagem.reciprocity':'피장파장',
};
const ids=Object.keys(labels) as HwihaLegacyStratagemId[];

export default function HwihaLegacyStratagemForm({generalId,refreshKey=0,onReserved}:{generalId:number;refreshKey?:number;onReserved?:()=>void}){
    const [inputId,setInputId]=useState<HwihaLegacyStratagemId>('stratagem.play');
    const [options,setOptions]=useState<HwihaLegacyStratagemOptions|null>(null);
    const [index,setIndex]=useState(-1);
    const [message,setMessage]=useState<string|null>(null);
    const [busy,setBusy]=useState(false);
    const [tracking,setTracking]=useState<string|null>(null);
    const generation=useRef(0);const submitting=useRef(false);
    useEffect(()=>{
        const current=++generation.current;setOptions(null);setIndex(-1);setMessage(null);
        api.legacyStratagemOptions(inputId,generalId).then(value=>{
            if(generation.current!==current)return;
            setOptions(value);setIndex(value.choices.findIndex(choice=>choice.available));
        }).catch(()=>{if(generation.current===current)setMessage('계책 선택지를 불러오지 못했습니다.');});
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
                    setMessage(result.ok?'계책이 실행되었습니다.':result.reason??'계책을 실행할 수 없습니다.');
                    setTracking(null);onReserved?.();return;
                }
            }catch{/* accepted request remains queued */}
            if(active)timer=setTimeout(check,3000);
        };
        timer=setTimeout(check,3000);
        return()=>{active=false;clearTimeout(timer);};
    },[tracking,onReserved]);
    const choice=options?.choices[index];
    async function submit(){
        if(!choice?.available||submitting.current||tracking)return;
        submitting.current=true;setBusy(true);setMessage(null);
        let acceptedId:string|null=null;
        try{
            const result=await submitCommandAndAwaitResult(async()=>{
                const accepted=await api.playLegacyStratagem(inputId,generalId,choice.arguments);
                if('requestId' in accepted&&typeof accepted.requestId==='string')acceptedId=accepted.requestId;
                return accepted;
            });
            if(result.status==='rejected')setMessage(result.reason??'계책을 접수할 수 없습니다.');
            else if(result.status==='applied'){setMessage('계책이 실행되었습니다.');onReserved?.();}
            else{setMessage('접수되었습니다. 다음 개인 턴의 계책 단계에서 사용합니다.');setTracking(acceptedId);onReserved?.();}
        }catch(error){
            if(acceptedId){setTracking(acceptedId);setMessage('접수된 계책의 결과를 확인하고 있습니다.');}
            else setMessage(error instanceof Error?error.message:'계책을 접수할 수 없습니다.');
        }finally{submitting.current=false;setBusy(false);}
    }
    return <section aria-labelledby="legacy-stratagem-title">
        <h3 id="legacy-stratagem-title">계책 카드</h3>
        <p>각 카드는 한 달에 한 번 사용합니다. 현재 위치의 아군 창고에서 비용을 냅니다.</p>
        <label>계책<select className="os-inset" aria-label="계책 선택" value={inputId}
            onChange={event=>setInputId(event.target.value as HwihaLegacyStratagemId)} disabled={busy||!!tracking}>
            {ids.map(id=><option key={id} value={id}>{labels[id]}</option>)}
        </select></label>
        {options&&<label>대상<select className="os-inset" aria-label="계책 대상" value={index}
            onChange={event=>setIndex(Number(event.target.value))} disabled={busy||!!tracking}>
            {options.choices.map((row,i)=><option key={i} value={i} disabled={!row.available}>
                {row.label}{row.available?'':` — ${row.reason??'사용 불가'}`}
            </option>)}
        </select></label>}
        {!options&&!message&&<p role="status">계책 선택지를 불러오는 중입니다.</p>}
        {options&&!options.available&&<p role="status">{options.reason??'현재 사용할 수 없습니다.'}</p>}
        {message&&<p role="status">{message}</p>}
        <button className="os-button os-button--primary" type="button"
            disabled={busy||!!tracking||!choice?.available} onClick={()=>void submit()}>
            {busy?'접수 중…':`${labels[inputId]} 사용`}
        </button>
    </section>;
}
