import type { Curve, Element, MagicCircleSpec, Vec3 } from './spec';
import {
  addElement,
  moveElement,
  removeElement,
  setElementType,
  withCurve,
  withElement,
  withSpec,
} from './spec';
import { CurveEditor, CURVE_PRESETS } from './curve-editor';
import { PARTICLE_IDS, particleDefFor } from './particles';
import { textureUrl } from './mc-textures';

export interface PanelApi {
  getSpec(): MagicCircleSpec;
  setSpec(next: MagicCircleSpec, opts?: { fit?: boolean; toastErrors?: boolean }): void;
  getSelected(): number;
  setSelected(i: number): void;
}

const TYPE_LABEL: Record<string, string> = { ring: '环', arc: '弧', polygon: '多边形', star: '星形', glyph: '符文' };
const AXIS_PRESETS: { label: string; axis: Vec3 }[] = [
  { label: '地面', axis: [0, 1, 0] },
  { label: '竖直-X', axis: [1, 0, 0] },
  { label: '竖直-Z', axis: [0, 0, 1] },
];

function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  className?: string,
): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (className) e.className = className;
  return e;
}

function fieldRow(label: string, input: HTMLElement): HTMLDivElement {
  const row = el('div', 'f-row');
  const l = el('label', 'f-label');
  l.textContent = label;
  row.append(l, input);
  return row;
}

function numberInput(value: number, opts: { min?: number; step?: number; max?: number } = {}): HTMLInputElement {
  const i = el('input');
  i.type = 'number';
  i.value = String(value);
  if (opts.min !== undefined) i.min = String(opts.min);
  if (opts.max !== undefined) i.max = String(opts.max);
  if (opts.step !== undefined) i.step = String(opts.step);
  return i;
}

/** 数值输入：有效则实时 apply，无效提交时回显旧值。 */
function bindNumeric(i: HTMLInputElement, apply: (v: number) => void, read: () => number): void {
  i.addEventListener('input', () => {
    const v = parseFloat(i.value);
    if (Number.isFinite(v)) apply(v);
  });
  i.addEventListener('change', () => {
    const v = parseFloat(i.value);
    if (!Number.isFinite(v)) i.value = String(read());
  });
}

let curveField: 'scale' | 'alpha' | 'rotation' = 'scale';

