'use strict';
/* Machinery Logbook — offline app. All data stays on this phone (IndexedDB). */

const APP_VERSION = '2.0';
const $ = (sel, el = document) => el.querySelector(sel);
const app = $('#app');

/* ---------------- Database ---------------- */
let dbPromise;
function db() {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const r = indexedDB.open('machinery-logbook', 1);
      r.onupgradeneeded = () => {
        const d = r.result;
        if (!d.objectStoreNames.contains('machines')) d.createObjectStore('machines', { keyPath: 'id' });
        if (!d.objectStoreNames.contains('logs')) {
          d.createObjectStore('logs', { keyPath: 'id' }).createIndex('machineId', 'machineId');
        }
        if (!d.objectStoreNames.contains('files')) {
          const s = d.createObjectStore('files', { keyPath: 'id' });
          s.createIndex('machineId', 'machineId');
          s.createIndex('logId', 'logId');
        }
      };
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    });
  }
  return dbPromise;
}
const asP = (r) => new Promise((res, rej) => { r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
const txDone = (t) => new Promise((res, rej) => { t.oncomplete = () => res(); t.onerror = t.onabort = () => rej(t.error); });

const store = {
  async all(name) { return asP((await db()).transaction(name).objectStore(name).getAll()); },
  async get(name, id) { return asP((await db()).transaction(name).objectStore(name).get(id)); },
  async by(name, index, value) { return asP((await db()).transaction(name).objectStore(name).index(index).getAll(value)); },
  async put(name, ...objs) {
    const t = (await db()).transaction(name, 'readwrite');
    objs.forEach((o) => t.objectStore(name).put(o));
    return txDone(t);
  },
  async del(name, ...ids) {
    const t = (await db()).transaction(name, 'readwrite');
    ids.forEach((id) => t.objectStore(name).delete(id));
    return txDone(t);
  }
};

/* ---------------- Helpers ---------------- */
const uid = () => (crypto.randomUUID ? crypto.randomUUID() : Date.now().toString(36) + Math.random().toString(36).slice(2));
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const today = () => { const d = new Date(); d.setMinutes(d.getMinutes() - d.getTimezoneOffset()); return d.toISOString().slice(0, 10); };
const fmtDate = (s) => { if (!s) return ''; const d = new Date(s + (s.length === 10 ? 'T00:00:00' : '')); return isNaN(d) ? s : d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }); };
const fmtSize = (n) => n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(0) + ' KB' : (n / 1048576).toFixed(1) + ' MB';

let urls = [];
const objUrl = (blob) => { const u = URL.createObjectURL(blob); urls.push(u); return u; };
const releaseUrls = () => { urls.forEach((u) => URL.revokeObjectURL(u)); urls = []; };

function toast(msg, ms = 2400) {
  const t = $('#toast');
  t.textContent = msg; t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { t.hidden = true; }, ms);
}

function setTitle(text, back) {
  $('#title').textContent = text;
  const b = $('#backBtn');
  b.hidden = !back;
  b.onclick = back ? () => { location.hash = back; } : null;
}

const STATUS = { 'Done': 'ok', 'Pending': 'warn', 'Follow-up needed': 'bad' };
const WORK_TYPES = ['Preventive maintenance', 'Corrective repair', 'Inspection', 'Breakdown', 'Overhaul', 'Modification', 'Other'];

/* Shrink camera photos so the phone doesn't fill up (max 1600px, JPEG). */
async function compressImage(file, max = 1600, quality = 0.8) {
  if (!file.type.startsWith('image/') || file.type === 'image/gif') return file;
  try {
    let src;
    try { src = await createImageBitmap(file, { imageOrientation: 'from-image' }); }
    catch { src = await new Promise((res, rej) => { const i = new Image(); i.onload = () => res(i); i.onerror = rej; i.src = URL.createObjectURL(file); }); }
    const w = src.width, h = src.height, k = Math.min(1, max / Math.max(w, h));
    const c = document.createElement('canvas');
    c.width = Math.round(w * k); c.height = Math.round(h * k);
    c.getContext('2d').drawImage(src, 0, 0, c.width, c.height);
    const blob = await new Promise((res) => c.toBlob(res, 'image/jpeg', quality));
    return blob && blob.size < file.size ? blob : file;
  } catch { return file; }
}

/* Open a hidden file picker and resolve with the chosen files. */
function pick(id) {
  return new Promise((resolve) => {
    const input = $('#' + id);
    input.value = '';
    input.onchange = () => resolve([...input.files]);
    input.click();
  });
}

async function savePhotos(files, machineId, logId) {
  const recs = [];
  for (const f of files) {
    const blob = await compressImage(f);
    recs.push({ id: uid(), machineId, logId: logId || undefined, kind: 'photo', name: f.name || 'photo.jpg', type: blob.type || f.type, size: blob.size, blob, createdAt: new Date().toISOString() });
  }
  if (recs.length) await store.put('files', ...recs);
  return recs.length;
}

