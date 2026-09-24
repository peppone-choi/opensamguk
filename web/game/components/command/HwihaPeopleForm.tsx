'use client';
import {useEffect,useRef,useState} from 'react';
import {api} from '../../lib/api';
import {submitCommandAndAwaitResult} from '../../lib/commandSubmit';
import type {HwihaPeopleActionId,HwihaPeopleOptions} from '../../lib/types';

export const peopleLabels:Record<HwihaPeopleActionId,string>={
    'action.search':'인재탐색','action.employ':'등용','action.persuadeCaptive':'포로 설득',
};
export function isPeopleActionId(value:string):value is HwihaPeopleActionId{return value in peopleLabels;}

export default function HwihaPeopleForm({inputId,generalId,turnIdx,refreshKey,unavailable,onToast,onClose,onReserved}:{
    inputId:HwihaPeopleActionId;generalId:number;turnIdx:number;refreshKey?:number;unavailable:boolean;
    onToast:(message:string,type:'success'|'error'|'info')=>void;onClose:()=>void;onReserved?:()=>void;
}){
    const [data,setData]=useState<HwihaPeopleOptions|null>(null);
    const [selected,setSelected]=useState<number|null>(null);
    const [reason,setReason]=useState<string|null>(null);
    const [busy,setBusy]=useState(false);
    const generation=useRef(0);const submitting=useRef(false);
    const validTurn=Number.isInteger(turnIdx)&&turnIdx>=0&&turnIdx<12;
    useEffect(()=>{
        const current=++generation.current;
        submitting.current=false;setBusy(false);setData(null);setSelected(null);setReason(null);
        if(!unavailable&&validTurn)api.peopleOptions(inputId,generalId).then(value=>{
            if(generation.current!==current)return;
            if(value.inputId!==inputId){setReason('인물 행동 정보를 확인하지 못했습니다.');return;}
            setData(value);setSelected(value.targets.find(target=>target.available)?.generalId??null);
        }).catch(()=>{if(generation.current===current)setReason('인물 행동 정보를 불러오지 못했습니다.');});
        return()=>{generation.current=current+1;};
    },[inputId,generalId,turnIdx,refreshKey,unavailable,validTurn]);
    if(unavailable)return <p role="status">본인 장수의 인물 행동만 예약할 수 있습니다.</p>;
    if(!validTurn)return <p role="status">인물 행동은 1~12순에만 예약할 수 있습니다.</p>;
    async function reserve(){
        if(!data?.available||submitting.current||(inputId!=='action.search'&&selected==null))return;
        submitting.current=true;setBusy(true);setReason(null);const current=generation.current;
        try{
            const args=inputId==='action.search'?{}:{targetGeneralId:selected};
            const result=await submitCommandAndAwaitResult(()=>api.command(inputId,args,generalId,turnIdx));
            if(current!==generation.current)return;
            if(result.status==='reserved'||result.status==='applied'){
                onToast(result.status==='reserved'?`${peopleLabels[inputId]} 명령이 예약되었습니다.`:`${peopleLabels[inputId]} 명령이 실행되었습니다.`,'success');
                onReserved?.();onClose();
            }else setReason(result.reason??`${peopleLabels[inputId]}을 예약할 수 없습니다.`);
        }catch(error){if(current===generation.current)setReason(error instanceof Error?error.message:'인물 행동 예약에 실패했습니다.');}
        finally{if(current===generation.current){submitting.current=false;setBusy(false);}}
    }
    return <div className="cmd-form"><h3>{peopleLabels[inputId]}</h3>
        <p>{inputId==='action.search'?'현재 縣의 재야 인물을 찾아 공개합니다.':
            inputId==='action.employ'?'찾아낸 재야 인물에게 휘하 합류를 제안합니다.':'현재 위치의 포로를 설득합니다.'}</p>
        {!data&&!reason&&<p role="status">인물 행동 정보를 불러오는 중입니다.</p>}
        {data&&!data.available&&<p role="status">{data.reason??'현재 실행할 수 없습니다.'}</p>}
        {data?.available&&inputId==='action.search'&&<p>찾을 수 있는 인물 {data.undiscoveredCount??0}명</p>}
        {data?.available&&inputId!=='action.search'&&<label>대상 인물
            <select className="os-inset" aria-label="대상 인물" value={selected??''} onChange={e=>setSelected(Number(e.target.value))}>
                {data.targets.map(target=><option key={target.generalId} value={target.generalId} disabled={!target.available}>
                    {target.name}{target.available?'':` — ${target.reason??'불가'}`}
                </option>)}
            </select>
        </label>}
        {reason&&<p role="alert">{reason}</p>}
        <button type="button" className="cmd-submit os-button os-button--primary"
            disabled={busy||!data?.available||(inputId!=='action.search'&&selected==null)} onClick={()=>void reserve()}>
            {busy?'처리 중...':`${peopleLabels[inputId]} 예약`}
        </button>
    </div>;
}
