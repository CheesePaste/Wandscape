import type { Vec3 } from './spec';
import type { Camera } from './geometry';
import { normalize, pointAt, projectPoint } from './geometry';
import type { MagicCircleSpec } from './spec';

export interface ViewState {
  /** 每格像素数（缩放）。 */
  scale: number;
  /** 世界原点在屏幕上的 x（px）。 */
  ox: number;
  /** 世界原点在屏幕上的 y（px）。 */
  oy: number;
}

export function createView(): ViewState {
  return { scale: 44, ox: 0, oy: 0 };
}

export function worldToScreen(
  view: ViewState,
  cam: Camera,
  p: Vec3,
): { x: number; y: number } {
  const q = projectPoint(cam, p);
  return { x: view.ox + q.x * view.scale, y: view.oy + q.y * view.scale };
}

export function screenToWorld(
  view: ViewState,
  cam: Camera,
  sx: number,
  sy: number,
): { x: number; y: number } {
  return { x: (sx - view.ox) / view.scale, y: (sy - view.oy) / view.scale };
}

/** 以屏幕点 (sx, sy) 为锚缩放（保持锚点下的世界点不动）。 */
export function zoomAt(
  view: ViewState,
  cam: Camera,
  sx: number,
  sy: number,
  factor: number,
  min = 2,
  max = 400,
): void {
  const before = screenToWorld(view, cam, sx, sy);
  view.scale = Math.min(max, Math.max(min, view.scale * factor));
  view.ox = sx - before.x * view.scale;
  view.oy = sy - before.y * view.scale;
}

export function panBy(view: ViewState, dx: number, dy: number): void {
  view.ox += dx;
  view.oy += dy;
}

/** 把全部元素包围盒居中并缩放适配到画布（px）。 */
export function fitToSpec(
  view: ViewState,
  cam: Camera,
  spec: MagicCircleSpec,
  w: number,
  h: number,
  padding = 48,
): void {
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  const consider = (p: Vec3): void => {
    const s = projectPoint(cam, p);
    minX = Math.min(minX, s.x);
    minY = Math.min(minY, s.y);
    maxX = Math.max(maxX, s.x);
    maxY = Math.max(maxY, s.y);
  };

  consider([0, 0, 0]);
  for (const el of spec.elements) {
    const n = normalize(el.axis ?? [0, 1, 0]);
    const radius = Math.max(el.radius, 0.001);
    const y = el.type === 'glyph' ? 0 : (el.y_offset ?? 0);
    for (let i = 0; i <= 64; i++) {
      consider(pointAt(n, radius, (i / 64) * 360 + (el.rotation_offset_deg ?? 0), y));
    }
  }

  const spanX = Math.max(maxX - minX, 0.5);
  const spanY = Math.max(maxY - minY, 0.5);
  view.scale = Math.min((w - padding * 2) / spanX, (h - padding * 2) / spanY, 200);
  view.ox = w / 2 - ((minX + maxX) / 2) * view.scale;
  view.oy = h / 2 - ((minY + maxY) / 2) * view.scale;
}