/* ---------------- Tree helpers ---------------- */
// Every item (main group, machine, system, part…) is a record in "machines".
// parentId points to the item it belongs under; no parentId = top of the tree.
function buildTree(items) {
  const byId = new Map(items.map((m) => [m.id, m]));
  const kids = new Map();
  for (const m of items) {
    const p = m.parentId && byId.has(m.parentId) ? m.parentId : '';
    if (!kids.has(p)) kids.set(p, []);
    kids.get(p).push(m);
  }
  for (const arr of kids.values()) arr.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }));
  return { byId, kids };
}
function pathOf(id, byId) {
  const out = [], seen = new Set();
  let cur = byId.get(id);
  while (cur && !seen.has(cur.id)) { seen.add(cur.id); out.unshift(cur); cur = cur.parentId ? byId.get(cur.parentId) : null; }
  return out;
}
function descendantsOf(id, kids) {
  const out = [], stack = [...(kids.get(id) || [])];
  while (stack.length) { const n = stack.pop(); out.push(n); stack.push(...(kids.get(n.id) || [])); }
  return out;
}
const itemIcon = (m, kids) => (kids.get(m.id) || []).length || !m.parentId ? '📂' : '⚙️';
const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
function getExpanded() { try { return new Set(JSON.parse(localStorageGet('expanded') || '[]')); } catch { return new Set(); } }
function saveExpanded(set) { localStorageSet('expanded', JSON.stringify([...set])); }

/* ---------------- Router ---------------- */
async function render() {
  releaseUrls();
  app.onclick = null;
  const parts = (location.hash.slice(1) || '/').split('/').filter(Boolean);
  window.scrollTo(0, 0);
  try {
    if (parts[0] === 'm' && parts[1]) {
      if (parts[2] === 'log' && parts[3]) return await viewLog(parts[1], parts[3]);
      if (parts[2] === 'report') return await viewReport(parts[1], parts[3] === 'all');
      return await viewMachine(parts[1], parts[2]);
    }
    if (parts[0] === 'settings') return await viewSettings();
    return await viewHome();
  } catch (err) {
    console.error(err);
    app.innerHTML = `<div class="card">Something went wrong: ${esc(err.message || err)}</div>`;
  }
}
window.addEventListener('hashchange', render);
$('#settingsBtn').onclick = () => { location.hash = '#/settings'; };

/* ---------------- Home: the tree ---------------- */
async function viewHome() {
  setTitle('Machinery Logbook', null);
  const [items, logs] = await Promise.all([store.all('machines'), store.all('logs')]);
  const { byId, kids } = buildTree(items);
  const count = {}, lastLog = {};
  for (const l of logs) {
    count[l.machineId] = (count[l.machineId] || 0) + 1;
    if (!lastLog[l.machineId] || l.date > lastLog[l.machineId].date) lastLog[l.machineId] = l;
  }
  const expanded = getExpanded();

  app.innerHTML = `
    ${items.length ? `<input class="search" id="search" type="search" placeholder="Search any machine or part…">
    <div class="btn-row tree-tools"><button class="btn small ghost" id="expandAll">Expand all</button><button class="btn small ghost" id="collapseAll">Collapse all</button></div>` : ''}
    <div id="list" class="tree"></div>
    <button class="btn accent fab" id="addRoot">＋ Main item</button>`;

  const list = $('#list');
  const sub = (m) => {
    const ch = (kids.get(m.id) || []).length, n = count[m.id] || 0;
    return [ch ? plural(ch, 'sub-item', 'sub-items') : '', n ? plural(n, 'entry', 'entries') : '', lastLog[m.id] ? 'last ' + fmtDate(lastLog[m.id].date) : ''].filter(Boolean).join(' · ');
  };
  const row = (m, depth) => {
    const ch = kids.get(m.id) || [];
    const open = expanded.has(m.id);
    return `<div class="tree-row${depth ? '' : ' root'}" style="--d:${depth}">
        ${ch.length ? `<button class="tw" data-toggle="${m.id}" aria-label="${open ? 'Collapse' : 'Expand'}">${open ? '▾' : '▸'}</button>` : '<span class="tw"></span>'}
        <a class="tree-link" href="#/m/${m.id}">
          <span class="tree-name">${itemIcon(m, kids)} ${esc(m.name)}</span>
          <span class="meta">${esc(sub(m) || [m.maker, m.model].filter(Boolean).join(' ') || ' ')}</span>
        </a>
      </div>${open ? ch.map((c) => row(c, depth + 1)).join('') : ''}`;
  };
  const draw = (q = '') => {
    q = q.trim().toLowerCase();
    if (!items.length) {
      list.innerHTML = `<div class="empty"><div class="big">🌳</div><p><b>Your tree is empty.</b><br>Tap <b>＋ Main item</b> to start. Then add items inside it, as deep as you need:</p>
        <p class="meta">📂 Engine Room → 📂 Main Engine → 📂 Fuel System → ⚙️ Fuel Injection Pump</p></div>`;
      return;
    }
    if (q) {
      const hits = items.filter((m) => [m.name, m.category, m.maker, m.model, m.serial, m.location].join(' ').toLowerCase().includes(q));
      list.innerHTML = hits.map((m) => {
        const path = pathOf(m.id, byId).slice(0, -1).map((p) => p.name).join(' › ');
        return `<a class="card" href="#/m/${m.id}"><p class="card-title">${itemIcon(m, kids)} ${esc(m.name)}</p>
          <div class="meta">${path ? esc(path) : 'Main item'}</div></a>`;
      }).join('') || '<div class="empty">No match.</div>';
      return;
    }
    list.innerHTML = (kids.get('') || []).map((m) => row(m, 0)).join('');
  };
  draw();
  const s = $('#search');
  if (s) s.oninput = () => draw(s.value);
  list.onclick = (e) => {
    const t = e.target.closest('[data-toggle]');
    if (!t) return;
    const id = t.dataset.toggle;
    expanded.has(id) ? expanded.delete(id) : expanded.add(id);
    saveExpanded(expanded); draw(s ? s.value : '');
  };
  const ea = $('#expandAll'), ca = $('#collapseAll');
  if (ea) ea.onclick = () => { items.forEach((m) => { if ((kids.get(m.id) || []).length) expanded.add(m.id); }); saveExpanded(expanded); draw(); };
  if (ca) ca.onclick = () => { expanded.clear(); saveExpanded(expanded); draw(); };
  $('#addRoot').onclick = () => machineForm(null, '');
}

