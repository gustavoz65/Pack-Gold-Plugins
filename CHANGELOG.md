# Changelog do Pack Gold Plugins

Todas as mudanças dos plugins Gold para o Nukkit Gold (MCPE 0.15.10), da mais nova para a mais antiga.
Cada versão traz o que é **novo**, o que foi **melhorado** e o que foi **corrigido**, separado por plugin.
As notas do core (o servidor em si) ficam em `builds/VERSAO.md` do repositório Nukkit-Gold-Edition.

---

## 0.4.3 — atualização de 04/11/2026

### Novo
- **GoldBuild**: varinha de machado de madeira. Quebrar um bloco (segurar, no celular) marca o ponto 1 e tocar marca o ponto 2; o bloco não quebra. Só vale para quem tem `goldbuild.admin`; o machado dos outros jogadores continua normal.
- **GoldBuild**: menu de ferramentas na hotbar (vara de blaze "Menu do WE"): preencher, paredes, trocar um bloco por outro, esfera com raio escolhido, limpar a área, desfazer e marcar os pontos onde você está. O bloco é escolhido **pelos blocos do seu inventário**, sem digitar id. `/we varinha` entrega a varinha e o menu.
- **GoldCore**: `/painel discord teste` e o botão de teste do menu agora dizem se o Discord **recebeu** a mensagem (com o tempo em ms) ou o motivo: webhook apagado (404), token inválido, limite do Discord, VPS sem internet de saída ou sem DNS.

### Corrigido
- **GoldAuth**: repetir `/2fa ativar` antes de confirmar gerava outra chave, e a chave já cadastrada no app passava a dar "código incorreto". Agora a chave é a mesma até confirmar. A chave aparece em blocos de 4 e sem negrito, com o aviso de que só existem letras A–Z e números 2–7.
- **GoldCosmetics**: comprar sem saldo fechava a confirmação sem abrir nada e, no cliente 0.15, a interface podia ficar sem hotbar. A confirmação já mostra "Saldo insuficiente (você tem X, custa Y)" e tocar volta para a categoria.
- **GoldCore (menus de todos os plugins)**: quando um menu fecha sem abrir outro, o servidor reenvia inventário, armadura e item da mão para o cliente redesenhar a hotbar.
- **Egg do Pterodactyl** (Nukkit-Gold-Edition): a instalação não depende mais do `apt` (o espelho do Debian respondia 404). O Java usa 85% da memória do servidor (`MaxRAMPercentage`), deixando folga para o Docker não matar o processo.

---

## 0.4.3 — atualização de 03/11/2026

### Novo
- **Todas as mensagens editáveis**: cerca de 1.800 mensagens dos 31 plugins em `plugins/<Plugin>/mensagens.yml`, com cores, paleta do painel e valores `{0}` `{1}`. `/painel msg` busca e troca no jogo e `/painel mensagens recarregar` relê os arquivos. Versões novas acrescentam as mensagens novas sem apagar as editadas. O build falha se algum texto colorido for escrito direto no código.
- **GoldChat**: `/chat formato <grupo> <formato>`, `/chat formatos`, `/chat entrada|saida <texto>` e `/chat anuncio lista|adicionar|remover|intervalo`.
- **GoldSkills** (novo plugin): 10 habilidades estilo mcMMO (mineração, lenhador, escavação, herbalismo, espadas, machado, arqueiro, desarmado, acrobacia, reparação), curva de XP do pack de rankup, anti-farm de blocos colocados, poderes opcionais, boosters, `/xprate`, anúncio de nível, `/mctop` e tag `{mctop}` do 1º colocado.
- **GoldComandos**: as zonas controlam os outros plugins. `/zona plugin` desliga todos os comandos de um plugin na zona (e some do `/help`), `/zona recurso` desliga partes (perfil do PvP, território das facções, XP e poderes das habilidades, barra do HUD) e `/zona hud` dá uma barra própria para a zona.
- **Pack de rankup 1.8 adaptado (fase 1)**:
  - **GoldHud**: barra estilo scoreboard com placeholders.
  - **GoldEconomy**: Banco Central (`/banco`).
  - **GoldCrates**: caixas misteriosas assinadas, com raridades e prêmios de comando.
  - **GoldEssentials**: `/config`, `/luz`, `/setloja`, `/loja` e `/mundovip`.
  - **GoldChat**: `/aviso` e opção de esconder entrada e saída.
  - **GoldProtect**: anti-dupe de carrinho, barco e moldura.
- **Manual de configuração em PDF** (`docs/Manual-Nukkit-Gold-0.4.3.pdf`, também no zip).

### Melhorado
- **GoldEconomy**: uma linha por conta no banco de dados, sem o antigo limite de 10 mil contas; só as contas alteradas são gravadas, em lote.

