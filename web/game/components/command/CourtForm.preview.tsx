/** Non-production eight-state preview. Every response below is a synthetic UI fixture. */
import React from 'react';
import { createRoot } from 'react-dom/client';
import CourtForm from './CourtForm';
import { api } from '../../lib/api';
import '../../../shared/src/tokens.css';
import './CourtForm.preview.css';

let replied = false;
const names = ['default','hover','focus','active','disabled','loading','error','success'];
api.dispatchOptions = async (id, target) => {
    if (id === 26) return new Promise(() => {});
    if (id === 27) throw new Error('synthetic read failure');
    return {result:true,targets:[{generalId:2,label:'조운 (시각 검증용)'}],
        counties: target ? [{countyId:7,label:'허현 (시각 검증용)',available:id !== 25,reason:'현재 발령할 수 없습니다.'}] : [],
        queued:id === 25 ? {requestId:'preview-private',targetGeneralId:2,countyId:7} : null};
};
api.dispatchPending = async id => {
    if (id === 26) return new Promise(() => {});
    if (id === 27) throw new Error('synthetic read failure');
    return {result:true,dispatches:[{dispatchId:'preview-order',issuerId:1,targetId:id,countyId:7,
        issuerLabel:'유비 (시각 검증용)',targetLabel:'조운 (시각 검증용)',countyLabel:'허현 (시각 검증용)',
        issuedAt:{year:200,month:1,phase:1},dueAt:{year:200,month:5,phase:1},status:id===28 || (id===21 && replied)?'ACCEPTED':'PENDING'}]};
};
api.courtDispatchReply = async () => { replied = true; return {status:'AVAILABLE',requestId:'preview-result'} as const; };
api.courtDispatch = async () => ({status:'AVAILABLE',requestId:'preview-result'} as const);
api.commandResult = async () => ({status:'RESOLVED',ok:true,type:'executionApplied',requestId:'preview-result',result:{}} as any);
createRoot(document.getElementById('root')!).render(<main><h1>발령·응답 — 컴포넌트 검증</h1>
<p>Mock 응답입니다. 실제 인증·API·엔진 실행 증거가 아닙니다.</p>
{names.map((name,index)=><section className="preview-state" data-preview-state={name} key={name}>
<h2>{name}</h2><CourtForm generalId={21+index}/></section>)}</main>);
setTimeout(() => {
    for (const name of ['hover','focus','active']) document.querySelector(`[data-preview-state=${name}] button`)?.classList.add(`is-${name}`);
}, 400);
