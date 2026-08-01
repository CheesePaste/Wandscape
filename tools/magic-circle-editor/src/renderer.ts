import type { Element, GlyphElement, MagicCircleSpec, Vec3 } from './spec';
import type { Camera } from './geometry';
import { circleOutline, elementOutlinePoints, glyphPoints } from './geometry';
import type { ViewState } from './view';
import { worldToScreen } from './view';
import { elementFrame, STATIC_FRAME, type ElementFrame } from './anim';
import { computeLiveParticles, type LiveParticle } from './particles';
import { getTexture } from './mc-textures';

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

export interface RenderOpts {
  /** 归一化时刻 [0,1]；null = 静态预览（几何模式显示全部元素）。 */
  time?: number | null;
  /** 选中元素索引，-1 = 无。 */
  selected?: number;
  /** 预览模式：particle = 按发射模型渲染粒子，geometry = 几何线框。 */
  mode?: 'geometry' | 'particle';
}

/** 画布主渲染：填充背景 + 网格 + 元素（几何或粒子） + 中心点。 */
export function render(
  ctx: CanvasRenderingContext2D,
  w: number,
  h: number,
  spec: MagicCircleSpec,
  view: ViewState,
  cam: Camera,
  opts: RenderOpts = {},
): void {
  const mode = opts.mode ?? 'geometry';
  const time = mode === 'particle' ? (opts.time ?? 0) : (opts.time ?? null);
  const selected = opts.selected ?? -1;
  const dur = Math.max(1, spec.duration_ticks);

  ctx.fillStyle = '#0b0e14';
  ctx.fillRect(0, 0, w, h);

  if (cam.name === '俯视') {
    drawGrid(ctx, view, w, h);
  } else {
    drawFrontGuide(ctx, view, cam, w, h);
  }

  spec.elements.forEach((el, i) => {
    if (i === selected) drawSelection(ctx, view, cam, el);

    if (mode === 'particle') {
      drawParticlePreview(ctx, view, cam, el, time!, dur, i);
      return;
    }

    const frame: ElementFrame = time === null ? STATIC_FRAME : elementFrame(el, time);
    if (!frame.active) return;
    // 契约旋转合成：angle = rotation_offset + rotate_speed×(T-T0)/20 + anim.rotation(lt)
    const animRot = time === null ? 0 : frame.rotationDeg + ((el.rotate_speed ?? 0) * (time * dur - (el.start ?? 0) * dur)) / 20;
    drawElement(ctx, view, cam, el, {
      alpha: frame.alpha,
      radiusScale: frame.radiusScale,
      rotationDeg: animRot,
    });
  });

  drawCenter(ctx, view, cam);
}

/** 粒子模式：几何引导线（淡）+ 存活粒子。 */
function drawParticlePreview(
  ctx: CanvasRenderingContext2D,
  view: ViewState,
  cam: Camera,
  el: Element,
  t: number,
  dur: number,
  elIndex: number,
): void {
  // 淡引导线（看清楚粒子在环/弧/符文轨道上）
  ctx.save();
  ctx.strokeStyle = 'rgba(255,255,255,0.06)';
  ctx.lineWidth = 1;
  if (el.type === 'glyph') {
    strokeProjected(ctx, view, cam, circleOutline(el.axis ?? [0, 1, 0], el.radius, 0));
  } else {
    strokeProjected(ctx, view, cam, elementOutlinePoints(el));
  }
  ctx.restore();

  const parts = computeLiveParticles(el, t, dur, elIndex);
  for (const p of parts) {
    const s = worldToScreen(view, cam, p.pos);
    const px = Math.max(1, p.size * view.scale);
    drawParticleSprite(ctx, p, s.x, s.y, px);
  }
}

