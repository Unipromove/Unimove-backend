// Entrada do painel: login, troca de senha temporaria, layout e roteador por hash.
import { api, session, hooks, h, mount, toast, attempt } from './core.js';
import * as inicio from './views/inicio.js';
import * as metricas from './views/metricas.js';
import * as corridas from './views/corridas.js';
import * as motoristas from './views/motoristas.js';
import * as usuarios from './views/usuarios.js';
import * as cidades from './views/cidades.js';
import * as precos from './views/precos.js';
import * as campanhas from './views/campanhas.js';
import * as auditoria from './views/auditoria.js';

const ROUTES = [
  ['inicio', 'Início', inicio],
  ['metricas', 'Métricas', metricas],
  ['corridas', 'Corridas', corridas],
  ['motoristas', 'Motoristas', motoristas],
  ['usuarios', 'Usuários', usuarios],
  ['cidades', 'Cidades', cidades],
  ['precos', 'Preços', precos],
  ['campanhas', 'Campanhas', campanhas],
  ['auditoria', 'Auditoria', auditoria],
];

const $ = id => document.getElementById(id);

function show(screen) {
  for (const id of ['login', 'change-password', 'app']) $(id).hidden = id !== screen;
}

function logout(message) {
  session.token = null;
  show('login');
  if (message) toast(message, 'bad');
}

hooks.onUnauthorized = message => logout(message);
hooks.onPasswordChangeRequired = () => show('change-password');

/** Parse de "#/rota/sub?x=1" em { route, rest, params }. */
function parseHash() {
  const [path, qs] = location.hash.replace(/^#\/?/, '').split('?');
  const [route, ...rest] = path.split('/').filter(Boolean);
  return { route: route || 'inicio', rest, params: Object.fromEntries(new URLSearchParams(qs || '')) };
}

/** Atualiza a query da rota atual sem perder o caminho (filtros, paginacao). */
export function navigate(route, params = {}) {
  const qs = new URLSearchParams(Object.entries(params).filter(([, v]) => v !== undefined && v !== '')).toString();
  location.hash = `#/${route}${qs ? `?${qs}` : ''}`;
}

let renderSeq = 0;
async function render() {
  if (!session.token) return show('login');
  show('app');
  const { route, rest, params } = parseHash();
  const entry = ROUTES.find(r => r[0] === route) || ROUTES[0];
  for (const a of $('nav').children) a.classList.toggle('active', a.dataset.route === entry[0]);
  const seq = ++renderSeq;
  const root = h('div');
  mount($('view'), root);
  try {
    await entry[2].render(root, { rest, params, navigate: p => navigate(entry[0], p), refresh: render, isCurrent: () => seq === renderSeq });
  } catch (e) {
    if (seq === renderSeq && e.status !== 401) mount(root, h('p', { class: 'empty' }, `Não foi possível carregar: ${e.message}`));
  }
}

$('nav').append(...ROUTES.map(([id, title]) => h('a', { href: `#/${id}`, dataset: { route: id } }, title)));

$('login-form').addEventListener('submit', async e => {
  e.preventDefault();
  const form = e.currentTarget;
  const { email, password } = Object.fromEntries(new FormData(form));
  const auth = await attempt(() => api('/auth/login', { method: 'POST', body: { email, password } }));
  if (!auth) return;
  if (auth.role !== 'ADMIN') return toast('Esta conta não é de administrador.', 'bad');
  session.token = auth.token;
  form.reset();
  if (auth.mustChangePassword) return show('change-password');
  render();
});

$('change-password-form').addEventListener('submit', async e => {
  e.preventDefault();
  const form = e.currentTarget;
  const ok = await attempt(() => api('/users/me/password', { method: 'PUT', body: Object.fromEntries(new FormData(form)) }),
    'Senha alterada. Entre com a nova senha.');
  if (!ok) return;
  form.reset();
  // A troca revoga os tokens anteriores: novo login.
  logout();
});

$('logout').addEventListener('click', () => logout());
window.addEventListener('hashchange', render);
render();
