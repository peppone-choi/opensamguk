'use client';
import {useEffect,useRef,useState} from 'react';
import {api} from '../../lib/api';
import {submitCommandAndAwaitResult} from '../../lib/commandSubmit';
import type {HwihaTransferActionId,HwihaTransferOptions} from '../../lib/types';

export const transferLabels:Record<HwihaTransferActionId,string>={
    'action.gift':'증여','action.donate':'헌납',
};
export function isTransferActionId(value:string):value is HwihaTransferActionId{return value in transferLabels;}
const resourceLabels:Record<string,string>={MONEY:'전',GRAIN:'곡',IRON:'철',TIMBER:'목재',HORSES:'말'};

export default function HwihaTransferForm({inputId,generalId,turnIdx,refreshKey,unavailable,onToast,onClose,onReserved}:{
    inputId:HwihaTransferActionId;generalId:number;turnIdx:number;refreshKey?:number;unavailable:boolean;
    onToast:(message:string,type:'success'|'error'|'info')=>void;onClose:()=>void;onReserved?:()=>void;
}){
    const [data,setData]=useState<HwihaTransferOptions|null>(null);
    const [resource,setResource]=useState('');
    const [target,setTarget]=useState('');
    const [amount,setAmount]=useState(1);
    const [reason,setReason]=useState<string|null>(null);
    const [busy,setBusy]=useState(false);
    const generation=useRef(0);const submitting=useRef(false);
    const validTurn=Number.isInteger(turnIdx)&&turnIdx>=0&&turnIdx<12;
    useEffect(()=>{
        const current=++generation.current;submitting.current=false;setBusy(false);setData(null);
        setResource('');setTarget('');setAmount(1);setReason(null);
        if(!unavailable&&validTurn)api.transferOptions(inputId,generalId).then(value=>{
            if(generation.current!==current)return;
            if(value.inputId!==inputId){setReason('자원 이전 정보를 확인하지 못했습니다.');return;}
            setData(value);setResource(value.resources.find(item=>item.available)?.resource??'');
            setTarget(String(value.targets.find(item=>item.available)?.generalId??''));
        }).catch(()=>{if(generation.current===current)setReason('자원 이전 정보를 불러오지 못했습니다.');});
        return()=>{generation.current=current+1;};
    },[inputId,generalId,turnIdx,refreshKey,unavailable,validTurn]);
    if(unavailable)return <p role="status">본인 장수의 자원 이전만 예약할 수 있습니다.</p>;
    if(!validTurn)return <p role="status">자원 이전은 1~12순에만 예약할 수 있습니다.</p>;
    const selected=data?.resources.find(item=>item.resource===resource);
    const validAmount=Number.isInteger(amount)&&amount>0&&amount<=(selected?.maxAmount??0);
    async function reserve(){
        if(!data?.available||!resource||!validAmount||(inputId==='action.gift'&&!target)||submitting.current)return;
        submitting.current=true;setBusy(true);setReason(null);const current=generation.current;
        try{
            const args={resource,amount,...(inputId==='action.gift'?{targetGeneralId:Number(target)}:{})};
            const result=await submitCommandAndAwaitResult(()=>api.command(inputId,args,generalId,turnIdx));
            if(current!==generation.current)return;
            if(result.status==='reserved'||result.status==='applied'){
                onToast(result.status==='reserved'?`${transferLabels[inputId]} 명령이 예약되었습니다.`:
                    `${transferLabels[inputId]} 명령이 실행되었습니다.`,'success');
                onReserved?.();onClose();
            }else setReason(result.reason??`${transferLabels[inputId]}를 예약할 수 없습니다.`);
        }catch(error){if(current===generation.current)setReason(error instanceof Error?error.message:'자원 이전 예약에 실패했습니다.');}
        finally{if(current===generation.current){submitting.current=false;setBusy(false);}}
    }
    return <div className="cmd-form"><h3>{transferLabels[inputId]}</h3>
        <p>{inputId==='action.gift'?'현재 省의 다른 장수에게 개인 보유 자원을 줍니다.':
            '현재 縣 소유 세력의 국고에 개인 보유 자원을 냅니다.'}</p>
        {!data&&!reason&&<p role="status">자원 이전 정보를 불러오는 중입니다.</p>}
        {data&&!data.available&&<p role="status">{data.reason??'현재 실행할 수 없습니다.'}</p>}
        {data?.available&&<><label>자원<select className="os-inset" aria-label="자원" value={resource}
            onChange={e=>{setResource(e.target.value);setAmount(1);}}>
            {data.resources.map(item=><option key={item.resource} value={item.resource} disabled={!item.available}>
                {resourceLabels[item.resource]??item.resource} — {item.maxAmount}{item.available?'':` (${item.reason??'불가'})`}
            </option>)}
        </select></label>
        {inputId==='action.gift'&&<label>받는 장수<select className="os-inset" aria-label="받는 장수"
            value={target} onChange={e=>setTarget(e.target.value)}>
            {data.targets.map(item=><option key={item.generalId} value={item.generalId} disabled={!item.available}>
                {item.name}{item.available?'':` — ${item.reason??'불가'}`}
            </option>)}
        </select></label>}
        <label>수량<input className="os-inset" aria-label="수량" type="number" min={1}
            max={selected?.maxAmount??1} value={amount} onChange={e=>setAmount(Number(e.target.value))}/></label></>}
        {reason&&<p role="alert">{reason}</p>}
        <button type="button" className="cmd-submit os-button os-button--primary"
            disabled={busy||!data?.available||!resource||!validAmount||(inputId==='action.gift'&&!target)}
            onClick={()=>void reserve()}>{busy?'처리 중...':`${transferLabels[inputId]} 예약`}</button>
    </div>;
}
