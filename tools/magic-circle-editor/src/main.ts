import './style.css';
import type { MagicCircleSpec } from './spec';
import { createDefaultSpec, normalizeSpec, removeElement, validateSpec } from './spec';
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
import { pickElementAt, render } from './renderer';
import { initPanel, type PanelApi } from './panel';
import { ensureLoaded } from './mc-textures';

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

// ---------- 播放 / 选中状态 ----------

let animTime = 0; // 归一化 [0,1]
let playing = true;
let loop = true;
let speed = 1;
let staticMode = false;
let lastFrame = 0;
let selectedIndex = -1;
let renderMode: 'particle' | 'geometry' = 'particle';

// ---------- 渲染 ----------

function draw(): void {
  if (cssW <= 0 || cssH <= 0) return;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  // 粒子模式需要时刻才能算出存活粒子，始终用 animTime；几何模式才认静态开关。
  const time = renderMode === 'particle' ? animTime : staticMode ? null : animTime;
  render(ctx, cssW, cssH, spec, view, cam, { time, selected: selectedIndex, mode: renderMode });
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
  syncTimeline();
  draw();
}

function setCamera(name: ViewName): void {
  viewName = name;
  cam = CAMERAS[name];
  document.querySelectorAll('#view-group button').forEach((b) => {
    b.classList.toggle('active', b.getAttribute('data-view') === name);
  });
  updateStatus();
  draw();
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

// ---------- 面板 ----------

const api: PanelApi = {
  getSpec: () => spec,
  setSpec: (next, opts) => setSpec(next, opts),
  getSelected: () => selectedIndex,
  setSelected: (i) => {
    selectedIndex = i;
    panel.render();
  },
};
const panel = initPanel(api);

// ---------- 画布尺寸 ----------

function resize(): void {
  const r = wrap.getBoundingClientRect();
  cssW = Math.max(1, r.width);
  cssH = Math.max(1, r.height);
  canvas.width = Math.round(cssW * dpr);
  canvas.height = Math.round(cssH * dpr);
  canvas.style.width = `${cssW}px`;
  canvas.style.height = `${cssH}px`;
  draw();
}
new ResizeObserver(resize).observe(wrap);
resize();

// ---------- 拖拽 / 缩放 / 点击选中 ----------

let down = false;
let moved = false;
let downX = 0;
let downY = 0;
let lastX = 0;
let lastY = 0;
let clickPick = -1;

canvas.addEventListener('pointerdown', (e) => {
  down = true;
  moved = false;
  downX = e.clientX;
  downY = e.clientY;
  lastX = e.clientX;
  lastY = e.clientY;
  const rect = canvas.getBoundingClientRect();
  clickPick = pickElementAt(view, cam, spec, e.clientX - rect.left, e.clientY - rect.top);
  canvas.classList.add('dragging');
  canvas.setPointerCapture(e.pointerId);
});

canvas.addEventListener('pointermove', (e) => {
  if (!down) return;
  if (!moved && Math.hypot(e.clientX - downX, e.clientY - downY) > 3) moved = true;
  if (moved) {
    panBy(view, e.clientX - lastX, e.clientY - lastY);
    updateStatus();
    draw();
  }
  lastX = e.clientX;
  lastY = e.clientY;
});

const endDrag = (): void => {
  if (down) {
    down = false;
    if (!moved) {
      selectedIndex = clickPick;
      panel.render();
    }
  }
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
    draw();
  },
  { passive: false },
);

// ---------- 时间线 ----------

const scrub = document.getElementById('scrub') as HTMLInputElement;
const playBtn = document.getElementById('btn-play') as HTMLButtonElement;
const readout = document.getElementById('time-readout')!;
const timelineCanvas = document.getElementById('timeline-canvas') as HTMLCanvasElement;
const tctx = timelineCanvas.getContext('2d')!;

function togglePlay(): void {
  if (staticMode) {
    staticMode = false;
    (document.getElementById('chk-static') as HTMLInputElement).checked = false;
  }
  playing = !playing;
  if (playing && animTime >= 1) animTime = 0;
  updatePlayBtn();
  draw();
}

function updatePlayBtn(): void {
  playBtn.textContent = playing ? '⏸' : '▶';
}

function syncTimeline(): void {
  scrub.value = String(Math.round(animTime * 1000));
  const tick = Math.round(animTime * spec.duration_ticks);
  readout.textContent = `t ${animTime.toFixed(2)} · tick ${tick}/${spec.duration_ticks}`;
  drawTimeline();
}

