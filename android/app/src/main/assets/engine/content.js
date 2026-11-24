'use strict';
try{globalThis.__rkT?.('engine')}catch{}
const L=globalThis.AmazonBuyerLogic;
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const perfNow=()=>globalThis.performance?.now?.()??Date.now();
const waitDom=(ms=100)=>new Promise(resolve=>{let done=false,obs=null,to=null;const finish=()=>{if(done)return;done=true;if(to!==null)clearTimeout(to);try{obs?.disconnect()}catch{}resolve()};to=setTimeout(finish,ms);try{if(document.documentElement&&typeof MutationObserver!=='undefined'){obs=new MutationObserver(finish);obs.observe(document.querySelector('#desktop_buybox,#buybox,#rightCol,#checkout-container,#spc-orders')||document.body||document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['disabled','aria-disabled','class','value']})}}catch{}});
const txt=e=>(e?.innerText||e?.textContent||e?.value||'').trim();
const body=()=>document.body?.innerText||'';
const send=(type,productId,data={})=>{
 const isEligible=type==='STATUS'&&String(data.status||'').startsWith('ELIGIBLE');
 const started=(isEligible&&!Number.isFinite(Number(data.eventPushedAt)))?{eventPushedAt:Date.now()}:{ };
 // Include the last flow stage with failures, but never add an awaited IPC to the hot checkout path.
 const failure=['FLOW_BLOCKED','ACTION_REQUIRED','SUBMISSION_UNCLEAR','DEFINITE_SUBMISSION_FAILURE'].includes(type);
 const forensic=failure&&flowCache?.id===productId?{flowStep:flowCache.step,perf:flowCache.perf||null,flowAgeMs:Date.now()-Number(flowCache.started||Date.now())}:{ };
 return chrome.runtime.sendMessage({type,productId,...(type==='STATUS'&&!Object.prototype.hasOwnProperty.call(data,'observedAt')?{observedAt:Date.now()}:{}),...started,...data,...forensic}).catch(()=>null);
};
const sendMsg=(...a)=>send(...a);
// v111: the delivery note at most once a minute per product (a few DOM reads; never on the buy path)
const _dlv={};function deliverySeen(p){const k=String(p?.id||''),now=Date.now();if(_dlv[k]&&now-_dlv[k].at<60000)return null;try{const d=deliverability();_dlv[k]={at:now};return d}catch{return null}}


// v3.23 ultra-low-latency DOM detector. Mutations are coalesced into one microtask, not a timer.
let watchedProduct=null,passiveCheckQueued=false,pendingEligible=null,lastMutationWakePerf=0;
function schedulePassiveCheck(mutations){
 if(!watchedProduct||passiveCheckQueued)return;
 passiveCheckQueued=true;const wakePerf=perfNow(),wakeAt=Date.now();lastMutationWakePerf=wakePerf;
 void send('DETECTION_WAKE',watchedProduct.id,{source:'dom_mutation',observedAt:wakeAt,perfNow:wakePerf,mutationCount:Array.isArray(mutations)?mutations.length:null});
 queueMicrotask(async()=>{passiveCheckQueued=false;try{if(await loadFlow())return;await check({...watchedProduct},{trigger:'dom_mutation',triggerAt:wakeAt,triggerPerf:wakePerf})}catch{}});
}
try{
 if(typeof MutationObserver!=='undefined'){
  const install=()=>{const roots=['#desktop_qualifiedBuyBox','#desktop_buybox','#buybox','#rightCol','#buybox_feature_div','#qualifiedBuybox','#newAccordionRow'].map(s=>document.querySelector(s)).filter(Boolean),targets=roots.length?roots:[document.body||document.documentElement];for(const target of targets)new MutationObserver(schedulePassiveCheck).observe(target,{childList:true,subtree:true,characterData:true,attributes:true,attributeFilter:['disabled','aria-disabled','class','value','style','data-a-color','data-a-size']})};
  if(document.body)install();else document.addEventListener('DOMContentLoaded',install,{once:true});
 }
}catch{}
const shown=e=>!!e&&(e.offsetParent!==null||(typeof e.getClientRects==='function'&&e.getClientRects().length>0))&&e.getAttribute('aria-hidden')!=='true';
const visible=e=>shown(e)&&!e.disabled&&e.getAttribute('aria-disabled')!=='true';
const actionVisible=e=>visible(e)||!!(e&&!e.disabled&&e.getAttribute?.('aria-disabled')!=='true'&&e.matches?.('.a-button-input')&&shown(e.closest?.('.a-button')));
function unsafePurchaseContext(e){return !!e?.closest?.('#sims-fbt,#frequentlyBoughtTogether,#bundleV2_feature_div,[data-csa-c-content-id*=\"bundle\" i],[data-card-metrics-id*=\"bundle\" i],[class*=\"bundle\" i],#rhf,[data-csa-c-content-id*=\"recommend\" i],[data-card-metrics-id*=\"recommend\" i]')}
function primaryBuyRoots(){return ['#desktop_qualifiedBuyBox','#buybox','#rightCol','#buybox_feature_div','#desktop_buybox','#qualifiedBuybox','#newAccordionRow'].map(x=>document.querySelector(x)).filter(Boolean)}

const canon=s=>String(s||'').replace(/\s+/g,' ').trim();
function controls(selectors,rx){
 const out=[];
 for(const sel of selectors)for(const e of document.querySelectorAll(sel))if(visible(e)&&!out.includes(e))out.push(e);
 if(rx)for(const e of document.querySelectorAll('button,input[type="submit"],input[type="button"],a[role="button"],span[role="button"]'))if(visible(e)&&rx.test(canon(txt(e)||e.getAttribute('aria-label')||e.getAttribute('value')))&&!out.includes(e))out.push(e);
 return out;
}
function firstControl(selectors,rx){return controls(selectors,rx)[0]||null}
function uniqueControl(selectors,rx){const a=controls(selectors,rx);return a.length===1?a[0]:null}
function add(){return firstControl([
 '#add-to-cart-button','input#add-to-cart-button','input[name="submit.add-to-cart"]','button[name="submit.add-to-cart"]',
 '#desktop_qualifiedBuyBox input[name="submit.add-to-cart"]','#buybox input[name="submit.add-to-cart"]','[data-action="add-to-cart"] input','[data-action="add-to-cart"] button'
 ],/^add to cart$/i)}
function buyNow(){return firstControl([
 '#buy-now-button','input#buy-now-button','input[name="submit.buy-now"]','button[name="submit.buy-now"]',
 '#desktop_qualifiedBuyBox input[name="submit.buy-now"]','#buybox input[name="submit.buy-now"]',
 '[data-action="buy-now"] input','[data-action="buy-now"] button'
 ],/^(buy now|buy it now)$/i)}
