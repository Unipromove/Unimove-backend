# Deploy — VPS Hetzner (API + Postgres + documentos + Caddy)

Tudo roda numa VPS so, sem Render e sem Supabase. Push na `main` → testes →
imagem no GHCR → o CI entra na VPS por SSH e roda o `scripts/deploy.sh`.
Sem hibernacao: schedulers e SSE rodam 24h.

```
git push origin main            (repo Unipromove/Unimove-backend)
   │
   ├─ [test]    mvn -B verify (unitarios + IT contra Postgres)
   ├─ [build]   push ghcr.io/unipromove/unimove-backend:<sha> e :latest (amd64 + arm64)
   └─ [deploy]  tar (compose + Caddyfile + scripts) | ssh -> /opt/unimove
                GITHUB_TOKEN | ssh -> deploy.sh <sha> --ghcr-login  (login, pull, logout)
                     up, health local; falhou => volta para a tag anterior
                health publico em https://<dominio>/actuator/health
```

| Peca | Onde |
|---|---|
| API (Spring Boot) | container `unimove-api-prod`, heap ~768 MB (`mem_limit: 1536m`) |
| Postgres 17 | container `unimove-postgres-prod`, volume `unimove_pgdata_prod`, so em `127.0.0.1` |
| Documentos do motorista | volume `unimove_documents`, **cifrados** com a `DATA_ENCRYPTION_KEY`; o admin abre por URL assinada de 5 min servida pela API (`/files/documents/**`) |
| TLS | Caddy + Let's Encrypt automatico (`Caddyfile`), dominio DuckDNS |
| Backup | cron diario 03:30 UTC: `pg_dump` + tar dos documentos, 7 copias **so na VPS** |

> ⚠️ **Backup so local.** Se a VPS for perdida, banco e documentos se perdem junto
> com os backups. Antes de usuarios reais: ative *Backups* no console da Hetzner
> (+20% no preco) ou contrate uma Storage Box.

---

## Configurar (uma vez) — 4 passos

### 1. DuckDNS

Em https://www.duckdns.org (login com GitHub/Google) crie o subdominio, ex
`unimove`, e em *current ip* ponha o **IP da VPS** (console da Hetzner → sua VPS →
IPv4). O IP da Hetzner e fixo: nao precisa de atualizador.

### 2. Preparar a VPS (um script faz tudo)

Abra o dashboard do Render (servico → *Environment*) ao lado: o script pergunta
os valores. No PowerShell, na raiz do repo:

```powershell
scp scripts/bootstrap-vps.sh root@IP-DA-VPS:
ssh -t root@IP-DA-VPS bash bootstrap-vps.sh
```

| O script pergunta | Responda |
|---|---|
| Subdominio DuckDNS | `unimove.duckdns.org` |
| `JWT_SECRET`, `DATA_ENCRYPTION_KEY` | cole do Render, ou Enter para gerar novos (o banco comeca vazio, entao tanto faz) |
| `PHONE_VERIFICATION_CHANNEL` | o do Render (`LOG` ou `WHATSAPP`; se `WHATSAPP`, pergunta as 3 credenciais) |
| `GOOGLE_LOGIN_ENABLED` | o do Render (se `true`, pergunta `GOOGLE_CLIENT_IDS`) |
| `PRIVACY_TERMS_VERSION`, `PRIVACY_POLICY_URL` | o do Render, ou Enter |

O `DATABASE_PASSWORD` e gerado sozinho. Nao copie `DATABASE_URL`, `DATABASE_USER` nem
`SUPABASE_*`: nao existem mais.

O script instala Docker, atualizacoes automaticas e firewall (22/80/443), cria o
usuario `deploy` com a sua chave SSH (a que o root ja tem) e uma chave so do CI,
desliga login por senha, grava o `/opt/unimove/.env` (chmod 600) e agenda o backup.
Pode rodar de novo sem estragar nada.

> Se a VPS foi criada **com senha** (sem chave SSH), o script pede para colar sua
> chave publica. Sem nenhuma, ele mantem o login por senha para nao te trancar fora.

**Guarde o `.env` num gerenciador de senhas** (`ssh deploy@IP cat /opt/unimove/.env`):
sem a `DATA_ENCRYPTION_KEY`, CPF/CNH, documentos e backups ficam ilegiveis.

### 3. GitHub

O script termina imprimindo um bloco **PRONTO** com os valores. No repo
Unipromove/Unimove-backend → Settings → Secrets and variables → Actions:

