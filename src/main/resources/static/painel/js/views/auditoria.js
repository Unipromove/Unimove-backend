// Auditoria: trilha das acoes administrativas, com atalho para o alvo.
import { api, h, mount, header, table, pager, filters, fmt, loading } from '../core.js';

const ACTIONS = {
  DRIVER_APPROVED: 'Motorista aprovado', DRIVER_DOCUMENT_APPROVED: 'Documento aprovado',
  DRIVER_DOCUMENT_REJECTED: 'Documento reprovado', DRIVER_DOCUMENT_VIEWED: 'Arquivo de documento visto',
  DRIVER_DOCUMENTATION_VIEWED: 'Ficha do motorista vista', USER_SUSPENDED: 'Conta suspensa',
  USER_REACTIVATED: 'Conta reativada', USER_PASSWORD_RESET: 'Senha resetada', PRICING_UPSERTED: 'Preço salvo',
  PRICING_DELETED: 'Preço excluído', CITY_OPENED: 'Cidade aberta', CITY_DEACTIVATED: 'Cidade desativada',
  RIDE_CANCELLED: 'Corrida cancelada', CAMPAIGN_SENT: 'Campanha enviada',
};

/** Leva ao alvo quando ha tela para ele. */
function target(e) {
  if (!e.targetId) return '—';
  // Acoes de documento apontam para o id do documento, nao do motorista: sem link.
  const href = {
    USER: e.action.startsWith('DRIVER_') ? `#/motoristas/${e.targetId}` : null,
    RIDE: `#/corridas/${e.targetId}`, CITY: '#/cidades', PRICING: '#/precos', CAMPAIGN: '#/campanhas',
  }[e.targetType] || null;
  const text = `${e.targetType || ''} ${e.targetId.length > 12 ? fmt.id(e.targetId) : e.targetId}`.trim();
  return href ? h('a', { href, title: e.targetId }, text) : h('span', { title: e.targetId }, text);
}

export async function render(root, { params, navigate, isCurrent }) {
  const head = header('Auditoria', 'Mais recentes primeiro');
  const form = filters([
    { name: 'action', label: 'Ação', options: Object.keys(ACTIONS) },
    { name: 'targetId', label: 'Alvo (id ou slug)' },
  ], params, navigate);
  // Os nomes das acoes ficam so nesta tela.
  for (const o of form.querySelectorAll('select[name=action] option')) if (ACTIONS[o.value]) o.textContent = ACTIONS[o.value];
  mount(root, head, form, loading());
  const page = await api('/admin/audit', { query: { size: 30, ...params } });
  if (!isCurrent()) return;
  mount(root, head, form,
    table([
      { title: 'Quando', render: e => fmt.date(e.createdAt) },
      { title: 'Ação', render: e => ACTIONS[e.action] || e.action },
      { title: 'Alvo', render: target },
      { title: 'Admin', render: e => h('span', { title: e.adminId }, fmt.id(e.adminId)) },
      { title: 'Detalhes', render: e => e.details || '—' },
    ], page.content, { empty: 'Nenhuma ação registrada com esses filtros.' }),
    pager(page, n => navigate({ ...params, page: n })));
}