/* ---------------- Item form ---------------- */
function openDialog(html, onSubmit) {
  const dlg = $('#dlg');
  dlg.innerHTML = html;
  const form = $('form', dlg);
  form.onsubmit = async (e) => {
    e.preventDefault();
    const btn = $('[type=submit]', form);
    btn.disabled = true;
    try { if ((await onSubmit(new FormData(form), form)) !== false) dlg.close(); }
    catch (err) { console.error(err); toast('Could not save: ' + (err.message || err)); }
    finally { btn.disabled = false; }
  };
  $('[data-cancel]', dlg).onclick = () => dlg.close();
  dlg.showModal();
  return dlg;
}

// m = item to edit (or null for new); parentId = where a new item goes ('' = top level).
async function machineForm(m, parentId = '') {
  const items = await store.all('machines');
  const { byId, kids } = buildTree(items);
  const blocked = new Set(m ? [m.id, ...descendantsOf(m.id, kids).map((d) => d.id)] : []);
  const current = m ? (m.parentId && byId.has(m.parentId) ? m.parentId : '') : parentId;
  const options = [];
  const walk = (pid, depth) => (kids.get(pid) || []).forEach((it) => {
    if (blocked.has(it.id)) return;
    options.push(`<option value="${it.id}" ${it.id === current ? 'selected' : ''}>${'   '.repeat(depth)}${depth ? '└ ' : ''}${esc(it.name)}</option>`);
    walk(it.id, depth + 1);
  });
  walk('', 0);
  const v = (k) => esc(m?.[k] ?? '');
  const parentName = !m && parentId && byId.get(parentId) ? byId.get(parentId).name : '';
  openDialog(`
    <form>
      <h2>${m ? 'Edit item' : parentName ? 'New sub-item' : 'New main item'}</h2>
      ${parentName ? `<p class="meta" style="margin:-6px 0 12px">Inside: ${esc(pathOf(parentId, byId).map((p) => p.name).join(' › '))}</p>` : ''}
      <label>Name *<input name="name" required value="${v('name')}" placeholder="${parentName ? 'e.g. Fuel System, Turbocharger, Cylinder No. 1' : 'e.g. Engine Room, Main Engine, Deck Machinery'}"></label>
      <label>Belongs under<select name="parentId"><option value="">— Top of the tree (main item) —</option>${options.join('')}</select></label>
      <label>Type / category<input name="category" value="${v('category')}" placeholder="e.g. Diesel generator, Pump, Bearing"></label>
      <div class="two">
        <label>Maker<input name="maker" value="${v('maker')}"></label>
        <label>Model / part no.<input name="model" value="${v('model')}"></label>
      </div>
      <div class="two">
        <label>Serial no.<input name="serial" value="${v('serial')}"></label>
        <label>Running hours<input name="hours" type="number" inputmode="decimal" min="0" step="any" value="${v('hours')}"></label>
      </div>
      <label>Location<input name="location" value="${v('location')}" placeholder="e.g. Engine room, port side"></label>
      <label>Notes<textarea name="notes" placeholder="Ratings, spare part numbers, contacts…">${v('notes')}</textarea></label>
      <div class="form-actions"><button type="button" class="btn ghost" data-cancel>Cancel</button><button type="submit" class="btn">Save</button></div>
    </form>`,
  async (fd) => {
    const now = new Date().toISOString();
    const rec = { ...(m || { id: uid(), createdAt: now }), updatedAt: now };
    for (const k of ['name', 'category', 'maker', 'model', 'serial', 'location', 'notes']) rec[k] = (fd.get(k) || '').toString().trim();
    rec.hours = fd.get('hours') === '' ? '' : Number(fd.get('hours'));
    const pid = (fd.get('parentId') || '').toString();
    if (pid && !blocked.has(pid)) rec.parentId = pid; else delete rec.parentId;
    await store.put('machines', rec);
    if (rec.parentId) { const ex = getExpanded(); pathOf(rec.parentId, byId).forEach((p) => ex.add(p.id)); saveExpanded(ex); }
    toast('Saved');
    if (m) render(); else location.hash = '#/m/' + rec.id;
  });
}

