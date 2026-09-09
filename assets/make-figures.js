/*
 * Generates the figures in assets/ from the Linux-server benchmark numbers
 * reported in README.md ("Primary result — Linux server, median of 5").
 *
 * The data below is the single source of truth for both figures; if the
 * benchmark is re-run, edit the arrays here and regenerate.
 *
 *   node assets/make-figures.js chart  assets/chart.html
 *   node assets/make-figures.js poster assets/poster.html
 *
 * then screenshot each at 2x with headless Chrome:
 *
 *   chrome --headless=new --disable-gpu --hide-scrollbars \
 *          --force-device-scale-factor=2 --window-size=1200,660 \
 *          --screenshot=assets/throughput-vs-threads.png assets/chart.html
 *
 *   chrome --headless=new --disable-gpu --hide-scrollbars \
 *          --force-device-scale-factor=2 --window-size=1200,1350 \
 *          --screenshot=assets/erq-linkedin-hero.png assets/poster.html
 *
 * The .html files are intermediates and are not committed.
 */

const fs = require('fs');

/* ---------- data: Linux server, median of 5 (README.md) ---------- */
const threads = [1, 2, 4, 8, 16, 24, 32, 48, 64, 80, 96, 128];
const S = {
  erq: {
    name: 'ERQ', full: 'Elastic Relaxed Queue (ours)',
    color: '#3987e5',
    med: [29.8, 8.2, 10.0, 10.0, 16.9, 17.7, 21.3, 37.9, 25.2, 26.0, 39.0, 29.3],
    min: [26.7, 7.1, 8.8, 9.5, 14.3, 16.7, 16.0, 32.5, 22.4, 20.0, 27.9, 26.1],
    max: [30.5, 8.4, 10.4, 11.0, 17.5, 19.6, 27.2, 41.4, 26.1, 35.0, 41.2, 37.7],
  },
  rcqb: {
    name: 'RCQB', full: 'Relaxed Concurrent Queue (Kappes, 2022)',
    color: '#d95926',
    med: [32.1, 12.0, 9.3, 11.1, 11.7, 5.2, 13.6, 20.2, 15.0, 14.4, 17.2, 18.0],
    min: [31.9, 11.7, 8.7, 10.8, 4.1, 3.6, 11.7, 4.4, 13.7, 2.8, 3.5, 4.5],
    max: [32.3, 13.7, 15.8, 11.4, 15.8, 16.0, 17.5, 20.5, 15.5, 21.2, 19.2, 18.1],
  },
  msq: {
    name: 'MSQ', full: 'Michael–Scott Queue (1996)',
    color: '#199e70',
    // no min/max: MSQ's per-trial spread was not recorded, so it is a median line only
    med: [43.9, 9.0, 5.9, 3.0, 2.3, 2.4, 2.2, 1.9, 1.4, 1.7, 2.0, 2.0],
    min: null, max: null,
  },
};

const YMAX = 45;
const n = v => Math.round(v * 100) / 100;

