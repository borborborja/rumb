"use strict";
/* Rumb desktop mode — vanilla SPA. Served by the phone's embedded server. */

/* ============================================================= *
 *  API helper                                                   *
 * ============================================================= */
async function api(path, opts) {
  const o = Object.assign({ credentials: "include" }, opts || {});
  const res = await fetch(path, o);
  if (res.status === 401) {
    showLogin();
    throw { auth: true };
  }
  return res;
}
async function apiJson(path, opts) {
  const res = await api(path, opts);
  if (!res.ok) throw new Error("HTTP " + res.status);
  return res.json();
}

/* ============================================================= *
 *  Format helpers (all null-guarded)                            *
 * ============================================================= */
function pad2(n) { return n < 10 ? "0" + n : "" + n; }

// milliseconds -> H:MM:SS or M:SS
function hms(ms) {
  if (ms == null || isNaN(ms)) return "—";
  let s = Math.round(ms / 1000);
  const h = Math.floor(s / 3600); s -= h * 3600;
  const m = Math.floor(s / 60); s -= m * 60;
  return h > 0 ? h + ":" + pad2(m) + ":" + pad2(s) : m + ":" + pad2(s);
}
// seconds -> H:MM:SS
function hmsSec(sec) { return sec == null ? "—" : hms(sec * 1000); }

