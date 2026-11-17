/* Restalker Mobile - the desktop's Amazon checkout (catalog.js + logic.js + content.js inside amz_part.js, all UNCHANGED copies from
 * Restalker v120's buyer_engine) put into the app's own browser. Exactly how the desktop's other buying browsers do it (multibuy.py
 * _part_source): `chrome` is a local stand-in, and the part's answer function is registered on window under a random name. */
(() => {
  // 1.5: {bb: "picture"} - the product's main picture on this page (Amazon's own image address; nothing is downloaded here)
  // 1.7 (owner: "The pictures aren't populating still"): Amazon's phone pages and computer pages keep the picture in different places;
  // every known place is read, and only a real product picture (/images/I/) is taken - never the site's logo / share image / grey pixel.
  // pinfo = which places the page had (for the report when none is found).
  const picture = () => {
    // 1.8 (the 1.7 report: real pages gave picture addresses in forms 1.7 refused): any address of an Amazon product picture is
    // turned into its plain form https://m.media-amazon.com/images/I/<picture id>.jpg (Amazon's picture server makes any size / kind
    // from the id). Site graphics (/images/G/), style sheets and scripts are never taken. The first refused addresses go into pinfo.
    const canon = (u) => {
      const m = /^(?:https?:)?\/\/([a-z0-9.-]*\.)?(media-amazon\.com|ssl-images-amazon\.com|images-amazon\.com)\/images\/I\/([A-Za-z0-9+%-]{5,40})(?:\.[^\/?#]*)?\.(jpe?g|png|webp|avif|gif)(?:[?#].*)?$/i.exec(u);
      return m ? 'https://m.media-amazon.com/images/I/' + m[3] + '.jpg' : null;
    };
    const got = [], seen = [], bad = [];
    const add = (u, where) => {
      u = (u || '').trim(); if (!u || u.startsWith('data:') || u.length > 600) return;
      const c = canon(u); seen.push(where + (c ? '+' : '-'));
      if (c) got.push(c); else if (bad.length < 3 && !/\/images\/G\/|grey-pixel|transparent-pixel/i.test(u)) bad.push(where + ':' + u.slice(0, 90));
    };
    let els = [];
    try { els = Array.from(document.querySelectorAll('#landingImage, #imgTagWrapperId img, #main-image, #imgBlkFront, #ebooksImgBlkFront, ' +
      'img[data-old-hires], img[data-a-hires], #main-image-container img, #image-block img, img.a-dynamic-image')).slice(0, 12); } catch (x) {}
    for (const e of els) {
      add(e.getAttribute('data-old-hires'), 'oldhires'); add(e.getAttribute('data-a-hires'), 'ahires');
      try { const d = JSON.parse(e.getAttribute('data-a-dynamic-image') || '{}');
            Object.keys(d).sort((a, b) => ((d[b] || [0])[0] || 0) - ((d[a] || [0])[0] || 0)).forEach((u) => add(u, 'dyn')); } catch (x) {}
      add(e.getAttribute('data-src'), 'datasrc'); add(e.getAttribute('src'), 'src');
    }
    if (!got.length) {
      try { for (const sc of Array.from(document.scripts).slice(0, 400)) {
        const t = sc.textContent || ''; if (t.indexOf('/images/I/') < 0) continue;
        for (const re of [/"hiRes"\s*:\s*"(https:[^"]+)"/g, /"large"\s*:\s*"(https:[^"]+)"/g, /"mainUrl"\s*:\s*"(https:[^"]+)"/g]) {
          let m, n = 0; while ((m = re.exec(t)) && n++ < 3) add(m[1].replace(/\\\//g, '/'), 'script');
        }
        if (got.length) break;
      } } catch (x) {}
    }
    if (!got.length) {
      for (const q of ['meta[property="og:image"]', 'meta[name="twitter:image"]', 'link[rel="image_src"]']) {
        const m = document.querySelector(q); if (m) add(m.getAttribute('content') || m.getAttribute('href'), q.startsWith('link') ? 'imgsrc' : 'meta');
      }
    }
    return {u: got[0] || null, info: 'els=' + els.length + ' ' + (seen.slice(0, 12).join(',') || 'none') + ' url=' + String(location.pathname).slice(0, 40) +
      (bad.length ? ' refused=' + bad.join(' | ') : '')};
  };
  const chrome = {runtime: {onMessage: {addListener(fn) { Object.defineProperty(window, '__rk___TOKEN__', {value: (m, s, send) => {
                              if (m && m.bb === 'picture') { const r = picture(); send({picture: r.u, pinfo: r.info}); return false; }
                              return fn(m, s, send); }}); }},
                            sendMessage: () => Promise.resolve()}};
/*__RK_PART__*/
})();