### Corrigido
- **GoldSkills**: espadas e machado não davam XP em PvP (o jogador tem networkId −1 no 0.15).
- Descrições de comandos com vírgula saíam cortadas no `/help` (ex.: `/config`).
- **GoldBreaker** e **GoldBuild** declaram dependência do GoldCore (quebrariam ao carregar com as mensagens novas).

---

## 0.4.3 — 31/10 a 01/11/2026

### Novo
- **GoldStaff**: `/suporte` com triagem pela IA da Groq (resumo, categoria e prioridade), atendimento isolado entre jogador e staff, `/finalizar`, `/avaliar` e `/tickets metricas` por membro da equipe.
- **GoldPerms**: cargos hierárquicos (dono, gerente, admin, moderador, ajudante) com peso e tag; `/cargo` só para quem está abaixo, `/cargos` e `/equipe`. Ninguém pune cargo igual ou superior.
- **GoldComandos** (novo plugin): comandos liberados ou bloqueados por zona de mundos, `/help` filtrado, boas-vindas na primeira visita, itens da hotbar que executam comandos e OP só pelo console ou de IP autorizado (`opseguro`).
- **GoldParty** (novo plugin): 5 provas sorteadas entre 10 por partida, pontos por colocação, ranking final e salas simultâneas com fila de blocos por tick.
- **GoldDevice**: Windows 10 Edition reconhecido pela conta Xbox e pela mira dos cliques.

### Melhorado
- **GoldStaff**: triagem da Groq com prompt enxuto e modelo pequeno (gasta menos tokens); chave da API mascarada no painel.

---

## 0.4.2 — 31/10/2026

### Novo
- **GoldSkyWars**: SkyWars estilo Hypixel, refeito a partir do plugin de luizgeh, com 19 kits, baús com garantia e refil e modo insano. Foram corrigidas as falhas do original: criar arena sem permissão, nome de arena virando caminho de arquivo, espectador voando no lobby e inventário apagado.
- **GoldBedWars**: até 8 times, forja, diamante e esmeralda, loja em 7 categorias, melhorias do time, armadilha, bola de fogo e ovo-ponte.
- Mapas prontos `sw_ilhas` e `bw_quatro` com os comandos em `arenas-comandos.txt`.

---

## 0.4.0 — 31/10/2026

### Novo
- **GoldDevice**: plataforma do jogador, modo leve `/fps` e limite de CPS por plataforma.
- **GoldAuth**: verificação em duas etapas (TOTP) e aparelhos confiáveis.
- **GoldKeys**: keys de liberação com pedido, pagamento por Pix confirmado pela staff, validade e resgate único.
- **GoldCosmetics**: a Nuvem Voadora voa; os efeitos respeitam o modo leve.

---

## 0.3.2 — 30 e 31/10/2026

### Corrigido
- **GoldCosmetics**: modelos 3D não caem mais, chaveiro no ombro direito, menu mais claro (usar e tirar), HUD não some após a compra; metadados dos blocos no formato do 0.15.

---

## 0.3.1 — 30/10/2026

### Novo
- **GoldCore**: painel de configuração `/painel` (paleta de cores, prefixos, mensagens, preços) e alertas no Discord.
- **GoldCosmetics**: Nuvem Voadora 3D (paga em cash) e pingentes-chaveiro 3D; preços e mensagens no painel.

### Corrigido
- **GoldNPC**: NPC de criatura não flutua mais ao olhar para o jogador.

---

## 0.3.0 — 30/10/2026

### Novo
- **GoldPvP**: perfis, rankings, ranked 1v1 com elos estilo Valorant, anti-fraude, lista de elos, elo no chat e livro do perfil na hotbar.
- **GoldCosmetics** (novo plugin): pingentes, trilhas, efeitos de abate e cor do nome.
- **GoldNPC**: NPCs de ranking com a skin do top 3.
- **GoldFactions**: poder com pesos, spawners, invasão de borda e mini mapa no mundo.
- **GoldHG**: início estilo PC (5 jogadores, 50 s, entrada até o PvP) e `/hg regras`.

---

## 0.2.0 — 30/10/2026

### Novo
- Todos os módulos do roadmap:
  - GoldCore (SQLite assíncrono, assinatura de itens, menus, viagens e efeitos);
  - GoldAuth, GoldPerms, GoldEconomy, GoldNPC, GoldHG;
  - GoldEssentials, GoldChat, GoldProtect, GoldStaff;
  - GoldKits, GoldVip, GoldShop, GoldVault, GoldHud, GoldPvP;
  - GoldCrates, GoldMarket, GoldFactions, GoldEnchant, GoldBreaker, GoldWorld, GoldBuild.