// meters -> "12.3 km"
function km(m) {
  if (m == null || isNaN(m)) return "—";
  return (m / 1000).toFixed(1) + " km";
}
function kmFromKm(v, dec) {
  if (v == null || isNaN(v)) return "—";
  return v.toFixed(dec == null ? 1 : dec) + " km";
}
function num(v, dec, unit) {
  if (v == null || isNaN(v)) return "—";
  return v.toFixed(dec == null ? 0 : dec) + (unit || "");
}
function date(epochMs) {
  if (epochMs == null) return "—";
  const d = new Date(epochMs);
  return pad2(d.getDate()) + "/" + pad2(d.getMonth() + 1) + "/" + d.getFullYear();
}
function dateTime(epochMs) {
  if (epochMs == null) return "—";
  const d = new Date(epochMs);
  return date(epochMs) + " " + pad2(d.getHours()) + ":" + pad2(d.getMinutes());
}
function esc(s) {
  return (s == null ? "" : String(s)).replace(/[&<>"']/g, c =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

/* ============================================================= *
 *  Inline SVG icon system (no emoji)                            *
 *  Each entry: { m:'s'|'f', c: inner svg markup }               *
 *  m='s' -> stroked (currentColor), m='f' -> filled            *
 * ============================================================= */
const ICONS = {
  // --- tabs ---
  library:     { m: "s", c: '<line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3.5" y1="6" x2="3.51" y2="6"/><line x1="3.5" y1="12" x2="3.51" y2="12"/><line x1="3.5" y1="18" x2="3.51" y2="18"/>' },
  routes:      { m: "s", c: '<circle cx="6" cy="19" r="3"/><path d="M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15"/><circle cx="18" cy="5" r="3"/>' },
  competition: { m: "s", c: '<line x1="10" y1="2" x2="14" y2="2"/><line x1="12" y1="14" x2="15" y2="11"/><circle cx="12" cy="14" r="8"/>' },
  records:     { m: "s", c: '<path d="M6 9H4.5a2.5 2.5 0 0 1 0-5H6"/><path d="M18 9h1.5a2.5 2.5 0 0 0 0-5H18"/><path d="M4 22h16"/><path d="M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20.24 7 22"/><path d="M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20.24 17 22"/><path d="M18 2H6v7a6 6 0 0 0 12 0V2Z"/>' },
  progress:    { m: "s", c: '<path d="M3 3v18h18"/><path d="m19 9-5 5-4-4-3 3"/>' },
  // --- activity types (Material-style filled figures) ---
  walk:  { m: "f", c: '<path d="M13.5 5.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zM9.8 8.9 7 23h2.1l1.8-8 2.1 2v6h2v-7.5l-2.1-2 .6-3C14.8 12 16.8 13 19 13v-2c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1-1-1.7-1-.3 0-.5.1-.8.1L6 8.3V13h2V9.6l1.8-.7z"/>' },
  run:   { m: "f", c: '<path d="M13.49 5.48c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm-3.6 13.9 1-4.4 2.1 2v6h2v-7.5l-2.1-2 .6-3c1.3 1.5 3.3 2.5 5.5 2.5v-2c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1-1-1.7-1-.3 0-.5.1-.8.1l-5.2 2.2v4.7h2v-3.4l1.8-.7-1.6 8.1-4.9-1-.4 2 7 1.4z"/>' },
  bike:  { m: "s", c: '<circle cx="18.5" cy="17.5" r="3.3"/><circle cx="5.5" cy="17.5" r="3.3"/><circle cx="15" cy="5" r="1"/><path d="M12 17.5V14l-3-3 4-3 2 3h2"/>' },
  mtb:   { m: "s", c: '<circle cx="18.5" cy="17.5" r="3.3"/><circle cx="5.5" cy="17.5" r="3.3"/><circle cx="12" cy="5" r="1"/><path d="M5.5 17.5 9 8l3 3h3l-2.5 6.5"/><path d="M15 11h2.5l1 6.5"/>' },
  hike:  { m: "s", c: '<path d="m3 20 5-11 3 5 4-8 6 14"/><path d="M3 20h18"/>' },
  ski:   { m: "s", c: '<line x1="3" y1="19" x2="21" y2="15"/><line x1="4" y1="21" x2="20" y2="17"/><circle cx="16" cy="4" r="1.4"/><path d="M9 20l3-7 2 3 3 1"/>' },
  skate: { m: "s", c: '<circle cx="7" cy="18" r="1.8"/><circle cx="17" cy="18" r="1.8"/><path d="M3 14h18l-1.5 2H4.5z"/>' },
  kayak: { m: "s", c: '<line x1="12" y1="2" x2="12" y2="22"/><path d="M12 2c-2 0-3 1.5-3 3.5S10 9 12 9"/><path d="M12 15c2 0 3 1.5 3 3.5S14 22 12 22"/>' },
  swim:  { m: "s", c: '<path d="M2 8c.6.5 1.2 1 2.5 1C7 9 7 7 9.5 7c2.6 0 2.4 2 5 2 2.5 0 2.5-2 5-2 1.3 0 1.9.5 2.5 1"/><path d="M2 13c.6.5 1.2 1 2.5 1 2.5 0 2.5-2 5-2 2.6 0 2.4 2 5 2 2.5 0 2.5-2 5-2 1.3 0 1.9.5 2.5 1"/><path d="M2 18c.6.5 1.2 1 2.5 1 2.5 0 2.5-2 5-2 2.6 0 2.4 2 5 2 2.5 0 2.5-2 5-2 1.3 0 1.9.5 2.5 1"/>' },
  pin:   { m: "s", c: '<path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z"/><circle cx="12" cy="10" r="3"/>' },
  // --- controls / actions ---
  play:     { m: "f", c: '<path d="M8 5v14l11-7z"/>' },
  prev:     { m: "f", c: '<path d="M15 5v14L4 12z"/>' },
  next:     { m: "f", c: '<path d="M9 5v14l11-7z"/>' },
  undo:     { m: "s", c: '<path d="M9 14 4 9l5-5"/><path d="M4 9h10.5A5.5 5.5 0 0 1 20 14.5 5.5 5.5 0 0 1 14.5 20H11"/>' },
  add:      { m: "s", c: '<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>' },
  check:    { m: "s", c: '<polyline points="20 6 9 17 4 12"/>' },
  star:     { m: "f", c: '<path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01z"/>' },
  download: { m: "s", c: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>' },
  upload:   { m: "s", c: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/>' },
  edit:     { m: "s", c: '<path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.12 2.12 0 0 1 3 3L12 15l-4 1 1-4z"/>' },
  trash:    { m: "s", c: '<polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/>' }
};

// Return an inline <svg> string for a named icon. Extra CSS class optional.
function icon(name, cls) {
  const ic = ICONS[name] || ICONS.pin;
  const base = ic.m === "f"
    ? 'fill="currentColor" stroke="none"'
    : 'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"';
  return '<svg class="icon' + (cls ? " " + cls : "") + '" viewBox="0 0 24 24" ' +
    base + ' aria-hidden="true">' + ic.c + "</svg>";
}

// Map an activity type string -> icon name.
const ACT_ICON = {
  trail_running: "run", running: "run", walking: "walk", hiking: "hike", trekking: "hike",
  mountain_biking: "mtb", mtb: "mtb", cycling: "bike", biking: "bike",
  swimming: "swim", skiing: "ski", skating: "skate", kayaking: "kayak", kayak: "kayak",
  default: "pin"
};
function actIcon(t) {
  let name = ACT_ICON.default;
  if (t) {
    const k = t.toLowerCase();
    for (const key in ACT_ICON) {
      if (key !== "default" && k.indexOf(key) >= 0) { name = ACT_ICON[key]; break; }
    }
  }
  return icon(name, "act-icon");
}
const DIFF_LABEL = { EASY: "Fàcil", MODERATE: "Moderat", HARD: "Difícil", VERY_HARD: "Molt difícil" };
function diffBadge(d) {
  if (!d) return "";
  return '<span class="badge diff-' + esc(d) + '">' + (DIFF_LABEL[d] || esc(d)) + "</span>";
}

/* ============================================================= *
 *  Toast + misc UI helpers                                      *
 * ============================================================= */
let toastTimer = null;
function toast(msg, isErr) {
  let el = document.querySelector(".toast");
  if (el) el.remove();
  el = document.createElement("div");
  el.className = "toast" + (isErr ? " err" : "");
  el.textContent = msg;
  document.body.appendChild(el);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.remove(), 3200);
}
function loadingHtml() { return '<div class="loading">Carregant…</div>'; }
function emptyHtml(msg) { return '<div class="empty">' + esc(msg) + "</div>"; }

/* ============================================================= *
 *  Login gate                                                   *
 * ============================================================= */
const $login = document.getElementById("login");
const $app = document.getElementById("app");

function showLogin() {
  $login.classList.remove("hidden");
  $app.classList.add("hidden");
  const pin = document.getElementById("pin");
  pin.value = "";
  setTimeout(() => pin.focus(), 50);
}
function hideLogin() {
  $login.classList.add("hidden");
  $app.classList.remove("hidden");
}

document.getElementById("loginForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const pin = document.getElementById("pin").value.trim();
  const errEl = document.getElementById("loginError");
  errEl.classList.add("hidden");
  try {
    const res = await fetch("/api/auth", {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ pin })
    });
    const data = await res.json().catch(() => ({}));
    if (res.ok && data.ok) {
      hideLogin();
      boot();
    } else {
      errEl.classList.remove("hidden");
    }
  } catch (_) {
    errEl.textContent = "Error de connexió";
    errEl.classList.remove("hidden");
  }
});

/* ============================================================= *
 *  Canvas chart helpers                                         *
 * ============================================================= */
// Prepare a canvas for crisp drawing on HiDPI; returns {ctx,w,h}.
function setupCanvas(cv) {
  const dpr = window.devicePixelRatio || 1;
  const rect = cv.getBoundingClientRect();
  const w = Math.max(1, Math.round(rect.width));
  const h = Math.max(1, Math.round(rect.height));
  cv.width = w * dpr;
  cv.height = h * dpr;
  const ctx = cv.getContext("2d");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  return { ctx, w, h };
}

// Generic line chart: xs/ys arrays (same length). color, fill optional.
function lineChart(cv, xs, ys, opts) {
  opts = opts || {};
  const { ctx, w, h } = setupCanvas(cv);
  const pad = { l: 42, r: 10, t: 10, b: 20 };
  const iw = w - pad.l - pad.r, ih = h - pad.t - pad.b;
  const pts = [];
  for (let i = 0; i < ys.length; i++) if (ys[i] != null && !isNaN(ys[i])) pts.push([xs[i], ys[i]]);
  if (pts.length < 2) {
    ctx.fillStyle = "#8b96a5"; ctx.font = "12px sans-serif";
    ctx.fillText("Sense dades", pad.l, pad.t + ih / 2);
    return;
  }
  const xmin = xs[0], xmax = xs[xs.length - 1] || 1;
  let ymin = Infinity, ymax = -Infinity;
  for (const p of pts) { if (p[1] < ymin) ymin = p[1]; if (p[1] > ymax) ymax = p[1]; }
  if (ymin === ymax) { ymin -= 1; ymax += 1; }
  const sx = v => pad.l + (xmax === xmin ? 0 : (v - xmin) / (xmax - xmin)) * iw;
  const sy = v => pad.t + ih - (v - ymin) / (ymax - ymin) * ih;

  // grid + y labels
  ctx.strokeStyle = "#2a323d"; ctx.fillStyle = "#8b96a5";
  ctx.font = "10px sans-serif"; ctx.lineWidth = 1;
  for (let i = 0; i <= 3; i++) {
    const yv = ymin + (ymax - ymin) * i / 3;
    const y = sy(yv);
    ctx.beginPath(); ctx.moveTo(pad.l, y); ctx.lineTo(w - pad.r, y); ctx.stroke();
    ctx.fillText((opts.fmt ? opts.fmt(yv) : yv.toFixed(0)), 4, y + 3);
  }
  // x labels (km)
  ctx.textAlign = "center";
  for (let i = 0; i <= 4; i++) {
    const xv = xmin + (xmax - xmin) * i / 4;
    ctx.fillText((xv / 1000).toFixed(1), sx(xv), h - 5);
  }
  ctx.textAlign = "left";

  const color = opts.color || "#E63946";
  if (opts.fill) {
    const grad = ctx.createLinearGradient(0, pad.t, 0, pad.t + ih);
    grad.addColorStop(0, opts.fill);
    grad.addColorStop(1, "rgba(0,0,0,0)");
    ctx.beginPath();
    ctx.moveTo(sx(pts[0][0]), sy(pts[0][1]));
    for (const p of pts) ctx.lineTo(sx(p[0]), sy(p[1]));
    ctx.lineTo(sx(pts[pts.length - 1][0]), pad.t + ih);
    ctx.lineTo(sx(pts[0][0]), pad.t + ih);
    ctx.closePath(); ctx.fillStyle = grad; ctx.fill();
  }
  ctx.beginPath();
  ctx.moveTo(sx(pts[0][0]), sy(pts[0][1]));
  for (const p of pts) ctx.lineTo(sx(p[0]), sy(p[1]));
  ctx.strokeStyle = color; ctx.lineWidth = 2; ctx.lineJoin = "round"; ctx.stroke();
}

// Bar chart: labels[], values[]. color.
function barChart(cv, labels, values, opts) {
  opts = opts || {};
  const { ctx, w, h } = setupCanvas(cv);
  const pad = { l: 34, r: 10, t: 10, b: 24 };
  const iw = w - pad.l - pad.r, ih = h - pad.t - pad.b;
  let ymax = 0;
  for (const v of values) if (v > ymax) ymax = v;
  if (ymax === 0) ymax = 1;
  const n = values.length;
  const bw = iw / n * 0.66;
  const step = iw / n;

  ctx.strokeStyle = "#2a323d"; ctx.fillStyle = "#8b96a5"; ctx.font = "10px sans-serif";
  for (let i = 0; i <= 3; i++) {
    const yv = ymax * i / 3, y = pad.t + ih - (yv / ymax) * ih;
    ctx.beginPath(); ctx.moveTo(pad.l, y); ctx.lineTo(w - pad.r, y); ctx.stroke();
    ctx.fillText(yv.toFixed(0), 4, y + 3);
  }
  ctx.textAlign = "center";
  for (let i = 0; i < n; i++) {
    const x = pad.l + step * i + (step - bw) / 2;
    const bh = (values[i] / ymax) * ih;
    ctx.fillStyle = opts.color || "#E63946";
    ctx.fillRect(x, pad.t + ih - bh, bw, bh);
    if (labels[i] != null && (n <= 12)) {
      ctx.fillStyle = "#8b96a5";
      ctx.fillText(labels[i], x + bw / 2, h - 8);
    }
  }
  ctx.textAlign = "left";
}

// Gap chart: green fill below zero (faster), red above (slower).
function gapChart(cv, gaps) {
  const { ctx, w, h } = setupCanvas(cv);
  const pad = { l: 46, r: 10, t: 12, b: 22 };
  const iw = w - pad.l - pad.r, ih = h - pad.t - pad.b;
  if (!gaps || gaps.length < 2) {
    ctx.fillStyle = "#8b96a5"; ctx.font = "12px sans-serif";
    ctx.fillText("Sense dades de diferència", pad.l, pad.t + ih / 2);
    return;
  }
  const xmax = gaps[gaps.length - 1].distM || 1;
  let gmin = Infinity, gmax = -Infinity;
  for (const g of gaps) { if (g.gapSeconds < gmin) gmin = g.gapSeconds; if (g.gapSeconds > gmax) gmax = g.gapSeconds; }
  if (gmin > 0) gmin = 0; if (gmax < 0) gmax = 0;
  if (gmin === gmax) { gmin -= 1; gmax += 1; }
  const sx = v => pad.l + (v / xmax) * iw;
  const sy = v => pad.t + ih - (v - gmin) / (gmax - gmin) * ih;

  // y grid + labels (seconds)
  ctx.strokeStyle = "#2a323d"; ctx.fillStyle = "#8b96a5"; ctx.font = "10px sans-serif";
  for (let i = 0; i <= 4; i++) {
    const yv = gmin + (gmax - gmin) * i / 4, y = sy(yv);
    ctx.beginPath(); ctx.moveTo(pad.l, y); ctx.lineTo(w - pad.r, y); ctx.stroke();
    ctx.fillText((yv > 0 ? "+" : "") + yv.toFixed(0) + "s", 4, y + 3);
  }
  ctx.textAlign = "center";
  for (let i = 0; i <= 4; i++) {
    const xv = xmax * i / 4;
    ctx.fillText((xv / 1000).toFixed(1), sx(xv), h - 5);
  }
  ctx.textAlign = "left";

  const zeroY = sy(0);
  // filled area split at zero line
  function fillArea(sign, color) {
    ctx.beginPath();
    ctx.moveTo(sx(gaps[0].distM), zeroY);
    for (const g of gaps) {
      const gv = sign > 0 ? Math.max(0, g.gapSeconds) : Math.min(0, g.gapSeconds);
      ctx.lineTo(sx(g.distM), sy(gv));
    }
    ctx.lineTo(sx(gaps[gaps.length - 1].distM), zeroY);
    ctx.closePath(); ctx.fillStyle = color; ctx.fill();
  }
  fillArea(-1, "rgba(46,204,113,.35)");  // below zero = faster = green
  fillArea(1, "rgba(230,57,70,.35)");    // above zero = slower = red

  // line
  ctx.beginPath();
  ctx.moveTo(sx(gaps[0].distM), sy(gaps[0].gapSeconds));
  for (const g of gaps) ctx.lineTo(sx(g.distM), sy(g.gapSeconds));
  ctx.strokeStyle = "#e6edf3"; ctx.lineWidth = 1.5; ctx.stroke();

  // zero line
  ctx.beginPath(); ctx.moveTo(pad.l, zeroY); ctx.lineTo(w - pad.r, zeroY);
  ctx.strokeStyle = "#8b96a5"; ctx.lineWidth = 1; ctx.setLineDash([4, 3]); ctx.stroke();
  ctx.setLineDash([]);
}

/* ============================================================= *
 *  Leaflet map management                                       *
 * ============================================================= */
const TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
const TILE_ATTR = '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>';
const maps = {}; // id -> L.Map (so we can tear down)

// Base maps mirror the native app's MapSource catalogue (OSM + ICGC raster layers).
const ICGC_ATTR = '© <a href="https://www.icgc.cat">ICGC</a>';
const ICGC = (layer) => `https://geoserveis.icgc.cat/servei/catalunya/mapa-base/wmts/${layer}/MON3857NW/{z}/{x}/{y}.png`;
const BASE_MAPS = [
  { id: "icgc_topografic", name: "ICGC Topogràfic", url: ICGC("topografic"), attr: ICGC_ATTR, maxZoom: 20 },
  { id: "icgc_topografic_gris", name: "ICGC Topogràfic gris", url: ICGC("topografic-gris"), attr: ICGC_ATTR, maxZoom: 20 },
  { id: "icgc_orto", name: "ICGC Ortofoto", url: ICGC("orto"), attr: ICGC_ATTR, maxZoom: 20 },
  { id: "icgc_orto_hibrida", name: "ICGC Ortofoto híbrida", url: ICGC("orto-hibrida"), attr: ICGC_ATTR, maxZoom: 20 },
  { id: "icgc_geologic", name: "ICGC Geològic", url: ICGC("geologic"), attr: ICGC_ATTR, maxZoom: 20 },
  { id: "osm", name: "OpenStreetMap", url: TILE_URL, attr: TILE_ATTR, maxZoom: 19 },
];
const DEFAULT_BASE_MAP = "icgc_topografic";
function preferredBaseMap() {
  try { return localStorage.getItem("baseMapId") || DEFAULT_BASE_MAP; } catch (e) { return DEFAULT_BASE_MAP; }
}

function destroyMap(key) {
  if (maps[key]) { maps[key].remove(); delete maps[key]; }
}
function newMap(el, key) {
  destroyMap(key);
  const map = L.map(el, { attributionControl: true, zoomControl: true });
  const preferred = preferredBaseMap();
  const overlays = {};
  BASE_MAPS.forEach((b) => {
    const layer = L.tileLayer(b.url, { attribution: b.attr, maxZoom: b.maxZoom });
    overlays[b.name] = layer;
    if (b.id === preferred) layer.addTo(map);
  });
  // Fallback if the stored id no longer matches any layer.
  if (!Object.values(overlays).some((l) => map.hasLayer(l))) overlays[BASE_MAPS[0].name].addTo(map);
  L.control.layers(overlays, null, { collapsed: true }).addTo(map);
  map.on("baselayerchange", (e) => {
    const found = BASE_MAPS.find((b) => b.name === e.name);
    if (found) { try { localStorage.setItem("baseMapId", found.id); } catch (err) {} }
  });
  maps[key] = map;
  return map;
}
// Draw a polyline from samples; fit bounds.
function drawTrackMap(el, key, samples) {
  const map = newMap(el, key);
  const pts = (samples || []).filter(s => s.lat != null && s.lon != null).map(s => [s.lat, s.lon]);
  if (pts.length === 0) { map.setView([41.39, 2.16], 12); return map; }
  const line = L.polyline(pts, { color: "#E63946", weight: 4, opacity: .9 }).addTo(map);
  map.fitBounds(line.getBounds(), { padding: [24, 24] });
  // start / end markers
  L.circleMarker(pts[0], { radius: 6, color: "#fff", fillColor: "#2ECC71", fillOpacity: 1, weight: 2 })
    .addTo(map).bindTooltip("Inici");
  L.circleMarker(pts[pts.length - 1], { radius: 6, color: "#fff", fillColor: "#E63946", fillOpacity: 1, weight: 2 })
    .addTo(map).bindTooltip("Final");
  setTimeout(() => map.invalidateSize(), 60);
  return map;
}

/* ============================================================= *
 *  Tab routing                                                  *
 * ============================================================= */
const TABS = ["library", "routes", "competition", "records", "progress"];
let currentTab = "library";

function switchTab(tab) {
  if (TABS.indexOf(tab) < 0) tab = "library";
  currentTab = tab;
  document.querySelectorAll(".tab").forEach(b =>
    b.classList.toggle("active", b.dataset.tab === tab));
  document.querySelectorAll(".view").forEach(v =>
    v.classList.toggle("active", v.id === "view-" + tab));
  renderTab(tab);
}
document.getElementById("tabs").addEventListener("click", (e) => {
  const btn = e.target.closest(".tab");
  if (btn) switchTab(btn.dataset.tab);
});

function renderTab(tab) {
  if (tab === "library") renderLibrary();
  else if (tab === "routes") renderRoutes();
  else if (tab === "competition") renderCompetitions();
  else if (tab === "records") renderRecords();
  else if (tab === "progress") renderProgress();
}

/* ============================================================= *
 *  Shared track list + detail (used by Library & Routes)        *
 * ============================================================= */
function trackRow(t) {
  return '<tr data-id="' + t.id + '">' +
    '<td class="act-cell">' + actIcon(t.activityType) + "</td>" +
    "<td><b>" + esc(t.name) + "</b>" +
    (t.isCompetition ? '<span class="badge badge-comp">Competició</span>' : "") +
    (t.archived ? '<span class="badge badge-arch">Arxivat</span>' : "") + "</td>" +
    "<td>" + kmFromKm(t.distanceKm) + "</td>" +
    "<td>" + num(t.ascentM, 0, " m") + "</td>" +
    "<td>" + diffBadge(t.difficulty) + "</td>" +
    "<td>" + esc(t.municipality || "—") + "</td>" +
    "<td>" + date(t.createdAt) + "</td>" +
    "</tr>";
}

function trackTable(tracks, onClick) {
  if (!tracks.length) return emptyHtml("Cap activitat encara.");
  const html =
    '<div class="tbl-wrap"><table><thead><tr>' +
    "<th></th><th>Nom</th><th>Distància</th><th>Desnivell</th><th>Dificultat</th><th>Municipi</th><th>Data</th>" +
    "</tr></thead><tbody>" + tracks.map(trackRow).join("") + "</tbody></table></div>";
  const wrap = document.createElement("div");
  wrap.innerHTML = html;
  wrap.querySelectorAll("tr[data-id]").forEach(tr =>
    tr.addEventListener("click", () => onClick(Number(tr.dataset.id))));
  return wrap;
}

// Build the stats grid, hiding null rows.
function statsGrid(s) {
  const rows = [];
  const add = (k, v) => { if (v !== "—" && v != null) rows.push([k, v]); };
  add("Distància", kmFromKm(s.distanceKm));
  add("Temps total", hmsSec(s.totalTimeS));
  add("Temps en moviment", hmsSec(s.movingTimeS));
  add("Vel. mitjana", num(s.avgSpeedKmh, 1, " km/h"));
  add("Vel. màxima", num(s.maxSpeedKmh, 1, " km/h"));
  add("Ascens", num(s.ascentM, 0, " m"));
  add("Descens", num(s.descentM, 0, " m"));
  add("FC mitjana", num(s.avgHr, 0, " bpm"));
  add("FC màxima", num(s.maxHr, 0, " bpm"));
  add("Cadència", num(s.avgCadence, 0, " rpm"));
  add("Potència", num(s.avgPower, 0, " W"));
  add("Calories", num(s.kcal, 0, " kcal"));
  return '<div class="stats-grid">' + rows.map(r =>
    '<div class="stat"><div class="k">' + r[0] + '</div><div class="v">' + r[1] + "</div></div>").join("") + "</div>";
}

// Render a track detail into a container. showHr controls HR chart.
async function renderTrackDetail(container, id, backFn) {
  container.innerHTML = loadingHtml();
  let d;
  try { d = await apiJson("/api/track/" + id); }
  catch (e) { if (!e.auth) container.innerHTML = emptyHtml("No s'ha pogut carregar."); return; }
  const t = d.track, s = d.stats, samples = d.samples || [];
  const hasHr = samples.some(x => x.hr != null);
  const hasEle = samples.some(x => x.ele != null);
  const hasSpeed = samples.some(x => x.speedKmh != null);

  container.innerHTML =
    '<span class="back-link">← Tornar</span>' +
    '<div class="detail-head">' +
    "<div><h2 class=\"title\">" + actIcon(t.activityType) + " " + esc(t.name) +
    (t.isCompetition ? '<span class="badge badge-comp">Competició</span>' : "") + "</h2>" +
    '<p class="subtitle">' + esc(t.municipality || "") + (t.municipality ? " · " : "") +
    date(t.createdAt) + " · " + diffBadge(t.difficulty) + "</p></div>" +
    '<div class="detail-actions">' +
    '<a class="btn" href="/api/track/' + t.id + '/gpx">' + icon("download") + " Baixa GPX</a>" +
    '<button class="btn" data-act="rename">' + icon("edit") + " Reanomenar</button>" +
    '<button class="btn btn-danger" data-act="delete">' + icon("trash") + " Eliminar</button>" +
    "</div></div>" +
    statsGrid(s) +
    '<div class="map" id="detailMap"></div>' +
    '<div class="charts">' +
    (hasEle ? '<div class="chart-box"><h4>Altitud (m) / distància</h4><canvas class="chart" id="cEle"></canvas></div>' : "") +
    (hasSpeed ? '<div class="chart-box"><h4>Velocitat (km/h) / distància</h4><canvas class="chart" id="cSpd"></canvas></div>' : "") +
    (hasHr ? '<div class="chart-box"><h4>Freqüència cardíaca (bpm) / distància</h4><canvas class="chart" id="cHr"></canvas></div>' : "") +
    "</div>";

  container.querySelector(".back-link").addEventListener("click", backFn);
  container.querySelector('[data-act="rename"]').addEventListener("click", () => renameTrack(t, backFn));
  container.querySelector('[data-act="delete"]').addEventListener("click", () => deleteTrack(t, backFn));

  drawTrackMap(container.querySelector("#detailMap"), "detail", samples);

  const xs = samples.map(x => x.distM);
  if (hasEle) lineChart(container.querySelector("#cEle"), xs, samples.map(x => x.ele), { color: "#8b96a5", fill: "rgba(139,150,165,.4)" });
  if (hasSpeed) lineChart(container.querySelector("#cSpd"), xs, samples.map(x => x.speedKmh), { color: "#2ECC71", fill: "rgba(46,204,113,.35)" });
  if (hasHr) lineChart(container.querySelector("#cHr"), xs, samples.map(x => x.hr), { color: "#E63946", fill: "rgba(230,57,70,.3)" });
}

async function renameTrack(t, done) {
  const name = prompt("Nou nom:", t.name);
  if (name == null || !name.trim()) return;
  try {
    const res = await api("/api/track/" + t.id + "/rename", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: name.trim() })
    });
    if (res.ok) { toast("Reanomenat"); done(); }
    else toast("Error en reanomenar", true);
  } catch (e) { if (!e.auth) toast("Error de connexió", true); }
}
async function deleteTrack(t, done) {
  if (!confirm("Eliminar «" + t.name + "»?")) return;
  try {
    const res = await api("/api/track/" + t.id, { method: "DELETE" });
    if (res.ok) { toast("Eliminat"); done(); }
    else toast("Error en eliminar", true);
  } catch (e) { if (!e.auth) toast("Error de connexió", true); }
}

