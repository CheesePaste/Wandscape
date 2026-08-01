import type { Curve } from './spec';

/** 常用曲线形状预设。 */
export const CURVE_PRESETS: Record<string, { label: string; curve: Curve }> = {
  hold: { label: '恒1', curve: [[0, 1]] },
  fadeIn: { label: '淡入', curve: [[0, 0], [1, 1]] },
  fadeOut: { label: '淡出', curve: [[0, 1], [1, 0]] },
  pulse: { label: '脉冲', curve: [[0, 0], [0.5, 1], [1, 0]] },
  rampIn: { label: '渐放', curve: [[0, 0], [0.5, 1], [1, 1.2]] },
};

const PAD_L = 26;
const PAD_R = 10;
const PAD_T = 8;
const PAD_B = 18;
const PICK_RADIUS = 10;

type Pt = [number, number];

export class CurveEditor {
  private canvas: HTMLCanvasElement;
  private ctx: CanvasRenderingContext2D;
  private curve: Pt[] = [];
  private onChange: (curve: Curve) => void;
  private yMin = 0;
  private yMax = 1;
  private dragging = -1;

  constructor(canvas: HTMLCanvasElement, onChange: (curve: Curve) => void) {
    this.canvas = canvas;
    this.onChange = onChange;
    canvas.width = 240;
    canvas.height = 90;
    this.ctx = canvas.getContext('2d')!;
    this.bindEvents();
  }

  setCurve(curve: Curve): void {
    this.curve = curve.map((k) => [k[0], k[1]] as Pt);
    this.computeRange();
    this.draw();
  }

  /** 当前内部曲线（供面板读）。 */
  getCurve(): Curve {
    return this.curve.map((k) => [k[0], k[1]] as Pt);
  }

  private computeRange(): void {
    let lo = Infinity;
    let hi = -Infinity;
    for (const [, v] of this.curve) {
      lo = Math.min(lo, v);
      hi = Math.max(hi, v);
    }
    if (!Number.isFinite(lo)) {
      lo = 0;
      hi = 1;
    }
    let min = Math.min(0, lo) - 0.15;
    let max = Math.max(1, hi) + 0.15;
    if (max - min < 0.5) {
      const mid = (min + max) / 2;
      min = mid - 0.25;
      max = mid + 0.25;
    }
    this.yMin = min;
    this.yMax = max;
  }

  private toCanvas(p: Pt): Pt {
    const x = PAD_L + (p[0] / 1) * (this.canvas.width - PAD_L - PAD_R);
    const y =
      PAD_T +
      (1 - (p[1] - this.yMin) / (this.yMax - this.yMin)) *
        (this.canvas.height - PAD_T - PAD_B);
    return [x, y];
  }

  private toValue(cx: number, cy: number): Pt {
    const t = Math.min(1, Math.max(0, (cx - PAD_L) / (this.canvas.width - PAD_L - PAD_R)));
    const v =
      this.yMax -
      ((cy - PAD_T) / (this.canvas.height - PAD_T - PAD_B)) * (this.yMax - this.yMin);
    return [t, v];
  }

  private fromClient(clientX: number, clientY: number): Pt {
    const rect = this.canvas.getBoundingClientRect();
    return [
      (clientX - rect.left) * (this.canvas.width / rect.width),
      (clientY - rect.top) * (this.canvas.height / rect.height),
    ];
  }

  private nearestIndex(cx: number, cy: number): number {
    let best = -1;
    let bestD = Infinity;
    this.curve.forEach((p, i) => {
      const [x, y] = this.toCanvas(p);
      const d = Math.hypot(x - cx, y - cy);
      if (d < bestD) {
        bestD = d;
        best = i;
      }
    });
    return bestD <= PICK_RADIUS ? best : -1;
  }

