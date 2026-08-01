import type { Element, MagicCircleSpec, Vec3 } from './spec';
import type { Camera } from './geometry';
import { elementOutlinePoints, glyphPoints } from './geometry';
import type { ViewState } from './view';
import { worldToScreen } from './view';

interface Rgb {
  r: number;
  g: number;
  b: number;
}

const TYPE_FALLBACK: Record<string, Rgb> = {
  ring: { r: 0x44, g: 0xcc, b: 0xff },
  arc: { r: 0xff, g: 0x88, b: 0x00 },
  glyph: { r: 0xff, g: 0xdd, b: 0x66 },
};

function parseHex(hex?: string): Rgb | null {
  if (!hex) return null;
  const m = /^#([0-9a-fA-F]{6})$/.exec(hex.trim());
  if (!m) return null;
  const v = parseInt(m[1], 16);
  return { r: (v >> 16) & 0xff, g: (v >> 8) & 0xff, b: v & 0xff };
}

const withAlpha = (c: Rgb, a: number): string =>
  `rgba(${c.r},${c.g},${c.b},${Math.round(a * 1000) / 1000})`;

function colorFor(el: Element): Rgb {
  return parseHex(el.color) ?? TYPE_FALLBACK[el.type] ?? TYPE_FALLBACK.ring;
}

/** 画布主渲染：填充背景 + 网格 + 元素 + 中心点。 */
export function render(
  ctx: CanvasRenderingContext2D,
  w: number,
  h: number,
  spec: MagicCircleSpec,
  view: ViewState,
  cam: Camera,
): void {
  ctx.fillStyle = '#0b0e14';
  ctx.fillRect(0, 0, w, h);

  if (cam.name === '俯视') {
    drawGrid(ctx, view, w, h);
  } else {
    drawFrontGuide(ctx, view, cam, w, h);
  }

  for (const el of spec.elements) {
    drawElement(ctx, view, cam, el);
  }

  drawCenter(ctx, view, cam);
}

function strokeProjected(
  ctx: CanvasRenderingContext2D,
  view: ViewState,
  cam: Camera,
  pts: Vec3[],
): void {
  if (pts.length === 0) return;
  ctx.beginPath();
  const s0 = worldToScreen(view, cam, pts[0]);
  ctx.moveTo(s0.x, s0.y);
  for (let i = 1; i < pts.length; i++) {
    const s = worldToScreen(view, cam, pts[i]);
    ctx.lineTo(s.x, s.y);
  }
  ctx.stroke();
}

function drawGrid(
  ctx: CanvasRenderingContext2D,
  view: ViewState,
  w: number,
  h: number,
): void {
  const x0 = (0 - view.ox) / view.scale;
  const x1 = (w - view.ox) / view.scale;
  const z0 = -(0 - view.oy) / view.scale;
  const z1 = -(h - view.oy) / view.scale;
  const minX = Math.min(x0, x1);
  const maxX = Math.max(x0, x1);
  const minZ = Math.min(z0, z1);
  const maxZ = Math.max(z0, z1);

  ctx.save();
  ctx.lineWidth = 1;

  for (let k = Math.ceil(minX); k <= Math.floor(maxX); k++) {
    ctx.strokeStyle = k % 5 === 0 ? 'rgba(255,255,255,0.12)' : 'rgba(255,255,255,0.045)';
    ctx.beginPath();
    ctx.moveTo(view.ox + k * view.scale, 0);
    ctx.lineTo(view.ox + k * view.scale, h);
    ctx.stroke();
  }
  for (let k = Math.ceil(minZ); k <= Math.floor(maxZ); k++) {
    ctx.strokeStyle = k % 5 === 0 ? 'rgba(255,255,255,0.12)' : 'rgba(255,255,255,0.045)';
    ctx.beginPath();
    ctx.moveTo(0, view.oy - k * view.scale);
    ctx.lineTo(w, view.oy - k * view.scale);
    ctx.stroke();
  }

  // 世界轴提示：X+ 红（东），Z+ 蓝（南）
  const ox = view.ox;
  const oy = view.oy;
  ctx.strokeStyle = 'rgba(255,90,90,0.8)';
  ctx.beginPath();
  ctx.moveTo(ox, oy);
  ctx.lineTo(ox + view.scale, oy);
  ctx.stroke();
  ctx.fillStyle = 'rgba(255,90,90,0.8)';
  ctx.font = '11px sans-serif';
  ctx.fillText('X+', ox + view.scale + 3, oy - 3);

  ctx.strokeStyle = 'rgba(90,160,255,0.8)';
  ctx.beginPath();
  ctx.moveTo(ox, oy);
  ctx.lineTo(ox, oy + 2 * view.scale);
  ctx.stroke();
  ctx.fillStyle = 'rgba(90,160,255,0.8)';
  ctx.fillText('Z+', ox + 3, oy + 2 * view.scale + 3);

  ctx.restore();
}

