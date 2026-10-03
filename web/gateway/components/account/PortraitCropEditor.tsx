'use client';

import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type PointerEvent } from 'react';
import { Seg } from '@opensamguk/ui';
import { CROP_KINDS, PORTRAIT_FRAMES, clamp, cropAt, cropZoom, initialCrops, moveCrop, validateSource, validateSourceDimensions, zoomCrop, type CropKind, type CropRect, type PortraitCrops } from '@/lib/portraitCrop';

type Props = {
    file: File;
    initial?: PortraitCrops;
    disabled?: boolean;
    onChange: (crops: PortraitCrops | null) => void;
    /** 올리기 사유(설계서 §2.5 A19): 원본을 읽는 중 · 못 쓰는 파일 · 세 구도를 한 번씩 봤는지. 보관 구도로 다시 열면 이미 본 것으로 친다. */
    onState?: (state: CropEditorState) => void;
};

export type CropEditorState = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'ready'; checked: boolean };

function imageStyle(rect: CropRect): CSSProperties {
    return { position: 'absolute', maxWidth: 'none', width: `${100 / rect.width}%`, height: `${100 / rect.height}%`, left: `${-100 * rect.x / rect.width}%`, top: `${-100 * rect.y / rect.height}%`, pointerEvents: 'none', userSelect: 'none' };
}

