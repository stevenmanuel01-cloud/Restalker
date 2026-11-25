/* Restalker v88 - the Amazon checkout for the OTHER buying browsers (Chrome, Edge, Hidden Chrome, Firefox, Camoufox).
 * (owner 2026-11-01: "incorporate it as you have for all other stores. All stores ... buy all browsers simultaneously")
 * It is the buying browser's OWN Amazon checkout (catalog.js + logic.js + content.js, unchanged - every money check is the same), put
 * into the page by Restalker. Where content.js would talk to its extension, this part talks to Restalker instead:
 *  - the purchase state (which step) is kept in the tab's sessionStorage, so it survives the page changes of a checkout;
 *  - every message content.js sends is kept there too; Restalker reads them (FLOW_BLOCKED, PURCHASE_SUCCESS, ...);
 *  - "SUBMITTING" (right before Place your order) waits for Restalker's answer: Restalker takes the ONE-order latch for the product
 *    (shared with the buying browser and every other browser) - only the browser holding it may press Place your order.
 * Only Buy now / Pre-order is used here (never the shared cart). Nothing is ever typed: no password, no card number.
 */
(() => {
  if (globalThis.__RK_AMZ_PART__) return;
  globalThis.__RK_AMZ_PART__ = true;
  const outer = chrome;                       // (Restalker's stand-in: registers the answer function for its questions)
  const SS = {get: k => { try { return sessionStorage.getItem(k); } catch { return null; } },
              set: (k, v) => { try { sessionStorage.setItem(k, v); } catch {} },
              del: k => { try { sessionStorage.removeItem(k); } catch {} }};
  const msgs = () => { try { return JSON.parse(SS.get('__rkAmzMsgs') || '[]'); } catch { return []; } };
  const push = m => { const n = Number(SS.get('__rkAmzN') || 0) + 1; SS.set('__rkAmzN', String(n)); m.n = n; const a = msgs(); a.push(m); SS.set('__rkAmzMsgs', JSON.stringify(a.slice(-200))); return n; };
  const handlers = [];
  const local = {runtime: {onMessage: {addListener(fn) { handlers.push(fn); }}, sendMessage: async (m) => {
    const t = m && m.type;
    if (t === 'FLOW_GET') { try { return {flow: JSON.parse(SS.get('__rkAmzFlow') || 'null')}; } catch { return {flow: null}; } }
    if (t === 'FLOW_SAVE') { SS.set('__rkAmzFlow', JSON.stringify(m.flow || null)); return {ok: true}; }
    if (t === 'FLOW_CLEAR') { SS.del('__rkAmzFlow'); return {ok: true}; }
    const keep = {type: t, productId: m.productId, reason: m.reason || m.decision || null, price: m.price ?? null, orderNumber: m.orderNumber || null,
                  fees: m.fees || null, at: Date.now(), page: location.pathname.slice(0, 60),
                  step: m.page || null, label: m.label || null, headings: m.headings || null, notes: m.notes || null,
                  limitSeller: m.limitSeller || null, limitSellerName: m.limitSellerName || null};   // v91; v155: which seller Amazon's purchase limit is for
    if (t === 'STATUS' || t === 'CHECKOUT_PRICE_EVIDENCE') { if (t === 'CHECKOUT_PRICE_EVIDENCE') push(keep); return {ok: true}; }
    const n = push(keep);
    if (t === 'SUBMITTING') {                 // the ONE-order latch: Restalker answers (ok = this browser presses Place your order)
      const end = Date.now() + 100000;          // (2.2: Who buys = both - the phone may wait up to 85 s for the PC's own try)
      while (Date.now() < end) {
        const v = SS.get('__rkAmzReply' + n);
        if (v) { SS.del('__rkAmzReply' + n); try { return JSON.parse(v); } catch { return {ok: false}; } }
        await new Promise(r => setTimeout(r, 25));
      }
      return {ok: false, reason: 'Restalker did not answer in time'};
    }
    return {ok: true};
  }}};
  (function (chrome) {
/*__RK_AMZ_BUNDLE__*/
  })(local);
  const content = (m) => new Promise(res => { const h = handlers[0]; if (!h) return res({ok: false, error: 'no Amazon checkout'}); try { h(m, {}, v => res(v)); } catch (e) { res({ok: false, error: String(e)}); } });
  outer.runtime.onMessage.addListener(async (m, _s, respond) => {
    try {
      if (m.bb === 'ping') return respond({ok: true, url: location.href, ready: document.readyState});
      if (m.bb === 'msgs') { const since = Number(m.since) || 0; return respond({ok: true, msgs: msgs().filter(x => x.n > since), last: Number(SS.get('__rkAmzN') || 0), flow: !!SS.get('__rkAmzFlow')}); }
      if (m.bb === 'reply') { SS.set('__rkAmzReply' + m.n, JSON.stringify(m.value || {ok: false})); return respond({ok: true}); }
      if (m.bb === 'reset') { SS.del('__rkAmzMsgs'); SS.del('__rkAmzFlow'); SS.del('__rkAmzN'); return respond({ok: true}); }
      if (m.bb === 'eligible') {
        const r = await content({type: 'REVALIDATE_QUEUED', product: m.product});
        let kind = null; try { kind = (globalThis.__rkAmzEntry && globalThis.__rkAmzEntry()) || null; } catch {}
        return respond({...(r || {}), kind, ready: document.readyState, title: (document.querySelector('#productTitle') || {}).innerText || document.title});   // (v155 ready: how far the page has been read - every answer says it)
      }
      if (m.bb === 'begin') return respond(await content({type: 'BEGIN_PURCHASE', product: m.product, listingPrice: m.price, hitDetectedAt: m.hit}));
      // v114 (diagnostics 92): every order the product is IN (its own link inside the order), not "the code appears somewhere on the
      // page" - Restalker counts only an order that was not there before the checkout began (a "Buy one more" finds the first order too)
      if (m.bb === 'orders') {
        const h = document.documentElement.innerHTML, a = String(m.asin || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
        const nums = [...h.matchAll(/\b(\d{3}-\d{7}-\d{7})\b/g)].map(x => [x.index, x[1]]), rx = new RegExp('(?:/dp/|/gp/product/|asin=)' + a, 'ig'), ids = [];
        let x; while (a && (x = rx.exec(h))) { let id = null; for (const [i, n] of nums) { if (i < x.index) id = n; else break; } if (id && !ids.includes(id)) ids.push(id); }
        return respond({ok: true, found: ids.length > 0, ids, signin: /\/ap\/signin/.test(location.href)});
      }
      respond({ok: false, error: 'unknown question'});
    } catch (e) { respond({ok: false, error: String(e)}); }
  });
})();
