// Precos: tabela por cidade+categoria (upsert), surge dinamico e exclusao.
import { api, h, mount, header, table, label, fmt, dialog, attempt, loading } from '../core.js';

const DEFAULT = '_DEFAULT';
const cityName = c => (c === DEFAULT ? 'Padrão (demais cidades)' : c);

async function edit(current, cities, done) {
  const v = await dialog({
    title: current ? `Editar ${cityName(current.cidade)} · ${label(current.category)}` : 'Nova configuração de preço',
    message: 'Preço = base + km × distância + min × tempo. O surge multiplica até o teto quando a demanda supera a oferta.',
    confirm: 'Salvar',
    fields: [
      current ? null : { name: 'cidade', label: 'Cidade', options: [DEFAULT, ...cities], required: true },
      current ? null : { name: 'category', label: 'Categoria', options: ['CARRO', 'MOTO'], required: true },
      { name: 'base', label: 'Tarifa base (R$)', type: 'number', step: '0.01', min: '0', required: true, value: current?.base },
      { name: 'perKm', label: 'Por km (R$)', type: 'number', step: '0.01', min: '0', required: true, value: current?.perKm },
      { name: 'perMin', label: 'Por minuto (R$)', type: 'number', step: '0.01', min: '0', required: true, value: current?.perMin },
      { name: 'surgeEnabled', label: 'Surge dinâmico', options: ['false', 'true'], value: String(current?.surgeEnabled ?? false) },
      { name: 'surgeCap', label: 'Teto do surge (1.00 a 3.00)', type: 'number', step: '0.01', min: '1', max: '3', value: current?.surgeCap ?? '1.50' },
    ].filter(Boolean),
  });
  if (!v) return;
  const body = {
    cidade: current?.cidade ?? v.cidade,
    category: current?.category ?? v.category,
    base: v.base, perKm: v.perKm, perMin: v.perMin,
    surgeEnabled: v.surgeEnabled === 'true',
    surgeCap: v.surgeCap || null,
  };
  if (await attempt(() => api('/admin/pricing', { method: 'PUT', body }), 'Preço salvo.')) done();
}

async function remove(c, done) {
  const ok = await dialog({
    title: `Excluir ${cityName(c.cidade)} · ${label(c.category)}?`, danger: true, confirm: 'Excluir',
    message: c.cidade === DEFAULT ? 'Sem o padrão, cidades sem configuração própria ficam sem preço.' : 'A cidade passa a usar o preço padrão.',
  });
  if (ok && await attempt(() => api('/admin/pricing', { method: 'DELETE', query: { cidade: c.cidade, category: c.category } }), 'Configuração excluída.')) done();
}

export async function render(root, { isCurrent, refresh }) {
  let cities = [];
  const head = header('Preços', 'Tarifa por cidade e categoria',
    h('button', { class: 'primary', onclick: () => edit(null, cities, refresh) }, 'Nova configuração'));
  mount(root, head, loading());
  const [configs, allCities] = await Promise.all([api('/admin/pricing'), api('/admin/cities')]);
  if (!isCurrent()) return;
  cities = allCities.filter(c => c.active).map(c => c.slug);
  configs.sort((a, b) => (a.cidade === DEFAULT ? -1 : b.cidade === DEFAULT ? 1 : a.cidade.localeCompare(b.cidade)) || a.category.localeCompare(b.category));
  mount(root, head, table([
    { title: 'Cidade', render: c => cityName(c.cidade) },
    { title: 'Categoria', render: c => label(c.category) },
    { title: 'Base', render: c => fmt.money(c.base) },
    { title: 'Por km', render: c => fmt.money(c.perKm) },
    { title: 'Por min', render: c => fmt.money(c.perMin) },
    { title: 'Surge', render: c => (c.surgeEnabled ? `Ligado · até ${c.surgeCap}×` : 'Desligado') },
    { title: 'Atualizado', render: c => fmt.date(c.updatedAt) },
    {
      title: '', render: c => h('div', { class: 'row-actions' },
        h('button', { class: 'ghost', onclick: () => edit(c, cities, refresh) }, 'Editar'),
        h('button', { class: 'danger', onclick: () => remove(c, refresh) }, 'Excluir')),
    },
  ], configs, { empty: 'Nenhum preço configurado: as corridas usam o padrão do sistema.' }));
}
