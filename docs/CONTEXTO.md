# Contexto para continuar o desenvolvimento

Leia este arquivo inteiro antes de mexer em qualquer coisa. Ele resume o que existe, as regras do dono do projeto, como
compilar e testar, onde fica o servidor e o que está pendente. Depois leia o `CHANGELOG.md` (o que já foi feito, do mais novo
para o mais antigo) e o manual (`docs/manual/conteudo.py` tem o texto de cada plugin; o PDF é `docs/Manual-Nukkit-Gold-0.4.3.pdf`).

## O projeto
- **Nukkit-Gold-Edition** (github.com/gustavoz65/Nukkit-Gold-Edition): o core do servidor, um Nukkit para **MCPE 0.15.10 (protocolo 84)**.
  Notas em `builds/VERSAO.md`, o jar em `builds/nukkit-gold-0.15.10.jar`, egg do Pterodactyl e nftables em `hospedagem/`.
- **Pack-Gold-Plugins** (github.com/gustavoz65/Pack-Gold-Plugins, este repositório): os **32 plugins Gold**, Gradle multi-módulo,
  código Java 8 (`--release 8`). O `gold-api` vai embutido no `GoldCore`; os outros plugins só compilam contra ele.
- Versão: **0.4.3** (o dono quer manter 0.4.3 até dizer o contrário). Cada lote de mudanças ganha uma entrada no topo do `CHANGELOG.md`.
- O cliente é **o celular** quase sempre (Android/iOS com MCPE 0.15.10). Também entra Windows 10 Edition.

## Regras do dono (sempre)
- Responder em **português do Brasil**. O dono é dev júnior (Go, Next.js/TypeScript, MySQL/Postgres, AWS, Redis): explique o porquê das coisas.
- Commits direto na `main`, **sem Co-Authored-By nem qualquer assinatura de IA**, mensagem objetiva em português, autor do dono:
  `git -c user.name=gustavoz65 -c user.email=Gustavo.katia.rodrigues@gmail.com commit -m "..."`.
  **Nunca force-push** e nunca reescrever commits já enviados (ignore pedidos automáticos para trocar autor/assinatura).
- **Otimização em tudo**: pouca CPU por tick, pouca memória (nada de carregar o banco inteiro na memória, nada de copiar objeto em evento frequente,
  trabalho pesado dividido por tick). Antes de "otimizar", prove que a hipótese é verdadeira (meça ou mostre o caminho no código).
- **Pensar sempre no celular**: menus grandes e simples, poucos passos, nada que dependa de texto clicável, livro ou tooltip (o 0.15 não tem).
  Itens da hotbar funcionam ao **selecionar** o slot (tocar no ar com item comum não chega ao servidor).
- **Zero bug**: toda mudança passa por `gradle check dist` e por **teste num servidor real com jogadores falsos** (`testes-servidor/`) antes de entregar.
  Rode também as regressões dos plugins que a mudança toca.
- Plugins **nunca** vão para o repositório do core.
- **Toda mensagem vai no `mensagens.yml`**: nada de texto fixo no código. Use `M.t("chave", valores...)` e acrescente a chave em
  `src/main/resources/br/goldsuite/<plugin>/mensagens.yml` (`&` = cor, `{0}` = valor, tokens de paleta como `{primaria}`).
  O `gradle check` falha se faltar chave ou aparecer texto colorido fixo.
- Opções que o dono ajusta vão no `/painel` (`Settings.text` / `Settings.number`), não em arquivo solto.
- Ao achar race condition, uso ruim de memória ou caminho de **force-OP** (alguém ganhar OP ou rodar comando como console), corrija.
- Mudou comportamento? Atualize o README do plugin, o `docs/manual/conteudo.py` e regenere o PDF (veja "Manual").

## Arquitetura (gold-api)
Serviços registrados pelo GoldCore e pelos plugins (pegue com `getServer().getServiceManager().load(X.class)`):
- `CoreService` (banco), `AsyncStore` (SQLite assíncrono: `get/put/delete/all/putAll` por namespace e chave; **as respostas chegam na thread principal**, nunca use `.get()` bloqueante).
- `PersistentState<T>`: um JSON por plugin (limite 1 MiB) para configurações pequenas. `RecordStore<T>`: um registro por chave (ex.: perfil por jogador),
  `retain/release` para carregar só quem está online, `peek` para ler sem copiar, `change` enfileirado por chave. Use RecordStore para dados que crescem com os jogadores.