/* ---------------- Item detail ---------------- */
async function viewMachine(id, tab) {
  const [items, allLogs] = await Promise.all([store.all('machines'), store.all('logs')]);
  const { byId, kids } = buildTree(items);
  const m = byId.get(id);
  if (!m) { location.hash = '#/'; return; }
  const children = kids.get(id) || [];
  const desc = descendantsOf(id, kids);
  if (!tab) tab = children.length ? 'items' : 'logs';
  const path = pathOf(id, byId);
  const parent = path.length > 1 ? path[path.length - 2] : null;
  setTitle(m.name, parent ? '#/m/' + parent.id : '#/');

  const files = await store.by('files', 'machineId', id);
  const manuals = files.filter((f) => f.kind === 'manual').sort((a, b) => a.name.localeCompare(b.name));
  const photos = files.filter((f) => f.kind === 'photo').sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  const includeSub = localStorageGet('includeSub') === '1' && desc.length > 0;
  const scope = new Set([id, ...(includeSub ? desc.map((d) => d.id) : [])]);
  const logs = allLogs.filter((l) => scope.has(l.machineId))
    .sort((a, b) => (b.date || '').localeCompare(a.date || '') || b.createdAt.localeCompare(a.createdAt));
  const ownLogCount = allLogs.filter((l) => l.machineId === id).length;
  const logCount = {};
  allLogs.forEach((l) => { logCount[l.machineId] = (logCount[l.machineId] || 0) + 1; });
  const photoCount = {};
  photos.forEach((p) => { if (p.logId) photoCount[p.logId] = (photoCount[p.logId] || 0) + 1; });

  const crumbs = `<nav class="crumbs"><a href="#/">🌳 Tree</a>${path.map((p, i) => i === path.length - 1 ? ` › <b>${esc(p.name)}</b>` : ` › <a href="#/m/${p.id}">${esc(p.name)}</a>`).join('')}</nav>`;
  const info = [['Type', m.category], ['Maker', m.maker], ['Model / part no.', m.model], ['Serial no.', m.serial], ['Location', m.location], ['Running hours', m.hours !== '' && m.hours != null ? Number(m.hours).toLocaleString() : '']]
    .filter(([, val]) => val !== '' && val != null)
    .map(([k, val]) => `<div><span>${k}</span><b>${esc(val)}</b></div>`).join('');

  let body = '';
  if (tab === 'items') {
    body = children.length ? children.map((c) => {
      const cc = (kids.get(c.id) || []).length, n = logCount[c.id] || 0;
      const meta = [cc ? plural(cc, 'sub-item', 'sub-items') : '', plural(n, 'entry', 'entries'), [c.maker, c.model].filter(Boolean).join(' ')].filter(Boolean).join(' · ');
      return `<a class="card" href="#/m/${c.id}"><div class="row"><p class="card-title">${itemIcon(c, kids)} ${esc(c.name)}</p><span class="chev">›</span></div><div class="meta">${esc(meta)}</div></a>`;
    }).join('') : `<div class="empty"><div class="big">📂</div><p>Nothing inside <b>${esc(m.name)}</b> yet.<br>Tap <b>＋ Sub-item</b> to add a system, machine or part.</p></div>`;
  } else if (tab === 'logs') {
    body = (desc.length ? `<label class="inline-check"><input type="checkbox" id="incSub" ${includeSub ? 'checked' : ''}> Also show work on sub-items (${desc.length})</label>` : '') +
      (logs.length ? logs.map((l) => {
        const where = l.machineId !== id && byId.get(l.machineId) ? pathOf(l.machineId, byId).slice(path.length).map((p) => p.name).join(' › ') : '';
        return `<a class="card" href="#/m/${l.machineId}/log/${l.id}">
          <div class="row"><p class="card-title">${esc(l.title)}</p>${l.status ? `<span class="chip ${STATUS[l.status] || ''}">${esc(l.status)}</span>` : ''}</div>
          ${where ? `<div class="meta where">⚙️ ${esc(where)}</div>` : ''}
          <div class="meta">${esc(fmtDate(l.date))}${l.type ? ' · ' + esc(l.type) : ''}${l.technician ? ' · ' + esc(l.technician) : ''}${photoCount[l.id] ? ' · 📷 ' + photoCount[l.id] : ''}</div>
          ${l.nextDue ? `<div class="meta">Next due: ${esc(fmtDate(l.nextDue))}</div>` : ''}
        </a>`;
      }).join('') : `<div class="empty"><div class="big">📝</div><p>No work recorded yet.<br>Tap <b>＋ Work entry</b> after each job.</p></div>`);
  } else if (tab === 'manuals') {
    body = `<div class="btn-row" style="margin-bottom:12px"><button class="btn" id="addManual">＋ Add manual / document</button></div>` +
      (manuals.length ? manuals.map((f) => `
        <div class="card">
          <p class="card-title" style="word-break:break-word">📄 ${esc(f.name)}</p>
          <div class="meta">${fmtSize(f.size)} · added ${esc(fmtDate(f.createdAt.slice(0, 10)))}</div>
          <div class="btn-row" style="margin-top:10px">
            <button class="btn small" data-open="${f.id}">Open</button>
            <button class="btn small ghost" data-save="${f.id}">Save to phone</button>
            <button class="btn small danger" data-delfile="${f.id}">Delete</button>
          </div>
        </div>`).join('') : `<div class="empty"><div class="big">📚</div><p>No manuals yet. Add PDF manuals, drawings, or scanned pages.</p></div>`);
  } else {
    body = `<div class="btn-row" style="margin-bottom:12px"><button class="btn" id="camPhoto">📷 Take photo</button><button class="btn ghost" id="galPhoto">🖼 From gallery</button></div>` +
      (photos.length ? `<div class="grid">${photos.map((p) => `<button data-photo="${p.id}" aria-label="Open photo"><img loading="lazy" src="${objUrl(p.blob)}" alt=""></button>`).join('')}</div>`
        : `<div class="empty"><div class="big">📷</div><p>No photos yet. Nameplates, before/after shots, and damage photos go here.</p></div>`);
  }

  const tabLink = (t, label) => `<a href="#/m/${id}/${t}" class="${tab === t ? 'active' : ''}">${label}</a>`;
  app.innerHTML = `
    ${crumbs}
    <div class="card">
      ${info ? `<div class="info-grid">${info}</div>` : '<div class="meta">No details yet.</div>'}
      ${m.notes ? `<div class="notes">${esc(m.notes)}</div>` : ''}
      <div class="btn-row no-print" style="margin-top:12px">
        <button class="btn small ghost" id="editMachine">Edit / move</button>
        <a class="btn small ghost" href="#/m/${id}/report${desc.length ? '/all' : ''}">Report / Print</a>
        <button class="btn small danger" id="delMachine">Delete</button>
      </div>
    </div>
    <nav class="tabs">
      ${tabLink('items', `Inside (${children.length})`)}
      ${tabLink('logs', `Work (${ownLogCount})`)}
      ${tabLink('manuals', `Manuals (${manuals.length})`)}
      ${tabLink('photos', `Photos (${photos.length})`)}
    </nav>
    ${body}
    ${tab === 'items' ? '<button class="btn accent fab" id="addChild">＋ Sub-item</button>' : ''}
    ${tab === 'logs' ? '<button class="btn accent fab" id="addLog">＋ Work entry</button>' : ''}`;

  $('#editMachine').onclick = () => machineForm(m);
  $('#delMachine').onclick = async () => {
    const msg = desc.length
      ? `Delete "${m.name}" AND everything inside it (${plural(desc.length, 'sub-item', 'sub-items')}), with all their work logs, manuals and photos? This cannot be undone.`
      : `Delete "${m.name}" and all its work logs, manuals and photos? This cannot be undone.`;
    if (!confirm(msg)) return;
    const ids = new Set([id, ...desc.map((d) => d.id)]);
    const allFiles = await store.all('files');
    await store.del('logs', ...allLogs.filter((l) => ids.has(l.machineId)).map((l) => l.id));
    await store.del('files', ...allFiles.filter((f) => ids.has(f.machineId)).map((f) => f.id));
    await store.del('machines', ...ids);
    toast('Deleted');
    location.hash = parent ? '#/m/' + parent.id + '/items' : '#/';
  };
  const inc = $('#incSub');
  if (inc) inc.onchange = () => { localStorageSet('includeSub', inc.checked ? '1' : '0'); render(); };
  const addChild = $('#addChild');
  if (addChild) addChild.onclick = () => machineForm(null, id);
  const addLog = $('#addLog');
  if (addLog) addLog.onclick = () => logForm(m);

  const addManual = $('#addManual');
  if (addManual) addManual.onclick = async () => {
    const chosen = await pick('pickManual');
    if (!chosen.length) return;
    const recs = chosen.map((f) => ({ id: uid(), machineId: id, kind: 'manual', name: f.name, type: f.type || 'application/octet-stream', size: f.size, blob: f, createdAt: new Date().toISOString() }));
    await store.put('files', ...recs);
    toast(`${recs.length} file(s) added`);
    render();
  };
  const cam = $('#camPhoto'), gal = $('#galPhoto');
  if (cam) cam.onclick = async () => { const n = await savePhotos(await pick('pickCamera'), id); if (n) { toast('Photo saved'); render(); } };
  if (gal) gal.onclick = async () => { const n = await savePhotos(await pick('pickGallery'), id); if (n) { toast(`${n} photo(s) saved`); render(); } };

  app.onclick = async (e) => {
    const t = e.target.closest('[data-open],[data-save],[data-delfile],[data-photo]');
    if (!t) return;
    if (t.dataset.photo) return showPhoto(t.dataset.photo);
    const fid = t.dataset.open || t.dataset.save || t.dataset.delfile;
    const f = files.find((x) => x.id === fid);
    if (!f) return;
    if (t.dataset.delfile) {
      if (!confirm(`Delete "${f.name}"?`)) return;
      await store.del('files', f.id); toast('Deleted'); render();
    } else {
      openFile(f, !!t.dataset.save);
    }
  };
}

