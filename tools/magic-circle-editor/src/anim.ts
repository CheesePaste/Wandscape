import type { Curve, Element } from './spec';

/**
 * 关键帧曲线采样——契约动画模型：归一化时间 [0,1]，线性插值。
 * 空/缺省曲线返回 fallback（scale/alpha 默认 1，rotation 默认 0）。
 */
export function sampleCurve(curve: Curve | undefined, t: number, fallback: number): number {
  if (!curve || curve.length === 0) return fallback;
  if (curve.length === 1) return curve[0][1];
  const tc = Math.min(1, Math.max(0, t));
  if (tc <= curve[0][0]) return curve[0][1];
  const last = curve[curve.length - 1];
  if (tc >= last[0]) return last[1];
  for (let i = 0; i < curve.length - 1; i++) {
    const [t0, v0] = curve[i];
    const [t1, v1] = curve[i + 1];
    if (tc >= t0 && tc <= t1) {
      const span = t1 - t0;
      const f = span === 0 ? 0 : (tc - t0) / span;
      return v0 + (v1 - v0) * f;
    }
  }
  return last[1];
}

/**
 * 元素局部时间：全局 t < start → 未激活（null）；
 * 否则 lt = (t - start) / (1 - start) 钳到 [0,1]。级联核心。
 */
export function elementLocalTime(start: number, t: number): number | null {
  if (t < start) return null;
  const denom = 1 - start;
  if (denom <= 0) return 0;
  return Math.min(1, Math.max(0, (t - start) / denom));
}

export interface ElementFrame {
  active: boolean;
  lt: number;
  radiusScale: number;
  alpha: number;
  /** anim.rotation 曲线采样（度）；rotate_speed 由渲染层按契约公式叠加。 */
  rotationDeg: number;
}

/** 元素在全局归一化时刻 t 的动画帧（仅 anim 曲线部分）。 */
export function elementFrame(el: Element, t: number): ElementFrame {
  const lt = elementLocalTime(el.start ?? 0, t);
  if (lt === null) {
    return { active: false, lt: 0, radiusScale: 0, alpha: 0, rotationDeg: 0 };
  }
  const anim = el.anim;
  const radiusScale = Math.max(0, sampleCurve(anim?.scale, lt, 1));
  const alpha = sampleCurve(anim?.alpha, lt, 1);
  const rotationDeg = sampleCurve(anim?.rotation, lt, 0);
  return { active: alpha > 0.001, lt, radiusScale, alpha, rotationDeg };
}

/** 静态预览帧：显示全部元素，alpha/scale 恒 1、无附加旋转。 */
export const STATIC_FRAME: ElementFrame = {
  active: true,
  lt: 0,
  radiusScale: 1,
  alpha: 1,
  rotationDeg: 0,
};