/** 初始化面板，返回 render() 供外部在结构/选中变化后调用。 */
export function initPanel(api: PanelApi): { render: () => void } {
  const listBox = document.getElementById('elem-list')!;
  const propsBox = document.getElementById('elem-props')!;

  // 粒子下拉建议列表
  const datalist = document.getElementById('particle-list')!;
  for (const id of PARTICLE_IDS) {
    const o = el('option');
    o.value = id;
    datalist.append(o);
  }

  // ----- 顶层字段 -----
  const fId = document.getElementById('f-id') as HTMLInputElement;
  const fDur = document.getElementById('f-duration') as HTMLInputElement;
  const fHeight = document.getElementById('f-height') as HTMLInputElement;

  fId.addEventListener('change', () => {
    api.setSpec(withSpec(api.getSpec(), { id: fId.value.trim() || 'untitled' }), { fit: false });
  });
  bindNumeric(
    fDur,
    (v) => api.setSpec(withSpec(api.getSpec(), { duration_ticks: Math.max(1, Math.round(v)) }), { fit: false }),
    () => api.getSpec().duration_ticks,
  );
  bindNumeric(
    fHeight,
    (v) => api.setSpec(withSpec(api.getSpec(), { height: Math.max(0, v) }), { fit: false }),
    () => api.getSpec().height ?? 0.1,
  );

  // ----- 元素操作按钮 -----
  const bindAdd = (id: string, type: 'ring' | 'arc' | 'polygon' | 'star' | 'glyph'): void => {
    document.getElementById(id)!.addEventListener('click', () => {
      const s = api.getSpec();
      const next = addElement(s, type);
      const idx = next.elements.length - 1;
      api.setSpec(next, { fit: true });
      api.setSelected(idx);
    });
  };
  bindAdd('btn-add-ring', 'ring');
  bindAdd('btn-add-arc', 'arc');
  bindAdd('btn-add-polygon', 'polygon');
  bindAdd('btn-add-star', 'star');
  bindAdd('btn-add-glyph', 'glyph');

  document.getElementById('btn-del-elem')!.addEventListener('click', () => {
    const si = api.getSelected();
    if (si < 0) return;
    api.setSpec(removeElement(api.getSpec(), si), { fit: false });
    api.setSelected(-1);
  });

  document.getElementById('btn-cascade')!.addEventListener('click', () => {
    const s = api.getSpec();
    const n = Math.max(1, s.elements.length);
    const elements = s.elements.map((e, i) => ({ ...e, start: n <= 1 ? 0 : i / n }));
    api.setSpec({ ...s, elements }, { fit: false });
    api.setSelected(api.getSelected()); // 刷新列表摘要
  });

  function render(): void {
    const spec = api.getSpec();
    const sel = api.getSelected();
    fId.value = spec.id;
    fDur.value = String(spec.duration_ticks);
    fHeight.value = String(spec.height ?? 0.1);
    (document.getElementById('elem-count')!).textContent = `${spec.elements.length}`;
    renderList(spec, sel);
    renderProps(spec, sel);
  }

  function renderList(spec: MagicCircleSpec, sel: number): void {
    listBox.replaceChildren();
    spec.elements.forEach((e, i) => {
      const row = el('div', 'elem-row' + (i === sel ? ' selected' : ''));
      const sw = el('span', 'swatch');
      sw.style.background = e.color ?? '#44ccff';
      const info = el('span', 'elem-info');
      info.textContent = `#${i} ${TYPE_LABEL[e.type] ?? e.type} · r${e.radius} · s${(e.start ?? 0).toFixed(2)}`;
      const up = el('button', 'mini');
      up.textContent = '▲';
      const down = el('button', 'mini');
      down.textContent = '▼';
      row.append(sw, info, up, down);

      row.addEventListener('click', (ev) => {
        if (ev.target === up || ev.target === down) return;
        api.setSelected(i);
      });
      up.addEventListener('click', () => {
        api.setSpec(moveElement(api.getSpec(), i, -1), { fit: false });
        api.setSelected(i - 1 >= 0 ? i - 1 : i);
      });
      down.addEventListener('click', () => {
        api.setSpec(moveElement(api.getSpec(), i, 1), { fit: false });
        api.setSelected(i + 1 < spec.elements.length ? i + 1 : i);
      });
      listBox.append(row);
    });
    const delBtn = document.getElementById('btn-del-elem') as HTMLButtonElement;
    delBtn.disabled = sel < 0;
  }

  function renderProps(spec: MagicCircleSpec, sel: number): void {
    propsBox.replaceChildren();
    if (sel < 0) {
      const hint = el('div', 'hint');
      hint.textContent = '未选中元素——点画布上的环/弧/符文，或从左侧列表选择。';
      propsBox.append(hint);
      return;
    }
    const e = spec.elements[sel];
    const color = e.color ?? '#44ccff';

    // ----- 类型 -----
    const typeSel = el('select');
    const TYPE_OPTIONS: Record<string, string> = {
      ring: '环 ring',
      arc: '弧 arc',
      polygon: '多边形 polygon',
      star: '星形 star',
      glyph: '符文 glyph',
    };
    for (const t of ['ring', 'arc', 'polygon', 'star', 'glyph'] as const) {
      const o = el('option');
      o.value = t;
      o.textContent = TYPE_OPTIONS[t];
      if (t === e.type) o.selected = true;
      typeSel.append(o);
    }
    typeSel.addEventListener('change', () => {
      api.setSpec(
        setElementType(api.getSpec(), sel, typeSel.value as 'ring' | 'arc' | 'polygon' | 'star' | 'glyph'),
        { fit: false },
      );
      api.setSelected(sel);
    });
    const secTitle = el('h4', 'sec');
    secTitle.textContent = '元素属性';
    propsBox.append(secTitle, fieldRow('类型', typeSel));

    // ----- 半径 -----
    const radiusI = numberInput(e.radius, { min: 0, step: 0.1 });
    bindNumeric(radiusI, (v) => patchElement({ radius: v }), () => e.radius);
    propsBox.append(fieldRow('半径 radius', radiusI));

    // ----- 朝向 axis -----
    const axisRow = el('div', 'axis-row');
    AXIS_PRESETS.forEach((p) => {
      const b = el('button', 'mini' + (axisEq(p.axis, e.axis) ? ' active' : ''));
      b.textContent = p.label;
      b.addEventListener('click', () => {
        patchElement({ axis: [...p.axis] as Vec3 });
        api.setSelected(sel);
      });
      axisRow.append(b);
    });
    propsBox.append(fieldRow('朝向', axisRow));

    const axisInputs = (['X', 'Y', 'Z'] as const).map((l, k) => {
      const i = numberInput(e.axis ? e.axis[k] : [0, 1, 0][k], { step: 0.1 });
      bindNumeric(
        i,
        (v) => {
          const cur = api.getSpec().elements[api.getSelected()].axis ?? [0, 1, 0];
          const next = [...cur] as Vec3;
          next[k] = v;
          if (next.some((n) => n !== 0)) patchElement({ axis: next });
        },
        () => api.getSpec().elements[api.getSelected()].axis?.[k] ?? [0, 1, 0][k],
      );
      return i;
    });
    const axisInputRow = el('div', 'axis-row');
    axisInputs.forEach((i) => axisInputRow.append(i));
    propsBox.append(fieldRow('法线 XYZ', axisInputRow));

    // ----- 粒子 / 颜色 -----
    const particleI = el('input');
    particleI.type = 'text';
    particleI.list = 'particle-list';
    particleI.value = e.particle ?? '';
    particleI.addEventListener('change', () => patchElement({ particle: particleI.value.trim() }));
    propsBox.append(fieldRow('粒子 particle', particleI));

    const pDef = particleDefFor(e.particle);
    const ph = el('div', 'hint');
    const url = textureUrl(pDef.frame);
    if (url) {
      const img = el('img', 'particle-swatch');
      img.src = url;
      ph.append(img);
    }
    ph.append(
      document.createTextNode(
        pDef.vanilla
          ? `原版粒子，color 不生效（${pDef.label}）`
          : `自定义粒子，可用 color 染色（${pDef.label}）`,
      ),
    );
    propsBox.append(ph);

    const colorI = el('input');
    colorI.type = 'color';
    colorI.value = color;
    const colorHex = el('input');
    colorHex.type = 'text';
    colorHex.value = color;
    colorI.addEventListener('input', () => {
      colorHex.value = colorI.value;
      patchElement({ color: colorI.value });
    });
    colorHex.addEventListener('change', () => {
      const v = colorHex.value.trim();
      if (/^#[0-9a-fA-F]{6}$/.test(v)) {
        colorI.value = v.toLowerCase();
        patchElement({ color: v.toLowerCase() });
      } else {
        colorHex.value = colorI.value;
      }
    });
    const colorRow = el('div', 'axis-row');
    colorRow.append(colorI, colorHex);
    propsBox.append(fieldRow('颜色 color', colorRow));

    // ----- 旋转 / 相位 -----
    const rotOffI = numberInput(e.rotation_offset_deg ?? 0, { step: 1 });
    bindNumeric(rotOffI, (v) => patchElement({ rotation_offset_deg: v }), () => e.rotation_offset_deg ?? 0);
    propsBox.append(fieldRow('相位 rotation_offset', rotOffI));

    const rotSpeedI = numberInput(e.rotate_speed ?? 0, { step: 1 });
    bindNumeric(rotSpeedI, (v) => patchElement({ rotate_speed: v }), () => e.rotate_speed ?? 0);
    propsBox.append(fieldRow('转速 °/s rotate_speed', rotSpeedI));

    const startI = numberInput(e.start ?? 0, { min: 0, max: 0.99, step: 0.05 });
    bindNumeric(startI, (v) => patchElement({ start: Math.min(0.99, Math.max(0, v)) }), () => e.start ?? 0);
    propsBox.append(fieldRow('起始 start [0,1)', startI));

    // ----- 类型专属 -----
    if (e.type === 'ring' || e.type === 'arc' || e.type === 'polygon' || e.type === 'star') {
      const isShape = e.type === 'polygon' || e.type === 'star';
      // 排布模式：beads = 有序亮点（默认），continuous = 连续密度拖尾
      const modeSel = el('select');
      for (const m of ['beads', 'continuous'] as const) {
        const o = el('option');
        o.value = m;
        o.textContent = m === 'beads' ? (isShape ? 'beads 顶点亮点' : 'beads 有序亮点') : 'continuous 连续拖尾';
        if ((e.mode ?? 'beads') === m) o.selected = true;
        modeSel.append(o);
      }
      modeSel.addEventListener('change', () => {
        patchElement({ mode: modeSel.value as 'beads' | 'continuous' });
        api.setSelected(api.getSelected()); // 重渲染 props，切换字段显隐
      });
      propsBox.append(fieldRow('排布 mode', modeSel));

      if ((e.mode ?? 'beads') === 'beads') {
        if (e.type === 'ring' || e.type === 'arc') {
          const beadsI = numberInput(e.beads ?? 16, { min: 2, step: 1 });
          bindNumeric(beadsI, (v) => patchElement({ beads: Math.max(2, Math.round(v)) }), () => e.beads ?? 16);
          propsBox.append(fieldRow('亮点数 beads', beadsI));
        } else if (e.type === 'polygon') {
          const sidesI = numberInput(e.sides, { min: 3, step: 1 });
          bindNumeric(sidesI, (v) => patchElement({ sides: Math.max(3, Math.round(v)) }), () => e.sides);
          propsBox.append(fieldRow('边数 sides', sidesI));
        } else {
          const pointsI = numberInput(e.points, { min: 2, step: 1 });
          bindNumeric(pointsI, (v) => patchElement({ points: Math.max(2, Math.round(v)) }), () => e.points);
          propsBox.append(fieldRow('星芒 points', pointsI));
          const ratioI = numberInput(e.inner_ratio, { min: 0.05, max: 1, step: 0.05 });
          bindNumeric(ratioI, (v) => patchElement({ inner_ratio: Math.min(1, Math.max(0.05, v)) }), () => e.inner_ratio);
          propsBox.append(fieldRow('内径比 inner_ratio', ratioI));
        }
      } else {
        const densityI = numberInput(e.density ?? 1.5, { min: 0, step: 0.1 });
        bindNumeric(densityI, (v) => patchElement({ density: Math.max(0, v) }), () => e.density ?? 1.5);
        propsBox.append(fieldRow('密度 density', densityI));

        const trailI = numberInput(e.trail_ticks ?? 10, { min: 1, step: 1 });
        bindNumeric(trailI, (v) => patchElement({ trail_ticks: Math.max(1, Math.round(v)) }), () => e.trail_ticks ?? 10);
        propsBox.append(fieldRow('拖尾 tick trail_ticks', trailI));
      }

      const yOffI = numberInput(e.y_offset ?? 0, { step: 0.05 });
      bindNumeric(yOffI, (v) => patchElement({ y_offset: v }), () => e.y_offset ?? 0);
      propsBox.append(fieldRow('纵向偏移 y_offset', yOffI));

      const intervalI = numberInput(e.interval_ticks ?? 0, { min: 0, step: 1 });
      bindNumeric(
        intervalI,
        (v) => {
          const iv = Math.max(0, Math.round(v));
          patchElement(iv >= 1 ? { interval_ticks: iv } : { interval_ticks: undefined });
        },
        () => e.interval_ticks ?? 0,
      );
      propsBox.append(fieldRow('脉冲 interval_ticks(0=关)', intervalI));
    }
    if (e.type === 'arc') {
      const aStartI = numberInput(e.arc_start_deg ?? 0, { step: 1 });
      bindNumeric(aStartI, (v) => patchElement({ arc_start_deg: v }), () => e.arc_start_deg ?? 0);
      propsBox.append(fieldRow('弧起点 arc_start', aStartI));

      const aSweepI = numberInput(e.arc_sweep_deg ?? 360, { min: -360, max: 360, step: 1 });
      bindNumeric(aSweepI, (v) => patchElement({ arc_sweep_deg: Math.max(-360, Math.min(360, v)) }), () => e.arc_sweep_deg ?? 360);
      propsBox.append(fieldRow('弧扫过 arc_sweep', aSweepI));
    }
    if (e.type === 'glyph') {
      const countI = numberInput(e.count, { min: 1, step: 1 });
      bindNumeric(countI, (v) => patchElement({ count: Math.max(1, Math.round(v)) }), () => e.count);
      propsBox.append(fieldRow('符文数 count', countI));

      const spriteI = el('input');
      spriteI.type = 'text';
      spriteI.value = e.sprite ?? '';
      spriteI.addEventListener('change', () => patchElement({ sprite: spriteI.value.trim() }));
      propsBox.append(fieldRow('贴图 sprite', spriteI));

      const scaleI = numberInput(e.scale ?? 0.3, { min: 0, step: 0.05 });
      bindNumeric(scaleI, (v) => patchElement({ scale: Math.max(0, v) }), () => e.scale ?? 0.3);
      propsBox.append(fieldRow('尺寸 scale', scaleI));
    }

    // ----- 动画曲线 -----
    const animTitle = el('h4', 'sec');
    animTitle.textContent = '动画曲线';
    propsBox.append(animTitle);

    const curveSelect = el('select');
    for (const f of ['scale', 'alpha', 'rotation'] as const) {
      const o = el('option');
      o.value = f;
      o.textContent = { scale: 'scale 缩放', alpha: 'alpha 透明度', rotation: 'rotation 附加旋转' }[f];
      if (f === curveField) o.selected = true;
      curveSelect.append(o);
    }
    propsBox.append(fieldRow('曲线', curveSelect));

    const curveCanvas = el('canvas', 'curve-canvas');
    propsBox.append(curveCanvas);

    // 缓动：linear / smoothstep，作用于该元素全部曲线
    const easingSel = el('select');
    for (const m of ['linear', 'smoothstep'] as const) {
      const o = el('option');
      o.value = m;
      o.textContent = m === 'linear' ? 'linear 线性' : 'smoothstep 平滑';
      if ((e.anim?.easing ?? 'linear') === m) o.selected = true;
      easingSel.append(o);
    }
    easingSel.addEventListener('change', () => {
      const easing = easingSel.value as 'linear' | 'smoothstep';
      const cur = api.getSpec();
      const si = api.getSelected();
      if (si < 0) return;
      api.setSpec(withElement(cur, si, { anim: { ...(cur.elements[si].anim ?? {}), easing } }), { fit: false });
      curveEditor.setEasing(easing);
    });
    propsBox.append(fieldRow('缓动 easing', easingSel));

    const hint = el('div', 'hint');
    hint.textContent = '空白处单击加点 · 拖拽移动 · 右键删点';
    propsBox.append(hint);

    const curveEditor = new CurveEditor(curveCanvas, (curve) => commitCurve(curve));
    curveEditor.setEasing(e.anim?.easing ?? 'linear');
    curveEditor.setCurve(currentCurve());

    curveSelect.addEventListener('change', () => {
      curveField = curveSelect.value as typeof curveField;
      curveEditor.setCurve(currentCurve());
    });

    const presetRow = el('div', 'axis-row');
    for (const [, p] of Object.entries(CURVE_PRESETS)) {
      const b = el('button', 'mini');
      b.textContent = p.label;
      b.addEventListener('click', () => {
        const c = p.curve.map((k) => [...k] as Curve[number]);
        curveEditor.setCurve(c);
        commitCurve(c);
      });
      presetRow.append(b);
    }
    propsBox.append(fieldRow('预设', presetRow));

    function patchElement(patch: Partial<Element>): void {
      const s = api.getSpec();
      const si = api.getSelected();
      if (si < 0) return;
      api.setSpec(withElement(s, si, patch), { fit: false });
      renderList(api.getSpec(), si); // 刷新列表摘要（半径/起始）
    }

    function commitCurve(curve: Curve): void {
      const s = api.getSpec();
      const si = api.getSelected();
      if (si < 0) return;
      api.setSpec(withCurve(s, si, curveField, curve), { fit: false });
    }

    function currentCurve(): Curve {
      const s = api.getSpec();
      const si = api.getSelected();
      if (si < 0) return [];
      const anim = s.elements[si].anim;
      return anim ? (anim[curveField] ?? []) : [];
    }
  }

  function axisEq(a: Vec3 | undefined, b: Vec3 | undefined): boolean {
    const aa = a ?? [0, 1, 0];
    const bb = b ?? [0, 1, 0];
    return aa.every((v, k) => Math.abs(v - bb[k]) < 1e-9);
  }

  return { render };
}