/* ============================================================= *
 *  View: Biblioteca (TRAINING)                                  *
 * ============================================================= */
async function renderLibrary() {
  const v = document.getElementById("view-library");
  v.innerHTML = '<h2 class="title">Biblioteca</h2><p class="subtitle">Els teus entrenaments</p>' + loadingHtml();
  destroyMap("detail");
  let tracks;
  try { tracks = await apiJson("/api/tracks?kind=TRAINING"); }
  catch (e) { if (!e.auth) v.innerHTML = emptyHtml("No s'ha pogut carregar."); return; }
  const list = document.createElement("div");
  list.appendChild(trackTable(tracks, (id) => openLibraryDetail(id)));
  v.innerHTML = '<h2 class="title">Biblioteca</h2><p class="subtitle">' + tracks.length + " entrenaments</p>";
  v.appendChild(list);
}
function openLibraryDetail(id) {
  const v = document.getElementById("view-library");
  renderTrackDetail(v, id, () => renderLibrary());
}

/* ============================================================= *
 *  View: Per seguir (ROUTE) + import + route creation           *
 * ============================================================= */
async function renderRoutes() {
  const v = document.getElementById("view-routes");
  destroyMap("detail");
  v.innerHTML =
    '<h2 class="title">Per seguir</h2><p class="subtitle">Rutes importades i creades</p>' +
    '<div class="toolbar">' +
    '<label class="btn">' + icon("upload") + ' Importar<input id="importFile" type="file" accept=".gpx,.kml,.tcx" hidden></label>' +
    '<button class="btn btn-primary" id="btnCreate">' + icon("add") + " Crear ruta</button>" +
    "</div>" +
    '<div id="routesList">' + loadingHtml() + "</div>" +
    '<div id="routeEditor"></div>';

  v.querySelector("#importFile").addEventListener("change", handleImport);
  v.querySelector("#btnCreate").addEventListener("click", () => openRouteEditor());

  let tracks;
  try { tracks = await apiJson("/api/tracks?kind=ROUTE"); }
  catch (e) { if (!e.auth) v.querySelector("#routesList").innerHTML = emptyHtml("No s'ha pogut carregar."); return; }
  const listEl = v.querySelector("#routesList");
  listEl.innerHTML = "";
  listEl.appendChild(trackTable(tracks, (id) => openRouteDetail(id)));
}
function openRouteDetail(id) {
  const v = document.getElementById("view-routes");
  destroyMap("routeEdit");
  renderTrackDetail(v, id, () => renderRoutes());
}