/** 画一个存活粒子：真实贴图（MC 16×16 粒子），可染色，纹理未就绪则跳过。 */
function drawParticleSprite(
  ctx: CanvasRenderingContext2D,
  p: LiveParticle,
  sx: number,
  sy: number,
  px: number,
): void {
  const a = Math.max(0, Math.min(1, p.alpha));
  ctx.save();
  ctx.globalAlpha = a;

  const img = p.texture ? getTexture(p.texture) : undefined;
  if (img) {
    ctx.drawImage(img, sx - px / 2, sy - px / 2, px, px);
    if (p.tint) {
      // source-atop：只作用已画贴图区域，保留贴图 alpha 软边，不改背景。
      ctx.globalAlpha = 1;
      ctx.globalCompositeOperation = 'source-atop';
      ctx.fillStyle = p.tint;
      ctx.fillRect(sx - px / 2, sy - px / 2, px, px);
      ctx.globalCompositeOperation = 'source-over';
    }
    ctx.restore();
    return;
  }

  // 兜底：贴图缺失/未加载时画菱形符文标记（tint 色描边），不糊成占位圆点
  const rgb = parseHex(p.tint ?? '#c8d2e0');
  if (rgb) {
    const half = Math.max(2.5, px * 0.5);
    ctx.strokeStyle = withAlpha(rgb, 0.9);
    ctx.lineWidth = Math.max(1, px * 0.12);
    ctx.beginPath();
    ctx.moveTo(sx, sy - half);
    ctx.lineTo(sx + half, sy);
    ctx.lineTo(sx, sy + half);
    ctx.lineTo(sx - half, sy);
    ctx.closePath();
    ctx.stroke();
  }
  ctx.restore();
}

interface DrawFrame {
  alpha: number;
  radiusScale: number;
  rotationDeg: number;
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
  f: DrawFrame,
): void {
  const color = colorFor(el);
  ctx.save();
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';

  if (el.type === 'glyph') {
    drawGlyph(ctx, view, cam, el, color, f);
    ctx.restore();
    return;
  }

  const pts = elementOutlinePoints(el, f);
  const lw = Math.max(1, 0.05 * view.scale);

  // 外发光
  ctx.strokeStyle = withAlpha(color, 0.2 * f.alpha);
  ctx.lineWidth = lw * 3.2;
  strokeProjected(ctx, view, cam, pts);

  // 内核
  ctx.strokeStyle = withAlpha(color, 0.95 * f.alpha);
  ctx.lineWidth = lw;
  strokeProjected(ctx, view, cam, pts);

  // 弧端点提示点
  if (el.type === 'arc' && pts.length > 1) {
    drawDot(ctx, view, cam, pts[0], color, lw * 1.6, f.alpha);
    drawDot(ctx, view, cam, pts[pts.length - 1], color, lw * 1.6, f.alpha);
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
  alpha = 1,
): void {
  const s = worldToScreen(view, cam, p);
  ctx.fillStyle = withAlpha(color, 0.9 * alpha);
  ctx.beginPath();
  ctx.arc(s.x, s.y, radius, 0, Math.PI * 2);
  ctx.fill();
}

function drawGlyph(
  ctx: CanvasRenderingContext2D,
  view: ViewState,
  cam: Camera,
  el: GlyphElement,
  color: Rgb,
  f: DrawFrame,
): void {
  const r = Math.max(2.5, (el.scale ?? 0.3) * view.scale * 0.45);
  for (const p of glyphPoints(el, f)) {
    const s = worldToScreen(view, cam, p);
    ctx.fillStyle = withAlpha(color, 0.18 * f.alpha);
    ctx.beginPath();
    ctx.arc(s.x, s.y, r * 2.1, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = withAlpha(color, 0.95 * f.alpha);
    ctx.beginPath();
    ctx.arc(s.x, s.y, r, 0, Math.PI * 2);
    ctx.fill();
  }
}

/** 选中高亮：白色虚线轮廓（glyph 用半径参考圆）。 */
function drawSelection(
  ctx: CanvasRenderingContext2D,
  view: ViewState,
  cam: Camera,
  el: Element,
): void {
  ctx.save();
  ctx.strokeStyle = 'rgba(255,255,255,0.8)';
  ctx.lineWidth = 1.4;
  ctx.setLineDash([6, 4]);
  if (el.type === 'glyph') {
    strokeProjected(ctx, view, cam, circleOutline(el.axis ?? [0, 1, 0], el.radius, 0));
  } else {
    strokeProjected(ctx, view, cam, elementOutlinePoints(el));
  }
  ctx.restore();
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

/** 命中测试：返回离 (sx, sy) 最近的元素索引（屏幕像素距离 ≤ threshold），无命中返回 -1。 */
export function pickElementAt(
  view: ViewState,
  cam: Camera,
  spec: MagicCircleSpec,
  sx: number,
  sy: number,
  threshold = 12,
): number {
  let best = -1;
  let bestD = Infinity;
  spec.elements.forEach((el, i) => {
    const pts = el.type === 'glyph' ? glyphPoints(el) : elementOutlinePoints(el);
    for (const p of pts) {
      const s = worldToScreen(view, cam, p);
      const d = Math.hypot(s.x - sx, s.y - sy);
      if (d < bestD) {
        bestD = d;
        best = i;
      }
    }
  });
  return bestD <= threshold ? best : -1;
}