  private bindEvents(): void {
    const c = this.canvas;
    c.addEventListener('pointerdown', (e) => {
      const [cx, cy] = this.fromClient(e.clientX, e.clientY);
      const idx = this.nearestIndex(cx, cy);
      if (idx >= 0) {
        this.dragging = idx;
      } else {
        this.addKeyframeAt(cx, cy);
      }
      c.setPointerCapture(e.pointerId);
      e.preventDefault();
    });
    c.addEventListener('pointermove', (e) => {
      const [cx, cy] = this.fromClient(e.clientX, e.clientY);
      if (this.dragging >= 0) {
        this.updateDragging(cx, cy);
      }
      this.draw();
    });
    const end = (): void => {
      if (this.dragging >= 0) {
        this.dragging = -1;
        this.commit();
      }
    };
    c.addEventListener('pointerup', end);
    c.addEventListener('pointercancel', end);
    c.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      const [cx, cy] = this.fromClient(e.clientX, e.clientY);
      const idx = this.nearestIndex(cx, cy);
      if (idx >= 0) {
        this.curve.splice(idx, 1);
        this.computeRange();
        this.commit();
      }
      this.draw();
    });
  }

  private addKeyframeAt(cx: number, cy: number): void {
    const [t, v] = this.toValue(cx, cy);
    // 与既有点太近（x 方向）则跳过
    if (this.curve.some((k) => Math.abs(k[0] - t) < 0.02)) {
      // 选中最近点便于拖动
      const idx = this.nearestIndex(cx, cy);
      if (idx >= 0) this.dragging = idx;
      return;
    }
    this.curve.push([t, v]);
    this.curve.sort((a, b) => a[0] - b[0]);
    this.computeRange();
    this.commit();
  }

  private updateDragging(cx: number, cy: number): void {
    const idx = this.dragging;
    const [t, v] = this.toValue(cx, cy);
    // t 钳在相邻关键帧之间（留 0.01 空隙），v 自由
    const prev = idx > 0 ? this.curve[idx - 1][0] + 0.01 : 0;
    const next = idx < this.curve.length - 1 ? this.curve[idx + 1][0] - 0.01 : 1;
    const tClamped = Math.min(Math.max(t, prev), next);
    this.curve[idx] = [tClamped, v];
    // 拖动中实时提交（防抖由 autosave 处理）
    this.commit();
  }

  private commit(): void {
    this.onChange(this.getCurve());
  }

  private draw(): void {
    const ctx = this.ctx;
    const W = this.canvas.width;
    const H = this.canvas.height;
    ctx.clearRect(0, 0, W, H);

    // 背景
    ctx.fillStyle = '#0d1220';
    ctx.fillRect(0, 0, W, H);

    // 0 / 1 参考线
    for (const ref of [0, 1]) {
      const y = this.toCanvas([0, ref])[1];
      ctx.strokeStyle = 'rgba(255,255,255,0.12)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(PAD_L, y);
      ctx.lineTo(W - PAD_R, y);
      ctx.stroke();
      ctx.fillStyle = 'rgba(255,255,255,0.35)';
      ctx.font = '9px sans-serif';
      ctx.textAlign = 'right';
      ctx.fillText(String(ref), PAD_L - 4, y + 3);
    }

    // x 刻度
    ctx.textAlign = 'center';
    for (const tx of [0, 0.5, 1]) {
      const x = this.toCanvas([tx, 0])[0];
      ctx.strokeStyle = 'rgba(255,255,255,0.07)';
      ctx.beginPath();
      ctx.moveTo(x, PAD_T);
      ctx.lineTo(x, H - PAD_B);
      ctx.stroke();
      ctx.fillStyle = 'rgba(255,255,255,0.3)';
      ctx.fillText(String(tx), x, H - 4);
    }
    ctx.textAlign = 'left';

    // 曲线
    if (this.curve.length > 0) {
      ctx.strokeStyle = '#44ccff';
      ctx.lineWidth = 1.6;
      ctx.beginPath();
      this.curve.forEach((p, i) => {
        const [x, y] = this.toCanvas(p);
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      });
      ctx.stroke();
    }

    // 关键帧点
    this.curve.forEach((p, i) => {
      const [x, y] = this.toCanvas(p);
      ctx.fillStyle = i === this.dragging ? '#ffdd66' : '#e8f4ff';
      ctx.beginPath();
      ctx.arc(x, y, i === this.dragging ? 4.5 : 3.5, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = '#0b0e14';
      ctx.lineWidth = 1;
      ctx.stroke();
    });
  }
}
