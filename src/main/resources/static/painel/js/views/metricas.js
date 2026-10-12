// Metricas: /admin/metrics com periodo e serie diaria em SVG proprio (sem lib).
import { api, h, mount, header, stat, fmt, filters, loading } from '../core.js';

const SVG = 'http://www.w3.org/2000/svg';
const s = (tag, attrs, ...children) => {
  const el = document.createElementNS(SVG, tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  el.append(...children);
  return el;
};

/** Barras: total de corridas por dia, com a parte concluida destacada. */
function dailyChart(days) {
  if (!days.length) return h('p', { class: 'empty' }, 'Sem corridas no período.');
  const W = 720, H = 220, pad = { t: 12, r: 8, b: 26, l: 36 };
  const max = Math.max(1, ...days.map(d => d.totalRides));
  const bw = (W - pad.l - pad.r) / days.length;
  const y = v => pad.t + (H - pad.t - pad.b) * (1 - v / max);
  const svg = s('svg', { viewBox: `0 0 ${W} ${H}`, class: 'chart', role: 'img', 'aria-label': 'Corridas por dia' });
  for (const t of [0, Math.round(max / 2), max]) {
    svg.append(s('line', { x1: pad.l, x2: W - pad.r, y1: y(t), y2: y(t), class: 'grid-line' }),
      s('text', { x: pad.l - 6, y: y(t) + 4, 'text-anchor': 'end', class: 'axis' }, String(t)));
  }
  const labelEvery = Math.ceil(days.length / 10);
  days.forEach((d, i) => {
    const x = pad.l + i * bw + bw * 0.15, w = Math.max(1, bw * 0.7);
    const tip = `${fmt.day(d.day)}: ${d.totalRides} corridas, ${d.completedRides} concluídas, ${fmt.money(d.revenue)}`;
    svg.append(s('g', {},
      s('title', {}, tip),
      s('rect', { x, width: w, y: y(d.totalRides), height: y(0) - y(d.totalRides), class: 'bar-total', rx: 2 }),
      s('rect', { x, width: w, y: y(d.completedRides), height: y(0) - y(d.completedRides), class: 'bar-done', rx: 2 })));
    if (i % labelEvery === 0) svg.append(s('text', { x: x + w / 2, y: H - 8, 'text-anchor': 'middle', class: 'axis' }, fmt.day(d.day)));
  });
  return h('div', {}, svg, h('p', { class: 'legend small muted' },
    h('span', { class: 'swatch done' }), 'Concluídas ', h('span', { class: 'swatch total' }), 'Total criadas'));
}

export async function render(root, { params, navigate, isCurrent }) {
  const head = header('Métricas', 'Período pela data de criação da corrida (padrão: últimos 30 dias)');
  const form = filters([
    { name: 'from', label: 'De', type: 'date' },
    { name: 'to', label: 'Até', type: 'date' },
  ], params, navigate);
  mount(root, head, form, loading());
  const m = await api('/admin/metrics', { query: params });
  if (!isCurrent()) return;
  const r = m.rides, rv = m.revenue, p = m.payments, u = m.users;
  mount(root, head, form,
    h('p', { class: 'muted small' }, `${fmt.day(m.from)} a ${fmt.day(m.to)}`),
    h('h2', {}, 'Corridas'),
    h('div', { class: 'grid' },
      stat('Total', fmt.num(r.total)),
      stat('Concluídas', fmt.num(r.completed), `${fmt.pct(r.completionRate)} de conclusão`, 'ok'),
      stat('Canceladas', fmt.num(r.cancelled), `${fmt.pct(r.cancellationRate)} de cancelamento`, r.cancellationRate > 0.2 ? 'bad' : ''),
      stat('Expiradas', fmt.num(r.expired), 'Ninguém aceitou'),
      stat('Ativas agora', fmt.num(r.active))),
    h('div', { class: 'card' }, dailyChart(m.byDay)),
    h('h2', {}, 'Receita e pagamentos'),
    h('div', { class: 'grid' },
      stat('Valor cobrado', fmt.money(rv.gross), 'Soma das concluídas (não é o recebido)'),
      stat('Ticket médio', fmt.money(rv.averageTicket)),
      stat('Taxas de cancelamento', fmt.money(rv.cancellationFees)),
      stat('Dinheiro confirmado', fmt.money(p.cashConfirmed), 'Único valor que de fato entrou', 'ok'),
      stat('Dinheiro pendente', fmt.num(p.cashPending), 'Corridas a conciliar', p.cashPending ? 'warn' : ''),
      stat('Dinheiro divergente', fmt.num(p.cashDivergent), 'Recebido abaixo do preço', p.cashDivergent ? 'bad' : ''),
      stat('Pix simulado', fmt.num(p.pixSimulated), 'Sem PSP: não conta como receita')),
    h('h2', {}, 'Base de usuários (agora)'),
    h('div', { class: 'grid' },
      stat('Passageiros', fmt.num(u.totalPassengers)),
      stat('Motoristas', fmt.num(u.totalDrivers)),
      stat('Motoristas online', fmt.num(u.onlineDrivers), null, 'ok'),
      stat('Motoristas pendentes', fmt.num(u.pendingDrivers), null, u.pendingDrivers ? 'warn' : ''),
      stat('Contas suspensas', fmt.num(u.suspendedUsers))));
}
