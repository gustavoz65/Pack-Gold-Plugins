# Contexto para continuar o desenvolvimento (novo chat)

Leia este arquivo inteiro antes de mexer em qualquer coisa. Ele resume o que existe, as regras do dono do projeto e o que está pendente.

## O projeto
- **Nukkit-Gold-Edition** (github.com/gustavoz65/Nukkit-Gold-Edition): o core do servidor (Nukkit para **MCPE 0.15.10, protocolo 84**). Notas de versão em `builds/VERSAO.md`, o jar em `builds/nukkit-gold-0.15.10.jar`, instaladores em `scripts/` e hospedagem (nftables e egg do Pterodactyl) em `hospedagem/`.
- **Pack-Gold-Plugins** (github.com/gustavoz65/Pack-Gold-Plugins): os **31 plugins Gold** (Gradle multi-módulo, Java 8). `gold-api` vai embutido no `GoldCore`; os outros só compilam contra ele.
- Versão atual: **0.4.3** (o dono quer manter 0.4.3 até dizer o contrário). Histórico em `CHANGELOG.md`. Manual em `docs/Manual-Nukkit-Gold-0.4.3.pdf`, gerado por `docs/manual/gerar.py`.

## Regras do dono (sempre)
- Responder em **português do Brasil**. O dono é dev júnior (Go, Next.js/TypeScript, MySQL/Postgres, AWS, Redis).
- Commits direto na `main`, **sem Co-Authored-By nem assinatura do Claude**, mensagem objetiva:
  `git -c user.name=gustavoz65 -c user.email=Gustavo.katia.rodrigues@gmail.com commit -m "..."`. Nunca force-push.
- **Otimização em tudo** (pouca operação pesada, pouca memória) e **pensar sempre no celular** (UX mobile).
- **Zero bug**: testar num servidor real com jogadores simulados antes de entregar.
- Plugins **nunca** vão para o repositório do core.
- **Toda mensagem vai no `mensagens.yml`**: nada de texto fixo no código. Use `M.t("chave", valores...)` e acrescente a chave em `src/main/resources/br/goldsuite/<plugin>/mensagens.yml`. O `gradle check` falha se faltar chave ou se aparecer texto colorido fixo.
- Opções que o dono ajusta vão no `/painel` (`Settings.text` / `Settings.number`).

## Como compilar e testar
- Plugins: `timeout 900 gradle -q --offline check dist` na raiz do Pack-Gold-Plugins (os jars saem em `dist/`).
- Core: `bash scripts/build.sh` no Nukkit-Gold-Edition.
- Testes de servidor real: plugin de teste com jogadores falsos (SourceInterface proxy), como em `teste-cos/*/src/teste/*.java` do ambiente antigo:
  - para o jogador aparecer como online, entra por reflexão em `Server.playerList`;
  - o chat é digitado com `handleDataPacket(TextPacket)`;
  - os menus são clicados pelo campo `open` do `MenuEngine`.
- Pegadinhas do 0.15 que já custaram bug:
  - `Player.getNetworkId()` é −1;
  - `sendMessage` descarta linha vazia;
  - tocar no ar com item comum não chega ao servidor (use o truque de selecionar o item na hotbar);
  - abrir um menu logo depois de outro fechar exige espera (o `MenuEngine` já trata isso).

## Hospedagem do dono
- VPS Contabo (EUA) com **Pterodactyl** (painel em http://IP, Wings no mesmo host, firewall da Contabo; sem UFW).
- Servidor "Rush MC" na porta 19132. Egg em `hospedagem/egg-nukkit-gold.json`.

## Pendências (em ordem sugerida)
1. **Plugin de separação por lobby (novo, "trabalha em silêncio")**. Pedido do dono, com as palavras dele resumidas:
   - quem está no lobby de factions **não vê o chat** de quem está no lobby de SkyWars, e isso vale para todos os lobbies;
   - a mesma separação vale para a hotbar, o perfil e qualquer coisa que um lobby mostra;
   - o ecossistema tem que servir tanto para **um servidor só com tudo** quanto para **servidores separados**, e a UX é sempre pensada no celular.

   Sugestão de desenho:
   - reutilizar as **zonas do GoldComandos** (`ZoneService`) como "canais";
   - chat, entrada/saída, anúncios e mortes só para quem está na mesma zona (ou num grupo de zonas ligado);
   - lista de jogadores (tab) opcional por zona;
   - staff com um modo "ver tudo";
   - tudo configurável por comando e painel, sem nada para o jogador fazer.
2. **Tradutores de multiversão 0.16 e 1.0** (protocolos 91 e 100–107). Referências de código em `ref/` do ambiente antigo: as versões do Nukkit para esses protocolos. O ideal é ficar dentro do proxy (item 3), para todos os servidores aceitarem as versões novas.
3. **Proxy da rede (GoldProxy)** para ligar minigames e PvP:
   - o 0.15 não tem `TransferPacket`, então o proxy mantém a conexão e troca o servidor por trás (limpa entidades e chunks, faz a troca de dimensão e reescreve o ID do jogador nos pacotes);
   - os dados compartilhados (contas, dinheiro, cash, cosméticos, cargos, keys) saem do SQLite local para MySQL/MariaDB, mantendo o SQLite para quem roda um servidor só.
4. Opção no painel para **2FA obrigatório para OP e staff**: quem não ativou entra, mas fica sem os poderes até ativar.
5. Script/rotina de backup diário (no Pterodactyl já dá com Schedules > Create Backup).
