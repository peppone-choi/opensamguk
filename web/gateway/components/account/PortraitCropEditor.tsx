'use client';

import { useEffect, useRef, useState, type CSSProperties, type PointerEvent } from 'react';
import { CROP_KINDS, PORTRAIT_FRAMES, clamp, cropAt, cropZoom, initialCrops, moveCrop, validateSource, validateSourceDimensions, zoomCrop, type CropKind, type CropRect, type PortraitCrops } from '@/lib/portraitCrop';

type Props = {
    file: File;
    initial?: PortraitCrops;
    disabled?: boolean;
    onChange: (crops: PortraitCrops | null) => void;
};

function imageStyle(rect: CropRect): CSSProperties {
    return { position: 'absolute', maxWidth: 'none', width: `${100 / rect.width}%`, height: `${100 / rect.height}%`, left: `${-100 * rect.x / rect.width}%`, top: `${-100 * rect.y / rect.height}%`, pointerEvents: 'none', userSelect: 'none' };
}

export default function PortraitCropEditor({ file, initial, disabled = false, onChange }: Props) {
    const [source, setSource] = useState<{ url: string; w: number; h: number } | null>(null);
    const [crops, setCrops] = useState<PortraitCrops | null>(null);
    const [kind, setKind] = useState<CropKind>('hero');
    const [error, setError] = useState('');
    const viewport = useRef<HTMLDivElement>(null);
    const points = useRef(new Map<number, { x: number; y: number }>());
    const changeRef = useRef(onChange);
    changeRef.current = onChange;

    useEffect(() => {
        let alive = true;
        let url = '';
        setSource(null);
        setCrops(null);
        setError('');
        changeRef.current(null);
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
                } catch (e) { setError((e as Error).message); }
            };
            img.onerror = () => { if (alive) setError('이미지를 읽지 못했습니다. 다른 파일을 선택하세요.'); };
            img.src = url;
        } catch (e) { setError((e as Error).message); }
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
    useEffect(() => {
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

    if (error) return <p role="alert">{error}</p>;
    if (!source || !crops || !rect) return <p role="status">원본을 불러오는 중…</p>;
    const frame = PORTRAIT_FRAMES[kind];
    return <div className="portrait-editor">
        <div className="portrait-editor__choices" role="group" aria-label="편집할 전콘 종류">
            {CROP_KINDS.map((key) => <button type="button" key={key} aria-pressed={kind === key} disabled={disabled} onClick={() => { points.current.clear(); setKind(key); }}>
                {PORTRAIT_FRAMES[key].label}
            </button>)}
        </div>
        <div className="portrait-editor__workspace">
            <div ref={viewport} className="portrait-editor__viewport" style={{ aspectRatio: `${frame.width} / ${frame.height}` }}
                role="group" aria-label={`${frame.label} 자르기 영역`} onPointerDown={pointerDown} onPointerMove={pointerMove}
                onPointerUp={pointerEnd} onPointerCancel={pointerEnd} onLostPointerCapture={pointerEnd}>
                <img src={source.url} alt={`${frame.label} 편집 원본`} draggable={false} style={imageStyle(rect)} />
                <span className="portrait-editor__grid" aria-hidden="true" />
            </div>
            <fieldset className="portrait-editor__controls" disabled={disabled}>
                <legend>{frame.label} 구도 조절</legend>
                <p>이미지를 끌어 얼굴 위치를 맞추세요. 휠이나 두 손가락으로 확대할 수 있습니다.</p>
                <label>확대·축소 <output>{zoom.toFixed(1)}배</output>
                    <input aria-label={`${frame.label} 확대·축소`} type="range" min="1" max="8" step="0.01" value={clamp(zoom, 1, 8)} onChange={(e) => setZoom(Number(e.target.value))} />
                </label>
                <label>좌우 위치<input aria-label={`${frame.label} 좌우 위치`} type="range" min="0" max="1" step="0.001" value={rect.width >= 1 ? 0.5 : rect.x / (1 - rect.width)} disabled={rect.width >= 1} onChange={(e) => update({ ...rect, x: Number(e.target.value) * (1 - rect.width) })} /></label>
                <label>상하 위치<input aria-label={`${frame.label} 상하 위치`} type="range" min="0" max="1" step="0.001" value={rect.height >= 1 ? 0.5 : rect.y / (1 - rect.height)} disabled={rect.height >= 1} onChange={(e) => update({ ...rect, y: Number(e.target.value) * (1 - rect.height) })} /></label>
                <button type="button" className="btn-ghost" onClick={() => update(cropAt(source.w, source.h, kind))}>이 구도 초기화</button>
            </fieldset>
        </div>
        <div className="portrait-editor__previews" role="group" aria-label="전콘 세 종류 미리보기">
            {CROP_KINDS.map((key) => <figure key={key}>
                <div className={`portrait-editor__preview portrait-editor__preview--${key}`} style={{ aspectRatio: `${PORTRAIT_FRAMES[key].width} / ${PORTRAIT_FRAMES[key].height}` }}>
                    <img src={source.url} alt={`${PORTRAIT_FRAMES[key].label} 저장 미리보기`} draggable={false} style={imageStyle(crops[key])} />
                </div>
                <figcaption>{PORTRAIT_FRAMES[key].label} <small>{PORTRAIT_FRAMES[key].width}×{PORTRAIT_FRAMES[key].height}</small></figcaption>
            </figure>)}
        </div>
        <p className="portrait-editor__hint">세 구도는 각각 유지됩니다. 아래 업로드 버튼을 누르면 함께 저장합니다.</p>
    </div>;
}
