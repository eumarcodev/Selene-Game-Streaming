// Generates the Selene (moon) icons as SVG, plus the Android vector drawable of the monochrome mark.
// Usage: node branding/generate.cjs [outDir]   (default: branding/svg)
// Rasters (launcher PNGs, banners) come from branding/export.py.
// The matching Helios (sun) icons are generated in Helios-Server/branding.
'use strict';
const fs = require('fs');
const path = require('path');
const { C, f, oct, polyD, gem, studPts, stud, ring, waveD, waves, fit, svg } = require('./icon-core.cjs');

const out = process.argv[2] || path.join(__dirname, 'svg');
fs.mkdirSync(out, { recursive: true });
const write = (name, content) => fs.writeFileSync(path.join(out, name), content);

const defs = `
  <radialGradient id="sBg" cx="50%" cy="50%" r="72%">
    <stop offset="0" stop-color="#0B2346"/><stop offset=".5" stop-color="#0C111D"/><stop offset="1" stop-color="${C.obsidian}"/>
  </radialGradient>
  <radialGradient id="lBg" cx="50%" cy="45%" r="75%">
    <stop offset="0" stop-color="#16131A"/><stop offset="1" stop-color="${C.obsidian}"/>
  </radialGradient>
  <radialGradient id="sGlow"><stop offset="0" stop-color="${C.cyan}" stop-opacity=".45"/><stop offset="1" stop-color="${C.sapphire}" stop-opacity="0"/></radialGradient>
  <linearGradient id="silver" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#FFFFFF"/><stop offset=".35" stop-color="${C.silver}"/><stop offset=".7" stop-color="#7C879C"/><stop offset="1" stop-color="#EEF2FA"/>
  </linearGradient>
  <linearGradient id="moon" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#FFFFFF"/><stop offset=".45" stop-color="${C.silver}"/><stop offset="1" stop-color="#8E99B0"/>
  </linearGradient>
  <radialGradient id="sapphire" cx="40%" cy="36%" r="70%">
    <stop offset="0" stop-color="#C8F6FF"/><stop offset=".3" stop-color="${C.cyan}"/><stop offset=".68" stop-color="${C.sapphire}"/><stop offset="1" stop-color="#121A5A"/>
  </radialGradient>
  <linearGradient id="recv" gradientUnits="userSpaceOnUse" x1="40" y1="0" x2="320" y2="0">
    <stop offset="0" stop-color="${C.orange}" stop-opacity="0"/><stop offset=".3" stop-color="${C.orange}" stop-opacity=".85"/><stop offset=".7" stop-color="${C.cyan}"/><stop offset="1" stop-color="#C8F6FF"/>
  </linearGradient>`;

// Crescent open to the left (outer circle (512,512) r250, cut circle (420,512) r215),
// waves arriving from the left: the client receives the stream and reflects it.
const CRESCENT = 'M 377.6 301.2 A 250 250 0 1 1 377.6 722.8 A 215 215 0 1 0 377.6 301.2 Z';
const S_WAVES = [[70, 452, 58, 4, 20, 12], [50, 512, 64, 4, 24, 16], [70, 572, 58, 4, 20, 12]];
const S_STUDS = [[512, 194], [512, 830], [830, 512]];
const S_GEM = [430, 512, 120];
function selene() {
  let s = `<circle cx="512" cy="512" r="330" fill="url(#sGlow)"/>`;
  s += ring('silver', C.silver);
  s += `<path d="${CRESCENT}" fill="url(#moon)" stroke="#5B6478" stroke-width="3"/>`;
  s += `<path d="M 377.6 722.8 A 215 215 0 1 0 377.6 301.2" fill="none" stroke="${C.cyan}" stroke-width="6" filter="url(#glow)" stroke-opacity=".9"/>`;
  s += waves(S_WAVES, 'recv');
  s += S_STUDS.map(([x, y]) => stud(x, y, C.amethyst, 'silver')).join('');
  s += gem(...S_GEM, 'sapphire', 'silver', '#E6FBFF');
  return s;
}

