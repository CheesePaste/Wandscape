import type { MagicCircleSpec } from './spec';
import { normalizeSpec, validateSpec } from './spec';

const STORAGE_KEY = 'wandscape.magic-circle-editor.v1';

/** 序列化为可读 JSON（2 空格缩进）。 */
export function serializeSpec(spec: MagicCircleSpec): string {
  return JSON.stringify(spec, null, 2) + '\n';
}

/** 解析文本并归一化+校验；不合法时抛 Error（中文消息）。 */
export function parseSpec(text: string): MagicCircleSpec {
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch (err) {
    throw new Error(`JSON 解析失败：${err instanceof Error ? err.message : String(err)}`);
  }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new Error('JSON 顶层必须是对象');
  }
  const norm = normalizeSpec(raw as MagicCircleSpec);
  const errs = validateSpec(norm);
  if (errs.length > 0) {
    throw new Error(`契约校验失败：\n- ${errs.join('\n- ')}`);
  }
  return norm;
}

/** 触发浏览器下载 `${id}.json`。 */
export function downloadJson(spec: MagicCircleSpec): void {
  const blob = new Blob([serializeSpec(spec)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${spec.id || 'magic_circle'}.json`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export function readFileAsText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error ?? new Error('读取文件失败'));
    reader.readAsText(file);
  });
}

/** localStorage 自动保存（草稿）。失败仅告警，不中断编辑。 */
export function saveToLocal(spec: MagicCircleSpec): void {
  try {
    localStorage.setItem(STORAGE_KEY, serializeSpec(spec));
  } catch (err) {
    console.warn('[magic-circle-editor] localStorage 保存失败', err);
  }
}

/** 读取 localStorage 草稿；缺失或损坏返回 null。 */
export function loadFromLocal(): MagicCircleSpec | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    return parseSpec(raw);
  } catch (err) {
    console.warn('[magic-circle-editor] localStorage 草稿损坏，忽略', err);
    return null;
  }
}
