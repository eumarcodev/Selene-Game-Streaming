// Shared building blocks of the Helios / Selene icon family.
// The same file lives in Helios-Server/branding and Selene-Game-Streaming/branding;
// keep both copies identical so the two icons stay one family.
'use strict';

// Brand palette (both sides).
const C = {
  obsidian: '#0A0B0F',
  gold: '#F5B82E', orange: '#FF6B1A', amber: '#FFC861', ruby: '#E0263B',
  silver: '#D9DEE8', cyan: '#5CE1FF', sapphire: '#2F5BFF', amethyst: '#8B5CF6',
};

const f = (n) => Number(n.toFixed(1)).toString();
const oct = (cx, cy, r) => {
  const p = [];
  for (let k = 0; k < 8; k++) {
    const a = ((22.5 + 45 * k) * Math.PI) / 180;
    p.push([cx + r * Math.cos(a), cy + r * Math.sin(a)]);
  }
  return p;
};
const pts = (p) => p.map(([x, y]) => `${f(x)},${f(y)}`).join(' ');
const polyD = (p) => 'M ' + p.map(([x, y]) => `${f(x)} ${f(y)}`).join(' L ') + ' Z';

// Shared <defs> pieces: glow filter and clip paths.
const commonDefs = `
  <filter id="glow" filterUnits="userSpaceOnUse" x="-1024" y="-1024" width="4096" height="3072">
    <feGaussianBlur stdDeviation="10" result="b"/>
    <feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge>
  </filter>
  <clipPath id="emblemClip"><circle cx="512" cy="512" r="342"/></clipPath>
  <clipPath id="roundTile"><rect width="1024" height="1024" rx="230"/></clipPath>`;

// Emblem coordinates: 1024 x 1024, centered on 512,512.
function gem(cx, cy, r, fill, bezel, facet) {
  const o = oct(cx, cy, r), b = oct(cx, cy, r * 1.13), t = oct(cx, cy, r * 0.52);
  let s = `<polygon points="${pts(b)}" fill="url(#${bezel})" stroke="#000" stroke-opacity=".5" stroke-width="3"/>`;
  s += `<polygon points="${pts(o)}" fill="url(#${fill})"/>`;
  s += o.map((v, i) => `<line x1="${f(v[0])}" y1="${f(v[1])}" x2="${f(t[i][0])}" y2="${f(t[i][1])}" stroke="${facet}" stroke-opacity=".45" stroke-width="2.5"/>`).join('');
  s += `<polygon points="${pts(t)}" fill="${facet}" fill-opacity=".16" stroke="${facet}" stroke-opacity=".65" stroke-width="2.5"/>`;
  const hx = cx - r * 0.3, hy = cy - r * 0.42;
  s += `<ellipse cx="${f(hx)}" cy="${f(hy)}" rx="${f(r * 0.3)}" ry="${f(r * 0.1)}" transform="rotate(-30 ${f(hx)} ${f(hy)})" fill="#fff" fill-opacity=".5"/>`;
  return s;
}
const studPts = (x, y) => [[x, y - 20], [x + 14, y], [x, y + 20], [x - 14, y]];
const stud = (x, y, fill, metal) =>
  `<polygon points="${pts(studPts(x, y))}" fill="${fill}" stroke="url(#${metal})" stroke-width="4"/>`;
const ring = (metal, accent) =>
  `<circle cx="512" cy="512" r="318" fill="none" stroke="url(#${metal})" stroke-width="20"/>` +
  `<circle cx="512" cy="512" r="296" fill="none" stroke="${accent}" stroke-opacity=".35" stroke-width="3"/>`;
const waveD = (x, y, seg, n, amp) => {
  let d = `M ${f(x)} ${f(y)} q ${f(seg / 2)} ${-amp} ${f(seg)} 0`;
  for (let i = 1; i < n; i++) d += ` t ${f(seg)} 0`;
  return d;
};
// Wave list entries: [x, y, segment, count, amplitude, width]
const waves = (list, stroke) =>
  `<g filter="url(#glow)" stroke="url(#${stroke})" stroke-linecap="round" fill="none">` +
  list.map(([x, y, s, n, a, w]) => `<path d="${waveD(x, y, s, n, a)}" stroke-width="${w}"/>`).join('') + `</g>`;

const fit = (inner, s) => `<g transform="translate(512 512) scale(${s}) translate(-512 -512)">${inner}</g>`;
const svg = (defs, w, h, body) =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}" width="${w}" height="${h}"><defs>${defs}${commonDefs}\n</defs>${body}</svg>\n`;

module.exports = { C, f, oct, pts, polyD, gem, studPts, stud, ring, waveD, waves, fit, svg };
