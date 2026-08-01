/**
 * MC 粒子贴图加载。贴图来自 MC 1.21.1 资源 jar（assets/minecraft/textures/particle/*.png，
 * 16×16），由 Vite 内联为 base64 数据 URL，随单文件打包。
 *
 * 用法：启动时 `void ensureLoaded().then(draw)`；渲染前用 `getTexture(name)` 取图，
 * 未就绪返回 undefined（渲染跳过该粒子）。
 */

import critical_hitUrl from './assets/mc/critical_hit.png';
import enchanted_hitUrl from './assets/mc/enchanted_hit.png';
import flameUrl from './assets/mc/flame.png';
import generic_0Url from './assets/mc/generic_0.png';
import generic_1Url from './assets/mc/generic_1.png';
import generic_2Url from './assets/mc/generic_2.png';
import generic_3Url from './assets/mc/generic_3.png';
import generic_4Url from './assets/mc/generic_4.png';
import generic_5Url from './assets/mc/generic_5.png';
import generic_6Url from './assets/mc/generic_6.png';
import generic_7Url from './assets/mc/generic_7.png';
import glitter_0Url from './assets/mc/glitter_0.png';
import glitter_1Url from './assets/mc/glitter_1.png';
import glitter_2Url from './assets/mc/glitter_2.png';
import glitter_3Url from './assets/mc/glitter_3.png';
import glitter_4Url from './assets/mc/glitter_4.png';
import glitter_5Url from './assets/mc/glitter_5.png';
import glitter_6Url from './assets/mc/glitter_6.png';
import glitter_7Url from './assets/mc/glitter_7.png';
import glowUrl from './assets/mc/glow.png';
import noteUrl from './assets/mc/note.png';
import sga_aUrl from './assets/mc/sga_a.png';
import sga_bUrl from './assets/mc/sga_b.png';
import sga_cUrl from './assets/mc/sga_c.png';
import sga_dUrl from './assets/mc/sga_d.png';
import sga_eUrl from './assets/mc/sga_e.png';
import sga_fUrl from './assets/mc/sga_f.png';
import sga_gUrl from './assets/mc/sga_g.png';
import sga_hUrl from './assets/mc/sga_h.png';
import sga_iUrl from './assets/mc/sga_i.png';
import sga_jUrl from './assets/mc/sga_j.png';
import sga_kUrl from './assets/mc/sga_k.png';
import sga_lUrl from './assets/mc/sga_l.png';
import sga_mUrl from './assets/mc/sga_m.png';
import sga_nUrl from './assets/mc/sga_n.png';
import sga_oUrl from './assets/mc/sga_o.png';
import sga_pUrl from './assets/mc/sga_p.png';
import sga_qUrl from './assets/mc/sga_q.png';
import sga_rUrl from './assets/mc/sga_r.png';
import sga_sUrl from './assets/mc/sga_s.png';
import sga_tUrl from './assets/mc/sga_t.png';
import sga_uUrl from './assets/mc/sga_u.png';
import sga_vUrl from './assets/mc/sga_v.png';
import sga_wUrl from './assets/mc/sga_w.png';
import sga_xUrl from './assets/mc/sga_x.png';
import sga_yUrl from './assets/mc/sga_y.png';
import sga_zUrl from './assets/mc/sga_z.png';
import soul_0Url from './assets/mc/soul_0.png';
import soul_1Url from './assets/mc/soul_1.png';
import soul_2Url from './assets/mc/soul_2.png';
import soul_3Url from './assets/mc/soul_3.png';
import soul_4Url from './assets/mc/soul_4.png';
import soul_5Url from './assets/mc/soul_5.png';
import soul_6Url from './assets/mc/soul_6.png';
import soul_7Url from './assets/mc/soul_7.png';
import soul_8Url from './assets/mc/soul_8.png';
import soul_9Url from './assets/mc/soul_9.png';
import soul_10Url from './assets/mc/soul_10.png';

const URLS: Record<string, string> = {
  critical_hit: critical_hitUrl,
  enchanted_hit: enchanted_hitUrl,
  flame: flameUrl,
  generic_0: generic_0Url,
  generic_1: generic_1Url,
  generic_2: generic_2Url,
  generic_3: generic_3Url,
  generic_4: generic_4Url,
  generic_5: generic_5Url,
  generic_6: generic_6Url,
  generic_7: generic_7Url,
  glitter_0: glitter_0Url,
  glitter_1: glitter_1Url,
  glitter_2: glitter_2Url,
  glitter_3: glitter_3Url,
  glitter_4: glitter_4Url,
  glitter_5: glitter_5Url,
  glitter_6: glitter_6Url,
  glitter_7: glitter_7Url,
  glow: glowUrl,
  note: noteUrl,
  sga_a: sga_aUrl,
  sga_b: sga_bUrl,
  sga_c: sga_cUrl,
  sga_d: sga_dUrl,
  sga_e: sga_eUrl,
  sga_f: sga_fUrl,
  sga_g: sga_gUrl,
  sga_h: sga_hUrl,
  sga_i: sga_iUrl,
  sga_j: sga_jUrl,
  sga_k: sga_kUrl,
  sga_l: sga_lUrl,
  sga_m: sga_mUrl,
  sga_n: sga_nUrl,
  sga_o: sga_oUrl,
  sga_p: sga_pUrl,
  sga_q: sga_qUrl,
  sga_r: sga_rUrl,
  sga_s: sga_sUrl,
  sga_t: sga_tUrl,
  sga_u: sga_uUrl,
  sga_v: sga_vUrl,
  sga_w: sga_wUrl,
  sga_x: sga_xUrl,
  sga_y: sga_yUrl,
  sga_z: sga_zUrl,
  soul_0: soul_0Url,
  soul_1: soul_1Url,
  soul_2: soul_2Url,
  soul_3: soul_3Url,
  soul_4: soul_4Url,
  soul_5: soul_5Url,
  soul_6: soul_6Url,
  soul_7: soul_7Url,
  soul_8: soul_8Url,
  soul_9: soul_9Url,
  soul_10: soul_10Url,
};

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