/* ---------- the chart, at whatever box it is given ---------- */
function chartSVG(W, H, opts) {
  const L = 52, R = 132, T = 30, B = 50;
  const x0 = L, x1 = W - R, y0 = T, y1 = H - B;
  const pw = x1 - x0, ph = y1 - y0;
  const step = pw / (threads.length - 1);
  const X = i => x0 + i * step;
  const Y = v => y1 - (v / YMAX) * ph;

  const linePath = a => a.map((v, i) => (i ? 'L' : 'M') + n(X(i)) + ' ' + n(Y(v))).join(' ');
  const bandPath = s => {
    const up = s.max.map((v, i) => (i ? 'L' : 'M') + n(X(i)) + ' ' + n(Y(v))).join(' ');
    let dn = '';
    for (let i = s.min.length - 1; i >= 0; i--) dn += 'L' + n(X(i)) + ' ' + n(Y(s.min[i])) + ' ';
    return up + ' ' + dn + 'Z';
  };

  let g = '';

  // gridlines + y ticks
  for (const v of [0, 10, 20, 30, 40]) {
    const y = n(Y(v));
    const stroke = v === 0 ? '#383835' : '#2c2c2a';
    g += '<line x1="' + x0 + '" y1="' + y + '" x2="' + x1 + '" y2="' + y +
         '" stroke="' + stroke + '" stroke-width="1"/>';
    g += '<text x="' + (x0 - 12) + '" y="' + (y + 5) + '" class="tick" text-anchor="end">' + v + '</text>';
  }
  g += '<text x="' + (x0 - 12) + '" y="' + (y0 - 8) + '" class="unit">M ops/sec</text>';

  // min–max bands, under the lines
  for (const k of ['rcqb', 'erq']) {
    g += '<path d="' + bandPath(S[k]) + '" fill="' + S[k].color + '" fill-opacity="0.15"/>';
  }

  // median lines
  for (const k of ['msq', 'rcqb', 'erq']) {
    g += '<path d="' + linePath(S[k].med) + '" fill="none" stroke="' + S[k].color +
         '" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>';
  }

  // markers: filled dot + 2px surface ring so overlaps stay legible
  for (const k of ['msq', 'rcqb', 'erq']) {
    S[k].med.forEach((v, i) => {
      g += '<circle cx="' + n(X(i)) + '" cy="' + n(Y(v)) + '" r="4" fill="' + S[k].color +
           '" stroke="#1a1a19" stroke-width="2"/>';
    });
  }

  // x ticks + axis title
  threads.forEach((t, i) => {
    g += '<text x="' + n(X(i)) + '" y="' + (y1 + 26) + '" class="tick" text-anchor="middle">' + t + '</text>';
  });
  g += '<text x="' + n((x0 + x1) / 2) + '" y="' + (y1 + 46) + '" class="axtitle" text-anchor="middle">Threads</text>';

  // direct end labels at 128 threads
  const last = threads.length - 1;
  for (const k of ['erq', 'rcqb', 'msq']) {
    const s = S[k];
    const y = n(Y(s.med[last]));
    const lx = X(last) + 16;
    g += '<circle cx="' + n(lx + 4) + '" cy="' + y + '" r="4" fill="' + s.color + '"/>';
    g += '<text x="' + n(lx + 16) + '" y="' + (y - 1) + '" class="endname">' + s.name + '</text>';
    g += '<text x="' + n(lx + 16) + '" y="' + (y + 15) + '" class="endval">' + s.med[last].toFixed(1) + ' M</text>';
  }

  // annotation on the MSQ cliff, with a leader line
  if (opts.anno) {
    const ax = X(0), ay = Y(43.9);
    g += '<path d="M' + n(ax + 7) + ' ' + n(ay + 2) + ' L' + n(ax + 26) + ' ' + n(ay + 16) +
         '" stroke="#898781" stroke-width="1" fill="none"/>';
    g += '<text x="' + n(ax + 32) + '" y="' + n(ay + 21) + '" class="anno">' +
         'MSQ leads alone at 43.9 M — then loses 79% of it</text>';
    g += '<text x="' + n(ax + 32) + '" y="' + n(ay + 39) + '" class="anno">' +
         'the moment a second thread appears.</text>';
  }

  return '<svg viewBox="0 0 ' + W + ' ' + H + '" width="' + W + '" height="' + H + '" role="img" ' +
    'aria-label="Throughput in millions of operations per second versus thread count for MSQ, ' +
    'RCQB and ERQ on the Linux server. MSQ starts highest at one thread and collapses to about ' +
    '2 M from eight threads on. RCQB rises but its min-max band swings widely. ERQ has the ' +
    'highest median from 16 threads up and the narrowest band.">' + g + '</svg>';
}

const legend = ['erq', 'rcqb', 'msq'].map(k =>
  '<span class="lg"><i style="background:' + S[k].color + '"></i><b>' + S[k].name +
  '</b><em>' + S[k].full + '</em></span>').join('');

/* ---------- shared styles ---------- */
const CSS = `
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin:0; background:#0d0d0d;
         font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
         -webkit-font-smoothing: antialiased; }
  .card { background:#1a1a19; border:1px solid rgba(255,255,255,.10);
          border-radius:16px; padding:26px 32px 18px; }
  .ct { font-size:21px; font-weight:600; color:#fff; letter-spacing:-.01em; }
  .cs { margin-top:6px; font-size:14.5px; color:#898781; }
  .legend { display:flex; gap:28px; margin:18px 0 6px; flex-wrap:wrap; }
  .lg { display:flex; align-items:center; gap:9px; font-size:14.5px; }
  .lg i { width:16px; height:4px; border-radius:2px; flex:none; }
  .lg b { color:#fff; font-weight:600; }
  .lg em { font-style:normal; color:#898781; }
  svg { display:block; width:100%; height:auto; overflow:visible; }
  .tick { font-size:13.5px; fill:#898781; font-variant-numeric:tabular-nums; }
  .unit { font-size:13px; fill:#898781; }
  .axtitle { font-size:13.5px; fill:#898781; }
  .endname { font-size:15px; font-weight:650; fill:#fff; }
  .endval { font-size:13.5px; fill:#898781; font-variant-numeric:tabular-nums; }
  .anno { font-size:14px; fill:#c3c2b7; }
`;

const CARD_SUB = 'Linux server &middot; 2-second fixed-time window &middot; median of 5 trials ' +
  '&middot; shaded band = min&ndash;max spread';

/* ---------- layout 1: landscape figure for the README ---------- */
function chartPage() {
  return `<title>Throughput vs. thread count</title>
<style>${CSS}
  .page { width:1200px; height:660px; padding:20px; }
  .card { height:100%; }
  .note { margin-top:12px; font-size:12.5px; line-height:1.5; color:#898781; }
</style>
<div class="page">
  <div class="card">
    <div class="ct">Throughput vs. thread count</div>
    <div class="cs">${CARD_SUB}</div>
    <div class="legend">${legend}</div>
    ${chartSVG(1096, 430, { anno: false })}
    <div class="note">MSQ&rsquo;s per-trial spread was not recorded, so it is plotted as a median
    line only. Null dequeues count as completed ops for all three queues alike &mdash; see
    Limitations and caveats.</div>
  </div>
</div>`;
}