// Monochrome silhouette (themed icon, notification icon, TV channel logo).
function monoShapes() {
  const ringD = 'M 512 184 A 328 328 0 1 1 511.9 184 Z M 512 206 A 306 306 0 1 0 512.1 206 Z';
  const [gx, gy, gr] = S_GEM;
  return [
    { d: ringD, evenOdd: true },
    { d: CRESCENT },
    { d: polyD(oct(gx, gy, gr * 1.13)) + ' ' + polyD(oct(gx, gy, gr * 0.82)), evenOdd: true },
    { d: polyD(oct(gx, gy, gr * 0.45)) },
    ...S_STUDS.map(([x, y]) => ({ d: polyD(studPts(x, y)) })),
    ...S_WAVES.map(([x, y, s, n, a, w]) => ({ d: waveD(x, y, s, n, a), stroke: true, width: w + 6 })),
  ];
}
const monoSvg = () => monoShapes().map((p) => p.stroke
  ? `<path d="${p.d}" fill="none" stroke="#fff" stroke-width="${p.width}" stroke-linecap="round"/>`
  : `<path d="${p.d}" fill="#fff"${p.evenOdd ? ' fill-rule="evenodd"' : ''}/>`).join('');

// Banner (Android TV / OUYA): emblem on the left, wordmark on the right.
function banner(w, h) {
  const r = h * 0.36, cx = h * 0.46, s = r / 340;
  const tx = cx + r + h * 0.12, tw = w - tx - h * 0.12;
  return svg(defs, w, h,
    `<rect width="${w}" height="${h}" fill="url(#lBg)"/>` +
    `<g transform="translate(${f(cx - 512 * s)} ${f(h / 2 - 512 * s)}) scale(${f(s * 1000) / 1000})">${selene()}</g>` +
    `<text x="${f(tx)}" y="${f(h * 0.53)}" textLength="${f(tw)}" lengthAdjust="spacingAndGlyphs" font-family="Cinzel, 'Trajan Pro', Georgia, serif" font-size="${f(h * 0.2)}" fill="url(#silver)">SELENE</text>` +
    `<text x="${f(tx)}" y="${f(h * 0.7)}" textLength="${f(tw)}" lengthAdjust="spacing" font-family="Segoe UI, Roboto, Arial, sans-serif" font-size="${f(h * 0.075)}" fill="${C.cyan}">GAME STREAMING</text>`);
}

// Full-bleed square (Play Store icon).
write('selene-icon.svg', svg(defs, 1024, 1024, `<rect width="1024" height="1024" fill="url(#sBg)"/>${fit(selene(), 0.92)}`));
// Rounded tile (legacy launcher icon, README).
write('selene-icon-rounded.svg', svg(defs, 1024, 1024,
  `<g clip-path="url(#roundTile)"><rect width="1024" height="1024" fill="url(#sBg)"/>${fit(selene(), 1.0)}</g>`));
// Adaptive icon layers (108dp canvas, emblem inside the 66dp safe zone).
write('selene-foreground.svg', svg(defs, 1024, 1024, fit(selene(), 0.92)));
write('selene-background.svg', svg(defs, 1024, 1024, `<rect width="1024" height="1024" fill="url(#sBg)"/>`));
write('selene-monochrome.svg', svg(defs, 1024, 1024, fit(monoSvg(), 0.92)));
write('selene-monochrome-small.svg', svg(defs, 1024, 1024, fit(monoSvg(), 1.42)));
write('selene-banner-tv.svg', banner(320, 180));
write('selene-banner-ouya.svg', banner(732, 412));

// Android vector drawable of the monochrome mark (TV channel logo).
const items = monoShapes().map((p) => p.stroke
  ? `      <path android:pathData="${p.d}" android:strokeColor="#FFFFFF" android:strokeWidth="${p.width}" android:strokeLineCap="round"/>`
  : `      <path android:pathData="${p.d}" android:fillColor="#FFFFFF"${p.evenOdd ? ' android:fillType="evenOdd"' : ''}/>`).join('\n');
write('selene-monochrome.xml',
  `<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by branding/generate.cjs -->\n` +
  `<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="24dp" android:height="24dp"\n` +
  `    android:viewportWidth="1024" android:viewportHeight="1024">\n  <group android:pivotX="512" android:pivotY="512" android:scaleX="1.42" android:scaleY="1.42">\n` +
  items + `\n  </group>\n</vector>\n`);
console.log(`Selene SVGs written to ${out}`);