async function handleImport(e) {
  const file = e.target.files && e.target.files[0];
  if (!file) return;
  const name = file.name.replace(/\.[^.]+$/, "");
  try {
    const text = await file.text();
    const url = "/api/import?name=" + encodeURIComponent(name) +
      "&kind=ROUTE&filename=" + encodeURIComponent(file.name);
    const res = await api(url, { method: "POST", body: text });
    const data = await res.json().catch(() => ({}));
    if (res.ok && data.ok) { toast("Importat correctament"); renderRoutes(); }
    else toast("Error en importar" + (data.error ? ": " + data.error : ""), true);
  } catch (err) { if (!err.auth) toast("No s'ha pogut llegir el fitxer", true); }
  e.target.value = "";
}

// Route editor with interactive Leaflet map:
// magnetize (snap to trails/roads via /api/route/preview), live distance/ascent,
// mobile-provided profiles, and centering on the phone's current location.
async function openRouteEditor() {
  const ed = document.getElementById("routeEditor");
  document.getElementById("routesList").classList.add("hidden");

  // Profiles come from the app (localized to match mobile); fall back if unavailable.
  let profiles = [];
  try { profiles = await apiJson("/api/profiles"); }
  catch (e) { if (e && e.auth) return; }
  if (!Array.isArray(profiles) || !profiles.length) {
    profiles = [
      { id: "HIKING", label: "Senderisme" },
      { id: "TREKKING", label: "Bici-trekking" },
      { id: "MTB", label: "BTT" },
      { id: "SHORTEST", label: "Més curt" }
    ];
  }
  const profileOpts = profiles.map((p, i) =>
    '<option value="' + esc(p.id) + '"' + (i === 0 ? " selected" : "") + ">" + esc(p.label) + "</option>").join("");

  ed.innerHTML =
    '<span class="back-link">← Tornar a la llista</span>' +
    '<h3 class="section-title">Crear ruta</h3>' +
    '<p class="hint">Clica al mapa per afegir punts de pas. La ruta es magnetitza a camins i carreteres. Calen com a mínim 2 punts.</p>' +
    '<div class="route-editor">' +
    '<div class="route-form">' +
    '<input id="rName" type="text" placeholder="Nom de la ruta" style="min-width:220px">' +
    '<select id="rProfile">' + profileOpts + "</select>" +
    '<span class="wp-count" id="wpCount">0 punts</span>' +
    '<button class="btn" id="rUndo">' + icon("undo") + " Desfés</button>" +
    '<button class="btn btn-ghost" id="rClear">Buidar</button>' +
    '<button class="btn btn-primary" id="rSave">' + icon("check") + " Desar</button>" +
    "</div>" +
    '<div class="route-readout">' +
    '<div class="route-stat"><span class="k">Distància</span><span class="v" id="rDist">—</span></div>' +
    '<div class="route-stat"><span class="k">Ascens</span><span class="v" id="rAsc">—</span></div>' +
    '<div class="route-status" id="rStatus"></div>' +
    "</div>" +
    '<div class="map map-lg" id="routeMap"></div>' +
    "</div>";

  ed.querySelector(".back-link").addEventListener("click", closeRouteEditor);

  const map = newMap(ed.querySelector("#routeMap"), "routeEdit");
  map.setView([41.3874, 2.1686], 12);
  setTimeout(() => map.invalidateSize(), 60);

  // Center on the phone's current location if available (404 -> keep default view).
  (async () => {
    try {
      const res = await api("/api/location");
      if (!res.ok) return;
      const loc = await res.json().catch(() => null);
      if (loc && loc.lat != null && loc.lng != null) {
        map.setView([loc.lat, loc.lng], 15);
        L.circleMarker([loc.lat, loc.lng], {
          radius: 7, color: "#2ECC71", weight: 2, fillColor: "#2ECC71", fillOpacity: .35
        }).addTo(map).bindTooltip("Ubicació actual");
      }
    } catch (e) { /* ignore: leave default view */ }
  })();

  const waypoints = [];
  const markers = [];
  // Drawn route: snapped polyline when the preview succeeds, dashed straight line otherwise.
  const routeLine = L.polyline([], { color: "#E63946", weight: 4, opacity: .9 }).addTo(map);
  const countEl = ed.querySelector("#wpCount");
  const distEl = ed.querySelector("#rDist");
  const ascEl = ed.querySelector("#rAsc");
  const statusEl = ed.querySelector("#rStatus");
  const profileEl = ed.querySelector("#rProfile");

  let previewTimer = null;
  let previewSeq = 0; // guards against out-of-order responses

  function setStats(distanceM, ascentM) {
    distEl.textContent = (distanceM == null || isNaN(distanceM)) ? "—" : km(distanceM);
    ascEl.textContent = num(ascentM, 0, " m");
  }
  function drawStraight() {
    routeLine.setLatLngs(waypoints.map(w => [w.lat, w.lng]));
    routeLine.setStyle({ dashArray: "6,7" });
  }
  function setStatus(kind, text) {
    statusEl.className = "route-status" + (kind ? " " + kind : "");
    if (kind === "busy") statusEl.innerHTML = '<span class="spinner"></span> Calculant…';
    else statusEl.textContent = text || "";
  }

  async function runPreview() {
    const profile = profileEl.value;
    if (waypoints.length < 2) { drawStraight(); setStats(null, null); setStatus(); return; }
    const seq = ++previewSeq;
    setStatus("busy");
    try {
      const res = await api("/api/route/preview", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: "", profile, waypoints: waypoints.map(w => ({ lat: w.lat, lng: w.lng })) })
      });
      if (seq !== previewSeq) return; // a newer request superseded this one
      if (!res.ok) throw new Error("preview " + res.status);
      const data = await res.json();
      if (seq !== previewSeq) return;
      const pts = (data.points || []).filter(p => p.lat != null && p.lng != null).map(p => [p.lat, p.lng]);
      if (pts.length >= 2) {
        routeLine.setLatLngs(pts);
        routeLine.setStyle({ dashArray: null });
      } else {
        drawStraight();
      }
      setStats(data.distanceM, data.ascentM);
      setStatus();
    } catch (err) {
      if (err && err.auth) return;
      if (seq !== previewSeq) return;
      // Graceful fallback: show the straight line and a subtle note; never block.
      drawStraight();
      setStats(null, null);
      setStatus("warn", "No s'ha pogut calcular la ruta");
    }
  }
  function schedulePreview() {
    clearTimeout(previewTimer);
    previewTimer = setTimeout(runPreview, 250);
  }

  function refresh() {
    countEl.textContent = waypoints.length + (waypoints.length === 1 ? " punt" : " punts");
    if (waypoints.length < 2) { drawStraight(); setStats(null, null); setStatus(); }
    else schedulePreview();
  }

  map.on("click", (e) => {
    const wp = { lat: e.latlng.lat, lng: e.latlng.lng };
    waypoints.push(wp);
    const idx = markers.length;
    const m = L.circleMarker([wp.lat, wp.lng], {
      radius: 6, color: "#fff", weight: 2, fillColor: "#E63946", fillOpacity: 1, className: "wp-marker"
    }).addTo(map).bindTooltip("" + (idx + 1));
    markers.push(m);
    refresh();
  });
  ed.querySelector("#rUndo").addEventListener("click", () => {
    if (!waypoints.length) return;
    waypoints.pop();
    const m = markers.pop();
    if (m) map.removeLayer(m);
    refresh();
  });
  ed.querySelector("#rClear").addEventListener("click", () => {
    waypoints.length = 0;
    markers.forEach(m => map.removeLayer(m));
    markers.length = 0;
    refresh();
  });
  profileEl.addEventListener("change", () => { if (waypoints.length >= 2) schedulePreview(); });
  ed.querySelector("#rSave").addEventListener("click", async () => {
    const name = ed.querySelector("#rName").value.trim();
    const profile = profileEl.value;
    if (!name) { toast("Posa un nom a la ruta", true); return; }
    if (waypoints.length < 2) { toast("Calen com a mínim 2 punts", true); return; }
    try {
      const res = await api("/api/route", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, profile, waypoints })
      });
      const data = await res.json().catch(() => ({}));
      if (res.ok && data.ok) { toast("Ruta desada"); closeRouteEditor(); renderRoutes(); }
      else toast("Error en desar" + (data.error ? ": " + data.error : ""), true);
    } catch (err) { if (!err.auth) toast("Error de connexió", true); }
  });
}
function closeRouteEditor() {
  destroyMap("routeEdit");
  document.getElementById("routeEditor").innerHTML = "";
  const rl = document.getElementById("routesList");
  if (rl) rl.classList.remove("hidden");
}

