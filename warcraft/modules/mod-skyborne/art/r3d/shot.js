const { chromium } = require('/opt/node22/lib/node_modules/playwright');
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 800, height: 1000 } });
  p.on('console', m => console.log('console:', m.text()));
  p.on('pageerror', e => console.log('error:', e.message));
  const [,, url, out, w, h] = process.argv;
  await p.setViewportSize({ width: +w, height: +h });
  await p.goto(url); await p.waitForFunction(() => document.title === 'done', null, { timeout: 60000 });
  await p.screenshot({ path: out }); await b.close();
})();
