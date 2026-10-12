# Painel de admin

Interface web da operação do piloto, servida pelo próprio backend em **`/painel/`** (produção: `https://<DOMAIN>/painel/`). Não há infra, build nem dependência nova: são arquivos estáticos em `src/main/resources/static/painel/` que consomem a API `/admin/*` já existente.

## Acesso

- Login com e-mail e senha de uma conta **ADMIN** (`POST /auth/login`). Contas de outro perfil são recusadas na tela, e a API também responde 403.
- Admin com senha temporária (incluindo o seed V2) cai direto na tela de troca de senha (`PUT /users/me/password`) e depois entra de novo.
- O JWT fica no `sessionStorage` da aba: fechar a aba encerra a sessão. Um 401 (token expirado ou revogado, conta excluída) devolve para o login.

## Segurança

- `/painel/**` é público só para os **arquivos estáticos**. Os dados vêm de `/admin/*`, que segue exigindo JWT de `ADMIN`. O `SecurityWebMvcTest` cobre que liberar o painel não abriu a API.
- Respostas de `/painel/**` levam CSP restrita (`script-src 'self'`, sem inline e sem CDN, `frame-ancestors 'none'`). Dado vindo da API entra no DOM só por `textContent`, nunca por `innerHTML`.
- Ações destrutivas pedem confirmação em modal próprio. Ver ficha (CPF/CNH), abrir arquivo de documento, aprovar, reprovar, suspender, resetar senha, preço, cidade e campanha: tudo fica na trilha de auditoria pelo próprio backend.

## Telas

| Tela | O que faz | Endpoints |
|---|---|---|
| Início | Pendências: documentos na fila, motoristas pendentes, dinheiro a confirmar/divergente, corridas ativas, motoristas online | `/admin/drivers/documents/pending`, `/admin/drivers/pending`, `/admin/rides?paymentStatus=`, `/admin/metrics` |
| Métricas | Cards do período e gráfico diário (SVG próprio) | `GET /admin/metrics?from&to` |
| Corridas | Lista com filtros, detalhe e cancelamento com motivo; aba **Conciliação de dinheiro** (preço × recebido × diferença) | `GET /admin/rides`, `GET /admin/rides/{id}`, `POST /admin/rides/{id}/cancel` |
| Motoristas | Fila de documentos, cadastros pendentes e ficha (dados declarados, sinal do dígito da CNH, vigentes/histórico); ver arquivo por URL assinada, aprovar/reprovar documento, aprovar motorista | `/admin/drivers/...` |
| Usuários | Busca por nome/e-mail/telefone, perfil, situação e cidade; suspender, reativar e resetar senha (a temporária aparece uma vez, com botão copiar) | `/admin/users...` |
| Cidades | Abrir, reabrir e desativar (soft), com nº de contas | `/admin/cities` |
| Preços | Upsert por cidade+categoria (inclui padrão `_DEFAULT`), surge e teto, exclusão | `/admin/pricing` |
| Campanhas | Histórico; nova campanha com **prévia de público** antes de enviar | `/admin/campaigns`, `/admin/campaigns/audience` |
| Auditoria | Trilha com filtro por ação e alvo; link para a corrida, motorista, cidade, preço ou campanha | `GET /admin/audit` |

## Estrutura

```
static/painel/
├── index.html        shell: login, troca de senha, layout
├── app.css           tema claro/escuro, responsivo (menu vira barra no celular)
└── js/
    ├── main.js       login, roteador por hash (#/rota/sub?filtros)
    ├── core.js       cliente da API, DOM seguro (h()), tabela, paginação, modal, toast, formatação pt-BR
    └── views/*.js    uma tela por arquivo
```

Para adicionar uma tela: crie `views/x.js` exportando `render(root, ctx)` e registre em `ROUTES` no `main.js`. Os filtros e a página ficam na URL (`#/corridas?status=COMPLETED&page=1`), então dá para compartilhar o link de uma busca.

## Limitações conhecidas

- A API não tem busca de usuário por id. O passageiro aparece só pelo id no detalhe da corrida; o motorista tem link para a ficha.
- Sem atualização em tempo real: as telas recarregam ao navegar ou filtrar.
