import './style.css';
import type { MagicCircleSpec } from './spec';
import { createDefaultSpec, normalizeSpec, validateSpec } from './spec';
import {
  downloadJson,
  loadFromLocal,
  parseSpec,
  readFileAsText,
  saveToLocal,
  serializeSpec,
} from './io';
import type { Camera, ViewName } from './geometry';
import { CAMERAS } from './geometry';
import type { ViewState } from './view';
import { createView, fitToSpec, panBy, zoomAt } from './view';
import { render } from './renderer';

const canvas = document.getElementById('canvas') as HTMLCanvasElement;
const wrap = document.getElementById('canvas-wrap') as HTMLElement;
const ctx = canvas.getContext('2d')!;

let spec: MagicCircleSpec;
{
  const restored = loadFromLocal();
  spec = normalizeSpec(restored ?? createDefaultSpec());
}
let view: ViewState = createView();
let cam: Camera = CAMERAS.top;
let viewName: ViewName = 'top';

const dpr = window.devicePixelRatio || 1;
let cssW = 0;
let cssH = 0;

// ---------- 渲染调度 ----------

let rafPending = false;
function requestRender(): void {
  if (rafPending) return;
  rafPending = true;
  requestAnimationFrame(() => {
    rafPending = false;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    render(ctx, cssW, cssH, spec, view, cam);
  });
}

// ---------- 状态更新 ----------

let saveTimer: number | undefined;
function autosave(): void {
  window.clearTimeout(saveTimer);
  saveTimer = window.setTimeout(() => saveToLocal(spec), 400);
}

function setSpec(
  next: MagicCircleSpec,
  opts: { fit?: boolean; toastErrors?: boolean } = {},
): void {
  spec = normalizeSpec(next);
  const errs = validateSpec(spec);
  if (opts.toastErrors !== false && errs.length > 0) {
    showToast(`契约校验告警：${errs.join('；')}`, true);
  }
  if (opts.fit !== false) fitToSpec(view, cam, spec, cssW, cssH);
  updateStatus();
  autosave();
  requestRender();
}

function setCamera(name: ViewName): void {
  viewName = name;
  cam = CAMERAS[name];
  document.querySelectorAll('#view-group button').forEach((b) => {
    b.classList.toggle('active', b.getAttribute('data-view') === name);
  });
  updateStatus();
  requestRender();
}

function updateStatus(): void {
  document.getElementById('status-spec')!.textContent =
    `${spec.id} · ${spec.elements.length} 元素 · 视图 ${cam.name}`;
  document.getElementById('status-zoom')!.textContent = `${Math.round(view.scale)} px/格`;
}

let toastTimer: number | undefined;
function showToast(msg: string, error = false): void {
  const t = document.getElementById('toast')!;
  t.textContent = msg;
  t.style.borderColor = error ? '#ff6b6b' : '#44ccff';
  t.classList.remove('hidden');
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => t.classList.add('hidden'), 3000);
}

// ---------- 画布尺寸 ----------

function resize(): void {
  const r = wrap.getBoundingClientRect();
  cssW = Math.max(1, r.width);
  cssH = Math.max(1, r.height);
  canvas.width = Math.round(cssW * dpr);
  canvas.height = Math.round(cssH * dpr);
  canvas.style.width = `${cssW}px`;
  canvas.style.height = `${cssH}px`;
  requestRender();
}
new ResizeObserver(resize).observe(wrap);
resize();

// ---------- 拖拽 / 缩放 ----------

let dragging = false;
let lastX = 0;
let lastY = 0;

canvas.addEventListener('pointerdown', (e) => {
  dragging = true;
  lastX = e.clientX;
  lastY = e.clientY;
  canvas.classList.add('dragging');
  canvas.setPointerCapture(e.pointerId);
});

canvas.addEventListener('pointermove', (e) => {
  if (!dragging) return;
  panBy(view, e.clientX - lastX, e.clientY - lastY);
  lastX = e.clientX;
  lastY = e.clientY;
  updateStatus();
  requestRender();
});

const endDrag = (): void => {
  dragging = false;
  canvas.classList.remove('dragging');
};
canvas.addEventListener('pointerup', endDrag);
canvas.addEventListener('pointercancel', endDrag);
canvas.addEventListener('contextmenu', (e) => e.preventDefault());

canvas.addEventListener(
  'wheel',
  (e) => {
    e.preventDefault();
    const rect = canvas.getBoundingClientRect();
    const factor = Math.exp(-e.deltaY * 0.0012);
    zoomAt(view, cam, e.clientX - rect.left, e.clientY - rect.top, factor);
    updateStatus();
    requestRender();
  },
  { passive: false },
);

// ---------- 工具栏 ----------

document.getElementById('btn-new')!.addEventListener('click', () => {
  setSpec(createDefaultSpec());
  showToast('已新建空白法阵（默认示范）');
});

document.getElementById('btn-import')!.addEventListener('click', () => {
  document.getElementById('file-input')!.click();
});

document.getElementById('file-input')!.addEventListener('change', async (e) => {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  try {
    const text = await readFileAsText(file);
    setSpec(parseSpec(text));
    showToast(`已导入 ${file.name}`);
  } catch (err) {
    showToast(err instanceof Error ? err.message : String(err), true);
  }
});

document.getElementById('btn-export')!.addEventListener('click', () => {
  downloadJson(spec);
  showToast(`已导出 ${spec.id}.json`);
});

document.getElementById('btn-copy')!.addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText(serializeSpec(spec));
    showToast('JSON 已复制到剪贴板');
  } catch {
    showToast('复制失败（剪贴板不可用）', true);
  }
});

document.getElementById('btn-fit')!.addEventListener('click', () => {
  fitToSpec(view, cam, spec, cssW, cssH);
  updateStatus();
  requestRender();
});

document.querySelectorAll('#view-group button').forEach((b) => {
  b.addEventListener('click', () => {
    const name = (b as HTMLButtonElement).getAttribute('data-view') as ViewName | null;
    if (name) setCamera(name);
  });
});

// ---------- 保存 ----------

window.addEventListener('keydown', (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault();
    saveToLocal(spec);
    showToast('已保存到本地缓存');
  }
});

window.addEventListener('beforeunload', () => saveToLocal(spec));

// ---------- 启动 ----------

updateStatus();
if (loadFromLocal() !== null) {
  showToast('已从本地缓存恢复上次草稿');
}
requestRender();