// Amazon preorder listings can hide the normal price and use different purchase-control wording.
// Recognize explicit preorder/reservation purchase intent semantically, but reject non-purchase CTAs.
function controlLabel(e){return canon(txt(e)||e?.getAttribute?.('aria-label')||e?.getAttribute?.('value')||e?.getAttribute?.('title'))}
function preorderLabel(s){
 s=canon(s).toLowerCase();if(!s)return false;
 // Informational preorder text (including the real "Pre-order Price Guarantee" panel) is not a purchase action.
 if(/\b(notify|remind|watch|learn|details|terms|list|wishlist|registry|subscribe|sign up|email me|price guarantee|guarantee|savings|release date|arriving|ships?)\b/.test(s))return false;
 const intent=/\b(pre\s*[- ]?\s*order|preorder|pre\s*[- ]?\s*sale|presale|pre\s*[- ]?\s*purchase|prepurchase)\b/.test(s);
 const reserve=/^reserve(?: this item| item)?(?: now)?(?: for \$?[0-9,.]+)?$/i.test(s);
 const bare=/^(?:pre\s*[- ]?\s*order|preorder|pre\s*[- ]?\s*sale|presale|pre\s*[- ]?\s*purchase|prepurchase)$/i.test(s);
 if(reserve||bare)return true;
 return intent&&/\b(now|cart|buy|purchase|order|reserve|submit|checkout)\b/.test(s);
}
function actionablePreorderNode(e){
 if(!e||!actionVisible(e)||unsafePurchaseContext(e))return false;
 const tag=String(e.tagName||'').toLowerCase(),role=String(e.getAttribute?.('role')||'').toLowerCase();
 return ['button','input','a'].includes(tag)||role==='button'||!!e.getAttribute?.('data-action');
}
function preorderCandidates(){
 const scoped=primaryBuyRoots();
 const selectors='button,input[type="submit"],input[type="button"],input[type="image"],input.a-button-input,a[href],a[role="button"],[role="button"],[data-action]';
 const attrRx=/(pre[-_ ]?order|presale|pre[-_ ]?sale|pre[-_ ]?purchase|reserve)/i;
 const out=[];
 const consider=e=>{if(!actionablePreorderNode(e)||out.includes(e))return;const label=controlLabel(e),attrs=[e.id,e.getAttribute?.('name'),e.getAttribute?.('data-action'),e.getAttribute?.('aria-label'),e.getAttribute?.('title'),e.getAttribute?.('value')].filter(Boolean).join(' ');const attrIntent=attrRx.test(attrs)&&(preorderLabel(attrs)||!label||/^(submit|buy now|order now|continue|pre-order now|preorder now)$/i.test(label));if(preorderLabel(label)||attrIntent)out.push(e)};
 const considerTextProxy=e=>{
   const label=controlLabel(e);if(!preorderLabel(label)||unsafePurchaseContext(e))return;
   const wrap=e.closest?.('button,a,[role="button"],.a-button,[data-action]')||e.parentElement;
   if(!wrap||unsafePurchaseContext(wrap))return;
   const action=actionablePreorderNode(wrap)?wrap:[...(wrap.querySelectorAll?.('input.a-button-input,input[type="submit"],input[type="button"],button,a[role="button"]')||[])].find(actionablePreorderNode);
   if(action)consider(action);
 };
 const scan=root=>{for(const e of root.querySelectorAll(selectors))consider(e);for(const e of root.querySelectorAll('span,.a-button-text'))considerTextProxy(e)};
 for(const root of scoped)scan(root)
 if(out.length)return out;
 scan(document);
 return out;
}
function preorder(){
 const a=preorderCandidates();if(!a.length)return null;
 // Equivalent responsive/top-bottom preorder controls are valid; choose deterministically by screen position.
 a.sort((x,y)=>{const xr=x.getBoundingClientRect?.()||{top:0,left:0},yr=y.getBoundingClientRect?.()||{top:0,left:0};return (xr.top-yr.top)||(yr.left-xr.left)});
 return a[0];
}
function preorderEvidence(){
 for(const root of primaryBuyRoots()){const t=canon(txt(root));if(/\bpre\s*[- ]?\s*order now\b/i.test(t)||(/\bpre\s*[- ]?\s*order\b/i.test(t)&&/\b(released|release|ships?|arriv)/i.test(t)))return true}
 return false
}
function preorderAggressive(){
 // Last-resort Amazon buy-box resolver: remain scoped to the primary target buy box and reject disabled/bundle controls.
 for(const root of primaryBuyRoots()){
  const nodes=[...root.querySelectorAll('input,button,a,[role="button"],.a-button')];
  for(const e of nodes){if(unsafePurchaseContext(e))continue;const label=controlLabel(e);if(!preorderLabel(label))continue;
   if(e.matches?.('.a-button')){const child=e.querySelector('input.a-button-input,input[type="submit"],input[type="button"],button,a[role="button"]');if(child&&!child.disabled&&child.getAttribute?.('aria-disabled')!=='true')return child;continue}
   if(!e.disabled&&e.getAttribute?.('aria-disabled')!=='true')return e;
  }
 }
 return null
}
function purchaseEntry(){const p=preorder()||preorderAggressive();if(p)return {control:p,kind:'preorder'};const b=buyNow();if(b)return {control:b,kind:'buy_now'};const a=add();return a?{control:a,kind:'cart'}:null}
function mainAsin(){
 for(const sel of ['#ASIN','input[name="ASIN"]','input[name="asin"]']){const e=document.querySelector(sel),v=String(e?.value||e?.getAttribute('value')||'').trim().toUpperCase();if(/^[A-Z0-9]{10}$/.test(v))return v}
 return L.asinFromUrl(location.href);
}
function pageHasProduct(p){
 const asin=String(p.asin||'').toUpperCase();if(!asin)return false;
 const ma=mainAsin();if(ma)return ma===asin;
 return L.exactUrl(location.href,asin)&&L.exactProduct(body(),p);
}
function priceNodeValue(e,p){
 if(!e)return null;
 const raw=[e.getAttribute?.('content'),e.getAttribute?.('value'),e.getAttribute?.('data-price'),e.getAttribute?.('aria-label'),e.textContent].filter(Boolean).join(' ');
 const vals=[...new Set(L.dollars(raw).filter(n=>L.validPrice(n,p)).map(n=>Number(n.toFixed(2))))];if(vals.length===1)return vals[0];
 const whole=e.querySelector?.('.a-price-whole')?.textContent?.replace(/[^\d]/g,''),frac=e.querySelector?.('.a-price-fraction')?.textContent?.replace(/[^\d]/g,'');
 if(whole&&frac){const n=Number(whole+'.'+frac);if(L.validPrice(n,p))return Number(n.toFixed(2))}
 const direct=Number(e.getAttribute?.('content')||e.getAttribute?.('data-price')||e.value);if(L.validPrice(direct,p))return Number(direct.toFixed(2));
 return null;
}
function collectPrice(selectors,p){
 const vals=[];
 for(const sel of selectors)for(const e of document.querySelectorAll(sel)){
   if(e.closest?.('#snsBuyBox,[data-csa-c-content-id*="subscribe" i],[data-csa-c-content-id*="sns" i]'))continue;
   const isMeta=e.tagName==='META',priceBox=e.closest?.('.a-price,.priceToPay');if(!isMeta&&priceBox&&!shown(priceBox))continue;
   const n=priceNodeValue(e,p);if(n!==null)vals.push(n);
 }
 return [...new Set(vals)];
}
function productPrice(p){
 const groups=[
  ['#corePriceDisplay_desktop_feature_div .priceToPay .a-offscreen','#corePriceDisplay_desktop_feature_div .priceToPay','#corePriceDisplay_desktop_feature_div .a-price[data-a-color="price"] .a-offscreen'],
  ['#apex_desktop .priceToPay .a-offscreen','#apex_desktop .priceToPay','#apex_desktop .a-price[data-a-color="price"] .a-offscreen'],
  ['#corePrice_feature_div .priceToPay .a-offscreen','#corePrice_feature_div .priceToPay','#corePrice_feature_div .a-price .a-offscreen'],
  ['#desktop_qualifiedBuyBox .a-price .a-offscreen','#buybox .a-price .a-offscreen','#price_inside_buybox'],
  ['meta[itemprop="price"]','meta[property="product:price:amount"]','#twister-plus-price-data-price'],
  ['.priceToPay .a-offscreen','.priceToPay']
 ];
 for(const sels of groups){const u=collectPrice(sels,p);if(u.length===1)return u[0];if(u.length>1)return null}
 for(const e of document.querySelectorAll('script[type="application/ld+json"]'))try{
   const raw=JSON.parse(e.textContent||'null'),nodes=Array.isArray(raw)?raw:[raw];
   for(const n of nodes){
     const identity=[n?.sku,n?.productID,n?.url].filter(Boolean).join(' ').toUpperCase();
     if(identity&&!identity.includes(String(p.asin||'').toUpperCase()))continue;
     if(!identity&&n?.name&&!L.exactProduct(n.name,p))continue;
     const offers=Array.isArray(n?.offers)?n.offers:[n?.offers];
     for(const o of offers){const v=Number(o?.price);if(L.validPrice(v,p))return Number(v.toFixed(2))}
   }
 }catch{}
 return null;
}
function cartRoot(){return document.querySelector('#sc-active-cart,#activeCartViewForm,#sc-cart-column,[data-name="Active Items"]')||document}
function notRecommendation(e){return !e.closest?.('#rhf,#sims-fbt,[data-csa-c-content-id*="recommend" i],[data-card-metrics-id*="recommend" i]')}
function exactCart(p){
 const asin=String(p.asin||'').toUpperCase();if(!asin)return null;
 const root=cartRoot();
 let hits=[...root.querySelectorAll(
   `[data-asin="${asin}"],[data-itemid*="${asin}" i],a[href*="${asin}" i],input[value="${asin}"],input[value*="${asin}" i]`
 )].filter(e=>shown(e)&&notRecommendation(e));
 let rows=hits.map(e=>e.closest('.sc-list-item,.sc-list-item-content,[data-asin],[data-itemid],.a-row')||e).filter(Boolean);
 rows=[...new Set(rows)].filter(r=>!rows.some(o=>o!==r&&r.contains(o)));
 if(rows.length===1)return rows[0];
 if(rows.length>1){
   const tokenRows=rows.filter(r=>L.exactProduct(txt(r),p));if(tokenRows.length===1)return tokenRows[0];
 }
 let tokenRows=[...root.querySelectorAll('.sc-list-item,.sc-list-item-content,[data-itemid],.a-row')].filter(e=>shown(e)&&notRecommendation(e)&&L.exactProduct(txt(e),p));
 tokenRows=[...new Set(tokenRows)].filter(r=>!tokenRows.some(o=>o!==r&&r.contains(o)));
 return tokenRows.length===1?tokenRows[0]:null;
}
function activeCartRows(){
 let rows=[...cartRoot().querySelectorAll('.sc-list-item,.sc-list-item-content')].filter(e=>{
  if(!shown(e)||!notRecommendation(e))return false;
  if(e.closest?.('#sc-saved-cart-list,#sc-saved-cart-list-caption'))return false;
  const a=String(e.getAttribute('data-asin')||'').toUpperCase(),link=[...e.querySelectorAll('a[href]')].some(x=>!!L.asinFromUrl(x.href));
  return /^[A-Z0-9]{10}$/.test(a)||link;
 });
 rows=[...new Set(rows)].filter(r=>!rows.some(o=>o!==r&&r.contains(o)));return rows;
}
function cartPrice(p,ref){
 const r=exactCart(p);if(!r)return null;
 const groups=[['.sc-product-price'],['.a-price .a-offscreen'],['.sc-price'],['.a-color-price']];
 for(const sels of groups){const vals=[];for(const sel of sels)for(const e of r.querySelectorAll(sel)){const n=priceNodeValue(e,p);if(n!==null)vals.push(n)}const u=[...new Set(vals)];if(u.length===1)return u[0];if(u.length>1)return null}
 const raw=[...new Set(L.dollars(txt(r)).filter(n=>L.validPrice(n,p)&&L.priceAtOrBelowCap(n,p.cap)).map(n=>Number(n.toFixed(2))))];return raw.length===1?raw[0]:null;
}
function qtyOne(p){const r=exactCart(p);if(!r)return false;const q=r.querySelector('select[name*="quantity"],select[data-a-selector="quantity"],input[name*="quantity"],.a-dropdown-prompt');return q?L.qtyOne(q.value||txt(q),txt(r)):L.qtyOne('',txt(r))}
function checkoutItem(p){
 const asin=String(p.asin||'').toUpperCase();if(!asin)return null;
 const selectors=`[data-asin="${asin}"],[data-itemid*="${asin}" i],a[href*="${asin}" i],input[value="${asin}"],input[value*="${asin}" i]`;
 const hits=[...document.querySelectorAll(selectors)].filter(e=>shown(e)&&notRecommendation(e));
 let rows=hits.map(e=>e.closest('[data-asin],[data-itemid],.order-item,.checkout-item,[data-testid*="item" i],.a-row')||e).filter(Boolean);
 rows=[...new Set(rows)].filter(r=>!rows.some(o=>o!==r&&r.contains(o)));
 if(rows.length===1)return rows[0];
 if(rows.length>1){
   const exact=rows.filter(r=>L.exactProduct(txt(r),p));
   if(exact.length===1)return exact[0];
   const withQty=exact.filter(r=>r.querySelector?.('select[name*="quantity"],input[name*="quantity"],button,[role="button"]'));
   if(withQty.length===1)return withQty[0];
 }
 let candidates=[...document.querySelectorAll('.order-item,.checkout-item,[data-testid*="item" i],[data-itemid],.a-row')].filter(e=>shown(e)&&notRecommendation(e)&&L.exactProduct(txt(e),p));
 candidates=[...new Set(candidates)];
 if(candidates.length===1)return candidates[0];
 if(candidates.length>1){
   const withQty=candidates.filter(r=>r.querySelector?.('select[name*="quantity"],input[name*="quantity"],button,[role="button"]'));
   if(withQty.length===1)return withQty[0];
   // Prefer the smallest token-containing element; checkoutQtyOne can expand to its parent card.
   const leaf=candidates.filter(r=>!candidates.some(o=>o!==r&&r.contains?.(o)));
   if(leaf.length===1)return leaf[0];
 }
 return null;
}
function nodeQtyOne(node){
 if(!node)return false;
 const q=node.querySelector?.('select[name*="quantity"],input[name*="quantity"],.a-dropdown-prompt,[data-a-selector="value"],[data-testid*="quantity" i] input');
 if(q){
   const explicit=canon(q.value||txt(q));
   if(/^\d+$/.test(explicit))return explicit==='1';
   if(L.qtyOne(explicit,''))return true;
 }
 const controls=[...(node.querySelectorAll?.('button,[role="button"],input[type="button"]')||[])].filter(shown);
 const labels=controls.map(e=>canon([txt(e),e.getAttribute?.('aria-label'),e.getAttribute?.('title'),e.id,e.getAttribute?.('name'),e.getAttribute?.('data-action')].filter(Boolean).join(' '))).join(' | ');
 const hasDecrease=/decrease|remove|delete|minus|−/i.test(labels);
 const hasIncrease=/increase|plus|\+/i.test(labels);
 if(hasDecrease&&hasIncrease){
   const lines=String(node.innerText||node.textContent||'').split(/\n+/).map(canon).filter(Boolean);
   const numeric=lines.filter(x=>/^\d+$/.test(x)).map(Number);
   if(numeric.includes(0))return false;
   if(numeric.includes(1))return true;
 }
 return L.qtyOne('',txt(node));
}
function checkoutTokenCard(p,ref){
 // Purely additive strict fast path for a single, explicitly numbered target checkout card.
 // If quantity, product tokens, or exact listing price are ambiguous, use the untouched historical resolver.
 const explicit=[...document.querySelectorAll('select[name*="quantity"],input[name*="quantity"],select[data-a-selector="quantity"],input[data-testid*="quantity" i]')].filter(e=>shown(e)&&canon(e.value||txt(e))==='1');
 const matching=[];
 for(const q of explicit.slice(0,24)){
   for(let node=q.parentElement,depth=0;node&&depth<7;node=node.parentElement,depth++){
     const t=txt(node);
     if(t.length>2500)break;
     if(!L.exactProduct(t,p))continue;
     if(!Number.isFinite(ref)||!L.dollars(t).some(n=>L.priceMatches(n,ref,.02)))continue;
     if(!nodeQtyOne(node))continue;
     matching.push(node);break;
   }
 }
 if(matching.length===1&&!targetCheckoutQuantityZero(p))return matching[0];

 const selectors='.order-item,.checkout-item,[data-testid*="item" i],[data-itemid],.a-row,li,section,div';
 let seeds=[...document.querySelectorAll(selectors)].filter(e=>{
   if(!shown(e)||!notRecommendation(e))return false;
   const t=txt(e);return t.length>=10&&t.length<=2500&&L.exactProduct(t,p);
 });
 // Prefer the smallest exact-title region, then expand upward until price + quantity evidence coexist.
 seeds.sort((a,b)=>txt(a).length-txt(b).length);
 const seen=new Set(),matches=[];
 for(const seed of seeds.slice(0,80)){
   for(let depth=0,node=seed;depth<7&&node;depth++,node=node.parentElement){
     if(seen.has(node))continue;seen.add(node);
     const t=txt(node);if(!L.exactProduct(t,p)||t.length>5000)continue;
     const dollars=L.dollars(t).filter(n=>L.validPrice(n,p));
     const priceMatch=Number.isFinite(ref)&&dollars.some(n=>L.priceMatches(n,ref,.02));
     if(nodeQtyOne(node)&&(priceMatch||dollars.some(n=>L.priceAtOrBelowCap(n,p.cap))))matches.push({node,priceMatch,len:t.length});
   }
 }
 if(!matches.length)return null;
 matches.sort((a,b)=>(Number(b.priceMatch)-Number(a.priceMatch))||(a.len-b.len));
 const best=matches[0];
 // Ambiguity is only material if two equally strong, disjoint product cards exist.
 const rival=matches.find(x=>x!==best&&x.priceMatch===best.priceMatch&&!best.node.contains?.(x.node)&&!x.node.contains?.(best.node));
 if(rival&&Math.abs(rival.len-best.len)<30)return null;
 return best.node;
}
function checkoutIdentity(p,ref){
 const asinItem=checkoutItem(p);
 if(asinItem){
   let node=asinItem;
   if(!nodeQtyOne(node)){
     for(let i=0,n=node.parentElement;i<7&&n;i++,n=n.parentElement){
       if(L.exactProduct(txt(n),p)&&nodeQtyOne(n)){node=n;break}
     }
   }
   if(nodeQtyOne(node))return {item:node,method:'asin',qtyOne:true};
 }
 const tokenItem=checkoutTokenCard(p,ref);
 if(tokenItem&&nodeQtyOne(tokenItem))return {item:tokenItem,method:'title-price-quantity',qtyOne:true};
 return null;
}
function checkoutQtyOne(p,ref=null){
 const id=checkoutIdentity(p,ref);return !!id?.qtyOne;
}
// A target-linked zero quantity is a definite invalid checkout state, not a
// missing ASIN. Never infer sellout from an unrelated order or recommendation.
function targetCheckoutQuantityZero(p){
 const asinItem=checkoutItem(p);
 const selectors='.order-item,.checkout-item,[data-testid*="item" i],[data-itemid],.a-row,li,section';
 const cards=asinItem?[asinItem]:[...document.querySelectorAll(selectors)]
   .filter(e=>shown(e)&&notRecommendation(e)&&txt(e).length<=2200&&L.exactProduct(txt(e),p))
   .sort((x,y)=>txt(x).length-txt(y).length).slice(0,6);
 for(const card of cards){
   const q=card.querySelector?.('select[name*="quantity"],input[name*="quantity"],.a-dropdown-prompt,[data-a-selector="value"],[data-testid*="quantity" i] input');
   const v=canon(q?.value||txt(q));
   if(v==='0')return true;
   const lines=String(txt(card)).split(/\n/).map(canon);
   if(lines.some(x=>/^(?:qty|quantity)\s*:?\s*0$/i.test(x)))return true;
 }
 return false;
}
function moneyAfterLabel(text,labelRx){const lines=String(text||'').split(/\n+/).map(canon).filter(Boolean);for(let i=0;i<lines.length;i++){if(!labelRx.test(lines[i]))continue;const same=L.dollars(lines[i]);if(same.length)return same[same.length-1];for(let j=i+1;j<Math.min(lines.length,i+3);j++){const v=L.dollars(lines[j]);if(v.length)return v[0]}}return null}
// v111: the order summary as label + amount lines only (Items / Shipping / Tax / Total ...), for the diagnostics after a checkout stop.
// Only short lines with a money amount or a summary word; never a line with personal words (names, address, payment)
function summaryLines(){try{const all=[...document.querySelectorAll('#subtotals-marketplace-table,#order-summary,.order-summary,[data-testid*="order-summary" i],#checkout-order-summary')],e=all.find(shown)||all[0];if(!e)return ['(no order summary box on the page)'];
 return String(txt(e)).split(/\n+/).map(canon).filter(x=>x&&x.length<=70&&(/\$\s?\d/.test(x)||/^(items?|subtotal|shipping|delivery|tax|estimated|order total|total|promotion|discount|gift)/i.test(x))&&!personalWords(x)).slice(0,12)}catch{return []}}