function openFile(f, download) {
  const a = document.createElement('a');
  a.href = objUrl(f.blob instanceof Blob ? f.blob : new Blob([f.blob], { type: f.type }));
  if (download) a.download = f.name; else a.target = '_blank';
  a.rel = 'noopener';
  document.body.appendChild(a); a.click(); a.remove();
}

/* ---------------- Photo viewer ---------------- */
async function showPhoto(fileId) {
  const f = await store.get('files', fileId);
  if (!f) return;
  const v = $('#viewer');
  $('#viewerImg').src = objUrl(f.blob);
  v.hidden = false;
  $('#viewerClose').onclick = () => { v.hidden = true; };
  $('#viewerDelete').onclick = async () => {
    if (!confirm('Delete this photo?')) return;
    await store.del('files', fileId);
    v.hidden = true; toast('Photo deleted'); render();
  };
}
window.addEventListener('popstate', () => { $('#viewer').hidden = true; });

/* ---------------- Work log form ---------------- */
function logForm(m, l) {
  const v = (k, d = '') => esc(l?.[k] ?? d);
  const opt = (list, cur) => list.map((x) => `<option ${x === cur ? 'selected' : ''}>${esc(x)}</option>`).join('');
  let pending = [];
  const dlg = openDialog(`
    <form>
      <h2>${l ? 'Edit work entry' : 'New work entry'}</h2>
      <p class="meta" style="margin:-6px 0 12px">${esc(m.name)}</p>
      <div class="two">
        <label>Date *<input name="date" type="date" required value="${v('date', today())}"></label>
        <label>Running hours<input name="hours" type="number" inputmode="decimal" min="0" step="any" value="${v('hours', m.hours ?? '')}"></label>
      </div>
      <label>Job title *<input name="title" required value="${v('title')}" placeholder="e.g. Replaced fuel filter"></label>
      <div class="two">
        <label>Type of work<select name="type">${opt(WORK_TYPES, l?.type || 'Preventive maintenance')}</select></label>
        <label>Status<select name="status">${opt(Object.keys(STATUS), l?.status || 'Done')}</select></label>
      </div>
      <label>Work done<textarea name="workDone" placeholder="What was found, what was done, readings taken…">${v('workDone')}</textarea></label>
      <label>Parts / materials used<textarea name="parts" placeholder="Part no., qty…">${v('parts')}</textarea></label>
      <div class="two">
        <label>Done by<input name="technician" value="${v('technician')}"></label>
        <label>Next due date<input name="nextDue" type="date" value="${v('nextDue')}"></label>
      </div>
      ${l ? '' : `
      <div class="btn-row" style="margin-bottom:10px">
        <button type="button" class="btn small ghost" id="fCam">📷 Take photo</button>
        <button type="button" class="btn small ghost" id="fGal">🖼 Add from gallery</button>
      </div>
      <div class="pending-photos" id="pend"></div>`}
      <div class="form-actions"><button type="button" class="btn ghost" data-cancel>Cancel</button><button type="submit" class="btn">Save</button></div>
    </form>`,
  async (fd) => {
    const now = new Date().toISOString();
    const rec = { ...(l || { id: uid(), machineId: m.id, createdAt: now }), updatedAt: now };
    for (const k of ['date', 'title', 'type', 'status', 'workDone', 'parts', 'technician', 'nextDue']) rec[k] = (fd.get(k) || '').toString().trim();
    rec.hours = fd.get('hours') === '' ? '' : Number(fd.get('hours'));
    await store.put('logs', rec);
    if (pending.length) await savePhotos(pending, m.id, rec.id);
    if (rec.hours !== '' && (m.hours === '' || m.hours == null || rec.hours > Number(m.hours))) {
      await store.put('machines', { ...m, hours: rec.hours, updatedAt: now });
    }
    toast('Work entry saved');
    if (l) render(); else location.hash = `#/m/${m.id}/log/${rec.id}`;
  });
  const showPending = () => {
    $('#pend', dlg).innerHTML = pending.map((f) => `<img src="${objUrl(f)}" alt="">`).join('');
  };
  const fc = $('#fCam', dlg), fg = $('#fGal', dlg);
  if (fc) fc.onclick = async () => { pending.push(...await pick('pickCamera')); showPending(); };
  if (fg) fg.onclick = async () => { pending.push(...await pick('pickGallery')); showPending(); };
}