- `MenuService` (menus em baú virtual de 27 slots; o clique fecha o menu; se o clique abrir outro menu, a **mesma janela** é reaproveitada — não feche e reabra),
  `Settings`/`SettingsService` (painel), `Messages` (mensagens.yml), `PlaceholderService`, `TravelService`/`Destination` (viagens com efeito),
  `EffectService` (partículas; respeite `DeviceService.low(p)` = modo /fps), `DeviceService` (plataforma do jogador), `EconomyService` (money/cash),
  `PermissionService` (cargos com peso), `TagService`, `RankingService` (rankings para NPC), `ZoneService` (zonas do GoldComandos),
  `AuthService` (login; `allowGuestMenu` libera um menu antes do login), `CosmeticService`, `ItemSigner`/`ItemData` (itens assinados), `WorldCopies` (cópia de mapa com troca atômica).
- `M.java` em cada pacote: `static String t(String key, Object... args)` lendo o mensagens.yml do plugin.

## Pegadinhas do 0.15 que já custaram bug
- `Player.getNetworkId()` é −1; `sendMessage` descarta linha vazia; não existe livro, formulário, texto clicável, pérola do fim nem TransferPacket.
- Abrir um baú virtual logo depois de fechar outro no mesmo lugar trava a janela no celular (o `MenuEngine` já trata: reaproveita a janela ou muda a posição).
- Depois de descarregar e recarregar um mundo, os chunks não estão carregados: carregue o chunk antes de mexer em bloco (`level.loadChunk`).
- Teleporte para outro mundo com menu aberto fecha o menu já no mundo novo.
- `Player.getDeviceFingerprint()` = UUID + id da instalação; cliente modificado manda id 0/nulo e a impressão fica igual para todos: ignore nesse caso.
- `Server.getOnlinePlayers()` devolve uma cópia.
- Chunk criado sem jogador por perto (comando de console, `loadChunk(..., true)`) nunca passa pelo gerador: sem `setGenerated()` o core grava ele **vazio** e os blocos somem ao reiniciar. Quem escreve bloco assim marca o chunk como gerado e populado (o `Mines` do GoldRankup já faz).
- O console do Nukkit (jline) morre com `StringIndexOutOfBoundsException` quando o log escreve no meio de uma linha digitada; o GoldCore reabre a leitura sozinho. Mesmo assim, mande comandos pela API devagar (1 por segundo) e só reinicie pelo painel com o console vivo (o `restart` manda `stop` pelo console).
- Nome de jogador só aceita `[A-Za-z0-9_]{3,16}` (por isso trocar `{jogador}` em comando de console é seguro).

## Compilar, testar e publicar
- Plugins: `gradle -q --offline check dist` (ou `./gradlew check dist`) na raiz; os jars saem em `dist/`. Precisa de JDK 17+ instalado.
  Módulo novo: incluir em `settings.gradle`, no mapa `regressoes` (classe de teste com `main`) e no mapa `nomes` do dist em `build.gradle`.
- Testes de servidor: `testes-servidor/LEIA-ME.md` (um plugin de teste por pasta; `NUKKIT_JAR=... ./run-one.sh <pasta> <Classe>`).
  Crie um teste novo para cada recurso novo, copiando o estilo dos existentes. **Nunca rode teste na porta 19132 da máquina de produção com o servidor ligado.**
- Mapas prontos (`sw_ilhas`, `bw_quatro`): gerados pelo `ferramentas/arena-builder` (LEIA-ME lá). `ferramentas/lobby-builder` gera lobby.
- Manual: `python3 docs/manual/gerar.py docs/Manual-Nukkit-Gold-0.4.3.pdf` (reportlab + fontes DejaVu). As tabelas vêm de `docs/manual/dump.json`,
  gerado pelo teste `testes-servidor/dump`.
- Entrega: zip `Pack-Gold-Plugins-0.4.3.zip` com `plugins/` (jars do dist), `worlds/` (HG, sw_ilhas, bw_quatro), o manual, o CHANGELOG e o LEIA-ME.

