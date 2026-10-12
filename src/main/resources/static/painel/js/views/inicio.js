// Inicio: o que precisa de acao agora, com atalho para cada fila.
import { api, h, mount, header, fmt, loading } from '../core.js';

const total = (path, query) => api(path, { query: { size: 1, ...query } }).then(p => p.totalElements);

export async function render(root, { isCurrent }) {
  mount(root, header('Início', 'Pendências da operação'), loading());
  const [docs, drivers, cashPending, cashDivergent, metrics] = await Promise.all([
    total('/admin/drivers/documents/pending'),
    total('/admin/drivers/pending'),
    total('/admin/rides', { paymentStatus: 'PENDING' }),
    total('/admin/rides', { paymentStatus: 'DIVERGENT' }),
    api('/admin/metrics'),
  ]);
  if (!isCurrent()) return;

  const tile = (title, value, href, note) =>
    h('a', { class: `card stat link-card ${value > 0 ? 'warn' : ''}`, href },
      h('span', { class: 'muted small' }, title), h('strong', {}, fmt.num(value)), h('span', { class: 'small muted' }, note));

  mount(root,
    header('Início', 'Pendências da operação'),
    h('div', { class: 'grid' },
      tile('Documentos aguardando análise', docs, '#/motoristas', 'Fila de documentos'),
      tile('Motoristas pendentes de aprovação', drivers, '#/motoristas?tab=pendentes', 'Cadastros novos'),
      tile('Dinheiro a confirmar', cashPending, '#/corridas?tab=conciliacao&paymentStatus=PENDING', 'Motorista ainda não registrou'),
      tile('Dinheiro divergente', cashDivergent, '#/corridas?tab=conciliacao&paymentStatus=DIVERGENT', 'Recebido abaixo do preço')),
    h('h2', {}, 'Agora'),
    h('div', { class: 'grid' },
      tile('Corridas ativas', metrics.rides.active, '#/corridas', 'Em qualquer etapa não terminal'),
      tile('Motoristas online', metrics.users.onlineDrivers, '#/usuarios?role=MOTORISTA', `de ${fmt.num(metrics.users.totalDrivers)} motoristas`),
      tile('Contas suspensas', metrics.users.suspendedUsers, '#/usuarios?status=SUSPENDED', 'Revisar se cabe reativação')),
    h('p', { class: 'muted small' }, `Últimos 30 dias: ${fmt.num(metrics.rides.total)} corridas, ${fmt.money(metrics.revenue.gross)} cobrados. `,
      h('a', { href: '#/metricas' }, 'Ver métricas')));
}