/* ---------------- Work log detail ---------------- */
async function viewLog(mid, lid) {
  const [items, l] = await Promise.all([store.all('machines'), store.get('logs', lid)]);
  const { byId } = buildTree(items);
  const m = byId.get(mid);
  if (!m || !l) { location.hash = m ? '#/m/' + mid : '#/'; return; }
  setTitle(l.title, `#/m/${mid}/logs`);
  const path = pathOf(mid, byId);
  const photos = (await store.by('files', 'logId', lid)).sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  const field = (label, val) => val !== '' && val != null ? `<div class="field"><span>${label}</span><div>${esc(val)}</div></div>` : '';

  app.innerHTML = `
    <nav class="crumbs"><a href="#/">🌳 Tree</a>${path.map((p) => ` › <a href="#/m/${p.id}/logs">${esc(p.name)}</a>`).join('')}</nav>
    <div class="card">
      <div class="row"><p class="card-title">${esc(l.title)}</p>${l.status ? `<span class="chip ${STATUS[l.status] || ''}">${esc(l.status)}</span>` : ''}</div>
      <div class="meta">${esc(m.name)} · ${esc(fmtDate(l.date))}</div>
      ${field('Type of work', l.type)}
      ${field('Running hours', l.hours !== '' && l.hours != null ? Number(l.hours).toLocaleString() : '')}
      ${field('Work done', l.workDone)}
      ${field('Parts / materials used', l.parts)}
      ${field('Done by', l.technician)}
      ${field('Next due', l.nextDue ? fmtDate(l.nextDue) : '')}
      <div class="btn-row" style="margin-top:14px">
        <button class="btn small ghost" id="editLog">Edit</button>
        <button class="btn small danger" id="delLog">Delete</button>
      </div>
    </div>
    <div class="row" style="margin:16px 0 8px"><b>Photos (${photos.length})</b>
      <div class="btn-row"><button class="btn small" id="lCam">📷 Take</button><button class="btn small ghost" id="lGal">🖼 Gallery</button></div>
    </div>
    ${photos.length ? `<div class="grid">${photos.map((p) => `<button data-photo="${p.id}" aria-label="Open photo"><img loading="lazy" src="${objUrl(p.blob)}" alt=""></button>`).join('')}</div>` : '<div class="empty">No photos for this job.</div>'}`;

  $('#editLog').onclick = () => logForm(m, l);
  $('#delLog').onclick = async () => {
    if (!confirm('Delete this work entry and its photos?')) return;
    await store.del('files', ...photos.map((p) => p.id));
    await store.del('logs', lid);
    toast('Deleted'); location.hash = `#/m/${mid}/logs`;
  };
  $('#lCam').onclick = async () => { const n = await savePhotos(await pick('pickCamera'), mid, lid); if (n) { toast('Photo saved'); render(); } };
  $('#lGal').onclick = async () => { const n = await savePhotos(await pick('pickGallery'), mid, lid); if (n) { toast(`${n} photo(s) saved`); render(); } };
  app.onclick = (e) => { const t = e.target.closest('[data-photo]'); if (t) showPhoto(t.dataset.photo); };
}