function summaryText(){const all=[...document.querySelectorAll('#subtotals-marketplace-table,#order-summary,.order-summary,[data-testid*="order-summary" i],#checkout-order-summary')],e=all.find(shown)||all[0];return e?txt(e):body()}
function checkoutComponents(p,cart){
 const t=summaryText();
 let base=moneyAfterLabel(t,/^(items?|item subtotal|subtotal|merchandise subtotal)(\s*\(\d+\s*items?\))?\s*:?/i),
     shipping=moneyAfterLabel(t,/^(shipping|shipping & handling|shipping and handling|delivery|delivery fee)\s*:?/i),
     tax=moneyAfterLabel(t,/^(estimated tax|estimated tax to be collected|tax|sales tax)\s*:?/i),
     total=moneyAfterLabel(t,/^(order total|grand total|total)\s*:?/i);

 const summary=document.querySelector('#subtotals-marketplace-table,#order-summary,.order-summary,[data-testid*="order-summary" i],#checkout-order-summary')||document;
 const domMoney=(rx)=>{
   for(const e of summary.querySelectorAll('.a-row,[data-testid],li,div')){
     const z=canon(txt(e));if(!rx.test(z))continue;
     const vals=L.dollars(z);if(vals.length)return vals[vals.length-1];
   }
   return null;
 };
 if(base===null)base=domMoney(/^(items?|item subtotal|subtotal|merchandise subtotal)\b/i);
 if(shipping===null)shipping=domMoney(/^(shipping|delivery)\b/i);
 if(tax===null)tax=domMoney(/^(estimated tax|tax|sales tax)\b/i);
 if(total===null)total=domMoney(/^(order total|grand total|total)\b/i);

 if(shipping===null&&/(shipping|shipping & handling|delivery)[^\n]{0,100}\b(free|no charge|\$0\.00)\b/i.test(t))shipping=0;
 if(base===null&&Number.isFinite(cart))base=cart;
 if(tax===null&&/\b(tax|sales tax)[^\n]{0,80}\$0\.00\b/i.test(t))tax=0;
 if(!Number.isFinite(base)||!Number.isFinite(shipping)||!Number.isFinite(tax))return null;
 if(!Number.isFinite(total))return null;
 if(!L.priceAtOrBelowCap(base,p.cap))return null;
 return {base:Number(base),shipping:Number(shipping),tax:Number(tax),total:Number(total)};
}
let flowCache=null;
async function saveFlow(f){flowCache=f;const r=await send('FLOW_SAVE',f.id,{flow:f});if(!r?.ok)flowCache=null;return !!r?.ok}
async function loadFlow(){if(flowCache)return flowCache;const r=await chrome.runtime.sendMessage({type:'FLOW_GET'}).catch(()=>null);flowCache=r?.flow||null;return flowCache}
async function clearFlow(){flowCache=null;await chrome.runtime.sendMessage({type:'FLOW_CLEAR'}).catch(()=>null)}
async function resumeSafe(){try{return await resume()}catch(e){const f=await loadFlow().catch(()=>null);if(f?.id)return send('FLOW_BLOCKED',f.id,{reason:'Purchase runtime error: '+String(e?.message||e)});return null}}
function checkoutButton(){return firstControl([
 'input[name="proceedToRetailCheckout"]','button[name="proceedToRetailCheckout"]',
 '#sc-buy-box-ptc-button input','#sc-buy-box-ptc-button button',
 '#proceed-to-checkout-action input','#proceed-to-checkout-action button',
 '#attach-sidesheet-checkout-button input','#attach-sidesheet-checkout-button button','#attach-sidesheet-checkout-button',
 '#NATC_SMART_WAGON_CONF_MSG_SUCCESS input[name="proceedToRetailCheckout"]',
 '[data-feature-id="proceed-to-checkout-action"] input','[data-feature-id="proceed-to-checkout-action"] button'
 ],/^(proceed to checkout|checkout)$/i)}