/* ---------- layout 2: portrait poster (social / slides) ---------- */
function posterPage() {
  const tiles = [
    ['31 / 31', 'Correctness tests passing', 'No item lost or duplicated, up to 64 threads'],
    ['1 → 64', 'Lanes, decided at runtime', 'K grows past 15% CAS failure, shrinks under 8%'],
    ['14.3 M', 'ERQ’s worst trial, 16+ threads', 'RCQB’s worst over the same range: 2.8 M'],
  ].map(t => '<div class="tile"><div class="tv">' + t[0] + '</div><div class="tl">' + t[1] +
    '</div><div class="ts">' + t[2] + '</div></div>').join('');

  return `<title>ERQ benchmark poster</title>
<style>${CSS}
  .page { width:1200px; height:1350px; padding:56px 56px 44px; display:flex; flex-direction:column; }
  header { display:flex; align-items:flex-start; justify-content:space-between; gap:40px; }
  .eyebrow { font-size:14px; font-weight:600; letter-spacing:.13em; text-transform:uppercase;
             color:#898781; margin-bottom:14px; }
  h1 { margin:0; font-size:50px; line-height:1.04; font-weight:650; color:#fff; letter-spacing:-.022em; }
  .sub { margin:14px 0 0; font-size:19px; line-height:1.5; color:#c3c2b7; max-width:640px; }
  .hero { text-align:right; flex:none; padding-top:26px; }
  .hero .fig { font-size:82px; font-weight:650; color:#fff; line-height:.9; letter-spacing:-.03em; }
  .hero .cap { margin-top:12px; font-size:14px; line-height:1.45; color:#898781;
               max-width:215px; margin-left:auto; }
  .card { margin-top:32px; }
  .tiles { display:grid; grid-template-columns:repeat(3,1fr); gap:16px; margin-top:18px; }
  .tile { background:#1a1a19; border:1px solid rgba(255,255,255,.10); border-radius:14px; padding:20px 22px; }
  .tv { font-size:34px; font-weight:650; color:#fff; letter-spacing:-.02em; line-height:1; }
  .tl { margin-top:11px; font-size:14.5px; font-weight:600; color:#c3c2b7; }
  .ts { margin-top:4px; font-size:13.5px; color:#898781; line-height:1.4; }
  footer { margin-top:auto; padding-top:20px; display:flex; align-items:flex-end;
           justify-content:space-between; gap:36px; }
  .note { font-size:12.5px; line-height:1.55; color:#898781; max-width:790px; }
  .repo { font-size:13px; color:#c3c2b7; text-align:right; flex:none; font-weight:500; line-height:1.5; }
</style>
<div class="page">
  <header>
    <div>
      <div class="eyebrow">Multi-Core Programming &middot; Final Project</div>
      <h1>The queue that adds<br>lanes when it hurts</h1>
      <p class="sub">Three lock-free queues in Java, measured head to head: the 1996 textbook
      baseline, a 2022 relaxed design, and ERQ &mdash; ours, which watches its own CAS failure
      rate and re-shapes itself while running.</p>
    </div>
    <div class="hero">
      <div class="fig">20&times;</div>
      <div class="cap">ERQ over MSQ at 48 threads<br>37.9 vs 1.9 M ops/sec</div>
    </div>
  </header>

  <div class="card">
    <div class="ct">Throughput vs. thread count</div>
    <div class="cs">${CARD_SUB}</div>
    <div class="legend">${legend}</div>
    ${chartSVG(1024, 640, { anno: true })}
  </div>

  <div class="tiles">${tiles}</div>

  <footer>
    <div class="note">50% enqueue / 50% dequeue, fresh queue per trial. Bands show the full
    min&ndash;max spread across the 5 trials; MSQ&rsquo;s per-trial spread was not recorded, so it is
    plotted as a median line only. Null dequeues on a momentarily empty queue count as completed
    ops for all three queues alike &mdash; this inflates absolute figures but not the comparison.
    Full method, caveats and the Ryzen desktop confirmation run are in the repo.</div>
    <div class="repo">github.com/Dima252/<br>Concurrent-Queue-Algorithms-<br>MSQ-RCQB-Elastic-Relaxation</div>
  </footer>
</div>`;
}

/* ---------- entry ---------- */
const [layout, outPath] = process.argv.slice(2);
const pages = { chart: chartPage, poster: posterPage };
if (!pages[layout] || !outPath) {
  console.error('usage: node assets/make-figures.js <chart|poster> <out.html>');
  process.exit(1);
}
fs.writeFileSync(outPath, pages[layout]());
console.log('wrote', outPath);