/* ---------------- Printable report ---------------- */
async function viewReport(mid, includeSub) {
  const [items, allLogs, allFiles] = await Promise.all([store.all('machines'), store.all('logs'), store.all('files')]);
  const { byId, kids } = buildTree(items);
  const m = byId.get(mid);
  if (!m) { location.hash = '#/'; return; }
  setTitle('Report — ' + m.name, '#/m/' + mid);
  const desc = descendantsOf(mid, kids);
  const scope = new Set([mid, ...(includeSub ? desc.map((d) => d.id) : [])]);
  const depth = pathOf(mid, byId).length;
  const logs = allLogs.filter((l) => scope.has(l.machineId)).sort((a, b) => (b.date || '').localeCompare(a.date || ''));
  const byLog = {};
  allFiles.filter((f) => f.kind === 'photo' && f.logId && scope.has(f.machineId)).forEach((f) => { (byLog[f.logId] ||= []).push(f); });
  const details = [['Type', m.category], ['Maker', m.maker], ['Model / part no.', m.model], ['Serial no.', m.serial], ['Location', m.location], ['Running hours', m.hours]]
    .filter(([, x]) => x !== '' && x != null).map(([k, x]) => `<b>${k}:</b> ${esc(x)}`).join(' &nbsp;·&nbsp; ');
  const parentPath = pathOf(mid, byId).slice(0, -1).map((p) => p.name).join(' › ');
  const showItem = includeSub && desc.length;
  const itemName = (l) => l.machineId === mid ? m.name : pathOf(l.machineId, byId).slice(depth).map((p) => p.name).join(' › ');

  // Outline of everything inside this item.
  const outline = (pid, d) => (kids.get(pid) || []).map((c) =>
    `<div style="padding-left:${d * 16}px">${itemIcon(c, kids)} ${esc(c.name)}${c.model ? ` <small>(${esc(c.model)})</small>` : ''}</div>${outline(c.id, d + 1)}`).join('');

  app.innerHTML = `
    <div class="report">
      <div class="btn-row no-print" style="margin-bottom:12px">
        <button class="btn" onclick="window.print()">🖨 Print / Save as PDF</button>
        ${desc.length ? `<a class="btn ghost" href="#/m/${mid}/report${includeSub ? '' : '/all'}">${includeSub ? 'Only this item' : 'Include sub-items'}</a>` : ''}
        <label class="inline-check"><input type="checkbox" id="withPhotos" checked> Photos</label>
      </div>
      <div class="card">
        ${parentPath ? `<div class="meta">${esc(parentPath)}</div>` : ''}
        <h2>${esc(m.name)} — Maintenance Record</h2>
        <div class="meta">${details}</div>
        ${m.notes ? `<div class="notes">${esc(m.notes)}</div>` : ''}
        <div class="meta" style="margin-top:6px">Printed ${esc(fmtDate(today()))} · ${plural(logs.length, 'entry', 'entries')}${showItem ? ' (including sub-items)' : ''}</div>
        ${showItem ? `<div class="outline"><b>Contents</b>${outline(mid, 0)}</div>` : ''}
      </div>
      ${logs.length ? `<table>
        <thead><tr><th>Date</th>${showItem ? '<th>Item</th>' : ''}<th>Job</th><th>Details</th><th>By</th></tr></thead>
        <tbody>${logs.map((l) => `<tr>
          <td>${esc(fmtDate(l.date))}${l.hours !== '' && l.hours != null ? `<br><small>${esc(l.hours)} h</small>` : ''}</td>
          ${showItem ? `<td>${esc(itemName(l))}</td>` : ''}
          <td><b>${esc(l.title)}</b><br><small>${esc(l.type || '')}${l.status ? ' · ' + esc(l.status) : ''}</small></td>
          <td>${l.workDone ? `<div>${esc(l.workDone)}</div>` : ''}${l.parts ? `<div><b>Parts:</b> ${esc(l.parts)}</div>` : ''}${l.nextDue ? `<div><b>Next due:</b> ${esc(fmtDate(l.nextDue))}</div>` : ''}
            ${byLog[l.id] ? `<div class="thumbs">${byLog[l.id].map((p) => `<img src="${objUrl(p.blob)}" alt="">`).join('')}</div>` : ''}</td>
          <td>${esc(l.technician || '')}</td></tr>`).join('')}</tbody></table>` : '<div class="empty">No work entries yet.</div>'}
    </div>`;
  $('#withPhotos').onchange = (e) => { app.querySelectorAll('.thumbs').forEach((t) => { t.style.display = e.target.checked ? '' : 'none'; }); };
}

