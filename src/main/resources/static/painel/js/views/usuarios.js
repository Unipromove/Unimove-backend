// Usuarios: busca, suspensao/reativacao e reset de senha.
import { api, h, mount, header, table, pager, filters, badge, label, fmt, dialog, attempt, toast, loading } from '../core.js';

async function suspend(u, done) {
  const v = await dialog({
    title: `Suspender ${u.name}`, danger: true, confirm: 'Suspender',
    message: 'A conta perde o acesso na hora, mesmo com sessão aberta.',
    fields: [{ name: 'reason', label: 'Motivo', type: 'textarea', required: true }],
  });
  if (v && await attempt(() => api(`/admin/users/${u.userId}/suspend`, { method: 'POST', body: v }), 'Conta suspensa.')) done();
}

async function reactivate(u, done) {
  const ok = await dialog({ title: `Reativar ${u.name}?`, confirm: 'Reativar' });
  if (ok && await attempt(() => api(`/admin/users/${u.userId}/reactivate`, { method: 'POST' }), 'Conta reativada.')) done();
}

async function resetPassword(u) {
  const ok = await dialog({
    title: `Resetar senha de ${u.name}?`, confirm: 'Gerar senha temporária',
    message: 'Use só para quem perdeu o número: o caminho normal é o "esqueci minha senha" pelo WhatsApp. '
      + 'A senha aparece uma única vez; repasse por canal externo.',
  });
  if (!ok) return;
  const r = await attempt(() => api(`/admin/users/${u.userId}/reset-password`, { method: 'POST' }));
  if (!r) return;
  const copy = () => navigator.clipboard?.writeText(r.temporaryPassword).then(() => toast('Senha copiada.'), () => toast('Não foi possível copiar.', 'bad'));
  await dialog({
    title: 'Senha temporária', confirm: 'Fechar',
    content: h('div', {},
      h('p', {}, `Conta: ${r.email || u.name}. Será exigida a troca no primeiro acesso.`),
      h('div', { class: 'secret' }, h('code', {}, r.temporaryPassword), h('button', { type: 'button', class: 'ghost', onclick: copy }, 'Copiar'))),
  });
}

export async function render(root, { params, navigate, isCurrent, refresh }) {
  const head = header('Usuários', 'Busca por nome, e-mail ou telefone');
  const form = filters([
    { name: 'q', label: 'Buscar', placeholder: 'Nome, e-mail ou telefone' },
    { name: 'role', label: 'Perfil', options: ['PASSAGEIRO', 'MOTORISTA', 'ADMIN'] },
    { name: 'status', label: 'Situação', options: ['ACTIVE', 'SUSPENDED', 'DELETED'] },
    { name: 'cidade', label: 'Cidade' },
  ], params, navigate);
  mount(root, head, form, loading());
  const page = await api('/admin/users', { query: { size: 20, ...params } });
  if (!isCurrent()) return;

  mount(root, head, form,
    table([
      { title: 'Nome', render: u => (u.role === 'MOTORISTA' ? h('a', { href: `#/motoristas/${u.userId}` }, u.name) : u.name) },
      { title: 'Perfil', render: u => label(u.role) },
      { title: 'Cidade', render: u => u.cidade || '—' },
      { title: 'Contato', render: u => h('div', { class: 'small' }, u.email || '', u.email && u.phone ? h('br') : null, u.phone || '') },
      { title: 'Avaliação', render: u => (u.ratingCount ? `${Number(u.ratingAvg).toFixed(2)} (${u.ratingCount})` : '—') },
      { title: 'Desde', render: u => fmt.date(u.createdAt) },
      { title: 'Situação', render: u => badge(u.status) },
      {
        title: '', render: u => (u.role === 'ADMIN' || u.status === 'DELETED' ? null : h('div', { class: 'row-actions' },
          u.status === 'SUSPENDED'
            ? h('button', { class: 'ghost', onclick: () => reactivate(u, refresh) }, 'Reativar')
            : h('button', { class: 'danger', onclick: () => suspend(u, refresh) }, 'Suspender'),
          h('button', { class: 'ghost', onclick: () => resetPassword(u) }, 'Resetar senha'))),
      },
    ], page.content, { empty: 'Nenhum usuário encontrado.' }),
    pager(page, n => navigate({ ...params, page: n })));
}
