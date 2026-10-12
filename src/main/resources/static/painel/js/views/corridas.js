// Corridas: lista com filtros, conciliacao de dinheiro, detalhe e cancelamento.
import { api, h, mount, header, table, pager, filters, badge, label, fmt, dl, dialog, attempt, loading, link } from '../core.js';

const TERMINAL = ['COMPLETED', 'CANCELLED', 'EXPIRED'];
const STATUSES = ['PENDING_PAYMENT', 'AVAILABLE_IN_MURAL', 'DRIVER_EN_ROUTE', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'EXPIRED'];

// A busca de usuarios nao filtra por id: so o motorista tem ficha propria.
const driverLink = id => (id ? link(`#/motoristas/${id}`, fmt.id(id)) : '—');

async function cancel(ride, done) {
  const v = await dialog({
    title: 'Cancelar corrida', danger: true, confirm: 'Cancelar corrida',
    message: `Corrida ${fmt.id(ride.id)} (${label(ride.status)}). O motivo fica na trilha de auditoria.`,
    fields: [{ name: 'reason', label: 'Motivo', type: 'textarea', required: true }],
  });
  if (v && await attempt(() => api(`/admin/rides/${ride.id}/cancel`, { method: 'POST', body: v }), 'Corrida cancelada.')) done();
}

async function renderDetail(root, id, ctx) {
  mount(root, loading());
  const r = await api(`/admin/rides/${id}`);
  if (!ctx.isCurrent()) return;
  const coord = (lat, lng) => (lat == null ? '—' : `${lat}, ${lng}`);
  mount(root,
    header(`Corrida ${fmt.id(r.id)}`, r.id,
      h('a', { class: 'button ghost', href: '#/corridas' }, '‹ Voltar'),
      !TERMINAL.includes(r.status) && h('button', { class: 'danger', onclick: () => cancel(r, ctx.refresh) }, 'Cancelar corrida')),
    h('div', { class: 'grid two' },
      h('div', { class: 'card' }, h('h2', {}, 'Corrida'), dl([
        ['Status', badge(r.status)], ['Cidade', r.cidade], ['Categoria', label(r.category)],
        ['Passageiro', r.passageiroId], ['Motorista', driverLink(r.motoristaId)],
        ['Origem', coord(r.latOrigem, r.lngOrigem)], ['Destino', coord(r.latDestino, r.lngDestino)],
        ['Paradas', r.stops?.length ? r.stops.length : 'Nenhuma'],
        ['Distância', r.distanciaKm != null ? `${r.distanciaKm} km` : '—'], ['Tempo estimado', r.tempoMin != null ? `${r.tempoMin} min` : '—'],
      ])),
      h('div', { class: 'card' }, h('h2', {}, 'Pagamento'), dl([
        ['Preço', fmt.money(r.preco)], ['Surge', r.surgeMultiplier ? `${r.surgeMultiplier}×` : '—'],
        ['Método', label(r.paymentMethod)], ['Status', r.paymentStatus ? badge(r.paymentStatus) : '—'],
        ['Troco para', fmt.money(r.cashChangeFor)], ['Recebido', fmt.money(r.cashReceivedAmount)],
        ['Confirmado em', fmt.date(r.paymentConfirmedAt)], ['Observação', r.paymentNote],
        ['Taxa de cancelamento', fmt.money(r.cancellationFee)],
      ])),
      h('div', { class: 'card' }, h('h2', {}, 'Linha do tempo'), dl([
        ['Criada', fmt.date(r.createdAt)], ['Aceita', fmt.date(r.acceptedAt)], ['Iniciada', fmt.date(r.startedAt)],
        ['Concluída', fmt.date(r.completedAt)], ['Expirada', fmt.date(r.expiredAt)], ['Cancelada', fmt.date(r.cancelledAt)],
        ['Cancelada por', label(r.cancelledBy)], ['Motivo', r.cancelReason],
      ])),
      h('div', { class: 'card' }, h('h2', {}, 'Motorista'), dl([
        ['Última posição', coord(r.driverCurrentLat, r.driverCurrentLng)], ['Atualizada em', fmt.date(r.driverLocationUpdatedAt)],
        ['ETA de chegada', r.pickupEtaMin != null ? `${r.pickupEtaMin} min` : '—'],
        ['Avaliação', r.motoristaRatingAvg != null ? `${r.motoristaRatingAvg} (${r.motoristaRatingCount})` : '—'],
      ]))));
}

export async function render(root, ctx) {
  const { rest, params, navigate, isCurrent } = ctx;
  if (rest[0]) return renderDetail(root, rest[0], ctx);

  const recon = params.tab === 'conciliacao';
  const tabs = h('div', { class: 'tabs' },
    h('a', { href: '#/corridas', class: recon ? '' : 'active' }, 'Todas'),
    h('a', { href: '#/corridas?tab=conciliacao&paymentStatus=PENDING', class: recon ? 'active' : '' }, 'Conciliação de dinheiro'));
  const form = recon
    ? filters([{ name: 'paymentStatus', label: 'Pagamento', options: ['PENDING', 'DIVERGENT', 'CONFIRMED'] },
      { name: 'cidade', label: 'Cidade' }], params, v => navigate({ tab: 'conciliacao', ...v }))
    : filters([
      { name: 'status', label: 'Status', options: STATUSES },
      { name: 'paymentStatus', label: 'Pagamento', options: ['PENDING', 'CONFIRMED', 'DIVERGENT', 'SIMULATED'] },
      { name: 'cidade', label: 'Cidade' },
      { name: 'from', label: 'De', type: 'date' }, { name: 'to', label: 'Até', type: 'date' },
    ], params, navigate);
  const head = header('Corridas', recon ? 'Corridas em dinheiro: preço cobrado × valor registrado pelo motorista' : 'Mais recentes primeiro');
  mount(root, head, tabs, form, loading());

  const { tab, ...query } = params;
  const page = await api('/admin/rides', { query: { size: 20, ...query } });
  if (!isCurrent()) return;

  const open = r => { location.hash = `#/corridas/${r.id}`; };
  const columns = recon
    ? [
      { title: 'Corrida', render: r => fmt.id(r.id) },
      { title: 'Concluída', render: r => fmt.date(r.completedAt) },
      { title: 'Cidade', render: r => r.cidade },
      { title: 'Preço', render: r => fmt.money(r.preco) },
      { title: 'Recebido', render: r => fmt.money(r.cashReceivedAmount) },
      { title: 'Diferença', render: r => (r.cashReceivedAmount == null ? '—' : fmt.money(r.cashReceivedAmount - r.preco)) },
      { title: 'Situação', render: r => badge(r.paymentStatus) },
      { title: 'Observação', render: r => r.paymentNote || '—' },
    ]
    : [
      { title: 'Corrida', render: r => fmt.id(r.id) },
      { title: 'Criada', render: r => fmt.date(r.createdAt) },
      { title: 'Cidade', render: r => r.cidade },
      { title: 'Categoria', render: r => label(r.category) },
      { title: 'Status', render: r => badge(r.status) },
      { title: 'Pagamento', render: r => `${label(r.paymentMethod)} · ${label(r.paymentStatus)}` },
      { title: 'Preço', render: r => fmt.money(r.preco) },
    ];
  mount(root, head, tabs, form,
    table(columns, page.content, { onRow: open, empty: 'Nenhuma corrida com esses filtros.' }),
    pager(page, n => navigate({ ...params, page: n })));
}
