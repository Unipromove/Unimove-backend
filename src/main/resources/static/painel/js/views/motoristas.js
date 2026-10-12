// Motoristas: fila de documentos, cadastros pendentes e ficha com revisao de documentos.
import { api, h, mount, header, table, pager, badge, label, fmt, dl, dialog, attempt, loading } from '../core.js';

const DOC_NAMES = { CNH: 'CNH', CRLV: 'CRLV', SELFIE: 'Selfie' };
const openDriver = id => { location.hash = `#/motoristas/${id}`; };

/** Pede a URL assinada (acesso auditado) e mostra imagem ou abre o PDF. */
async function viewFile(driverId, doc) {
  const file = await attempt(() => api(`/admin/drivers/${driverId}/documents/${doc.id}/file`));
  if (!file) return;
  const newTab = h('a', { href: file.url, target: '_blank', rel: 'noopener noreferrer' }, 'Abrir em nova aba');
  const content = doc.contentType?.startsWith('image/')
    ? h('div', { class: 'doc-preview' }, h('img', { src: file.url, alt: DOC_NAMES[doc.type] || doc.type }))
    : h('p', {}, 'Arquivo PDF.');
  await dialog({
    title: `${DOC_NAMES[doc.type] || doc.type} · enviado em ${fmt.date(doc.submittedAt)}`,
    content: h('div', {}, content, h('p', { class: 'small muted' }, newTab, ` · link válido até ${fmt.date(file.expiresAt)}`)),
    confirm: 'Fechar',
  });
}

async function reviewDoc(driverId, doc, approve, done) {
  if (approve) {
    if (await attempt(() => api(`/admin/drivers/${driverId}/documents/${doc.id}/approve`, { method: 'POST' }), 'Documento aprovado.')) done();
    return;
  }
  const v = await dialog({
    title: `Reprovar ${DOC_NAMES[doc.type] || doc.type}`, danger: true, confirm: 'Reprovar',
    message: 'O motivo aparece para o motorista. Se ele estava aprovado, sai de operação até reenviar.',
    fields: [{ name: 'reason', label: 'Motivo', type: 'textarea', required: true }],
  });
  if (v && await attempt(() => api(`/admin/drivers/${driverId}/documents/${doc.id}/reject`, { method: 'POST', body: v }), 'Documento reprovado.')) done();
}

async function renderDriver(root, driverId, { isCurrent, refresh }) {
  mount(root, loading());
  const d = await api(`/admin/drivers/${driverId}/documentation`);
  if (!isCurrent()) return;
  const p = d.profile || {};
  const cnhCheck = p.cnhCheckDigitValid == null ? '' : p.cnhCheckDigitValid ? ' ✓ dígito confere' : ' ⚠ dígito não confere';
  const approveDriver = async () => {
    if (await attempt(() => api(`/admin/drivers/${driverId}/approve`, { method: 'POST' }), 'Motorista aprovado.')) refresh();
  };
  const docColumns = withActions => [
    { title: 'Documento', render: x => DOC_NAMES[x.type] || x.type },
    { title: 'Status', render: x => badge(x.status) },
    { title: 'Enviado', render: x => fmt.date(x.submittedAt) },
    { title: 'Revisado', render: x => fmt.date(x.reviewedAt) },
    { title: 'Motivo', render: x => x.rejectionReason || '—' },
    withActions && {
      title: '', render: x => h('div', { class: 'row-actions' },
        h('button', { class: 'ghost', onclick: () => viewFile(driverId, x) }, 'Ver'),
        x.status === 'SUBMITTED' && h('button', { class: 'primary', onclick: () => reviewDoc(driverId, x, true, refresh) }, 'Aprovar'),
        x.status === 'SUBMITTED' && h('button', { class: 'danger', onclick: () => reviewDoc(driverId, x, false, refresh) }, 'Reprovar')),
    },
  ].filter(Boolean);

  mount(root,
    header('Ficha do motorista', driverId,
      h('a', { class: 'button ghost', href: '#/motoristas' }, '‹ Voltar'),
      !d.approved && h('button', { class: 'primary', onclick: approveDriver }, 'Aprovar motorista')),
    h('p', {}, 'Documentação: ', badge(d.status), d.approved ? ' · motorista aprovado para operar' : ' · ainda não aprovado'),
    d.pending?.length ? h('p', { class: 'notice' }, `Falta: ${d.pending.join(', ')}`) : null,
    h('div', { class: 'grid two' },
      h('div', { class: 'card' }, h('h2', {}, 'Dados declarados'), dl([
        ['CPF', p.cpf], ['CNH', p.cnhNumber ? `${p.cnhNumber}${cnhCheck}` : null], ['Categoria CNH', p.cnhCategory],
        ['Validade CNH', p.cnhExpiresOn && fmt.fullDay(p.cnhExpiresOn)],
      ])),
      h('div', { class: 'card' }, h('h2', {}, 'Veículo'), dl([
        ['Tipo', label(p.vehicleType)], ['Placa', p.vehiclePlate], ['Marca/modelo', [p.vehicleBrand, p.vehicleModel].filter(Boolean).join(' ') || null],
        ['Ano', p.vehicleYear], ['Cor', p.vehicleColor],
      ]))),
    h('p', { class: 'small muted' }, 'Ver CPF/CNH e abrir arquivos fica registrado na trilha de auditoria.'),
    h('h2', {}, 'Documentos vigentes'),
    table(docColumns(true), d.current, { empty: 'Nenhum documento enviado.' }),
    h('h2', {}, 'Histórico de envios'),
    table(docColumns(false), d.history, { empty: 'Sem envios anteriores.' }));
}

export async function render(root, ctx) {
  const { rest, params, navigate, isCurrent } = ctx;
  if (rest[0]) return renderDriver(root, rest[0], ctx);

  const pendentes = params.tab === 'pendentes';
  const head = header('Motoristas', 'Análise de documentos e aprovação de cadastro');
  const tabs = h('div', { class: 'tabs' },
    h('a', { href: '#/motoristas', class: pendentes ? '' : 'active' }, 'Documentos na fila'),
    h('a', { href: '#/motoristas?tab=pendentes', class: pendentes ? 'active' : '' }, 'Cadastros pendentes'));
  mount(root, head, tabs, loading());

  const page = await api(pendentes ? '/admin/drivers/pending' : '/admin/drivers/documents/pending',
    { query: { size: 20, page: params.page } });
  if (!isCurrent()) return;

  const list = pendentes
    ? table([
      { title: 'Nome', render: x => x.name },
      { title: 'Cidade', render: x => x.cidade },
      { title: 'Veículo', render: x => [label(x.vehicleType), x.vehiclePlate].filter(Boolean).join(' · ') || '—' },
      { title: 'Cadastro', render: x => fmt.date(x.registeredAt) },
      { title: 'Documentação', render: x => badge(x.documentationStatus) },
      { title: 'Contato', render: x => x.phone || x.email },
    ], page.content, { onRow: x => openDriver(x.userId), empty: 'Nenhum motorista aguardando aprovação.' })
    : table([
      { title: 'Motorista', render: x => x.driverName },
      { title: 'Cidade', render: x => x.cidade },
      { title: 'Documento', render: x => DOC_NAMES[x.type] || x.type },
      { title: 'Enviado', render: x => fmt.date(x.submittedAt) },
    ], page.content, { onRow: x => openDriver(x.driverId), empty: 'Fila vazia. Nenhum documento aguardando análise.' });

  mount(root, head, tabs, list, pager(page, n => navigate({ ...params, page: n })));
}
