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

// জিরো ইমোজি: ইমোজির পরিবর্তে ম্যাটেরিয়াল আইকন ব্যবহার
function favicon(u) {
    if (!u || u === 'Home' || u.startsWith('file://')) {
        return `<div class="favicon-box"><span class="material-icons" style="font-size:20px;color:var(--sub);">language</span></div>`;
    }
    const h = host(u);
    const src = `https://www.google.com/s2/favicons?domain=${encodeURIComponent(h)}&sz=64`;
    return `<div class="favicon-box"><img src="${src}" onerror="this.parentElement.innerHTML='<span class=\\'material-icons\\' style=\\'font-size:20px;color:var(--sub);\\'>language</span>'"></div>`;
}

function header(title, extra = '') {
    return `<div class="hdr card"><button onclick="call('close')"><span class="material-icons" style="font-size:22px;">arrow_back</span></button><h2>${esc(title)}</h2>${extra}</div>`;
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

// প্যানেল ওপেন হলে ডার্ক মোড চেক করা
try {
    if (call('isDarkMode')) {
        document.body.classList.add('dark');
    }
} catch(e) {}