/* ============================================================= *
 *  View: Competició                                             *
 * ============================================================= */
async function renderCompetitions() {
  const v = document.getElementById("view-competition");
  destroyMap("detail");
  v.innerHTML = '<h2 class="title">Competició</h2><p class="subtitle">Els teus reptes</p>' + loadingHtml();
  let comps;
  try { comps = await apiJson("/api/competitions"); }
  catch (e) { if (!e.auth) v.innerHTML = emptyHtml("No s'ha pogut carregar."); return; }
  if (!comps.length) {
    v.innerHTML = '<h2 class="title">Competició</h2>' + emptyHtml("Cap competició encara.");
    return;
  }
  const cards = comps.map(c =>
    '<div class="card" data-ref="' + c.refId + '">' +
    '<div class="card-head"><div><div class="card-title">' +
    actIcon(c.activityType) + " " + esc(c.name) + "</div>" +
    '<div class="card-meta">' + c.attemptCount + (c.attemptCount === 1 ? " intent" : " intents") + "</div></div></div>" +
    '<div class="card-big">' + hms(c.bestMs) + "</div>" +
    '<div class="card-meta">Millor temps</div></div>').join("");
  v.innerHTML = '<h2 class="title">Competició</h2><p class="subtitle">' + comps.length + " reptes</p>" +
    '<div class="grid">' + cards + "</div>";
  v.querySelectorAll(".card[data-ref]").forEach(c =>
    c.addEventListener("click", () => openCompetition(Number(c.dataset.ref))));
}

