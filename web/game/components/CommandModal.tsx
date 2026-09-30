'use client';

import { Modal, Portrait } from '@opensamguk/ui';
import { useState } from 'react';
import type { TravelActionId } from '../lib/types';
import CourtForm from './command/CourtForm';
import DeployForm from './command/DeployForm';
import TravelForm from './command/TravelForm';
import FieldForm, { fieldLabels, isFieldActionId } from './command/FieldForm';
import MilitaryForm, { militaryLabels, isMilitaryActionId } from './command/MilitaryForm';
import PersonalForm, { personalLabels, isPersonalActionId } from './command/PersonalForm';
import PeopleForm, { peopleLabels, isPeopleActionId } from './command/PeopleForm';
import PoliticalForm, { politicalLabels, isPoliticalActionId } from './command/PoliticalForm';
import TransferForm, { transferLabels, isTransferActionId } from './command/TransferForm';
import DirectActionForm, { legacyDirectLabels, isLegacyDirectActionId } from './command/DirectActionForm';
import EnlistmentForm, { useRuleProfile } from './command/EnlistmentForm';

function isTravelActionId(value: string): value is TravelActionId {
    return value === 'action.move' || value === 'action.forcedMarch' || value === 'action.return';
}

interface CommandModalProps {
    ruleProfile?: string | null;
    courtMode?: boolean;
    refreshKey?: number;
    onClose: () => void;
    onToast: (message: string, type: 'success' | 'error' | 'info') => void;
    generalId: number;
    turnIdx?: number;
    onReserved?: () => void;
    pinnedCommand?: string;
    pinnedLabel?: string;
    hero?: { picture?: string | null; imageServer?: number | null; name?: string | null; nationColor?: string | null } | null;
}

const omittedActionIds = new Set([
    'action.muster', 'action.retire', 'action.persuadeCaptive', 'action.resign',
    'action.rise', 'action.independence', 'action.dissolve', 'action.donate',
    'action.tradeEquipment',
]);

const actionOptions = [
    ['action.enlist', '출사'], ['action.deploy', '출병'], ['action.move', '이동'],
    ['action.forcedMarch', '강행'], ['action.return', '귀환'],
    ...Object.entries(fieldLabels),
    ...Object.entries(militaryLabels),
    ...Object.entries(personalLabels),
    ...Object.entries(peopleLabels),
    ...Object.entries(politicalLabels),
    ...Object.entries(transferLabels),
    ...Object.entries(legacyDirectLabels),
].filter(([id]) => !omittedActionIds.has(id));

export default function CommandModal({
    onClose, ruleProfile, courtMode = false, refreshKey, onToast,
    generalId, turnIdx = 0, onReserved, pinnedCommand, pinnedLabel, hero = null,
}: CommandModalProps) {
    const profile = useRuleProfile(ruleProfile);
    const [selectedAction, setSelectedAction] = useState('action.enlist');
    const action = pinnedCommand || selectedAction;
    const formKey = `${action}:${generalId}:${refreshKey ?? ''}`;

    return (
        <Modal
            ariaLabel={courtMode ? '발령·조정·계책' : pinnedLabel ? `명령: ${pinnedLabel}` : '명령'}
            className="modal-content"
            overlayClassName="modal-overlay"
            onClose={onClose}
        >
            <div className={`modal-header cmd-header${hero ? ' cmd-header--hero' : ''}`}>
                {hero && <div className="cmd-header__hero" aria-hidden="true">
                    <Portrait picture={hero.picture} imageServer={hero.imageServer} size="hero" alt="" />
                </div>}
                <div className="cmd-header__text">
                    <h2 className="os-serif">{courtMode ? '발령·조정·계책' : '명령'}</h2>
                    {hero?.name && <span className="cmd-header__who">{hero.name}{!courtMode ? ` · ${turnIdx + 1}순` : ''}</span>}
                </div>
                <button type="button" className="os-button os-button--ghost os-button--sm cmd-close" onClick={onClose} aria-label="닫기">×</button>
            </div>
            {profile !== 'HWIHA' ? (
                <p role="status">서버 규칙을 확인하지 못해 명령을 예약할 수 없습니다.</p>
            ) : courtMode ? (
                <CourtForm key={generalId} generalId={generalId} refreshKey={refreshKey} onReserved={onReserved} />
            ) : (
                <>
                    {!pinnedCommand && <label>개인 행동
                        <select className="os-inset" aria-label="개인 행동" value={selectedAction} onChange={event => setSelectedAction(event.target.value)}>
                            {actionOptions.map(([id, label]) => <option key={id} value={id}>{label}</option>)}
                        </select>
                    </label>}
                    {action === 'action.deploy' ? (
                        <DeployForm key={formKey} generalId={generalId} turnIdx={turnIdx} refreshKey={refreshKey} unavailable={false} onToast={onToast} onClose={onClose} onReserved={onReserved} />
                    ) : isTravelActionId(action) ? (
                        <TravelForm key={formKey} inputId={action} generalId={generalId} turnIdx={turnIdx} refreshKey={refreshKey} unavailable={false} onToast={onToast} onClose={onClose} onReserved={onReserved} />
                    ) : isFieldActionId(action) ? (
                        <FieldForm key={formKey} inputId={action} generalId={generalId} turnIdx={turnIdx} refreshKey={refreshKey} unavailable={false} onToast={onToast} onClose={onClose} onReserved={onReserved} />
                    ) : isMilitaryActionId(action) ? (
                        <MilitaryForm key={formKey} inputId={action} generalId={generalId} turnIdx={turnIdx} refreshKey={refreshKey} unavailable={false} onToast={onToast} onClose={onClose} onReserved={onReserved} />
                    ) : isPersonalActionId(action) ? (
                        <PersonalForm key={formKey} inputId={action} generalId={generalId} turnIdx={turnIdx} refreshKey={refreshKey} unavailable={false} onToast={onToast} onClose={onClose} onReserved={onReserved} />
                    ) : isPeopleActionId(action) ? (
                        <PeopleForm key={formKey} inputId={action} generalId={generalId} turnIdx={turnIdx} refreshKey={refreshKey} unavailable={false} onToast={onToast} onClose={onClose} onReserved={onReserved} />
                    ) : isPoliticalActionId(action) ? (
                        <PoliticalForm key={formKey} inputId={action} generalId={generalId} turnIdx={turnIdx} refreshKey={refreshKey} unavailable={false} onToast={onToast} onClose={onClose} onReserved={onReserved} />
                    ) : isTransferActionId(action) ? (
                        <TransferForm key={formKey} inputId={action} generalId={generalId} turnIdx={turnIdx} refreshKey={refreshKey} unavailable={false} onToast={onToast} onClose={onClose} onReserved={onReserved} />
                    ) : isLegacyDirectActionId(action) ? (
                        <DirectActionForm key={formKey} inputId={action} generalId={generalId} turnIdx={turnIdx} refreshKey={refreshKey} unavailable={false} onToast={onToast} onClose={onClose} onReserved={onReserved} />
                    ) : (
                        <EnlistmentForm key={formKey} inputId={action as 'action.enlist' | 'action.randomEnlist' | 'action.targetEnlist'} generalId={generalId} turnIdx={turnIdx}
                            unavailable={!!pinnedCommand && !['action.enlist', 'action.randomEnlist', 'action.targetEnlist'].includes(pinnedCommand)}
                            onToast={onToast} onClose={onClose} onReserved={onReserved} />
                    )}
                </>
            )}
        </Modal>
    );
}