function placeOrderButton(){
 const canonical=controls([
   '#submitOrderButtonId input','#submitOrderButtonId button',
   'input[name="placeYourOrder1"]','input[name="placeYourOrder"]',
   'button[name="placeYourOrder1"]','button[name="placeYourOrder"]',
   '[data-testid*="place-order" i]'
 ],/^(place your order|place order)$/i);
 if(!canonical.length)return null;
 // Amazon commonly renders the same valid submit action twice (top summary + page footer).
 // Multiple equivalent visible/enabled Place Order controls are not ambiguous.
 canonical.sort((a,b)=>{
   const ar=a.getBoundingClientRect?.()||{top:0,left:0},br=b.getBoundingClientRect?.()||{top:0,left:0};
   return (ar.top-br.top)||(br.left-ar.left);
 });
 return canonical[0];
}
function confirmationEvidence(){const t=body(),order=L.orderNumber(t),url=String(location.href||'');return {ok:L.confirmation(t)&&(/thank.?you|order.?confirmation|\/gp\/buy\/thankyou/i.test(url)||!!order),orderNumber:order}}
function ensureOneTimePurchase(){
 const candidates=[...document.querySelectorAll('label,span,div')].filter(e=>shown(e)&&/^one[- ]time purchase$/i.test(canon(txt(e))));
 for(const e of candidates){
   const label=e.closest('label')||e;
   const radio=label.querySelector?.('input[type="radio"]')||document.querySelector(`input[type="radio"][value*="one" i]`);
   if(radio&&!radio.checked){try{label.click()}catch{try{radio.click()}catch{}}return true}
 }
 return false;
}
function checkoutInterstitial(){
 if(L.challenge(body()))return 'Amazon verification challenge';
 const actionable=[
   ['input[type="password"],input[name*="password" i]','Amazon requires login/password confirmation'],
   ['input[name*="verification" i],input[autocomplete="one-time-code"]','Amazon requires identity verification'],
   ['button,input[type="submit"]']
 ];
 for(const [sel,reason] of actionable.slice(0,2)){
   const e=[...document.querySelectorAll(sel)].find(visible);if(e)return reason;
 }
 const buttons=[...document.querySelectorAll('button,input[type="submit"],a[role="button"]')].filter(visible)
   .map(e=>canon(txt(e)||e.getAttribute('aria-label')||e.getAttribute('value')));
 if(buttons.some(t=>/^(update payment method|select payment method|choose payment method)$/i.test(t)))return 'Amazon requires payment confirmation';
 if(buttons.some(t=>/^(choose delivery option|select delivery option)$/i.test(t)))return 'Amazon requires delivery confirmation';
 return null;
}
// v155 (owner, 2026-11-24: "it is important to distinguish between sellers ... if it is a different seller, then the limit should not
// apply and checkout should continue"): WHO sells the offer in the buy box. Amazon's own number for the seller (in the buy box's form)
// is the key; the name is for you. Amazon's purchase limit is kept per seller: only the seller Amazon refused rests.
function buyBoxSeller(){
 let id='';
 for(const sel of ['#merchantID','input[name="merchantID"]','input[name="merchantId"]']){const e=document.querySelector(sel),v=String(e?.value||e?.getAttribute?.('value')||'').trim().toUpperCase();if(/^[A-Z0-9]{8,16}$/.test(v)){id=v;break}}
 let name='';
 for(const sel of ['#sellerProfileTriggerId','#merchantInfoFeature_feature_div .offer-display-feature-text-message','#tabular-buybox [tabular-attribute-name="Sold by"] .tabular-buybox-text-message','#tabular-buybox-truncate-1','#merchant-info a','#merchant-info']){const e=document.querySelector(sel);if(!e)continue;let t=canon(e.textContent||'').replace(/^(?:ships from and )?sold by\s*:?\s*/i,'').replace(/\s+and fulfilled by.*$/i,'').replace(/\.\s*$/,'').trim();if(t){name=t.slice(0,60);break}}
 if(!id&&/^amazon(?:\.com)?(?: services llc)?$/i.test(name))id='ATVPDKIKX0DER';
 return {id,name,key:id||(name?'n:'+name.toLowerCase():'')};
}
// -> null (buy), or {key,name} when the seller in the buy box is one Amazon's purchase limit was reached for. A seller that cannot be
// read is never taken for the limited one (it is tried; Amazon's answer then tells).
function sellerLimited(p){
 const m=p?.limitSellers;if(!m||typeof m!=='object')return null;
 const now=Date.now(),act=Object.entries(m).filter(([,v])=>Number(v?.until)>now);if(!act.length)return null;
 const all=act.find(([k])=>k==='*');if(all)return {key:'*',name:''};
 const s=buyBoxSeller();if(!s.key)return null;
 const nm=s.name?s.name.toLowerCase():'';
 const hit=act.find(([k,v])=>k===s.key||(nm&&(k==='n:'+nm||(!s.id||!/^[A-Z0-9]{8,16}$/.test(k))&&String(v?.name||'').toLowerCase()===nm)));
 return hit?{key:hit[0],name:s.name||String(hit[1]?.name||'')}:null;
}
// v155: while the page is still arriving, is the buy box COMPLETE - has the browser read past the end of the form the button is in?
// (Something after the form, or after one of the boxes around it, is in the page.) A page that is read to its end is always complete.
function rkBoxClosed(control){
 if(document.readyState!=='loading')return true;
 let n=control?.form||control?.closest?.('form')||control;if(!n)return false;
 for(let i=0;n&&n!==document.body&&n!==document.documentElement&&i<40;i++,n=n.parentNode)if(n.nextElementSibling)return true;
 return false;
}
function sellerText(root=document){
 const sels=['#merchant-info','#tabular-buybox-truncate-1','#tabular-buybox-truncate-0','[data-csa-c-slot-id*="merchant" i]','[data-testid*="seller" i]'];
 for(const sel of sels){const e=root.querySelector?.(sel);if(e&&shown(e)){const t=canon(txt(e));if(t)return t}}
 return '';
}
function checkoutBasePrice(p,item,ref,summaryBase=null){
 if(item){
   const vals=[];
   for(const sel of ['.a-price .a-offscreen','.a-color-price','.priceToPay .a-offscreen','[data-testid*="price" i]']){
     for(const e of item.querySelectorAll(sel)){const n=priceNodeValue(e,p);if(n!==null)vals.push(n)}
   }
   const u=[...new Set(vals.map(n=>Number(n.toFixed(2))))];
   if(u.length===1)return u[0];
   if(u.length>1){
     // Current Amazon checkout sometimes displays list/reference and selling prices
     // together. Never treat the reference price as the actual payable offer.
     // Require one unique amount on the exact target card that matches BOTH the
     // previously verified listing price and the single-item checkout subtotal.
     const matches=u.filter(n=>L.priceAtOrBelowCap(n,p.cap)&&
       Number.isFinite(ref)&&L.priceMatches(n,ref,.02)&&
       Number.isFinite(summaryBase)&&L.priceMatches(n,summaryBase,.02));
     return matches.length===1?matches[0]:null;
   }
   const raw=[...new Set(L.dollars(txt(item))
     .filter(n=>L.validPrice(n,p)&&L.priceAtOrBelowCap(n,p.cap))
     .map(n=>Number(n.toFixed(2))))];
   if(raw.length===1)return raw[0];
   // Do not invent a price when checkout's actual offer cannot be distinguished.
   return null;
 }
 return null;
}
function retryableCheckoutButton(){
 const co=checkoutButton();if(co)return co;
 return checkoutStepButton();
}
// v91 (diagnostics 75: every Amazon checkout of 11-03 stopped on Amazon's /checkout/p/.../itemselect step "Make updates to your items",
// which shows TWO identical "Continue" buttons (top and bottom of the page). v87 pressed a Continue only when there was exactly ONE,
// so the buying browser never pressed it and waited until its time ran out). Now: the same plainly labelled step button shown more than
// once counts as one button (the top one is pressed). Never a Place order / pay / buy button.
const STEP_LABEL_RX=/^(continue|continue to checkout|proceed to checkout|continue with (?:these|selected|your) items?|check ?out (?:these|selected) items|use this address|deliver to this address|ship to this address|use this payment method|save and continue|continue to review|continue to review order)$/i;
function stepLabelSafe(l){return STEP_LABEL_RX.test(l)&&!/place|pay now|buy|order/i.test(l.replace(/review (?:your )?order/i,''))}
function topFirst(a){return a.slice().sort((x,y)=>{const ar=x.getBoundingClientRect?.()||{top:0,left:0},br=y.getBoundingClientRect?.()||{top:0,left:0};return (ar.top-br.top)||(ar.left-br.left)})}
// (Amazon's buttons are often an <input class="a-button-input"> with NO words of its own - the words are in the span beside it,
// <span class="a-button-text">Continue</span>, linked by aria-labelledby. v91: those words count as the button's label.)
function ctlText(e){
 const own=controlLabel(e);if(own)return own;
 const ids=String(e?.getAttribute?.('aria-labelledby')||'').split(/\s+/).filter(Boolean);
 const by=canon(ids.map(id=>txt(document.getElementById(id))).join(' '));if(by)return by;
 const box=e?.closest?.('.a-button');return box?canon(txt(box.querySelector('.a-button-text')||box)):'';
}
// v94 (review): only on Amazon's checkout pages - never on sign-in / verification pages (/ap/...), where an unlabelled "Continue"
// would send your e-mail or make Amazon send a code, and never on the cart
function checkoutPath(){const x=location.pathname;return /^\/(?:checkout\/|gp\/buy\/|buy\/|gp\/checkoutportal\/)/i.test(x)&&!/^\/ap\//i.test(x)}
function checkoutStepButton(){
 if(!checkoutPath())return null;
 const notCart=e=>!e.closest?.('#sc-buy-box-ptc-button,#proceed-to-checkout-action,[data-feature-id="proceed-to-checkout-action"],#attach-sidesheet-checkout-button,#sc-active-cart')&&!/proceedToRetailCheckout|placeYourOrder/i.test(e.getAttribute?.('name')||'');
 const named=controls(['input[name="continue-bottom"]','button[name="continue-bottom"]','input[name="continue"]','button[name="continue"]',
   '[data-testid*="continue" i] button','[data-testid*="continue" i] input'],null).filter(e=>{const l=ctlText(e);return (!l||stepLabelSafe(l))&&notCart(e)});
 if(named.length)return topFirst(named)[0];
 const cands=[];
 for(const e of document.querySelectorAll('button,input[type="submit"],input[type="button"],a[role="button"],span[role="button"],input.a-button-input')){
   if(cands.includes(e)||!actionVisible(e)||!notCart(e))continue;
   if(e.closest?.('#submitOrderButtonId,[data-testid*="place-order" i]'))continue;
   if(stepLabelSafe(ctlText(e)))cands.push(e);
 }
 if(!cands.length)return null;
 const labels=[...new Set(cands.map(e=>ctlText(e).toLowerCase()))];
 if(labels.length===1)return topFirst(cands)[0];
 const plain=cands.filter(e=>/^continue$/i.test(ctlText(e)));
 return plain.length?topFirst(plain)[0]:null;
}
// v91: what an Amazon checkout STEP page says about the items (its alert / message boxes only - never the address or payment boxes,
// never names, e-mails or long numbers). Used to see at once that Amazon took the product away (sold out under us) instead of waiting.
function personalArea(e){return !!e.closest?.('[id*="address" i],[class*="address" i],[id*="payment" i],[class*="payment" i],[data-testid*="address" i],[data-testid*="payment" i],form[action*="address" i]')}
// v94: personal words never leave the page (the same rule as the store pages' summaries): street-like words, ZIP codes, e-mails,
// "Hi <name>", "ship / deliver / send to <...>"
const personalWords=w=>/\d{2,}\s+[a-z]/i.test(w)||/\b(court|ct|street|st|avenue|ave|road|rd|drive|dr|lane|ln|way|blvd|boulevard|place|pl|circle|cir|terrace|parkway|pkwy|trail|hwy|highway|apt|suite|unit)\b\.?\s*,?\s*\d|\d.*\b(court|street|avenue|road|drive|lane|way|boulevard|place|circle|terrace|parkway|trail|highway)\b/i.test(w)||/@|\b\d{5}(?:-\d{4})?\b/.test(w);
function cleanNote(t){return canon(t).replace(/[\w.+-]+@[\w-]+\.[\w.]+/g,'[email]').replace(/^(hi|hello|hey),?\s+[^,.!]+/i,'$1 [name]').replace(/\b(ship(?:ped|ping)?|deliver(?:ed|y|ing)?|send(?:ing)?|arriving|going) to\b.*$/i,'$1 to …').replace(/\b\d{3,}\b/g,'#').slice(0,200)}
const NOTE_SEL='.a-alert-content,[role="alert"],.a-color-error,.a-color-warning,[data-testid*="alert" i],[data-testid*="error" i],[data-testid*="warning" i],[class*="item-error" i],[class*="itemError" i],[class*="unavailable" i]';
// the page's (or one box's) alert / message words: raw (only for the checks, never sent) and cleaned (for the diagnostics)
function notesIn(root,raw){
 const out=[];
 for(const e of (root||document).querySelectorAll(NOTE_SEL)){
   if(!shown(e)||personalArea(e))continue;const r=canon(txt(e)).slice(0,300);if(r.length<6||personalWords(r))continue;
   const t=raw?r:cleanNote(r);if(!out.includes(t))out.push(t);if(out.length>=5)break;
 }
 return out;
}
function checkoutNotes(){return notesIn(document,false)}
// v94 (review): "Amazon took the product away" is read only from the product's OWN box on a checkout page, or from Amazon's
// "make updates to your items" step - never from the cart, never from a promotion / gift-wrap / delivery-speed message elsewhere
function productGoneNotes(p){
 if(!checkoutPath())return [];
 const asin=String(p.asin||'').toUpperCase();
 const ownBox=asin?[...document.querySelectorAll(`[data-asin="${asin}"]`)].find(shown):null;      // (v94: the product's OWN box only)
 const own=ownBox?notesIn(ownBox,true):[];
 if(!itemStepPage())return own;
 // the "make updates to your items" step: its messages count only when they are not inside ANOTHER product's box
 const other=e=>{const b=e.closest?.('[data-asin]');return !!(b&&String(b.getAttribute('data-asin')||'').toUpperCase()!==asin)};
 const page=[];for(const e of document.querySelectorAll(NOTE_SEL)){if(!shown(e)||personalArea(e)||other(e))continue;const r=canon(txt(e)).slice(0,300);if(r.length>=6&&!personalWords(r)&&!page.includes(r))page.push(r);if(page.length>=5)break}
 return own.concat(page.filter(x=>!own.includes(x)));
}
function checkoutHeadings(){return [...document.querySelectorAll('h1,h2,h3,[role="heading"]')].filter(e=>shown(e)&&!personalArea(e)&&!personalWords(canon(txt(e)))).map(e=>cleanNote(txt(e)).toLowerCase()).filter(x=>x&&x.length<100).slice(0,8)}
const ITEM_GONE_RX=/no longer available|out of stock|sold out|(?:item|items|product|quantity|offer)\b[^.]{0,50}\b(?:unavailable|not available|isn'?t available|aren'?t available)(?!\s+(?:for|with|on|in|to|as|via|through)\b)|(?:unavailable|not available) (?:from|at) (?:this|the selected) seller|can(?:no|')t be (?:purchased|ordered)|removed from your (?:order|cart)/i;
const ITEM_SHIP_RX=/(?:can(?:no|')t|could not|unable to) be (?:shipped|delivered) to (?:the |your |this )?(?:selected |chosen |current )?(?:shipping |delivery )?(?:address|location)|does(?: no|n')t ship to (?:the |your |this )?(?:selected |chosen )?(?:address|location)/i;

// v111 (diagnostics 88: the Nov 8 Hobbit drop at $455.88 was reached in 5 s and lost at Amazon's checkout - "This item cannot be shipped
// to ..."): what the product page's buy box says about delivery to the signed-in account's address. READ ONLY - it never stops a purchase;
// it only tells the owner ("yes" / "no" / null when the page says nothing). The address itself is never read or sent.
// (Amazon names the address in the message - "This item cannot be shipped to <name>'s address" - so anything after "to" counts)
// v118 (Buy 2 / 3: "ensure that product limits govern ... do not let it get hung up in checkout with an error message"): Amazon saying
// this account may not buy more of the product - the checkout stops at once (only an error like this; never the plain "Limit 1 per customer")
const STORE_LIMIT_RX=/reached\s+(?:the|your|its|a)?\s*(?:purchase|quantity|order|item|maximum)?\s*limit|exceed(?:ed|s)?\s+(?:the|your|its|a)?\s*(?:purchase|quantity|order|item|maximum)\s+limit|(?:purchase|quantity|order|item)\s+limit\s+(?:has\s+been\s+)?reached|(?:can|may)\s+only\s+(?:buy|purchase|order)\s+\d+|you(?:'ve| have)\s+already\s+(?:purchased|ordered|bought)\s+the\s+maximum/i;
const PAGE_SHIP_NO_RX=/(?:can(?:no|')t|could not|unable to) be (?:shipped|delivered) to\b|does(?: no|n')t (?:ship|deliver) to\b|(?:not available|unavailable) (?:for|in) your (?:area|location|region)\b/i;
function deliverability(){
 const box=['#exports_desktop_undeliverable_buybox','#desktop_buybox','#buybox','#rightCol','#outOfStock','#availability'].map(q=>document.querySelector(q)).filter(Boolean);
 const t=box.map(e=>canon(txt(e))).join(' \n ').slice(0,6000);
 if(document.querySelector('#exports_desktop_undeliverable_buybox,#exports_desktop_undeliverable_buybox_priceInsideBuybox_feature_div')||PAGE_SHIP_NO_RX.test(t)){
   const m=t.match(PAGE_SHIP_NO_RX);return {deliver:'no',deliverNote:m?shipNoteSafe(m[0]):'cannot be shipped to your address'};
 }
 if(/\b(?:free delivery|delivery\b[^.]{0,40}\b(?:mon|tue|wed|thu|fri|sat|sun|tomorrow|today|overnight|\d{1,2}\s*-\s*\d{1,2}|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)|arrives\b|ships from and sold by|deliver(?:y|ed) by)\b/i.test(t))return {deliver:'yes',deliverNote:''};
 return {deliver:null,deliverNote:''};
}
// the words of a "cannot be shipped to ..." message without the address: the part up to "to", then "[your address]"
function shipNoteSafe(x){const s=canon(String(x||''));const k=s.search(/\bto\b/i);return (k>0?s.slice(0,k+2)+' [your address]':s).slice(0,120)}
function itemStepPage(){return checkoutPath()&&!placeOrderButton()&&(/\/itemselect\b/i.test(location.pathname)||checkoutHeadings().some(h=>/make updates to your items/.test(h)))}
function stepPath(){return location.pathname.replace(/\/p-[\w-]+\//i,'/…/').slice(0,70)}
function checkoutDomEvidence(){
 if(placeOrderButton())return true;
 const summary=!!document.querySelector('#subtotals-marketplace-table,#order-summary,.order-summary,[data-testid*="order-summary" i],#checkout-order-summary');
 const checkoutRoot=!!document.querySelector('[data-testid*="checkout" i],#checkout-page,#checkout-container,[class*="checkout" i]');
 const address=!!document.querySelector('[data-testid*="address" i],#address-book-entry-0,#shipaddress');
 const payment=!!document.querySelector('[data-testid*="payment" i],#payment-information,[class*="payment" i]');
 return checkoutRoot||(summary&&(address||payment));
}
function checkoutState(){return /\/(?:checkout|buy)(?:\/|\?|$)|\/gp\/buy|\/hz\/checkout/i.test(location.href)||checkoutDomEvidence()}
const UNAVAIL_RX=/you cannot buy this item because it(?:'s|’s| is) out of stock|item is no longer available|this item is currently unavailable|no longer available from the seller/i;
function explicitCheckoutUnavailable(){const t=canon(body()).toLowerCase();return UNAVAIL_RX.test(t)}
// v94 (review): on a checkout page the words count only for THIS product - its own box, or the page when no OTHER product's box is on it
// (Amazon's "make updates to your items" step lists every item of the order with its own message)
function purchaseLimitAtCheckout(){try{return /reached the purchase limit|purchase limit for this item/i.test(document.body?.innerText||'')}catch{return false}}
function productUnavailableAtCheckout(p){
 if(!checkoutPath())return explicitCheckoutUnavailable();
 const asin=String(p.asin||'').toUpperCase();
 const others=[...document.querySelectorAll('[data-asin]')].some(e=>shown(e)&&String(e.getAttribute('data-asin')||'').toUpperCase()!==asin&&/^B[0-9A-Z]{9}$/.test(String(e.getAttribute('data-asin')||'')));
 if(!others)return explicitCheckoutUnavailable();
 const own=[...document.querySelectorAll(`[data-asin="${asin}"]`)].filter(shown).map(e=>canon(txt(e))).join(' | ');
 return UNAVAIL_RX.test(own)||productGoneNotes(p).some(x=>UNAVAIL_RX.test(x));
}
function preorderIntermediateButton(){
 const candidates=controls([
   'button','input[type="submit"]','input[type="button"]','a[role="button"]'
 ],/^(continue|continue to checkout|proceed to checkout|review order|review your order|continue to review order|continue to place order)$/i);
 if(candidates.length!==1)return null;
 const label=controlLabel(candidates[0]);
 if(/^continue$/i.test(label)&&!/(pre\s*[- ]?\s*order|preorder|review (?:your )?order|checkout)/i.test(body()))return null;
 return candidates[0];
}
function discover(p){
 if(L.challenge(body()))return send('ACTION_REQUIRED',p.id,{reason:'Amazon verification challenge during discovery'});
 const cards=[...document.querySelectorAll('[data-asin]')].filter(e=>/^[A-Z0-9]{10}$/.test(String(e.getAttribute('data-asin')||'')));
 const matches=[];for(const card of cards){const t=txt(card);if(!L.exactProduct(t,p))continue;const asin=String(card.getAttribute('data-asin')).toUpperCase();matches.push({asin,text:t})}
 if(!matches.length)return send('STATUS',p.id,{status:'SEARCHING — exact Amazon listing not found',price:null});
 const u=[...new Map(matches.map(x=>[x.asin,x])).values()];if(u.length!==1)return send('STATUS',p.id,{status:`DISCOVERY AMBIGUOUS — ${u.length} exact-looking listings`,price:null});
 return send('DISCOVERED',p.id,{asin:u[0].asin,price:null});
}
function productPageTitle(){return canon(txt(document.querySelector('#productTitle,h1#title,h1.a-size-large'))||'').slice(0,500)}
function titleTokens(title){
 const stop=new Set(['the','and','for','with','from','this','that','pack','packs','box','magic','gathering']);
 return canon(title).toLowerCase().replace(/[^a-z0-9' -]/g,' ').split(/\s+/).filter(x=>x.length>=3&&!stop.has(x)).slice(0,12);
}
function productReferencePrice(p){const vals=[];for(const sel of ['#listPrice','.basisPrice .a-offscreen','.a-text-price .a-offscreen','[data-a-strike="true"] .a-offscreen'])for(const e of document.querySelectorAll(sel)){const n=priceNodeValue(e,p);if(n!==null)vals.push(n)}const u=[...new Set(vals.map(n=>Number(n.toFixed(2))))];return u.length?Math.max(...u):null}
function customMetadata(p){const name=productPageTitle();if(!name)return {ok:false,reason:'product title unavailable'};const tokens=titleTokens(name);if(tokens.length<2)return {ok:false,reason:'product title could not be safely tokenized'};return {ok:true,name,tokens,price:productPrice(p),referencePrice:productReferencePrice(p),verifiedMsrp:null}}
// v62 (Restalker): the product page must SHOW the product of Restalker's row before anything is bought - its set name, and its kind in
// words (a box says box / display / case / "12 packs"; a single pack does not; a bundle says bundle). Diagnostics 60: a single
// Collector Booster was bought for a Collector Booster BOX row.
function rkShapeProblem(p,title){
 if(!p||(!p.rkShape&&!p.rkTheme))return '';
 const t=String(title||'').toLowerCase().replace(/booster display/g,'booster box').replace(/[^a-z0-9' ]+/g,' ').replace(/\s+/g,' ').trim();
 if(!t)return 'the product name on the page could not be read';
 const flat=t.replace(/[^a-z0-9 ]/g,'');
 if(Array.isArray(p.rkTheme)&&p.rkTheme.length&&!p.rkTheme.some(ws=>Array.isArray(ws)&&ws.every(w=>flat.includes(String(w)))))return 'the page does not name this set';
 const box=/\b(box|boxes|display|case)\b|\b(12|24|30|36)\s*(ct\s*)?(collector\s*|play\s*|set\s*|draft\s*)?(booster\s*)?(packs|boosters)\b/;
 const k=String(p.rkShape||'');
 if(['box','play_booster_box','set_booster_box'].includes(k)){if(!box.test(t))return 'the page does not say it is a box ("'+title.slice(0,60)+'")';if(/\b(commander deck|bundle)\b/.test(t))return 'the page shows a bundle / deck, not a box'}
 if(k==='play_booster_box'&&!/play booster/.test(t))return 'the page does not say Play Booster';
 if(k==='set_booster_box'&&!/set booster/.test(t))return 'the page does not say Set Booster';
 if(k==='pack'&&(box.test(t)||/\b(bundle|deck)\b/.test(t)))return 'the page shows a box / bundle, not a single pack';
 if(k==='bundle'&&!/\bbundle\b/.test(t))return 'the page does not say it is a bundle';
 return '';
}
async function check(p){const ctx=arguments[1]||{};
 const send=(type,id,o)=>{if(type==='STATUS'&&o&&typeof o==='object'&&!String(o.status||'').startsWith('ELIGIBLE')){const d=deliverySeen(p);if(d)o={...o,deliver:d.deliver,deliverNote:d.deliverNote}}return sendMsg(type,id,o)};   // (v111: read only; never on a buyable hit - no delay there)
 if(!Number.isFinite(p.cap)||p.cap<=0)return send('STATUS',p.id,{status:'CAP REQUIRED — not monitoring',price:null});
 if(L.challenge(body()))return send('ACTION_REQUIRED',p.id,{reason:'Amazon verification challenge'});
 if(L.throttled?.(body()))return send('STATUS',p.id,{status:'THROTTLED — Amazon temporarily unavailable; backing off',price:null,retryAfterMs:60000});
 if(!pageHasProduct(p))return send('STATUS',p.id,{status:'BLOCKED — exact ASIN/product not verified',price:null});
 {const why=rkShapeProblem(p,productPageTitle());if(why)return send('STATUS',p.id,{status:'BLOCKED — not the product of your table row: '+why,price:null});}
 if(p.customProduct){const title=productPageTitle();if(title&&title!==p.name){const tokens=titleTokens(title);if(tokens.length<2)return send('STATUS',p.id,{status:'BLOCKED — custom product title could not be safely tokenized',price:null});const meta=await send('PRODUCT_META',p.id,{name:title,tokens});if(!meta?.ok)return send('STATUS',p.id,{status:'BLOCKED — custom product identity could not be persisted',price:null});p={...p,name:title,tokens};}}
 // Latency rule: evaluate the already-rendered price first. Never spend seconds searching for a button on an over-cap listing.
 const parseStartPerf=perfNow();
 let v=productPrice(p),purchaseControl=purchaseEntry(),preEvidence=preorderEvidence();const parseDonePerf=perfNow();
 if(v!==null&&v>p.cap+.001)return send('STATUS',p.id,{status:`OVER CAP — $${v.toFixed(2)}`,price:v});
 // A real target buy box can expose preorder availability before its actionable input is discoverable.
 // Treat that as an eligible hit and resolve the control only after exclusive transaction ownership is acquired.
 const detectedAt=Date.now();const recognitionMs=Number.isFinite(Number(ctx.triggerPerf))?Math.max(0,perfNow()-Number(ctx.triggerPerf)):null;
 // v155: the seller in the buy box is the one Amazon's purchase limit was reached for -> not bought from this seller (nothing is
 // pressed, no checkout is opened); another seller's offer at or under the cap is bought as always
 const limGate=()=>{const lim=sellerLimited(p);return lim?send('STATUS',p.id,{status:'WAITING — seller limit: Amazon\'s limit applies to '+(lim.name||'this listing\'s seller')+'; an offer of another seller is bought',price:v,limitSeller:lim.key,limitSellerName:lim.name}):null};
 if((purchaseControl||preEvidence)&&(v===null||v<=p.cap+.001)){const g0=limGate();if(g0)return g0}
 if(v!==null&&v<=p.cap+.001&&(purchaseControl||preEvidence)){
   if(purchaseControl)pendingEligible={id:p.id,control:purchaseControl.control,kind:purchaseControl.kind,price:v,detectedAt};
   const r=await send('STATUS',p.id,{status:purchaseControl?'ELIGIBLE':'ELIGIBLE_CONTROL_PENDING',price:v,detectedAt,recognitionMs,trigger:ctx.trigger||'scheduled',parseMs:Math.max(0,parseDonePerf-parseStartPerf)});
   if(r?.beginPurchase)queueMicrotask(()=>void begin(p,v,r.hitDetectedAt||detectedAt,r.eventPushedAt||detectedAt).catch(e=>send('FLOW_BLOCKED',p.id,{reason:'Purchase runtime error: '+String(e?.message||e)})));
   return r;
 }
 if(v===null&&(purchaseControl?.kind==='preorder'||preEvidence)){
   if(purchaseControl)pendingEligible={id:p.id,control:purchaseControl.control,kind:purchaseControl.kind,price:null,detectedAt};
   const r=await send('STATUS',p.id,{status:purchaseControl?'ELIGIBLE_PREORDER_PRICE_DEFERRED':'ELIGIBLE_PREORDER_CONTROL_PENDING',price:null,detectedAt,recognitionMs,trigger:ctx.trigger||'scheduled',parseMs:Math.max(0,parseDonePerf-parseStartPerf)});
   if(r?.beginPurchase)queueMicrotask(()=>void begin(p,null,r.hitDetectedAt||detectedAt,r.eventPushedAt||detectedAt).catch(e=>send('FLOW_BLOCKED',p.id,{reason:'Purchase runtime error: '+String(e?.message||e)})));
   return r;
 }
 // One short mutation-assisted retry covers late buy-box hydration without delaying a genuinely eligible hit by seconds.
 await waitDom(60);purchaseControl=purchaseEntry();preEvidence=preorderEvidence();if(v===null)v=productPrice(p);
 if(v!==null&&v>p.cap+.001)return send('STATUS',p.id,{status:`OVER CAP — $${v.toFixed(2)}`,price:v});
 if((purchaseControl||preEvidence)&&(v===null||v<=p.cap+.001)){const g1=limGate();if(g1)return g1}
 if(v!==null&&v<=p.cap+.001&&(purchaseControl||preEvidence)){
   const hit=Date.now(),recognitionMs2=Number.isFinite(Number(ctx.triggerPerf))?Math.max(0,perfNow()-Number(ctx.triggerPerf)):null;if(purchaseControl)pendingEligible={id:p.id,control:purchaseControl.control,kind:purchaseControl.kind,price:v,detectedAt:hit};const r=await send('STATUS',p.id,{status:purchaseControl?'ELIGIBLE':'ELIGIBLE_CONTROL_PENDING',price:v,detectedAt:hit,recognitionMs:recognitionMs2,trigger:ctx.trigger||'scheduled'});
   if(r?.beginPurchase)queueMicrotask(()=>void begin(p,v,r.hitDetectedAt||hit,r.eventPushedAt||hit).catch(e=>send('FLOW_BLOCKED',p.id,{reason:'Purchase runtime error: '+String(e?.message||e)})));return r;
 }
 if(v===null&&(purchaseControl?.kind==='preorder'||preEvidence)){
   const hit=Date.now(),recognitionMs2=Number.isFinite(Number(ctx.triggerPerf))?Math.max(0,perfNow()-Number(ctx.triggerPerf)):null;if(purchaseControl)pendingEligible={id:p.id,control:purchaseControl.control,kind:purchaseControl.kind,price:null,detectedAt:hit};const r=await send('STATUS',p.id,{status:purchaseControl?'ELIGIBLE_PREORDER_PRICE_DEFERRED':'ELIGIBLE_PREORDER_CONTROL_PENDING',price:null,detectedAt:hit,recognitionMs:recognitionMs2,trigger:ctx.trigger||'scheduled'});
   if(r?.beginPurchase)queueMicrotask(()=>void begin(p,null,r.hitDetectedAt||hit,r.eventPushedAt||hit).catch(e=>send('FLOW_BLOCKED',p.id,{reason:'Purchase runtime error: '+String(e?.message||e)})));return r;
 }
 if(!purchaseControl&&!preEvidence)return send('STATUS',p.id,{status:v===null?'WAITING — exact price/purchase control unavailable':`WAITING — $${v.toFixed(2)}, purchase control unavailable`,price:v});
 return send('STATUS',p.id,{status:'WAITING — exact price unavailable',price:null});
}
function cartCount(){const n=Number((document.querySelector('#nav-cart-count')?.textContent||'').trim());return Number.isFinite(n)?n:null}
async function begin(p,lp,hitDetectedAt,eventPushedAt=null){
 if(!Number.isFinite(p.cap)||p.cap<=0)return send('FLOW_BLOCKED',p.id,{reason:'Missing/invalid cap'});
 if(L.challenge(body()))return send('ACTION_REQUIRED',p.id,{reason:'Challenge before purchase'});
 if(!pageHasProduct(p))return send('FLOW_BLOCKED',p.id,{reason:'Product changed'});
 if(ensureOneTimePurchase())await waitDom(20);
 let entry=null;
 if(pendingEligible?.id===p.id&&pendingEligible.control?.isConnected&&actionVisible(pendingEligible.control)&&!unsafePurchaseContext(pendingEligible.control))entry={control:pendingEligible.control,kind:pendingEligible.kind};
 pendingEligible=null;
 if(!entry)entry=purchaseEntry();
 // The eligibility check normally hands us the exact live control. Keep only a very short mutation fallback for late hydration.
 for(let i=0;i<5&&!entry;i++){await waitDom(i<2?8:16);entry=purchaseEntry()}
 if(!entry)return send('FLOW_BLOCKED',p.id,{reason:'Eligible hit retained, but the product\'s Buy now / Add to Cart button did not appear'});
 const entryKind=entry.kind,purchaseButton=entry.control;
 const fresh=productPrice(p);
 if(fresh!==null&&!L.priceAtOrBelowCap(fresh,p.cap))return send('FLOW_BLOCKED',p.id,{reason:'Live one-time purchase price is above cap'});
 if(fresh===null&&entryKind!=='preorder')return send('FLOW_BLOCKED',p.id,{reason:'Live one-time purchase price could not be revalidated under cap'});
 if(Number.isFinite(lp)&&fresh!==null&&!L.priceMatches(fresh,Number(lp),.02))return send('FLOW_BLOCKED',p.id,{reason:'Listing price changed before purchase'});
 {const lim=sellerLimited(p);if(lim)return send('FLOW_BLOCKED',p.id,{reason:'Not bought - seller limit: Amazon\'s limit applies to '+(lim.name||'this seller'),sellerGate:true})}
 try{globalThis.__rkT?.(document.readyState==='loading'?'beginEarly':'begin')}catch{}
 const f={id:p.id,seller:buyBoxSeller(),preDom:document.readyState==='loading',lp:fresh,cap:p.cap,asin:p.asin,name:p.name,tokens:Array.isArray(p.tokens)?p.tokens.slice(0,16):[],forbid:Array.isArray(p.forbid)?p.forbid.slice(0,16):[],customProduct:!!p.customProduct,step:'adding',mode:entryKind==='preorder'?'preorder':entryKind==='buy_now'?'buy_now':'fast_cart',priceDeferred:fresh===null,started:Number.isFinite(Number(hitDetectedAt))?Number(hitDetectedAt):Date.now(),addStarted:Date.now(),cartBefore:cartCount(),purchaseClickAt:Date.now(),perf:{eligibleHitAt:Number.isFinite(Number(hitDetectedAt))?Number(hitDetectedAt):Date.now(),eventPushedAt:Number.isFinite(Number(eventPushedAt))&&Number(eventPushedAt)>0?Number(eventPushedAt):null,controlClickAt:Date.now()}};
 if(!await saveFlow(f))return send('FLOW_BLOCKED',p.id,{reason:'Purchase state could not be persisted'});
 try{purchaseButton.click()}catch{await clearFlow();return send('FLOW_BLOCKED',p.id,{reason:'Purchase control click failed'})}
 if(f.preDom)void send('RK_PRE_DOM',p.id,{start:true,t:f.addStarted});      // v155: started while the page was still arriving - its arrival at Amazon's next page is watched

 // Amazon often adds successfully without navigating or exposing the same confirmation markup.
 // Do not make navigation/DOM acknowledgement a terminal gate; resume owns the transition.
 queueMicrotask(()=>void resumeSafe());
}
async function resume(){
 const f=await loadFlow();if(!f)return;
 if(f.preDom&&!f.preDomOk&&(/^\/(?:checkout\/|gp\/buy\/|buy\/|gp\/checkoutportal\/|ap\/)/i.test(location.pathname)||(!f.selfCart&&/^\/(?:gp\/)?cart(?:\/|$)/i.test(location.pathname)))){/* v155: the press arrived at a page AMAZON sent it to - its checkout, its sign-in, or its cart (not the cart the engine opens itself when a press led nowhere) */f.preDomOk=true;void saveFlow(f);void send('RK_PRE_DOM',f.id,{ok:true,t:f.addStarted})}
 if(Date.now()-(f.started||0)>10*60*1000){await clearFlow();return send('FLOW_BLOCKED',f.id,{reason:'Purchase flow expired'})}
 const base=globalThis.AMAZON_CATALOG.find(x=>x.id===f.id);const p=base?{...base,cap:Number(f.cap),asin:f.asin||base.asin,name:f.name||base.name,tokens:Array.isArray(f.tokens)&&f.tokens.length?f.tokens:base.tokens,forbid:Array.isArray(f.forbid)?f.forbid:base.forbid,customProduct:!!(f.customProduct||base.customProduct)}:(f.customProduct?{id:f.id,set:'Custom ASIN',name:f.name,asin:f.asin,url:'https://www.amazon.com/dp/'+f.asin,tokens:f.tokens||[],forbid:f.forbid||[],cap:Number(f.cap),customProduct:true}:null);if(!p){await clearFlow();return}
 if(L.challenge(body()))return send('ACTION_REQUIRED',p.id,{reason:'Amazon verification challenge during checkout'});
 if(f.step==='adding'){
   // Legal success signals: cart URL, checkout side-sheet, cart-count increase, or known ATC acknowledgement.
   // If Amazon exposes none of them, probe by navigating to the cart instead of aborting a possibly-successful add.
   // v88: by the clock, not by a number of looks (each look ends at the page's next change - Amazon's busy pages used the looks up in a
   // fraction of a second, before "Buy now" had even opened the checkout, and the checkout was then sent to the cart instead)
   const addT0=Date.now(),addMax=(f.mode==='buy_now'||f.mode==='preorder')?6000:1500;
   for(let i=0;Date.now()-addT0<addMax;i++){
     if(L.challenge(body()))return send('ACTION_REQUIRED',p.id,{reason:'Amazon verification challenge after Add to Cart'});
     if(explicitCheckoutUnavailable())return send('FLOW_BLOCKED',p.id,{reason:'Amazon says the product is unavailable / out of stock'});

     // Some preorder controls navigate directly into checkout rather than adding through the normal cart UI.
     if(checkoutState()){
       f.cp=f.lp;f.step='checkout';f.perf={...(f.perf||{}),checkoutDetectedAt:Date.now()};
       if(!await saveFlow(f))return send('FLOW_BLOCKED',p.id,{reason:'Preorder checkout transition state could not be persisted'});
       return resume();
     }

     if(/\/cart|\/gp\/cart/i.test(location.href)){
       f.perf={...(f.perf||{}),cartDetectedAt:Date.now()};f.step='cart';if(!await saveFlow(f))return send('FLOW_BLOCKED',p.id,{reason:'Cart transition state could not be persisted'});
       return resume();
     }
     if(f.mode==='preorder'){
       const next=preorderIntermediateButton();
       if(next&&!f.preorderIntermediateClicked){
         f.preorderIntermediateClicked=true;if(!await saveFlow(f))return send('FLOW_BLOCKED',p.id,{reason:'Preorder intermediate state could not be persisted'});
         try{next.click()}catch{return send('FLOW_BLOCKED',p.id,{reason:'Preorder intermediate click failed'})}
         await waitDom(75);continue;
       }
     }

     const co=checkoutButton();
     const n=cartCount(),raised=Number.isFinite(n)&&Number.isFinite(f.cartBefore)&&n>f.cartBefore;
     const ack=!!document.querySelector('#attach-added-to-cart-message,#NATC_SMART_WAGON_CONF_MSG_SUCCESS,#sw-atc-confirmation,[data-csa-c-content-id*="add-to-cart" i]');

     if(co&&(raised||ack)){
       f.cp=f.lp;f.step='checkout';f.mode='fast_cart_direct';f.perf={...(f.perf||{}),checkoutClickAt:Date.now()};
       if(!await saveFlow(f))return send('FLOW_BLOCKED',p.id,{reason:'Direct checkout state could not be persisted'});
       try{co.click()}catch{return send('FLOW_BLOCKED',p.id,{reason:'Direct checkout click failed'})}
       queueMicrotask(()=>void resumeSafe());return;
     }

     if(raised||ack){
       f.perf={...(f.perf||{}),cartAckAt:Date.now()};f.step='cart';f.selfCart=true;
       if(!await saveFlow(f))return send('FLOW_BLOCKED',p.id,{reason:'Cart transition state could not be persisted'});
       location.href='https://www.amazon.com/gp/cart/view.html';return;
     }

     await waitDom(f.mode==='buy_now'?35:60);
   }

   // Normal Add-to-Cart may succeed without acknowledgement, so the cart probe remains valid there.
   // A preorder can use a retailer-specific transition; never destroy an unknown transaction-owned preorder page.
   if(f.mode==='preorder')return send('ACTION_REQUIRED',p.id,{reason:'Unrecognized preorder transition preserved for inspection'});
   f.step='cart';f.selfCart=true;   // (v155: the engine goes to the cart itself here - that is no arrival of the press)
   if(!await saveFlow(f))return send('FLOW_BLOCKED',p.id,{reason:'Cart probe state could not be persisted'});
   location.href='https://www.amazon.com/gp/cart/view.html';return;
 }
 if(f.step==='cart'){
  // Amazon frequently changes cart row markup. The product was already confirmed added,
  // so do not lose the purchase solely because the cart DOM cannot be mapped to an ASIN.
  let row=null,cp=null,qty=false,co=null;
  for(let i=0;i<16;i++){
    row=exactCart(p);
    if(row){cp=cartPrice(p,f.lp);qty=qtyOne(p)}
    co=checkoutButton();
    if(co&&(row ? (cp!==null&&qty) : true))break;
    await waitDom(i<6?20:45);
  }
  if(row){
    if(cp===null||!L.priceAtOrBelowCap(cp,p.cap))return send('FLOW_BLOCKED',p.id,{reason:'Exact cart base price validation failed'});
    if(!qty)return send('FLOW_BLOCKED',p.id,{reason:'Quantity is not 1'});
    f.cp=cp;f.priceDeferred=false;
  }else{
    // A normal listing may carry its freshly validated product-page price forward.
    // A price-hidden preorder may not: checkout must reveal and validate a real merchandise price.
    if(!Number.isFinite(f.lp))f.cp=null;else f.cp=f.lp;
  }
  if(!co)return send('FLOW_BLOCKED',p.id,{reason:'Proceed to checkout unavailable'});
  f.step='checkout';f.perf={...(f.perf||{}),checkoutClickAt:Date.now()};if(!await saveFlow(f))return send('FLOW_BLOCKED',p.id,{reason:'Checkout state could not be persisted'});
  try{co.click()}catch{await clearFlow();return send('FLOW_BLOCKED',p.id,{reason:'Proceed to checkout click failed'})}
  queueMicrotask(()=>void resumeSafe());return;
 }
 if(f.step==='checkout'||f.step==='shipping_approval'){
  f.perf={...(f.perf||{}),checkoutLoopEnterAt:f.perf?.checkoutLoopEnterAt||Date.now()};
  // Fast path: on a fully rendered final review page, validate once and submit immediately.
  // Retries are used only when the page is genuinely incomplete.
  // v87 (diagnostics 66-70: every Amazon checkout since 10-29 stopped on Amazon's new checkout page /checkout/p/.../itemselect after
  // ~1.2 s with "identity not verified" and no product, price or Place order button yet): the old limit was 24 LOOKS, and each look
  // ends at the page's next change - Amazon's new checkout page changes constantly while it loads, so 24 looks were used up before it
  // had drawn the order. The checkout now waits by the CLOCK (up to CHECKOUT_WAIT_MS) for the order to appear, and presses one plain
  // "Continue" step if Amazon shows one first. Every money check below is unchanged.
  // v91: the 15 s are counted for EACH checkout step (a step that moves on starts its own 15 s), at most CHECKOUT_MAX_MS in all.
  const CHECKOUT_WAIT_MS=15000,CHECKOUT_MAX_MS=40000,loopT0=Date.now();let lastContinue=0,stepT0=Date.now(),stepKey='',presses=0,stepNotes='',stepSends=0;
  for(let i=0;Date.now()-stepT0<CHECKOUT_WAIT_MS&&Date.now()-loopT0<CHECKOUT_MAX_MS;i++){
    if(L.challenge(body()))return send('ACTION_REQUIRED',p.id,{reason:'Amazon verification challenge at checkout'});
    // v91: a new checkout step (address -> items -> review ...): its own clock, and one line in the diagnostics saying what it is
    if(location.pathname!==stepKey){if(stepKey)stepT0=Date.now();stepKey=location.pathname;stepNotes='';stepSends=0;presses=0}
    {const notes=checkoutNotes(),sig=notes.join('|');
     if((stepSends===0?(notes.length||Date.now()-stepT0>700):sig!==stepNotes)&&stepSends<4){stepNotes=sig;stepSends++;
       void send('CHECKOUT_PRICE_EVIDENCE',p.id,{decision:'CHECKOUT_STEP',phase:'checkout',attemptAgeMs:Date.now()-loopT0,page:stepPath(),headings:checkoutHeadings(),notes})}
     const own=productGoneNotes(p);
     const gone=own.find(t=>ITEM_GONE_RX.test(t));
     if(gone)return send('FLOW_BLOCKED',p.id,{reason:'Amazon took the product out of the checkout - sold out under us (Amazon says: "'+cleanNote(gone).slice(0,140)+'")'});
     const ship=own.find(t=>ITEM_SHIP_RX.test(t));
     if(ship)return send('ACTION_REQUIRED',p.id,{reason:'Amazon cannot send the product to the chosen address (Amazon says: "'+cleanNote(ship).slice(0,140)+'")'});
     // v111 (diagnostics 88, Nov 8: a "Buy now" checkout said "This item cannot be shipped to ..." OUTSIDE the product's own box; the
     // checkout waited 22 s, tried again, waited 22 s more - the drop was lost and nobody was told). A Buy now / pre-order checkout holds
     // only this product, so that message anywhere on the checkout page is about it: you are told at once (with the Remote Desktop button).
     if(!ship&&(f.mode==='buy_now'||f.mode==='preorder')&&Date.now()-stepT0>1200){
       const pageShip=notesIn(document,true).find(t=>ITEM_SHIP_RX.test(t)||PAGE_SHIP_NO_RX.test(t));
       if(pageShip)return send('ACTION_REQUIRED',p.id,{reason:'Amazon cannot send the product to your address (Amazon says: "'+shipNoteSafe(pageShip)+'") - check the delivery address in your Amazon account'});
     }
     {const lim=notesIn(document,true).concat(notes).find(t=>STORE_LIMIT_RX.test(t));
      if(lim&&Date.now()-stepT0>800)return send('ACTION_REQUIRED',p.id,{reason:'STORE LIMIT — Amazon will not sell more of it to this account (Amazon says: "'+cleanNote(lim).slice(0,140)+'")'});}
     // (a Buy now checkout that Amazon sent to the cart page without the product: Amazon took it away. Not for a checkout that
     // started from the cart - that page is the cart until it opens the checkout)
     if(!f.perf?.checkoutClickAt&&/\/(?:gp\/)?cart(?:\/|$)/i.test(location.pathname)&&Date.now()-stepT0>1500&&!exactCart(p))
       return send('FLOW_BLOCKED',p.id,{reason:'Amazon left the checkout and the product is not in the cart - sold out under us'});
    }
    // v154: Amazon's own sentence ("Sorry, you've reached the purchase limit for this item") is passed on as it is - until v153 it was
    // reported as "unavailable / out of stock" and tried again every few seconds, which can never work
    if(purchaseLimitAtCheckout()){
      // v155: for WHICH seller - the one whose offer was in the buy box when Buy Now was pressed (kept with the purchase state)
      const sl=f?.seller&&f.seller.key?f.seller:{id:'',name:'',key:''};
      return send('FLOW_BLOCKED',p.id,{reason:'Amazon says you have reached its purchase limit for this item'+(sl.name?' (seller: '+sl.name+')':sl.id?' (seller '+sl.id+')':''),limitSeller:sl.key||'*',limitSellerName:sl.name||''});
    }
    if(productUnavailableAtCheckout(p))return send('FLOW_BLOCKED',p.id,{reason:'Amazon checkout says the product is unavailable / out of stock'});
    const iterStart=perfNow();const identity=checkoutIdentity(p,f.cp);const identityAt=perfNow();
    // Extra zero-quantity DOM work runs only when normal checkout identity fails.
    // Sampling just three times also avoids slowing the proven fast-submit path.
    if(!identity&&(i===0||i===5||i===12)&&targetCheckoutQuantityZero(p)){
      void send('CHECKOUT_PRICE_EVIDENCE',p.id,{decision:'PRODUCT_QUANTITY_ZERO',identityMethod:'product-card',
        phase:'checkout',attemptAgeMs:Date.now()-Number(f.perf?.checkoutLoopEnterAt||Date.now()),
        targetAsin:p.asin,quantityZero:true});
      return send('FLOW_BLOCKED',p.id,{reason:'The product\'s checkout quantity is ZERO (quantity is zero); Amazon stock changed'});
    }
    const item=identity?.item||null;
    const qty=!!identity?.qtyOne;
    const fees=checkoutComponents(p,f.cp);const feesAt=perfNow();
    const place=placeOrderButton();const placeAt=perfNow();
    if(identity&&!f.perf.identityReadyAt)f.perf.identityReadyAt=Date.now();if(fees&&!f.perf.feesReadyAt)f.perf.feesReadyAt=Date.now();if(place&&!f.perf.placeReadyAt)f.perf.placeReadyAt=Date.now();f.perf.lastValidationCpuMs=Math.max(0,placeAt-iterStart);f.perf.identityCpuMs=Math.max(0,identityAt-iterStart);f.perf.feesCpuMs=Math.max(0,feesAt-identityAt);f.perf.placeCpuMs=Math.max(0,placeAt-feesAt);

    if(identity&&qty&&fees&&place){
      const checkoutItemPrice=checkoutBasePrice(p,item,f.cp,fees.base);
      void send('CHECKOUT_PRICE_EVIDENCE',p.id,{summaryBase:fees.base,listingReference:f.cp,
        candidates:[...new Set([...item.querySelectorAll('.a-price .a-offscreen,.a-color-price,.priceToPay .a-offscreen,[data-testid*="price" i]')]
          .map(e=>priceNodeValue(e,p)).filter(Number.isFinite))].slice(0,8),
        decision:Number.isFinite(checkoutItemPrice)?'MATCHED_EXACT_ITEM':'AMBIGUOUS_PAUSE'});
      if(!Number.isFinite(checkoutItemPrice)||!L.priceAtOrBelowCap(checkoutItemPrice,p.cap))
        return send('FLOW_BLOCKED',p.id,{reason:'Checkout offer/base price ambiguous or above cap; manual review required'});
      // For price-hidden preorders, checkout is the first authoritative price gate.
      const targetPrice=L.resolveTargetPrice(f.cp,checkoutItemPrice,fees.base,p.cap);
      if(!Number.isFinite(targetPrice))return send('FLOW_BLOCKED',p.id,{reason:'Checkout merchandise subtotal does not match the product\'s price'});
      f.cp=targetPrice;f.priceDeferred=false;

      const gate=L.checkoutFees(fees.base,fees.tax,fees.shipping,p.cap,fees.total);
      if(!gate.ok)return send('FLOW_BLOCKED',p.id,{reason:gate.reason});

      f.checkoutSeller=sellerText(item)||sellerText(document);

      if(gate.needsShippingApproval&&(!Number.isFinite(f.approvedShipping)||fees.shipping>f.approvedShipping+.01)){
        f.step='shipping_approval';f.fees=fees;delete f.shippingApproved;
        if(!await saveFlow(f))return send('FLOW_BLOCKED',p.id,{reason:'Shipping approval state could not be persisted'});
        return send('SHIPPING_APPROVAL_REQUIRED',p.id,{fees});
      }

      f.perf={...(f.perf||{}),validationCompleteAt:Date.now()};const ack=await send('SUBMITTING',p.id,{price:fees.base,fees,perf:f.perf});
      if(!ack?.ok)return send('FLOW_BLOCKED',p.id,{reason:'Purchase latch was not confirmed'});

      f.step='submitted';f.fp=fees.base;f.fees=fees;
      if(!await saveFlow(f))return send('SUBMISSION_UNCLEAR',p.id,{price:fees.base,reason:'Submission state could not be persisted after latch'});

      // No extra delay once all validation is complete.
      f.perf={...(f.perf||{}),placeOrderClickAt:Date.now()};try{place.click()}catch{return send('SUBMISSION_UNCLEAR',p.id,{price:fees.base,reason:'Place Order click threw after purchase latch'})}
      return resume();
    }

    // Only inspect actionable interstitials when the page is not ready to submit.
    const inter=checkoutInterstitial();
    if(inter)return send('ACTION_REQUIRED',p.id,{reason:inter});

    // If Amazon inserted one supported continuation (never a Place order / pay / buy button), advance it - at most every 2.5 s.
    // v91: Amazon's "make updates to your items" step is pressed on at once (after 0.25 s), other steps after 0.8 s; again every 2 s
    // while the page stays on the same step (at most 6 presses per step)
    const age=Date.now()-stepT0,itemStep=itemStepPage();
    if(age>(itemStep?250:800)&&Date.now()-lastContinue>(itemStep?2000:2500)&&!place&&presses<6){
      const next=f.perf?.checkoutClickAt?retryableCheckoutButton():checkoutStepButton();   // (a Buy now checkout never presses the cart's "Proceed to checkout")
      if(next){lastContinue=Date.now();presses++;void send('CHECKOUT_PRICE_EVIDENCE',p.id,{decision:'CONTINUE_STEP',phase:'checkout',attemptAgeMs:Date.now()-loopT0,page:stepPath(),label:ctlText(next).slice(0,40),presses});try{next.click()}catch{}}
    }

    await waitDom(age<2000?40:120);
    if(Date.now()-stepT0<400)await new Promise(r=>setTimeout(r,20));
  }

  // Diagnose the missing piece after retries are exhausted.
  const identity=checkoutIdentity(p,f.cp),item=identity?.item||null,qty=!!identity?.qtyOne,fees=checkoutComponents(p,f.cp),place=placeOrderButton();
  const qtyZero=targetCheckoutQuantityZero(p);
  void send('CHECKOUT_PRICE_EVIDENCE',p.id,{decision:qtyZero?'PRODUCT_QUANTITY_ZERO':!identity?'IDENTITY_MISSING':!qty?'QUANTITY_NOT_ONE':!fees?'FEES_MISSING':!place?'PLACE_ORDER_MISSING':'CHECKOUT_INCOMPLETE',
    phase:'checkout',attemptAgeMs:Date.now()-Number(f.perf?.checkoutLoopEnterAt||Date.now()),
    identityMethod:identity?.method||null,identityVerified:!!identity,quantityOne:qty,
    quantityZero:qtyZero,feesPresent:!!fees,placeOrderPresent:!!place,
    targetAsin:p.asin,page:stepPath(),headings:checkoutHeadings(),notes:checkoutNotes(),presses,summary:summaryLines()});
  if(qtyZero)return send('FLOW_BLOCKED',p.id,{reason:'The product\'s checkout quantity is ZERO (quantity is zero); Amazon stock changed'});
  const stuck=itemStepPage()&&!place,said=checkoutNotes()[0];
  if(stuck)return send('FLOW_BLOCKED',p.id,{reason:'Amazon\'s checkout stayed on its "make updates to your items" step (Continue pressed '+presses+'x)'+(said?' - Amazon says: "'+said.slice(0,140)+'"':' - no message from Amazon')});
  if(!identity)return send('FLOW_BLOCKED',p.id,{reason:'The product could not be confirmed on the checkout page (identity not verified; page '+stepPath()+')'});
  if(!qty)return send('FLOW_BLOCKED',p.id,{reason:'Checkout quantity is not 1'});
  if(!fees)return send('FLOW_BLOCKED',p.id,{reason:'Checkout item/tax/shipping components unavailable or ambiguous'});
  if(!place)return send('ACTION_REQUIRED',p.id,{reason:'Place order button unavailable or ambiguous'});
 }
 if(f.step==='submitted'){
  const subT0=Date.now();      // v88: by the clock (20 s), not 40 looks - Amazon's confirmation page changes constantly while it loads
  for(let i=0;Date.now()-subT0<20000;i++){
    const t=canon(body()).toLowerCase();
    const ev=confirmationEvidence();
    if(ev.ok){await clearFlow();return send('PURCHASE_SUCCESS',p.id,{price:f.fp,orderNumber:ev.orderNumber,perf:{...(f.perf||{}),confirmedAt:Date.now()}})}
    // Amazon can reject the attempted Place Order with a definitive inventory response.
    // This proves no order was placed, so it is safe to release ownership and auto-rearm immediately.
    if(explicitCheckoutUnavailable()){
      await clearFlow();return send('DEFINITE_SUBMISSION_FAILURE',p.id,{price:f.fp,reason:'Amazon says the product is unavailable / out of stock after Place order (no order)'});
    }
    if(/payment revision needed|payment declined|unable to process your payment|order could not be placed|problem with your order/i.test(t)){
      await clearFlow();return send('SUBMISSION_UNCLEAR',p.id,{price:f.fp,reason:'Amazon reported payment/order failure after submission'});
    }
    await waitDom(i<8?18:45);
  }
  // v114 (diagnostics 92: a Place order with no confirmation in 20 s - what Amazon showed instead was never recorded): the page's step,
  // headings and Amazon's own words (never an address or payment detail) go to the diagnostics first
  try{void send('CHECKOUT_PRICE_EVIDENCE',p.id,{decision:'NO_CONFIRMATION_AFTER_PLACE_ORDER',phase:'submitted',step:stepPath(),page:stepPath(),headings:checkoutHeadings(),notes:checkoutNotes(),summary:summaryLines()})}catch{}
  await clearFlow();return send('SUBMISSION_UNCLEAR',p.id,{price:f.fp});
 }
}
// Test-only hooks are inert in the extension unless explicitly enabled by the QC VM harness.
if(globalThis.__AMZ_ENABLE_TEST_HOOKS__)globalThis.__AMZ_TEST_HOOKS__={checkoutComponents,checkoutBasePrice,nodeQtyOne,checkoutIdentity,targetCheckoutQuantityZero,placeOrderButton,checkoutState,explicitCheckoutUnavailable};
chrome.runtime.onMessage.addListener((m,_s,r)=>{if(m&&m.type==='RK_FETCH')return false;/* v64: Restalker's page reader (rk_fetch.js) answers that one */(async()=>{
 if(m.type==='RESUME_FLOW'){const f=await loadFlow();if(!f)return r({ok:false});f.approvedShipping=Number(m.approvedShipping);if(Number.isFinite(Number(m.cap))&&Number(m.cap)>0)f.cap=Number(m.cap);f.step='shipping_approval';if(!await saveFlow(f))return r({ok:false});void resumeSafe();return r({ok:true})}
 const base=globalThis.AMAZON_CATALOG.find(x=>x.id===m.product?.id);if(!base&&!m.product?.customProduct)return r({ok:false});const p={...(base||{}),...(m.product||{})};
 if(m.type==='EXTRACT_CUSTOM_META')return r(customMetadata(p));
 if(m.type==='REVALIDATE_QUEUED'){
   // v155: asked many times a second while the page is arriving - until a buy button is in the page the answer costs nothing
   // (two lookups by name; the page's text is not read, so the page is not slowed down while it loads)
   if(document.readyState==='loading'&&!document.getElementById('buy-now-button')&&!document.getElementById('add-to-cart-button')&&!document.querySelector('input[name="submit.buy-now"],input[name="submit.add-to-cart"]'))return r({ok:true,eligible:false,ready:'loading',box:false,reason:'the page is still arriving'});
   if(L.challenge(body())||L.throttled?.(body()))return r({ok:true,eligible:false,reason:'Amazon challenge/throttle'});
   if(!pageHasProduct(p))return r({ok:true,eligible:false,reason:'product identity changed'});
   {const why=rkShapeProblem(p,productPageTitle());if(why)return r({ok:true,eligible:false,reason:'not the product of the table row: '+why});}
   const price=productPrice(p),entry=purchaseEntry(),pre=preorderEvidence();
   if(Number.isFinite(price)&&price>Number(p.cap)+.001)return r({ok:true,eligible:false,price,reason:'price above cap'});
   const eligible=(Number.isFinite(price)&&price<=Number(p.cap)+.001&&(!!entry||!!pre))||(price===null&&(entry?.kind==='preorder'||!!pre));
   const ready=document.readyState,box=rkBoxClosed(entry?.control);
   // v155: an answer given while the page is still arriving counts only once the buy box is complete (see rkBoxClosed)
   if(eligible&&!box)return r({ok:true,eligible:false,ready,box:false,price:Number.isFinite(price)?price:null,reason:'the buy box is still arriving'});
   if(eligible){const lim=sellerLimited(p);if(lim)return r({ok:true,eligible:false,limited:true,limitSeller:lim.key,limitSellerName:lim.name,ready,box,price:Number.isFinite(price)?price:null,reason:'purchase limit for this seller'+(lim.name?' ('+lim.name+')':'')})}
   return r({ok:true,eligible,ready,box,early:eligible&&ready==='loading',price:Number.isFinite(price)?price:null,reason:eligible?null:'purchase control no longer available'});
 }
 if(m.type==='CHECK_PRODUCT'){watchedProduct={...p};return r((await check(p))||{ok:false})}
 if(m.type==='DISCOVER_PRODUCT'){watchedProduct=null;return r((await discover(p))||{ok:false})}
 if(m.type==='BEGIN_PURCHASE'){{const why=rkShapeProblem(p,productPageTitle());if(why){send('FLOW_BLOCKED',p.id,{reason:'Not bought - this page is not the product of your table row: '+why});return r({ok:false,reason:why})}}void begin(p,m.listingPrice,m.hitDetectedAt).catch(e=>send('FLOW_BLOCKED',p.id,{reason:'Purchase runtime error: '+String(e?.message||e)}));return r({ok:true,started:true})}
 r({ok:false});
 })().catch(e=>{try{r({ok:false,error:'content handler error: '+String(e?.message||e)})}catch{}});return true});
// v155: this page part is in the page from its first moment now (so that "buyable" can be answered as soon as the buy box is read).
// A checkout that is under way goes on exactly when it did before: when the page has been read and is idle (the browser's own
// "idle" moment: after the page is read, at its first idle moment or 200 ms later, or when it is fully loaded - whichever is first).
(()=>{let went=false;const go=()=>{if(went)return;went=true;setTimeout(()=>void resumeSafe(),0)};
 if(typeof __RK_IN_PART__!=='undefined')return go();      // (the other buying browsers: from the first moment, as they always did)
 const afterDom=()=>{try{if(typeof requestIdleCallback==='function')requestIdleCallback(go,{timeout:200});else setTimeout(go,200)}catch{setTimeout(go,200)}};
 if(document.readyState==='complete')return go();
 if(document.readyState==='interactive')afterDom();else document.addEventListener('DOMContentLoaded',afterDom,{once:true});
 window.addEventListener('load',go,{once:true});
})();
