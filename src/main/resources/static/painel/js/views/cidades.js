// Cidades: abrir/reabrir e desativar (soft) cidades atendidas.
import { api, h, mount, header, table, badge, fmt, dialog, attempt, loading } from '../core.js';

export async function render(root, { isCurrent, refresh }) {
  const openCity = async () => {
    const v = await dialog({
      title: 'Abrir cidade', confirm: 'Abrir',
      message: 'Informe o nome como deve aparecer no app (ex.: Remanso). Uma cidade desativada com o mesmo nome é reativada.',
      fields: [{ name: 'cidade', label: 'Nome da cidade', required: true }],
    });
    if (v && await attempt(() => api('/admin/cities', { method: 'POST', body: v }), 'Cidade aberta.')) refresh();
  };
  const deactivate = async c => {
    const ok = await dialog({
      title: `Desativar ${c.displayName}?`, danger: true, confirm: 'Desativar',
      message: `Quem já opera ali (${fmt.num(c.userCount)} contas) continua normalmente; a cidade só some das escolhas novas.`,
    });
    if (ok && await attempt(() => api(`/admin/cities/${encodeURIComponent(c.slug)}`, { method: 'DELETE' }), 'Cidade desativada.')) refresh();
  };

  const head = header('Cidades', 'Cidades atendidas (whitelist do cadastro)', h('button', { class: 'primary', onclick: openCity }, 'Abrir cidade'));
  mount(root, head, loading());
  const cities = await api('/admin/cities');
  if (!isCurrent()) return;
  mount(root, head, table([
    { title: 'Cidade', render: c => c.displayName },
    { title: 'Slug', render: c => h('code', {}, c.slug) },
    { title: 'Contas', render: c => fmt.num(c.userCount) },
    { title: 'Situação', render: c => badge(c.active ? 'ACTIVE' : 'INACTIVE') },
    {
      title: '', render: c => (c.active
        ? h('button', { class: 'danger', onclick: () => deactivate(c) }, 'Desativar')
        : h('button', { class: 'ghost', onclick: async () => {
          if (await attempt(() => api('/admin/cities', { method: 'POST', body: { cidade: c.displayName } }), 'Cidade reativada.')) refresh();
        } }, 'Reativar')),
    },
  ], cities, { empty: 'Nenhuma cidade cadastrada.' }));
}
