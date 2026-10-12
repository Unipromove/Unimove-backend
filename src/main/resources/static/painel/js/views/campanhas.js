// Campanhas: historico e envio in-app com previa de publico (so opt-in).
import { api, h, mount, header, table, pager, badge, label, fmt, dialog, attempt, loading } from '../core.js';

async function compose(cities, done) {
  const v = await dialog({
    title: 'Nova campanha', confirm: 'Ver público',
    message: 'Vai para a caixa de entrada do app. Só alcança contas ativas com opt-in promocional.',
    fields: [
      { name: 'title', label: 'Título', required: true, maxlength: 80 },
      { name: 'body', label: 'Mensagem', type: 'textarea', required: true, maxlength: 500 },
      { name: 'cidade', label: 'Cidade', options: ['', ...cities] },
      { name: 'role', label: 'Perfil', options: ['', 'PASSAGEIRO', 'MOTORISTA'] },
    ],
  });
  if (!v) return;
  const target = { cidade: v.cidade || undefined, role: v.role || undefined };
  const preview = await attempt(() => api('/admin/campaigns/audience', { query: target }));
  if (!preview) return;
  const ok = await dialog({
    title: 'Confirmar envio', confirm: `Enviar para ${fmt.num(preview.recipients)}`, danger: true,
    content: h('div', {},
      h('p', {}, h('strong', {}, v.title)),
      h('p', { class: 'pre' }, v.body),
      h('p', { class: 'muted small' }, `Segmento: ${v.cidade || 'todas as cidades'} · ${v.role ? label(v.role) : 'todos os perfis'}`),
      preview.recipients === 0 && h('p', { class: 'notice' }, 'Ninguém no segmento: a campanha fica registrada como "Sem público".')),
  });
  if (ok && await attempt(() => api('/admin/campaigns', { method: 'POST', body: { title: v.title, body: v.body, ...target } }), 'Campanha enviada.')) done();
}

export async function render(root, { params, navigate, isCurrent, refresh }) {
  let cities = [];
  const head = header('Campanhas', 'Comunicação promocional in-app (sem push)',
    h('button', { class: 'primary', onclick: () => compose(cities, refresh) }, 'Nova campanha'));
  mount(root, head, loading());
  const [page, allCities] = await Promise.all([
    api('/admin/campaigns', { query: { size: 20, page: params.page } }),
    api('/admin/cities'),
  ]);
  if (!isCurrent()) return;
  cities = allCities.filter(c => c.active).map(c => c.slug);
  mount(root, head,
    table([
      { title: 'Enviada', render: c => fmt.date(c.sentAt || c.createdAt) },
      { title: 'Título', render: c => h('span', { title: c.body }, c.title) },
      { title: 'Cidade', render: c => c.targetCidade || 'Todas' },
      { title: 'Perfil', render: c => (c.targetRole ? label(c.targetRole) : 'Todos') },
      { title: 'Destinatários', render: c => fmt.num(c.recipients) },
      { title: 'Situação', render: c => badge(c.status) },
    ], page.content, { empty: 'Nenhuma campanha enviada ainda.' }),
    pager(page, n => navigate({ page: n })));
}