async function openCompetition(refId) {
  const v = document.getElementById("view-competition");
  v.innerHTML = loadingHtml();
  let d;
  try { d = await apiJson("/api/competition/" + refId); }
  catch (e) { if (!e.auth) v.innerHTML = emptyHtml("No s'ha pogut carregar."); return; }
  const rows = (d.attempts || []).map(a =>
    '<tr class="norow' + (a.isBest ? " best" : "") + '">' +
    "<td>" + dateTime(a.dateMs) + (a.isBest ? " " + icon("star", "star-best") : "") + "</td>" +
    "<td>" + hms(a.durationMs) + "</td>" +
    "<td>" + num(a.avgSpeedKmh, 1, " km/h") + "</td>" +
    "<td>" + num(a.avgHr, 0, " bpm") + "</td></tr>").join("");
  v.innerHTML =
    '<span class="back-link">← Tornar</span>' +
    '<h2 class="title">' + esc(d.name) + "</h2>" +
    '<p class="subtitle">' + (d.attempts ? d.attempts.length : 0) + " intents</p>" +
    '<div class="tbl-wrap"><table><thead><tr><th>Data</th><th>Temps</th><th>Vel. mitjana</th><th>FC mitjana</th></tr></thead><tbody>' +
    (rows || '<tr class="norow"><td colspan="4">Sense intents</td></tr>') + "</tbody></table></div>" +
    '<div class="chart-box" style="margin-top:20px"><h4>Diferència de l\'últim intent vs. millor (verd = més ràpid, vermell = més lent)</h4>' +
    '<canvas class="chart tall" id="cGap"></canvas></div>';
  v.querySelector(".back-link").addEventListener("click", () => renderCompetitions());
  gapChart(v.querySelector("#cGap"), d.gap || []);
}