/** 迷你时间线：每元素一条 [start,1] 窗口色带 + 播放头。 */
function drawTimeline(): void {
  const w = timelineCanvas.clientWidth;
  const h = 18;
  if (w <= 0) return;
  timelineCanvas.width = w * dpr;
  timelineCanvas.height = h * dpr;
  tctx.setTransform(dpr, 0, 0, dpr, 0, 0);

  tctx.clearRect(0, 0, w, h);
  tctx.fillStyle = '#0d1220';
  tctx.fillRect(0, 0, w, h);

  const n = spec.elements.length;
  const laneH = n > 0 ? Math.min(6, h / n) : h;
  spec.elements.forEach((e, i) => {
    const color = e.color ?? '#44ccff';
    const x0 = (e.start ?? 0) * w;
    const y = i * laneH + 1;
    tctx.fillStyle = color;
    tctx.globalAlpha = 0.55;
    tctx.fillRect(x0, y, w - x0, Math.max(2, laneH - 2));
    tctx.globalAlpha = 1;
  });

  const px = animTime * w;
  tctx.strokeStyle = '#ffffff';
  tctx.lineWidth = 1.2;
  tctx.beginPath();
  tctx.moveTo(px, 0);
  tctx.lineTo(px, h);
  tctx.stroke();
}

document.getElementById('btn-reset')!.addEventListener('click', () => {
  animTime = 0;
  syncTimeline();
  draw();
});
playBtn.addEventListener('click', togglePlay);
document.getElementById('chk-loop')!.addEventListener('change', (e) => {
  loop = (e.target as HTMLInputElement).checked;
});
document.getElementById('chk-static')!.addEventListener('change', (e) => {
  staticMode = (e.target as HTMLInputElement).checked;
  if (staticMode) playing = false;
  updatePlayBtn();
  draw();
});
document.getElementById('sel-speed')!.addEventListener('change', (e) => {
  speed = parseFloat((e.target as HTMLSelectElement).value) || 1;
});
scrub.addEventListener('input', () => {
  animTime = parseFloat(scrub.value) / 1000;
  syncTimeline();
  draw();
});

// ---------- 播放循环 ----------

function frame(now: number): void {
  if (playing) {
    if (lastFrame === 0) lastFrame = now;
    const dt = (now - lastFrame) / 1000;
    lastFrame = now;
    const realDur = spec.duration_ticks / 20; // 秒
    if (realDur > 0) {
      animTime += (dt * speed) / realDur;
      if (animTime >= 1) {
        if (loop) {
          animTime %= 1;
        } else {
          animTime = 1;
          playing = false;
          updatePlayBtn();
        }
      }
    }
    syncTimeline();
    draw();
  } else {
    lastFrame = 0;
  }
  requestAnimationFrame(frame);
}

// ---------- 工具栏 ----------

document.getElementById('btn-new')!.addEventListener('click', () => {
  setSpec(createDefaultSpec());
  selectedIndex = -1;
  panel.render();
  showToast('已新建（默认示范）');
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
    selectedIndex = -1;
    panel.render();
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
  draw();
});

document.querySelectorAll('#view-group button').forEach((b) => {
  b.addEventListener('click', () => {
    const name = (b as HTMLButtonElement).getAttribute('data-view') as ViewName | null;
    if (name) setCamera(name);
  });
});

document.querySelectorAll('#mode-group button').forEach((b) => {
  b.addEventListener('click', () => {
    const m = (b as HTMLButtonElement).getAttribute('data-mode');
    if (m !== 'geometry' && m !== 'particle') return;
    renderMode = m;
    document.querySelectorAll('#mode-group button').forEach((x) => {
      x.classList.toggle('active', x.getAttribute('data-mode') === m);
    });
    draw();
  });
});

// ---------- 快捷键 ----------

window.addEventListener('keydown', (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault();
    saveToLocal(spec);
    showToast('已保存到本地缓存');
    return;
  }
  const tag = (document.activeElement?.tagName ?? '').toLowerCase();
  if (tag === 'input' || tag === 'select' || tag === 'textarea') return;
  if (e.key === ' ' || e.code === 'Space') {
    e.preventDefault();
    togglePlay();
  } else if (e.key === 'Delete' || e.key === 'Backspace') {
    if (selectedIndex >= 0) {
      e.preventDefault();
      setSpec(removeElement(spec, selectedIndex), { fit: false });
      selectedIndex = -1;
      panel.render();
    }
  } else if (e.key === 'Escape') {
    if (selectedIndex >= 0) {
      selectedIndex = -1;
      panel.render();
    }
  }
});

window.addEventListener('beforeunload', () => saveToLocal(spec));

// ---------- 启动 ----------

updateStatus();
updatePlayBtn();
if (loadFromLocal() !== null) {
  showToast('已从本地缓存恢复上次草稿');
}
panel.render();
syncTimeline();
requestAnimationFrame(frame);

// 粒子贴图（base64 内联）就绪后重绘一次，避免首帧粒子空白
ensureLoaded().then(() => draw());
