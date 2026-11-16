/* Restalker Mobile - the desktop's Amazon checkout (catalog.js + logic.js + content.js inside amz_part.js, all UNCHANGED copies from
 * Restalker v120's buyer_engine) put into the app's own browser. Exactly how the desktop's other buying browsers do it (multibuy.py
 * _part_source): `chrome` is a local stand-in, and the part's answer function is registered on window under a random name. */
(() => {
  const chrome = {runtime: {onMessage: {addListener(fn) { Object.defineProperty(window, '__rk___TOKEN__', {value: fn}); }},
                            sendMessage: () => Promise.resolve()}};
/*__RK_PART__*/
})();
