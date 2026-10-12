// Nucleo do painel: cliente da API, sessao, DOM seguro e componentes comuns.
// Regra: dado vindo da API so entra no DOM por textContent (via h()), nunca innerHTML.

const TOKEN_KEY = 'unimove.admin.token';

export const session = {
  get token() { try { return sessionStorage.getItem(TOKEN_KEY); } catch { return null; } },
  set token(v) {
    try { v ? sessionStorage.setItem(TOKEN_KEY, v) : sessionStorage.removeItem(TOKEN_KEY); } catch { /* sem storage */ }
  },
};

export class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

// Chamado pelo main.js: 401 derruba para o login, 403 de senha temporaria abre a troca.
export const hooks = { onUnauthorized: () => {}, onPasswordChangeRequired: () => {} };

export async function api(path, { method = 'GET', body, query } = {}) {
  const url = new URL(path, location.origin);
  for (const [k, v] of Object.entries(query || {})) {
    if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, v);
  }
  const headers = { Accept: 'application/json' };
  if (session.token) headers.Authorization = `Bearer ${session.token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const data = res.status === 204 ? null : await res.json().catch(() => null);
  if (res.ok) return data;
  const message = data?.message || `Erro ${res.status}`;
  if (res.status === 401 && path !== '/auth/login') hooks.onUnauthorized(message);
  if (res.status === 403 && message.includes('senha temporária')) hooks.onPasswordChangeRequired();
  throw new ApiError(res.status, message);
}

// ---------- DOM ----------

export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'class') el.className = v;
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else if (k in el && typeof v !== 'string') el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat(Infinity)) {
    if (c === undefined || c === null || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

export function mount(container, ...children) {
  container.replaceChildren(...children.flat(Infinity).filter(Boolean));
}

// ---------- Formatacao ----------

const brl = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const dt = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' });

export const fmt = {
  money: v => (v === null || v === undefined ? '—' : brl.format(Number(v))),
  date: v => (v ? dt.format(new Date(v)) : '—'),
  day: v => (v ? new Date(`${v}T00:00:00`).toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' }) : '—'),
  fullDay: v => (v ? new Date(`${v}T00:00:00`).toLocaleDateString('pt-BR') : '—'),
  pct: v => `${(Number(v || 0) * 100).toFixed(1)}%`,
  num: v => Number(v || 0).toLocaleString('pt-BR'),
  id: v => (v ? String(v).slice(0, 8) : '—'),
};

export const LABELS = {
  PENDING_PAYMENT: 'Aguardando pagamento', AVAILABLE_IN_MURAL: 'No mural', DRIVER_EN_ROUTE: 'Motorista a caminho',
  IN_PROGRESS: 'Em andamento', COMPLETED: 'Concluída', CANCELLED: 'Cancelada', EXPIRED: 'Expirada',
  PENDING: 'Pendente', CONFIRMED: 'Confirmado', DIVERGENT: 'Divergente', SIMULATED: 'Simulado',
  ACTIVE: 'Ativo', SUSPENDED: 'Suspenso', DELETED: 'Excluído',
  PASSAGEIRO: 'Passageiro', MOTORISTA: 'Motorista', ADMIN: 'Admin',
  CARRO: 'Carro', MOTO: 'Moto', PIX: 'Pix', DINHEIRO: 'Dinheiro',
  INCOMPLETE: 'Incompleta', REJECTED: 'Reprovado', PENDING_REVIEW: 'Em análise', APPROVED: 'Aprovado',
  SUBMITTED: 'Enviado', SENT: 'Enviada', NO_AUDIENCE: 'Sem público', INACTIVE: 'Desativada',
  true: 'Sim', false: 'Não', '': 'Todos', _DEFAULT: 'Padrão (demais cidades)',
};
export const label = v => LABELS[v] || v || '—';

const TONE = {
  COMPLETED: 'ok', CONFIRMED: 'ok', ACTIVE: 'ok', APPROVED: 'ok', SENT: 'ok',
  CANCELLED: 'bad', DIVERGENT: 'bad', SUSPENDED: 'bad', REJECTED: 'bad', EXPIRED: 'bad', DELETED: 'bad', INACTIVE: 'bad',
  PENDING: 'warn', PENDING_REVIEW: 'warn', SUBMITTED: 'warn', INCOMPLETE: 'warn', NO_AUDIENCE: 'warn',
};
export const badge = v => h('span', { class: `badge ${TONE[v] || ''}` }, label(v));

// ---------- Componentes ----------

export function toast(message, tone = 'ok') {
  const el = h('div', { class: `toast ${tone}`, role: 'status' }, message);
  document.getElementById('toasts').append(el);
  setTimeout(() => el.remove(), tone === 'bad' ? 7000 : 3500);
}

/** Executa uma acao e mostra o erro da API em toast. Devolve undefined em caso de falha. */
export async function attempt(fn, okMessage) {
  try {
    const r = await fn();
    if (okMessage) toast(okMessage);
    return r ?? true;
  } catch (e) {
    toast(e.message, 'bad');
    return undefined;
  }
}

/**
 * Modal proprio (sem alert/confirm nativos). `fields` gera inputs; resolve com
 * os valores ou null se cancelado.
 */
export function dialog({ title, message, fields = [], confirm = 'Confirmar', danger = false, content }) {
  return new Promise(resolve => {
    const inputs = fields.map(f => {
      const input = f.type === 'textarea'
        ? h('textarea', { name: f.name, required: f.required, maxlength: f.maxlength || 500, rows: 3 })
        : f.options
          ? h('select', { name: f.name, required: f.required },
            f.options.map(o => h('option', { value: o, selected: String(f.value) === o }, label(o))))
          : h('input', {
            name: f.name, type: f.type || 'text', required: f.required, value: f.value ?? '',
            step: f.step, min: f.min, max: f.max, maxlength: f.maxlength, placeholder: f.placeholder,
          });
      return h('label', {}, f.label, input);
    });
    const close = value => { backdrop.remove(); resolve(value); };
    const form = h('form', {
      class: 'card modal',
      onsubmit: e => { e.preventDefault(); close(Object.fromEntries(new FormData(form))); },
    },
      h('h2', {}, title),
      message && h('p', {}, message),
      content,
      inputs,
      h('div', { class: 'actions' },
        h('button', { type: 'button', class: 'ghost', onclick: () => close(null) }, 'Voltar'),
        h('button', { type: 'submit', class: danger ? 'danger' : 'primary' }, confirm)));
    const backdrop = h('div', { class: 'backdrop', onclick: e => e.target === backdrop && close(null) }, form);
    backdrop.addEventListener('keydown', e => e.key === 'Escape' && close(null));
    document.body.append(backdrop);
    (form.querySelector('input,textarea,button[type=submit]'))?.focus();
  });
}

/** Tabela a partir de colunas { title, render(row) }. */
export function table(columns, rows, { empty = 'Nada por aqui.', onRow } = {}) {
  if (!rows?.length) return h('p', { class: 'empty' }, empty);
  return h('div', { class: 'table-wrap' }, h('table', {},
    h('thead', {}, h('tr', {}, columns.map(c => h('th', {}, c.title)))),
    h('tbody', {}, rows.map(r => h('tr', { class: onRow ? 'clickable' : '', onclick: onRow && (() => onRow(r)) },
      columns.map(c => h('td', { 'data-label': c.title }, c.render(r))))))));
}

/** Controles de paginacao para o formato Page do Spring. */
export function pager(page, go) {
  if (!page || page.totalPages <= 1) return null;
  return h('div', { class: 'pager' },
    h('button', { class: 'ghost', disabled: page.first, onclick: () => go(page.number - 1) }, '‹ Anterior'),
    h('span', { class: 'muted' }, `Página ${page.number + 1} de ${page.totalPages} · ${fmt.num(page.totalElements)} itens`),
    h('button', { class: 'ghost', disabled: page.last, onclick: () => go(page.number + 1) }, 'Próxima ›'));
}

/** Formulario de filtros: chama onSubmit com os valores nao vazios. */
export function filters(fields, values, onSubmit) {
  const form = h('form', {
    class: 'filters',
    onsubmit: e => { e.preventDefault(); onSubmit(Object.fromEntries([...new FormData(form)].filter(([, v]) => v !== ''))); },
  },
    fields.map(f => h('label', {}, f.label,
      f.options
        ? h('select', { name: f.name },
          h('option', { value: '' }, 'Todos'),
          f.options.map(o => h('option', { value: o, selected: values[f.name] === o }, label(o))))
        : h('input', { name: f.name, type: f.type || 'text', value: values[f.name] || '', placeholder: f.placeholder || '' }))),
    h('button', { type: 'submit', class: 'primary' }, 'Filtrar'));
  return form;
}

export const header = (title, subtitle, ...actions) =>
  h('header', { class: 'page-head' },
    h('div', {}, h('h1', {}, title), subtitle && h('p', { class: 'muted' }, subtitle)),
    h('div', { class: 'actions' }, actions));

export const stat = (title, value, note, tone) =>
  h('div', { class: `card stat ${tone || ''}` }, h('span', { class: 'muted small' }, title), h('strong', {}, value), note && h('span', { class: 'small muted' }, note));

export const loading = () => h('p', { class: 'muted' }, 'Carregando…');

/** Pares chave/valor para fichas de detalhe. */
export const dl = pairs => h('dl', { class: 'kv' }, pairs.flatMap(([k, v]) => [h('dt', {}, k), h('dd', {}, v ?? '—')]));

/** Link interno por hash (ex.: #/usuarios?q=...). */
export const link = (href, text) => h('a', { href }, text);
