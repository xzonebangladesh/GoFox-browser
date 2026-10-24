const A=window.Android||{};
function call(m,...a){try{return A[m](...a)}catch(e){return null}}
function get(m,...a){try{return JSON.parse(call(m,...a)||'[]')}catch(e){return []}}
function esc(s){return String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
const COLORS=['#4285f4','#e62117','#1877f2','#555','#FF6A1A','#e91e8c','#2e7d32','#8e44ad'];
function color(s){let h=0;for(const c of String(s))h=(h*31+c.charCodeAt(0))|0;return COLORS[Math.abs(h)%COLORS.length]}
function host(u){try{return new URL(u).hostname.replace(/^www\./,'')}catch(e){return u}}
function letter(n,u){const t=(n||host(u)||'?').trim()[0]||'?';return `<div class="circ" style="background:${color(host(u)||n)}">${esc(t.toUpperCase())}</div>`}
function dlg(html,bottom){const d=document.createElement('div');d.className='dim';if(bottom)d.style.alignItems='flex-end';
d.innerHTML=`<div class="${bottom?'sheet':'dlg'} glass">${html}</div>`;d.addEventListener('click',e=>{if(e.target===d)d.remove()});document.body.appendChild(d);return d}
function confirmDlg(title,msg,ok,fn){const d=dlg(`<h3>${esc(title)}</h3><p>${esc(msg)}</p><div class="btns"><button class="b0">Cancel</button><button class="b1">${esc(ok)}</button></div>`);
d.querySelector('.b0').onclick=()=>d.remove();d.querySelector('.b1').onclick=()=>{d.remove();fn()}}
const FOX='<svg width="88" height="88" viewBox="0 0 64 64"><polygon points="6,8 24,20 40,20 58,8 54,36 32,60 10,36" fill="#FF6A1A"/><polygon points="32,60 20,40 44,40" fill="#fff"/><circle cx="23" cy="31" r="3" fill="#222"/><circle cx="41" cy="31" r="3" fill="#222"/><circle cx="32" cy="42" r="3" fill="#222"/></svg>';
function header(t,extra){return `<div class="hdr glass"><button onclick="call('close')">&#8249;</button><h2>${t}</h2>${extra||''}</div>`}
function swipe(el,onDel,onTap){let x0=0,y0=0,dx=0,lock=null;
el.addEventListener('touchstart',e=>{x0=e.touches[0].clientX;y0=e.touches[0].clientY;dx=0;lock=null;el.style.transition='none'},{passive:true});
el.addEventListener('touchmove',e=>{const t=e.touches[0],mx=t.clientX-x0,my=t.clientY-y0;if(lock===null&&(Math.abs(mx)>8||Math.abs(my)>8))lock=Math.abs(mx)>Math.abs(my);
if(lock){dx=mx;el.style.transform=`translateX(${dx}px)`;el.style.opacity=1-Math.min(Math.abs(dx)/300,.7)}},{passive:true});
el.addEventListener('touchend',()=>{el.style.transition='transform .2s,opacity .2s';
if(lock&&Math.abs(dx)>110){el.style.transform=`translateX(${dx>0?420:-420}px)`;el.style.opacity=0;setTimeout(onDel,200)}
else{el.style.transform='';el.style.opacity=1;if(!lock&&onTap)onTap()}})}
