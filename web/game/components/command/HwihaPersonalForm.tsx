'use client';
import {useEffect,useRef,useState} from 'react';
import {api} from '../../lib/api';
import {submitCommandAndAwaitResult} from '../../lib/commandSubmit';
import type {HwihaPersonalActionId,HwihaPersonalOptions} from '../../lib/types';

export const personalLabels:Record<HwihaPersonalActionId,string>={
    'action.travel':'견문','action.selfTrain':'단련','action.recuperate':'요양','action.retire':'은퇴',
};
export function isPersonalActionId(value:string):value is HwihaPersonalActionId{return value in personalLabels;}

const statLabels:Record<string,string>={
    leadership:'통솔',strength:'무력',intelligence:'지력',politics:'정치',charm:'매력',
};

export default function HwihaPersonalForm({inputId,generalId,turnIdx,refreshKey,unavailable,onToast,onClose,onReserved}:{
    inputId:HwihaPersonalActionId;generalId:number;turnIdx:number;refreshKey?:number;unavailable:boolean;
    onToast:(message:string,type:'success'|'error'|'info')=>void;onClose:()=>void;onReserved?:()=>void;
}){
    const [data,setData]=useState<HwihaPersonalOptions|null>(null);
    const [selected,setSelected]=useState<string>('');
    const [reason,setReason]=useState<string|null>(null);
    const [busy,setBusy]=useState(false);
    const generation=useRef(0);const submitting=useRef(false);
    const validTurn=Number.isInteger(turnIdx)&&turnIdx>=0&&turnIdx<12;
    useEffect(()=>{
        const current=++generation.current;
        submitting.current=false;setBusy(false);setData(null);setSelected('');setReason(null);
        if(!unavailable&&validTurn)api.personalOptions(inputId,generalId).then(value=>{
            if(generation.current!==current)return;
            if(value.inputId!==inputId){setReason('개인 행동 정보를 확인하지 못했습니다.');return;}
            setData(value);setSelected(inputId==='action.retire'?
                String(value.successors?.find(person=>person.available)?.generalId??''):
                value.stats?.find(stat=>stat.available)?.stat??'');
        }).catch(()=>{if(generation.current===current)setReason('개인 행동 정보를 불러오지 못했습니다.');});
        return()=>{generation.current=current+1;};
    },[inputId,generalId,turnIdx,refreshKey,unavailable,validTurn]);
    if(unavailable)return <p role="status">본인 장수의 개인 행동만 예약할 수 있습니다.</p>;
    if(!validTurn)return <p role="status">개인 행동은 1~12순에만 예약할 수 있습니다.</p>;
    async function reserve(){
        if(!data?.available||submitting.current||((inputId==='action.selfTrain'||inputId==='action.retire')&&!selected))return;
        submitting.current=true;setBusy(true);setReason(null);const current=generation.current;
        try{
            const args=inputId==='action.selfTrain'?{stat:selected}:
                inputId==='action.retire'?{successorGeneralId:Number(selected)}:{};
            const result=await submitCommandAndAwaitResult(()=>api.command(inputId,args,generalId,turnIdx));
            if(current!==generation.current)return;
            if(result.status==='reserved'||result.status==='applied'){
                onToast(result.status==='reserved'?`${personalLabels[inputId]} 명령이 예약되었습니다.`:`${personalLabels[inputId]} 명령이 실행되었습니다.`,'success');
                onReserved?.();onClose();
            }else setReason(result.reason??`${personalLabels[inputId]}을 예약할 수 없습니다.`);
        }catch(error){if(current===generation.current)setReason(error instanceof Error?error.message:'개인 행동 예약에 실패했습니다.');}
        finally{if(current===generation.current){submitting.current=false;setBusy(false);}}
    }
    return <div className="cmd-form"><h3>{personalLabels[inputId]}</h3>
        <p>{inputId==='action.travel'?'현재 省에서 견문을 넓힙니다.':
            inputId==='action.selfTrain'?'한 능력을 단련하며 피로가 쌓입니다.':
            inputId==='action.retire'?'승계할 휘하 인물을 지정한 뒤 은퇴합니다.':'현재 자리에서 부상과 피로를 회복합니다.'}</p>
        {!data&&!reason&&<p role="status">개인 행동 정보를 불러오는 중입니다.</p>}
        {data&&!data.available&&<p role="status">{data.reason??'현재 실행할 수 없습니다.'}</p>}
        {data?.available&&inputId==='action.selfTrain'&&<label>단련할 능력
            <select className="os-inset" aria-label="단련할 능력" value={selected} onChange={e=>setSelected(e.target.value)}>
                {(data.stats??[]).map(stat=><option key={stat.stat} value={stat.stat} disabled={!stat.available}>
                    {statLabels[stat.stat]??stat.stat}{stat.available?'':` — ${stat.reason??'불가'}`}
                </option>)}
            </select>
        </label>}
        {data?.available&&inputId==='action.retire'&&<label>승계할 인물
            <select className="os-inset" aria-label="승계할 인물" value={selected} onChange={e=>setSelected(e.target.value)}>
                {(data.successors??[]).map(person=><option key={person.generalId} value={person.generalId} disabled={!person.available}>
                    {person.name}{person.available?'':` — ${person.reason??'불가'}`}
                </option>)}
            </select>
        </label>}
        {reason&&<p role="alert">{reason}</p>}
        <button type="button" className="cmd-submit os-button os-button--primary"
            disabled={busy||!data?.available||((inputId==='action.selfTrain'||inputId==='action.retire')&&!selected)} onClick={()=>void reserve()}>
            {busy?'처리 중...':`${personalLabels[inputId]} 예약`}
        </button>
    </div>;
}