/* ---------------- Settings / backup ---------------- */
async function viewSettings() {
  setTitle('Settings & Backup', '#/');
  let usage = '';
  if (navigator.storage?.estimate) {
    const { usage: u = 0, quota: q = 0 } = await navigator.storage.estimate();
    usage = `${fmtSize(u)} used of about ${fmtSize(q)} available`;
  }
  const persisted = navigator.storage?.persisted ? await navigator.storage.persisted() : false;
  const [machines, logs, files] = await Promise.all([store.all('machines'), store.all('logs'), store.all('files')]);
  const lastBackup = localStorageGet('lastBackup');

  app.innerHTML = `
    <div class="card">
      <p class="card-title">Your data</p>
      <div class="meta">${machines.length} items · ${logs.length} work entries · ${files.filter((f) => f.kind === 'photo').length} photos · ${files.filter((f) => f.kind === 'manual').length} manuals</div>
      <div class="meta">${usage}</div>
      <div class="meta">Protected storage: ${persisted ? '✅ on' : '⚠️ off — install the app to the home screen to turn it on'}</div>
      <div class="meta">Last backup: ${lastBackup ? esc(fmtDate(lastBackup.slice(0, 10))) : 'never'}</div>
    </div>
    <div class="card">
      <p class="card-title">Backup</p>
      <p class="meta">Everything is saved only on this phone. Make a backup file regularly and keep it in Google Drive, email, or your computer — so nothing is lost if the phone is lost or the app is removed.</p>
      <div class="btn-row">
        <button class="btn" id="exportBtn">⬇ Make backup file</button>
        <button class="btn ghost" id="importBtn">⬆ Restore from backup</button>
      </div>
    </div>
    <div class="card">
      <p class="card-title">About</p>
      <p class="meta">Machinery Logbook v${APP_VERSION}. Works without internet once installed.<br>
      Android (Chrome): menu ⋮ → <b>Add to Home screen</b> / <b>Install app</b>.<br>
      iPhone (Safari): Share → <b>Add to Home Screen</b>.</p>
    </div>`;

  $('#exportBtn').onclick = exportAll;
  $('#importBtn').onclick = importAll;
}

function localStorageGet(k) { try { return localStorage.getItem(k); } catch { return null; } }
function localStorageSet(k, v) { try { localStorage.setItem(k, v); } catch { /* ignore */ } }

function blobToBase64(blob) {
  return new Promise((res, rej) => {
    const r = new FileReader();
    r.onload = () => res(String(r.result).split(',')[1] || '');
    r.onerror = () => rej(r.error);
    r.readAsDataURL(blob);
  });
}
function base64ToBlob(b64, type) {
  const bin = atob(b64), bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new Blob([bytes], { type: type || 'application/octet-stream' });
}

async function exportAll() {
  toast('Preparing backup…', 10000);
  const [machines, logs, files] = await Promise.all([store.all('machines'), store.all('logs'), store.all('files')]);
  const parts = [`{"app":"machinery-logbook","version":1,"exportedAt":${JSON.stringify(new Date().toISOString())},"machines":${JSON.stringify(machines)},"logs":${JSON.stringify(logs)},"files":[`];
  for (let i = 0; i < files.length; i++) {
    const { blob, ...meta } = files[i];
    parts.push((i ? ',' : '') + JSON.stringify({ ...meta, data: await blobToBase64(blob) }));
  }
  parts.push(']}');
  const name = `machinery-logbook-backup-${today()}.json`;
  const file = new File(parts, name, { type: 'application/json' });

  // On phones, offer the share sheet (save to Drive, Files, email…). Otherwise download.
  if (navigator.canShare?.({ files: [file] })) {
    try {
      await navigator.share({ files: [file], title: 'Machinery Logbook backup' });
      localStorageSet('lastBackup', new Date().toISOString());
      toast('Backup shared'); render(); return;
    } catch (err) { if (err.name === 'AbortError') { toast('Backup cancelled'); return; } }
  }
  const a = document.createElement('a');
  a.href = objUrl(file); a.download = name;
  document.body.appendChild(a); a.click(); a.remove();
  localStorageSet('lastBackup', new Date().toISOString());
  toast('Backup file saved to Downloads');
  setTimeout(render, 1500);
}

async function importAll() {
  const [f] = await pick('pickBackup');
  if (!f) return;
  let data;
  try { data = JSON.parse(await f.text()); } catch { toast('That file is not a valid backup.'); return; }
  if (data?.app !== 'machinery-logbook') { toast('That file is not a Machinery Logbook backup.'); return; }
  if (!confirm(`Restore ${data.machines.length} items, ${data.logs.length} work entries and ${data.files.length} files?\n\nExisting records with the same ID will be replaced; other records are kept.`)) return;
  toast('Restoring…', 10000);
  await store.put('machines', ...data.machines);
  await store.put('logs', ...data.logs);
  for (const x of data.files) {
    const { data: b64, ...meta } = x;
    await store.put('files', { ...meta, blob: base64ToBlob(b64, meta.type) });
  }
  toast('Restore complete');
  location.hash = '#/';
  render();
}

/* ---------------- Start ---------------- */
if ('serviceWorker' in navigator && (location.protocol === 'https:' || location.hostname === 'localhost' || location.hostname === '127.0.0.1')) {
  navigator.serviceWorker.register('sw.js').catch((e) => console.warn('Offline mode not available:', e));
}
if (navigator.storage?.persist) navigator.storage.persist().catch(() => {});
render();