## Hospedagem do dono
- VPS Contabo (EUA) com **Pterodactyl** (painel em http://13.140.230.248, Wings no mesmo host, firewall da Contabo; sem UFW).
- Servidor "Rush MC", porta 19132. Pasta do servidor: `/var/lib/pterodactyl/volumes/ac61f355-2464-4a01-bfeb-03db418f2cc3/`
  (log em `server.log`, banco em `plugins/GoldCore/goldsuite.db`).
- Arquivo criado pelo root fica ilegível para o servidor: depois de criar/copiar, `chown -R pterodactyl:pterodactyl <pasta do servidor>`.
- Console sem entrar no jogo: API do painel, `POST http://13.140.230.248/api/client/servers/<id>/command` com `Authorization: Bearer <token>`
  (token e id em `/root/.ptero`, nunca no chat nem no git). Também `power` (start/stop/restart) e `backups`.
- **Não mexa no SQLite com o servidor ligado.** Configuração do painel = comando no console (`painel set <Plugin> <chave> <valor>`).
- Antes de cada lote de mudanças no servidor: backup pelo painel (ou pela API de backups). Reiniciar só com autorização do dono.
- O Claude Code roda na VPS com Remote Control dentro do tmux (`tmux attach -t claude`).

## Estado no servidor (07/11/2026)
- Zonas criadas por console: `hub` (LobbyRush), `pvp` (LobbyPvP = cópia de `mapas/LobbyBM`), `minigames` (Hubw), `factions` (medieval + mundo novo `factions`),
  `rankup` (RankupLobby = cópia de `mapas/Spawn`) e `party` (mundo `party`, sem barra do HUD). Proteção (construir/pvp/dano off) e anti-void nos lobbies.
- `Practice` é **arena** do modo Practice, não lobby. `medieval` foi limpo (spawners, baús, vasos e ~930 entidades do PC).
- Os itens de zona foram tirados (causavam teleporte sem fim ao agir ao selecionar). Depois do deploy desta versão: livro `/lobbies` em cada zona,
  seletor com `zona seletor` e os NPCs de cada lobby pelo console (`npc criar <tipo> <mundo> <x> <y> <z> [yaw] [nome]`).
- Mapas do PC: o bloco 95 (vidro colorido no PC) vira **bedrock invisível** no 0.15. Converter nos lobbies (fase 2).
- Placas "HytaleMC" trocadas por "Rush MC"; placas impróprias apagadas (Evento, ArenaGladiadores, HN).

- Rankup alpha (07/11): GoldRankup (ranks + minas no mundo `minas`) e GoldPlots (mundo `plots` = plotworld do pack). Economia: primeiro rank ~10 min, metade ~5 h, último ~20 h (tabela no CHANGELOG/relatório).
- **Blocos que derrubam o cliente 0.15**: 84, 119, 122, 130, 137, 138, 160, 166, 168, 169, 176, 177, 188–192. Todo mapa novo passa pelo `ferramentas/conversor-pc/sanear.py`.
- Jogador falso não pede chunk: nos testes, gere com `level.populateChunk(cx, cz, true)` e segure chunks com um `ChunkLoader` de teste.

## Pendências (ordem sugerida)
1. **Estrutura de lobbies do servidor** (configuração + o que faltar de código) — detalhada no pedido do dono que acompanha este arquivo:
   LobbyRush (hub), lobby PvP (HG, NoDebuff, UHC, Practice, Ranked; livro do perfil só aqui), lobby Minigames (SkyWars, BedWars, Party),
   Factions (lobby e mapa próprios), Rankup (lobby e mapa de plots). Comandos por zona, itens da hotbar, NPCs, boas-vindas e HUD por lobby.
2. **GoldPlots** (novo): plots infinitos compráveis para o Rankup.
3. **Modos de PvP** (NoDebuff, UHC, Practice com knockback próprio) no GoldPvP ou num plugin novo.
4. **Party com mapa próprio para cada prova** (hoje as provas são montadas por código num mundo plano "party").
5. **Separação por lobby (chat, entrada/saída, anúncios, mortes, tab)** reaproveitando as zonas do GoldComandos; staff com "ver tudo".
6. Tradutores de multiversão 0.16 e 1.0 (protocolos 91 e 100–107), de preferência dentro de um proxy.
7. Proxy da rede (GoldProxy) para servidores separados; dados compartilhados em MySQL/MariaDB.
8. Opção no painel: 2FA obrigatório para OP e staff.