- aba **Secrets**: `VPS_HOST`, `VPS_USER`, `VPS_KNOWN_HOSTS`, `VPS_SSH_KEY`
  (a chave privada inteira, com as linhas `BEGIN`/`END`);
- aba **Variables**: `VPS_PUBLIC_URL`.

A chave privada do CI so aparece essa vez e nao fica na VPS. Perdeu? Apague a linha
`github-actions-unimove` de `/home/deploy/.ssh/authorized_keys` e rode o script de novo.

Nao precisa de token do GHCR: o job de deploy usa o `GITHUB_TOKEN` dele mesmo
para puxar a imagem privada e faz logout no fim.

### 4. Merge

Abra o PR para a `main`; com os testes verdes, faca o merge. Em **Actions** o
*CI/CD* roda **Testes → Imagem → GHCR → Deploy na VPS**. No primeiro deploy o
Caddy pede o certificado HTTPS (~30 s) e o Flyway cria o banco do zero. Pronto
quando `https://unimove.duckdns.org/actuator/health` responde `"status":"UP"`.

---

## Depois do primeiro deploy

1. **Senha do admin** — a API bloqueia o admin do seed ate trocar a senha:
   ```powershell
   $api = "https://unimove.duckdns.org"
   $t = (Invoke-RestMethod -Method Post "$api/auth/login" -ContentType "application/json" -Body '{"email":"admin@unimove.local","password":"ChangeMe123!"}').token
   Invoke-RestMethod -Method Put "$api/users/me/password" -Headers @{Authorization="Bearer $t"} -ContentType "application/json" -Body '{"currentPassword":"ChangeMe123!","newPassword":"SUA-SENHA-FORTE"}'
   ```
2. **WhatsApp** (so se o canal for `WHATSAPP`): painel da Meta → WhatsApp →
   Configuration → Webhook → URL `https://unimove.duckdns.org/webhooks/whatsapp`,
   mesmo verify token. Login Google nao muda.
3. **Smoke** (Git Bash): `bash scripts/smoke.sh https://unimove.duckdns.org`
4. **Desligar o antigo:** servico no Render, projeto no Supabase e o pacote antigo
   `unimove` no GHCR da sua conta pessoal.

---

## Operacao

| Tarefa | Como |
|---|---|
| Versao no ar | `ssh deploy@IP grep IMAGE_TAG /opt/unimove/.env` |
| Rollback | GitHub → Actions → run do commit bom → *Re-run jobs*; ou na VPS `bash /opt/unimove/scripts/deploy.sh <sha>` (imagens dos ultimos 7 dias) |
| Logs | `docker compose -f /opt/unimove/docker-compose.prod.yml logs -f api` |
| psql | `docker exec -it unimove-postgres-prod psql -U unimove` |
| Banco do seu PC | `ssh -L 5432:127.0.0.1:5432 deploy@IP` |
| Trocar um segredo | `nano /opt/unimove/.env` e `bash /opt/unimove/scripts/deploy.sh $(sed -n 's/^IMAGE_TAG=//p' /opt/unimove/.env)` |
| Backup na mao | `bash /opt/unimove/scripts/backup.sh` (log do cron em `/opt/unimove/backups/backup.log`) |

> Migration **nao** volta com o rollback. Se a versao quebrada aplicou uma `V{n}`,
> corrija com uma `V{n+1}` nova, nunca editando a migration aplicada.

> **Nunca troque a `DATA_ENCRYPTION_KEY`** de um ambiente com dados: CPF/CNH e
> documentos ja gravados ficam ilegiveis.

**Testar um backup** (so vale depois de restaurado):
```bash
cd /opt/unimove
docker run -d --rm --name rt -e POSTGRES_PASSWORD=x postgres:17-alpine && sleep 5
docker cp backups/<data>.dump rt:/tmp/b.dump
docker exec rt pg_restore --no-owner --exit-on-error -U postgres -d postgres /tmp/b.dump
docker exec rt psql -U postgres -Atc "select count(*) from users"; docker rm -f rt
```
Restaurar os documentos: `docker cp - unimove-api-prod:/data < backups/<data>-docs.tgz`.

## Limites conhecidos

| Limite | Quando resolver |
|---|---|
| Backup so na VPS | antes de usuarios reais: Backups da Hetzner ou Storage Box |
| ~200 ms de latencia (VPS na Europa) | se incomodar no piloto: VPS em Ashburn |
| Instancia unica (SSE em memoria, documentos em disco local) | ao escalar: Redis e object storage |
| OSRM/Photon nas demos publicas, sem SLA | self-host |
| Sem staging | antes de abrir para usuarios reais |