/* ============================================================= *
 *  View: Rècords                                                *
 * ============================================================= */
const REC_LABEL = {
  FASTEST_1K: "1 km més ràpid", FASTEST_5K: "5 km més ràpid", FASTEST_10K: "10 km més ràpid",
  FASTEST_HALF: "Mitja marató més ràpida", LONGEST_DISTANCE: "Distància més llarga",
  MAX_ASCENT: "Ascens màxim", MAX_SPEED: "Velocitat màxima", LONGEST_TIME: "Durada més llarga"
};
function recordValue(r) {
  switch (r.kind) {
    case "LONGEST_DISTANCE": return kmFromKm((r.value || 0) / 1000, 1);
    case "MAX_ASCENT": return num(r.value, 0, " m");
    case "MAX_SPEED": return num(r.value, 1, " km/h");
    case "LONGEST_TIME": return hms(r.value);
    default: return hms(r.valueMs); // FASTEST_* time records
  }
}
const TROPHY_SVG =
  '<svg viewBox="0 0 24 24" fill="none"><path d="M6 3h12v3a6 6 0 0 1-12 0V3Z" fill="#E63946"/>' +
  '<path d="M4 4H2v2a4 4 0 0 0 4 4M20 4h2v2a4 4 0 0 1-4 4" stroke="#f0ad4e" stroke-width="1.6"/>' +
  '<path d="M10 12h4v3h-4z" fill="#f0ad4e"/><path d="M8 20h8v1.5H8z" fill="#f0ad4e"/>' +
  '<path d="M10 15h4l1 5H9l1-5Z" fill="#E63946"/></svg>';

