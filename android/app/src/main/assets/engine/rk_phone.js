/* Restalker Mobile - the desktop's Amazon checkout (catalog.js + logic.js + content.js inside amz_part.js, all UNCHANGED copies from
 * Restalker v120's buyer_engine) put into the app's own browser. Exactly how the desktop's other buying browsers do it (multibuy.py
 * _part_source): `chrome` is a local stand-in, and the part's answer function is registered on window under a random name. */
(() => {
  // 1.5: {bb: "picture"} - the product's main picture on this page (Amazon's own image address; nothing is downloaded here)
  const picture = () => {
    const e = document.querySelector('#landingImage, #imgTagWrapperId img, #main-image, #imgBlkFront');
    let u = e && (e.getAttribute('data-old-hires') || '');
    if (!u && e) { try { const d = JSON.parse(e.getAttribute('data-a-dynamic-image') || '{}'); u = Object.keys(d)[0] || ''; } catch (x) {} }
    if (!u && e) u = e.getAttribute('src') || '';
    if (!u || u.startsWith('data:')) { const m = document.querySelector('meta[property="og:image"]'); u = (m && m.content) || ''; }
    return /^https:\/\//.test(u) ? u : null;
  };
  const chrome = {runtime: {onMessage: {addListener(fn) { Object.defineProperty(window, '__rk___TOKEN__', {value: (m, s, send) => {
                              if (m && m.bb === 'picture') { send({picture: picture()}); return false; }
                              return fn(m, s, send); }}); }},
                            sendMessage: () => Promise.resolve()}};
/*__RK_PART__*/
})();
