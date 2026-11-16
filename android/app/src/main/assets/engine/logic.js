(function(root,factory){const api=factory();root.AmazonBuyerLogic=api;if(typeof module!=='undefined'&&module.exports)module.exports=api;})(globalThis,function(){
 const norm=s=>String(s||'').toLowerCase().replace(/[’']/g,"'").replace(/\s+/g,' ').trim();
 const num=v=>{const n=Number(String(v??'').replace(/[$,]/g,'').trim());return Number.isFinite(n)?n:null};
 const dollars=t=>{const a=[];let m,r=/\$\s*([0-9]{1,5}(?:,[0-9]{3})*(?:\.[0-9]{2})?)/g;while((m=r.exec(String(t||'')))){const n=num(m[1]);if(n!==null)a.push(n)}return a};
 const asinFromUrl=url=>{try{const u=new URL(String(url||''));const m=u.pathname.match(/\/(?:dp|gp\/product)\/([A-Z0-9]{10})(?:\/|$)/i);return m?m[1].toUpperCase():null}catch{return null}};
 const exactUrl=(url,asin)=>{try{const u=new URL(String(url||'')),h=u.hostname.toLowerCase();return !!asin&&(h==='amazon.com'||h.endsWith('.amazon.com'))&&asinFromUrl(url)===String(asin).toUpperCase()}catch{return false}};
 function exactProduct(text,p){const t=norm(text);return Array.isArray(p.tokens)&&p.tokens.every(x=>t.includes(norm(x)))&&!(p.forbid||[]).some(x=>t.includes(norm(x)))}
 const validPrice=(n,p)=>{const cap=Number.isFinite(p?.cap)?p.cap:null;return n!==null&&Number.isFinite(n)&&n>=.01&&n<=(cap?Math.max(5000,cap*5):5000)};
 const priceAtOrBelowCap=(n,cap)=>Number.isFinite(Number(n))&&Number.isFinite(Number(cap))&&Number(n)>=0.01&&Number(n)<=Number(cap)+0.001;
 const priceMatches=(a,b,tolerance=.02)=>Number.isFinite(Number(a))&&Number.isFinite(Number(b))&&Math.abs(Number(a)-Number(b))<=tolerance;
 const resolveTargetPrice=(persisted,itemPrice,subtotal,cap)=>{const ip=Number(itemPrice),st=Number(subtotal),c=Number(cap);if(![ip,st,c].every(Number.isFinite)||!priceAtOrBelowCap(ip,c)||!priceAtOrBelowCap(st,c)||!priceMatches(ip,st,.02))return null;if(persisted!==null&&persisted!==undefined&&persisted!==''&&Number.isFinite(Number(persisted))){const ref=Number(persisted);if(!priceAtOrBelowCap(ref,c)||!priceMatches(ref,ip,.02)||!priceMatches(ref,st,.02))return null;return ref}return ip};
 function choosePrice(vals,p,ref=null){const u=[...new Set(vals.filter(n=>validPrice(n,p)).map(n=>Number(n.toFixed(2))))];if(!u.length)return null;if(ref!==null){const below=u.filter(n=>n<=Number(p.cap)+.01);if(!below.length)return null;below.sort((a,b)=>Math.abs(a-ref)-Math.abs(b-ref));return below[0]}return u.length===1?u[0]:null}
 const challenge=t=>{t=norm(t);return ['captcha','enter the characters you see below','sorry, we just need to make sure you are not a robot','unusual traffic','verify you are human'].some(x=>t.includes(x))};
 const throttled=t=>{t=norm(t);return ['too many requests','request was blocked','service unavailable','temporarily unavailable','sorry! something went wrong on our end','dogs of amazon'].some(x=>t.includes(x))};
 const confirmation=t=>{t=norm(t);return t.includes('thank you, your order has been placed')||t.includes('order placed')||/\border\s*#\s*[0-9-]{6,}/.test(t)};
 const orderNumber=t=>{const m=String(t||'').match(/\border\s*#?\s*([0-9]{3}-[0-9]{7}-[0-9]{7}|[0-9-]{10,})/i);return m?m[1]:null};
 const qtyOne=(v,t='')=>norm(v)==='1'||/\b(qty|quantity)\s*:?\s*1\b/.test(norm(t));
 const canBuy=s=>!!s.exact&&!!s.armed&&!s.purchased&&!s.attempted&&!s.inProgress&&priceAtOrBelowCap(s.price,s.cap);
 const checkoutFees=(base,tax,shipping,cap,total=null)=>{
  const vals=[base,tax,shipping,cap].map(Number);if(vals.some(x=>!Number.isFinite(x)))return {ok:false,reason:'Checkout fee components unavailable'};
  const [b,t,sh,c]=vals;if(c<=0||b<0||t<0||sh<0)return {ok:false,reason:'Invalid checkout fee values'};
  if(!priceAtOrBelowCap(b,c))return {ok:false,reason:`Base item price $${b.toFixed(2)} exceeds cap $${c.toFixed(2)}`};
  const taxMax=Number((c*.10).toFixed(2));if(t>taxMax+.001)return {ok:false,reason:`Tax $${t.toFixed(2)} exceeds 10% cap allowance $${taxMax.toFixed(2)}`};
  if(total!==null&&Number.isFinite(Number(total))&&Math.abs(Number(total)-(b+t+sh))>.05)return {ok:false,reason:'Checkout total does not match item + tax + shipping'};
  if(sh>20.001)return {ok:true,needsShippingApproval:true,taxMax};
  return {ok:true,needsShippingApproval:false,taxMax};
 };
 return {norm,num,dollars,asinFromUrl,exactUrl,exactProduct,validPrice,priceAtOrBelowCap,priceMatches,resolveTargetPrice,choosePrice,challenge,throttled,confirmation,orderNumber,qtyOne,canBuy,checkoutFees};
});