async function renderRecords() {
  const v = document.getElementById("view-records");
  destroyMap("detail");
  v.innerHTML = '<h2 class="title">Rècords</h2><p class="subtitle">Les teves millors marques</p>' + loadingHtml();
  let recs;
  try { recs = await apiJson("/api/records"); }
  catch (e) { if (!e.auth) v.innerHTML = emptyHtml("No s'ha pogut carregar."); return; }
  if (!recs.length) { v.innerHTML = '<h2 class="title">Rècords</h2>' + emptyHtml("Cap rècord encara."); return; }
  const cards = recs.map(r =>
    '<div class="card static">' +
    '<div class="card-head"><div class="rec-badge">' + TROPHY_SVG + "</div>" +
    '<div class="card-meta" style="text-align:right">' + date(r.dateMs) + "</div></div>" +
    '<div class="card-title" style="margin-top:6px">' + (REC_LABEL[r.kind] || esc(r.kind)) + "</div>" +
    '<div class="card-big">' + recordValue(r) + "</div>" +
    '<div class="card-meta">' + esc(r.trackName || "—") + "</div></div>").join("");
  v.innerHTML = '<h2 class="title">Rècords</h2><p class="subtitle">' + recs.length + " marques</p>" +
    '<div class="grid">' + cards + "</div>";
}

/* ============================================================= *
 *  View: Progrés                                                *
 * ============================================================= */
function deltaHtml(cur, prev) {
  if (prev == null || prev === 0) {
    if (cur > 0) return '<span class="delta up">nou</span>';
    return '<span class="delta flat">—</span>';
  }
  const pct = Math.round((cur - prev) / prev * 100);
  const cls = pct > 0 ? "up" : pct < 0 ? "down" : "flat";
  const sign = pct > 0 ? "+" : "";
  return '<span class="delta ' + cls + '">' + sign + pct + "%</span>";
}

async function renderProgress() {
  const v = document.getElementById("view-progress");
  destroyMap("detail");
  v.innerHTML = '<h2 class="title">Progrés</h2><p class="subtitle">Últimes 12 setmanes</p>' + loadingHtml();
  let p;
  try { p = await apiJson("/api/progress"); }
  catch (e) { if (!e.auth) v.innerHTML = emptyHtml("No s'ha pogut carregar."); return; }
  const weeks = p.weeks || [];
  const cur = weeks[weeks.length - 1] || { km: 0, hours: 0, ascentM: 0, count: 0 };
  const prev = weeks[weeks.length - 2] || { km: 0, hours: 0, ascentM: 0, count: 0 };

  const tile = (k, v2, d) =>
    '<div class="tile"><div class="k">' + k + '</div><div class="v">' + v2 + "</div>" + d + "</div>";

  v.innerHTML =
    '<h2 class="title">Progrés</h2><p class="subtitle">Aquesta setmana vs. l\'anterior</p>' +
    '<div class="tiles">' +
    tile("Distància", cur.km.toFixed(1) + " km", deltaHtml(cur.km, prev.km)) +
    tile("Temps", cur.hours.toFixed(1) + " h", deltaHtml(cur.hours, prev.hours)) +
    tile("Ascens", cur.ascentM + " m", deltaHtml(cur.ascentM, prev.ascentM)) +
    tile("Activitats", cur.count, deltaHtml(cur.count, prev.count)) +
    "</div>" +
    '<div class="streak">Ratxa: <b>' + p.streakWeeks + "</b> " +
    (p.streakWeeks === 1 ? "setmana seguida" : "setmanes seguides") + "</div>" +
    '<div class="chart-box"><h4>Distància per setmana (km) — 12 setmanes</h4>' +
    '<canvas class="chart tall" id="cWeeks"></canvas></div>' +
    '<h3 class="section-title">Totals històrics</h3>' +
    '<div class="tiles">' +
    tile("Distància total", p.totalKm.toFixed(0) + " km", "") +
    tile("Temps total", p.totalHours.toFixed(0) + " h", "") +
    tile("Ascens total", p.totalAscentM + " m", "") +
    tile("Activitats totals", p.totalCount, "") +
    "</div>";

  const labels = weeks.map(w => {
    const d = new Date(w.startEpochDay * 86400000);
    return pad2(d.getDate()) + "/" + pad2(d.getMonth() + 1);
  });
  barChart(v.querySelector("#cWeeks"), labels, weeks.map(w => w.km), { color: "#E63946" });
}

/* ============================================================= *
 *  Boot                                                         *
 * ============================================================= */
async function boot() {
  hideLogin();
  switchTab("library");
}

// Redraw active-tab charts/maps on resize (debounced).
let resizeTimer = null;
window.addEventListener("resize", () => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(() => {
    if (!$app.classList.contains("hidden")) renderTab(currentTab);
  }, 250);
});

// Populate tab icons from the icon system (single source of truth).
document.querySelectorAll(".tab[data-icon]").forEach(btn =>
  btn.insertAdjacentHTML("afterbegin", icon(btn.dataset.icon)));

// Initial probe: hit a cheap endpoint; 401 -> login, else load app.
(async function init() {
  try {
    const res = await fetch("/api/tracks?kind=TRAINING", { credentials: "include" });
    if (res.status === 401) { showLogin(); return; }
    boot();
  } catch (_) {
    showLogin();
  }
})();
