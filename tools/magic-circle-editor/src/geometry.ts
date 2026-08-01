import type { Element, GlyphElement, Vec3 } from './spec';

export const rad = (deg: number): number => (deg * Math.PI) / 180;

export function dot(a: Vec3, b: Vec3): number {
  return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
}

export function cross(a: Vec3, b: Vec3): Vec3 {
  return [
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0],
  ];
}

export function length(v: Vec3): number {
  return Math.hypot(v[0], v[1], v[2]);
}

export function normalize(v: Vec3): Vec3 {
  const l = length(v);
  if (l === 0) return [0, 1, 0];
  return [v[0] / l, v[1] / l, v[2] / l];
}

/**
 * 由法线 n 构造正交基：a = normalize(cross(n, m))，b = cross(n, a)。
 * 与 magic-circles.md「旋转与朝向」一节、以及 MC 端定点公式完全一致——
 * 圆周点 p = a·cosθ + b·sinθ。
 */
export function orthonormalBasis(n: Vec3): { a: Vec3; b: Vec3 } {
  let m: Vec3 = [1, 0, 0];
  if (Math.abs(dot(normalize(n), m)) > 0.99) m = [0, 1, 0]; // 参考轴与 n 平行则换轴
  const a = normalize(cross(n, m));
  const b = cross(n, a);
  return { a, b };
}

/** 元素平面内角度 deg 处的圆周点（含 y_offset 纵向偏移）。 */
export function pointAt(n: Vec3, radius: number, deg: number, yOffset: number): Vec3 {
  const { a, b } = orthonormalBasis(n);
  const t = rad(deg);
  const c = Math.cos(t);
  const s = Math.sin(t);
  return [
    a[0] * c * radius + b[0] * s * radius,
    a[1] * c * radius + b[1] * s * radius + yOffset,
    a[2] * c * radius + b[2] * s * radius,
  ];
}

function buildArc(
  n: Vec3,
  radius: number,
  startDeg: number,
  sweepDeg: number,
  segments: number,
  yOffset: number,
): Vec3[] {
  const pts: Vec3[] = [];
  for (let i = 0; i <= segments; i++) {
    pts.push(pointAt(n, radius, startDeg + (sweepDeg * i) / segments, yOffset));
  }
  return pts;
}

export interface ElementPointOpts {
  /** 半径倍率（anim.scale），默认 1。 */
  radiusScale?: number;
  /** 附加旋转（度），叠在 rotation_offset_deg 之上（rotate_speed + anim.rotation），默认 0。 */
  rotationDeg?: number;
}

/** ring/arc 的外形折线点集（含 rotation_offset_deg 相位 + 可选半径缩放/附加旋转）。 */
export function elementOutlinePoints(el: Element, opts: ElementPointOpts = {}): Vec3[] {
  const n = normalize(el.axis ?? [0, 1, 0]);
  const offset = (el.rotation_offset_deg ?? 0) + (opts.rotationDeg ?? 0);
  const radius = el.radius * (opts.radiusScale ?? 1);
  const y = el.type === 'glyph' ? 0 : (el.y_offset ?? 0);

  if (el.type === 'arc') {
    const start = offset + (el.arc_start_deg ?? 0);
    const sweep = el.arc_sweep_deg ?? 360;
    const segments = Math.max(8, Math.round((Math.abs(sweep) / 360) * 160));
    return buildArc(n, radius, start, sweep, segments, y);
  }

  // ring：整圆（圆对相位不敏感，但仍用 offset 保持几何一致性）
  return buildArc(n, radius, offset, 360, 160, y);
}

/** glyph 符文位置点集（含 rotation_offset_deg 相位 + 可选半径缩放/附加旋转）。 */
export function glyphPoints(el: GlyphElement, opts: ElementPointOpts = {}): Vec3[] {
  const n = normalize(el.axis ?? [0, 1, 0]);
  const offset = (el.rotation_offset_deg ?? 0) + (opts.rotationDeg ?? 0);
  const radius = el.radius * (opts.radiusScale ?? 1);
  const count = Math.max(1, Math.round(el.count));
  const pts: Vec3[] = [];
  for (let i = 0; i < count; i++) {
    pts.push(pointAt(n, radius, offset + (i * 360) / count, 0));
  }
  return pts;
}

/** 平面内整圆轮廓（选中高亮用）。 */
export function circleOutline(
  n: Vec3,
  radius: number,
  yOffset = 0,
  segments = 96,
): Vec3[] {
  return buildArc(normalize(n), radius, 0, 360, segments, yOffset);
}

export type ViewName = 'top' | 'frontX' | 'frontZ';

/** 正交投影相机：screen = (dot(p, right), dot(p, up))，观察方向为 -normal。 */
export interface Camera {
  right: Vec3;
  up: Vec3;
  name: string;
}

export const CAMERAS: Record<ViewName, Camera> = {
  top: { right: [1, 0, 0], up: [0, 0, -1], name: '俯视' },
  frontX: { right: [0, 0, 1], up: [0, 1, 0], name: '前-X' },
  frontZ: { right: [1, 0, 0], up: [0, 1, 0], name: '前-Z' },
};

/** 世界点 → 相机平面坐标（单位：格）。 */
export function projectPoint(cam: Camera, p: Vec3): { x: number; y: number } {
  return { x: dot(p, cam.right), y: dot(p, cam.up) };
}
