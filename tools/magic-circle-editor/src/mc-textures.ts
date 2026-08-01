/**
 * MC 粒子贴图加载。贴图来自 MC 1.21.1 资源 jar（assets/minecraft/textures/particle/*.png，
 * 16×16），由 Vite 经 import.meta.glob 全量导入并内联为 base64 数据 URL，随单文件打包。
 *
 * 用法：启动时 `void ensureLoaded().then(draw)`；渲染前用 `getTexture(name)` 取图，
 * 未就绪返回 undefined（渲染跳过该粒子）。
 */

// 自动加载 src/assets/mc/ 下全部 PNG（文件名去扩展名作为 key）。
const modules = import.meta.glob('./assets/mc/*.png', { eager: true, as: 'url' });

const URLS: Record<string, string> = {};
for (const [path, url] of Object.entries(modules)) {
  const name = path.slice(path.lastIndexOf('/') + 1).replace(/\.png$/, '');
  URLS[name] = url;
}

const cache = new Map<string, HTMLImageElement>();
let loadPromise: Promise<void> | null = null;

function loadOne(name: string): Promise<void> {
  return new Promise((resolve) => {
    const img = new Image();
    img.onload = () => resolve();
    img.onerror = () => resolve(); // 缺图不阻塞渲染，getTexture 返回 undefined 由渲染层跳过
    img.src = URLS[name];
    cache.set(name, img);
  });
}

export function ensureLoaded(): Promise<void> {
  if (loadPromise) return loadPromise;
  loadPromise = Promise.all(Object.keys(URLS).map(loadOne)).then(() => undefined);
  return loadPromise;
}

export function getTexture(name: string): HTMLImageElement | undefined {
  return cache.get(name);
}

/** 取贴图数据 URL（面板小预览用，无需等加载完成）。 */
export function textureUrl(name: string): string | undefined {
  return URLS[name];
}