export default function PortraitCropEditor({ file, initial, disabled = false, onChange, onState }: Props) {
    const [source, setSource] = useState<{ url: string; w: number; h: number } | null>(null);
    const [crops, setCrops] = useState<PortraitCrops | null>(null);
    const [kind, setKind] = useState<CropKind>('hero');
    const [error, setError] = useState('');
    const seen = useRef(new Set<CropKind>());
    const viewport = useRef<HTMLDivElement>(null);
    const points = useRef(new Map<number, { x: number; y: number }>());
    const changeRef = useRef(onChange);
    changeRef.current = onChange;
    const stateRef = useRef(onState);
    stateRef.current = onState;

    useEffect(() => {
        let alive = true;
        let url = '';
        const fail = (message: string) => { setError(message); stateRef.current?.({ kind: 'error', message }); };
        setSource(null);
        setCrops(null);
        setError('');
        setKind('hero');
        changeRef.current(null);
        stateRef.current?.({ kind: 'loading' });
        try {
            validateSource(file);
            url = URL.createObjectURL(file);
            const img = new Image();
            img.onload = () => {
                if (!alive) return;
                try {
                    validateSourceDimensions(img.naturalWidth, img.naturalHeight);
                    const next = initial ?? initialCrops(img.naturalWidth, img.naturalHeight);
                    setSource({ url, w: img.naturalWidth, h: img.naturalHeight });
                    setCrops(next);
                    changeRef.current(next);
                    seen.current = new Set(initial ? CROP_KINDS : ['hero']);
                    stateRef.current?.({ kind: 'ready', checked: seen.current.size === CROP_KINDS.length });
                } catch (e) { fail((e as Error).message); }
            };
            img.onerror = () => { if (alive) fail('이미지를 읽지 못했습니다. 다른 파일을 선택하세요.'); };
            img.src = url;
        } catch (e) { fail((e as Error).message); }
        return () => { alive = false; if (url) URL.revokeObjectURL(url); points.current.clear(); };
    }, [file, initial]);

    const update = (rect: CropRect) => {
        if (!crops || disabled) return;
        const next = { ...crops, [kind]: rect };
        setCrops(next);
        changeRef.current(next);
    };
    const rect = crops?.[kind];
    const zoom = source && rect ? cropZoom(rect, source.w, source.h, kind) : 1;
    const setZoom = (value: number) => {
        if (source && rect) update(zoomCrop(rect, source.w, source.h, kind, value));
    };
    // Native non-passive handler lets wheel zoom the frame without scrolling the page.
    // Layout effect: attach in the same commit that shows the frame. A passive effect can run
    // after the frame is already on screen, so an early wheel scrolled the page instead.
    useLayoutEffect(() => {
        const element = viewport.current;
        const wheel = (e: WheelEvent) => {
            if (disabled) return;
            e.preventDefault();
            setZoom(zoom * Math.exp(-e.deltaY * 0.002));
        };
        element?.addEventListener('wheel', wheel, { passive: false });
        return () => element?.removeEventListener('wheel', wheel);
    });

    const pointerDown = (e: PointerEvent<HTMLDivElement>) => {
        if (disabled || (e.pointerType === 'mouse' && e.button !== 0)) return;
        e.currentTarget.setPointerCapture(e.pointerId);
        points.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    };
    const pointerMove = (e: PointerEvent<HTMLDivElement>) => {
        const previous = points.current.get(e.pointerId);
        if (!previous || !rect || disabled) return;
        const before = [...points.current.values()];
        points.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
        const after = [...points.current.values()];
        const box = e.currentTarget.getBoundingClientRect();
        if (after.length === 2 && source) {
            const distance = (p: { x: number; y: number }[]) => Math.hypot(p[0].x - p[1].x, p[0].y - p[1].y);
            const oldDistance = distance(before);
            if (oldDistance > 0) setZoom(zoom * distance(after) / oldDistance);
        } else if (box.width && box.height) {
            update(moveCrop(rect, -(e.clientX - previous.x) / box.width * rect.width, -(e.clientY - previous.y) / box.height * rect.height));
        }
    };
    const pointerEnd = (e: PointerEvent<HTMLDivElement>) => { points.current.delete(e.pointerId); };

    const choose = (next: CropKind) => {
        if (disabled) return;
        points.current.clear();
        setKind(next);
        if (seen.current.has(next)) return;
        seen.current.add(next);
        if (seen.current.size === CROP_KINDS.length) onState?.({ kind: 'ready', checked: true });
    };

    if (error) return <p role="alert">{error}</p>;
    if (!source || !crops || !rect) return <p role="status">원본을 불러오는 중…</p>;
    const frame = PORTRAIT_FRAMES[kind];
    return <div className="portrait-editor">
        <Seg
            label="편집할 구도"
            className="portrait-editor__choices"
            options={CROP_KINDS.map((key) => ({ value: key, label: PORTRAIT_FRAMES[key].label }))}
            value={kind}
            onChange={choose}
        />
        <div className="portrait-editor__stage">
            <div ref={viewport} className={`portrait-editor__viewport portrait-editor__viewport--${kind}`} style={{ aspectRatio: `${frame.width} / ${frame.height}` }}
                role="group" aria-label={`${frame.label} 자르기 영역`} onPointerDown={pointerDown} onPointerMove={pointerMove}
                onPointerUp={pointerEnd} onPointerCancel={pointerEnd} onLostPointerCapture={pointerEnd}>
                <img src={source.url} alt={`${frame.label} 편집 원본`} draggable={false} style={imageStyle(rect)} />
                <span className="portrait-editor__grid" aria-hidden="true" />
            </div>
        </div>
        <fieldset className="portrait-editor__controls" disabled={disabled}>
            <legend>{frame.label} 구도 조절</legend>
            <p>이미지를 끌어 얼굴 위치를 맞추세요. 휠이나 두 손가락으로 확대할 수 있습니다.</p>
            <label>확대·축소 <output>{zoom.toFixed(1)}배</output>
                <input aria-label={`${frame.label} 확대·축소`} type="range" min="1" max="8" step="0.01" value={clamp(zoom, 1, 8)} onChange={(e) => setZoom(Number(e.target.value))} />
            </label>
            <label>좌우 위치<input aria-label={`${frame.label} 좌우 위치`} type="range" min="0" max="1" step="0.001" value={rect.width >= 1 ? 0.5 : rect.x / (1 - rect.width)} disabled={rect.width >= 1} onChange={(e) => update({ ...rect, x: Number(e.target.value) * (1 - rect.width) })} /></label>
            <label>상하 위치<input aria-label={`${frame.label} 상하 위치`} type="range" min="0" max="1" step="0.001" value={rect.height >= 1 ? 0.5 : rect.y / (1 - rect.height)} disabled={rect.height >= 1} onChange={(e) => update({ ...rect, y: Number(e.target.value) * (1 - rect.height) })} /></label>
            <button type="button" className="os-button os-button--ghost os-button--sm" onClick={() => update(cropAt(source.w, source.h, kind))}>이 구도 초기화</button>
        </fieldset>
        <div className="portrait-editor__previews" role="group" aria-label="세 구도 미리보기">
            {CROP_KINDS.map((key) => <figure key={key}>
                <div className={`portrait-editor__preview portrait-editor__preview--${key}`} style={{ aspectRatio: `${PORTRAIT_FRAMES[key].width} / ${PORTRAIT_FRAMES[key].height}` }}>
                    <img src={source.url} alt={`${PORTRAIT_FRAMES[key].label} 저장 미리보기`} draggable={false} style={imageStyle(crops[key])} />
                </div>
                <figcaption>{PORTRAIT_FRAMES[key].label} <small>{PORTRAIT_FRAMES[key].width}×{PORTRAIT_FRAMES[key].height}</small></figcaption>
            </figure>)}
        </div>
        <p className="portrait-editor__hint">세 구도는 각각 유지됩니다. 「올리기」를 누르면 함께 저장합니다.</p>
    </div>;
}
