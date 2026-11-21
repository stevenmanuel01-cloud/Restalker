const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1243/chrome-linux64/chrome' });
  for (const [f, w, h, o] of [['icon.html', 512, 512, 'restalker-icon-512.png'], ['feature.html', 1024, 500, 'restalker-feature-1024x500.png']]) {
    const p = await b.newPage({ viewport: { width: w, height: h } }); await p.goto('file:///home/claude/store/' + f); await p.screenshot({ path: o, omitBackground: false }); await p.close();
  }
  await b.close();
})();
