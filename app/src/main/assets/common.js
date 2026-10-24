function call(method, ...args) {
    if (window.Android && window.Android[method]) {
        return window.Android[method](...args);
    }
    return '';
}

function get(method, ...args) {
    try {
        const res = call(method, ...args);
        return res ? JSON.parse(res) : [];
    } catch (e) {
        return [];
    }
}

function esc(s) {
    if (!s) return '';
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function host(u) {
    try {
        return new URL(u.startsWith('http') ? u : 'https://' + u).hostname.replace(/^www\./, '');
    } catch(e) {
        return u;
    }
}

// ফিক্স: কোনো ভাঙা টেক্সট নয়, পিওর গ্লোব ভেক্টর
function favicon(u) {
    if (!u || u === 'Home' || u.startsWith('file://')) {
        return `<div class="favicon-box"><svg viewBox="0 0 24 24" width="20" height="20" fill="var(--sub)"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z"/></svg></div>`;
    }
    const h = host(u);
    const src = `https://www.google.com/s2/favicons?domain=${encodeURIComponent(h)}&sz=64`;
    return `<div class="favicon-box"><img src="${src}" onerror="this.parentElement.innerHTML='<svg viewBox=\\'0 0 24 24\\' width=\\'20\\' height=\\'20\\' fill=\\'var(--sub)\\'><path d=\\'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v1c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z\\'/></svg>'"></div>`;
}

function header(title, extra = '') {
    return `
      <div class="hdr card">
        <button onclick="call('close')">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="var(--sub)"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>
        </button>
        <h2>${esc(title)}</h2>
        ${extra}
      </div>
    `;
}

function confirmDlg(title, msg, okText, onOk) {
    const dim = document.createElement('div');
    dim.className = 'dim';
    dim.innerHTML = `<div class="dlg card"><h3>${esc(title)}</h3><p>${esc(msg)}</p><div class="btns"><button class="b0">Cancel</button><button class="b1">${esc(okText)}</button></div></div>`;
    dim.querySelector('.b0').onclick = () => dim.remove();
    dim.querySelector('.b1').onclick = () => { dim.remove(); onOk(); };
    document.body.appendChild(dim);
}

function dlg(html) {
    const dim = document.createElement('div');
    dim.className = 'dim';
    dim.innerHTML = `<div class="dlg card">${html}</div>`;
    document.body.appendChild(dim);
    return dim;
}

try {
    if (call('isDarkMode')) {
        document.body.classList.add('dark');
    }
} catch(e) {}