/** 非俯视视图：画一个浅色十字与中心点作为空间参照。 */
function drawFrontGuide(
  ctx: CanvasRenderingContext2D,
  view: ViewState,
  cam: Camera,
  w: number,
  h: number,
): void {
  const o = worldToScreen(view, cam, [0, 0, 0]);
  ctx.save();
  ctx.strokeStyle = 'rgba(255,255,255,0.08)';
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(o.x, 0);
  ctx.lineTo(o.x, h);
  ctx.moveTo(0, o.y);
  ctx.lineTo(w, o.y);
  ctx.stroke();

  ctx.fillStyle = 'rgba(255,255,255,0.35)';
  ctx.font = '11px sans-serif';
  ctx.fillText(cam.name, o.x + 6, o.y - 6);
  ctx.restore();
}

function drawElement(
  ctx: CanvasRenderingContext2D,
  view: ViewState,
  cam: Camera,
  el: Element,
): void {
  const color = colorFor(el);
  ctx.save();
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';

  if (el.type === 'glyph') {
    drawGlyph(ctx, view, cam, el, color);
    ctx.restore();
    return;
  }

  const pts = elementOutlinePoints(el);
  const lw = Math.max(1, 0.05 * view.scale);

  // 外发光
  ctx.strokeStyle = withAlpha(color, 0.2);
  ctx.lineWidth = lw * 3.2;
  strokeProjected(ctx, view, cam, pts);

  // 内核
  ctx.strokeStyle = withAlpha(color, 0.95);
  ctx.lineWidth = lw;
  strokeProjected(ctx, view, cam, pts);

  // 弧端点提示点
  if (el.type === 'arc' && pts.length > 1) {
    drawDot(ctx, view, cam, pts[0], color, lw * 1.6);
    drawDot(ctx, view, cam, pts[pts.length - 1], color, lw * 1.6);
  }

  ctx.restore();
}

function drawDot(
  ctx: CanvasRenderingContext2D,
  view: ViewState,
  cam: Camera,
  p: Vec3,
  color: Rgb,
  radius: number,
): void {
  const s = worldToScreen(view, cam, p);
  ctx.fillStyle = withAlpha(color, 0.9);
  ctx.beginPath();
  ctx.arc(s.x, s.y, radius, 0, Math.PI * 2);
  ctx.fill();
}

function drawGlyph(
  ctx: CanvasRenderingContext2D,
  view: ViewState,
  cam: Camera,
  el: Element & { type: 'glyph' },
  color: Rgb,
): void {
  const r = Math.max(2.5, (el.scale ?? 0.3) * view.scale * 0.45);
  for (const p of glyphPoints(el)) {
    const s = worldToScreen(view, cam, p);
    ctx.fillStyle = withAlpha(color, 0.18);
    ctx.beginPath();
    ctx.arc(s.x, s.y, r * 2.1, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = withAlpha(color, 0.95);
    ctx.beginPath();
    ctx.arc(s.x, s.y, r, 0, Math.PI * 2);
    ctx.fill();
  }
}

/** 世界原点中心点标记。 */
function drawCenter(
  ctx: CanvasRenderingContext2D,
  view: ViewState,
  cam: Camera,
): void {
  const s = worldToScreen(view, cam, [0, 0, 0]);
  ctx.save();
  ctx.strokeStyle = 'rgba(255,255,255,0.25)';
  ctx.lineWidth = 1;
  const gap = 4;
  const arm = 6;
  for (const [dx, dy] of [[-1, 0], [1, 0], [0, -1], [0, 1]] as const) {
    ctx.beginPath();
    ctx.moveTo(s.x + dx * gap, s.y + dy * gap);
    ctx.lineTo(s.x + dx * (gap + arm), s.y + dy * (gap + arm));
    ctx.stroke();
  }
  ctx.restore();
}
