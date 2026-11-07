# Changelog do Pack Gold Plugins

Todas as mudanças dos plugins Gold para o Nukkit Gold (MCPE 0.15.10), da mais nova para a mais antiga.
Cada versão traz o que é **novo**, o que foi **melhorado** e o que foi **corrigido**, separado por plugin.
As notas do core (o servidor em si) ficam em `builds/VERSAO.md` do repositório Nukkit-Gold-Edition.

---

## 0.4.3 — hotbar por toque, seletor de lobbies e NPC pelo console (07/11/2026)

### Novo
- **GoldComandos**: seletor de lobbies. `/lobbies` (também `/jogos`) abre um menu único com um ícone por lobby. Ele é montado com `/zona seletor <posição> <id> <nome> <comando>`, aceita o contador `{mundo:Nome}` e funciona em qualquer zona. Na hotbar fica só o livro "Lobbies".
- **GoldNPC**: NPCs pelo console, sem ninguém no jogo.
  - `npc criar <tipo> <mundo> <x> <y> <z> [yaw] [nome]`;
  - `npc mover <mundo> <x> <y> <z> [yaw]` e `npc destino posicao ...`;
  - o console tem a sua própria seleção (`npc selecionar <id>`) e pode usar nome, comando, transição, ranking, skin de jogador online, lista e remover.
- **GoldEssentials**: `setwarp <nome> <mundo> <x> <y> <z> [yaw]` e `delwarp` pelo console; `warp` no console lista com as coordenadas.
- **gold-api**: `Destination.parse` (lê `<mundo> <x> <y> <z> [yaw]`) e `HotbarAction` (quando um item de menu da hotbar age).
- Testes de servidor novos: `nc/TesteNpcConsole` e `hb/TesteHotbar`.

### Melhorado
- **Itens de menu da hotbar** (GoldComandos, GoldPvP, GoldCrates, GoldBuild, GoldSkyWars e GoldBedWars):
  - agora agem quando o jogador **toca no chão** com o item. Só passar o dedo pela hotbar não dispara mais nada;
  - o segundo evento do mesmo toque (até 500 ms) é ignorado;
  - `painel set GoldCore hotbar.ao_selecionar 1` volta ao jeito antigo (padrão 0);
  - exceção: o espectador do SkyWars e do BedWars voa sem chão para tocar, então "Jogar de novo" e "Sair" continuam agindo ao selecionar.
  - A sopa ao selecionar do GoldHG (`/sopa`) continua como escolha de cada jogador, desligada por padrão.
- **GoldComandos**: `zona ver` mostra os slots de 1 a 9, como se digita no `zona item`.
- **GoldProtect**: duas opções novas no `mundoprotecao`, pensadas para os lobbies:
  - `fome off`: a fome não cai, e quem chega com fome fica com a barra cheia;
  - `movimento off`: o servidor não puxa mais o jogador de volta na beira dos blocos ("moveu erradamente"). Nas arenas a checagem continua.
- **Testes de servidor**: o `run-one.sh` sobe o servidor de teste na porta 19140 (`PORT=...`), nunca na 19132 da produção.

### Corrigido
- **GoldComandos**: teleporte sem fim entre lobbies. O item do slot já selecionado agia ao chegar no lobby e mandava para outro, e lá acontecia o mesmo. Agora o item só age ao tocar no chão.
- **GoldComandos**: os itens da zona não voltavam depois de morrer (renascer no mesmo mundo não trocava de zona) e caíam no chão junto com o drop da morte. Agora voltam ao renascer, ao entrar no servidor e depois do login, e nunca caem.
- **GoldProtect**: `mundoprotecao <mundo> dano off` respondia "bloqueado", mas só valia dentro de áreas. O mundo continuava tomando dano de queda, fogo e afogamento.
- **GoldNPC**: num roteiro de console, `npc criar` seguido de `npc selecionar <id>` podia acabar com o NPC novo selecionado, porque a criação grava de forma assíncrona e selecionava depois. Um `npc remover` em seguida apagava o NPC errado. Agora a criação só seleciona se ninguém selecionou outro no meio tempo.
- **Testes**: `TesteComandos` espera a gravação assíncrona do `opseguro` antes do próximo login (falhava em máquina com disco mais lento).

## 0.4.3 — correção dos menus no celular (04/11/2026)

### Corrigido
- **GoldCore (todas as janelas)**: no celular, trocar de tela (próxima página do `/hgkits`, voltar, abrir um submenu) podia deixar a janela vazia, sem abrir ou travada.
  - Agora, quando um botão abre outra tela, os itens trocam dentro da mesma janela, sem fechar e reabrir o baú.
  - Quando a tela nova só abre depois (por exemplo, após gravar no banco), o baú novo é criado em outra posição, para não brigar com o anterior.
  - Limitação do 0.15: o título da janela continua o da primeira tela. Por isso, o número da página foi para as setas.
- **GoldStaff (boas-vindas)**:
  - abre 3 s depois de entrar, quando o mapa já carregou no celular;
  - tocar no livro da página não fecha mais o menu.
- **GoldHG e GoldMarket**: o número da página aparece nas setas ("Próxima página (2/3)"), e o título fica fixo.

---

## 0.4.3 — segunda atualização de 04/11/2026

### Novo
- **GoldSkyWars** no estilo MythicSkyWars:
  - nível, XP e moedas próprias do SkyWars (abate e vitória), com placeholders `{sw_nivel}`, `{sw_moedas}`, `{sw_almas}` e `{sw_prestigio}`;
  - **Poço das Almas** (`/sw almas`): cada abate dá 1 alma; o giro sorteia moedas, XP, kits, vantagens ou jaulas, e prêmio repetido vira moedas;
  - **14 vantagens** com 4 níveis (`/sw vantagens`); no nível máximo, tocar liga e desliga;
  - **jaulas** de vidro, ferro, folhas, gelo e ouro (`/sw jaulas`) e **prestígio** por nível (`/sw prestigio`);
  - **votação da partida** pelo papel "Votar" da hotbar: baús, hora, clima, vida dupla e modificador;
  - **edição de mapa** com `/swmap`: jaula, espectador, tipo de baú (tocando no baú), jogadores por jaula, mínimo, nome, autor, ativar e salvar;
  - **minérios por ilha**: `/sw ilhas` entrega o graveto, um toque marca cada ilha e `/gerar minerios` coloca 4 diamantes, 5 ferros e 6 carvões escondidos em cada uma, e salva o mapa. A leitura das ilhas é feita aos poucos, sem travar o servidor.
- **GoldStaff**: punições com escala.
  - 3 advertências dão ban de 3 dias e a 4ª dá ban permanente (tudo ajustável no painel).
  - O ban usa conta + aparelho; o permanente também bloqueia o IP (o 0.15 não envia MAC).
  - Ban de quem está offline pega o aparelho e o IP dos últimos logins.
  - O desban só acontece com `/desbanir` depois da contestação no Discord, cujo link aparece na tela de bloqueio.
- **GoldStaff**: boas-vindas ao entrar, antes do login, com páginas (IP, regras e suporte) e botão de suporte. Editável no `config.yml`, recarrega com `/welcomebook reload`.
- **GoldChat**: enquetes com anúncio automático. `/enquete criar`, voto com `/entrar <enquete>` (menu ou número) e o resultado anunciado quando a enquete fecha.
- **GoldAuth**: antes do login o jogador anda livre pelo lobby. Qualquer toque, quebra, comando, NPC, item ou menu mostra "Entre na sua conta" (ou "Crie sua conta") com o comando certo. Fora do lobby ele fica parado, e quem cai no vazio volta ao spawn. O painel tem `andar_sem_login = 0` para o comportamento antigo.

### Melhorado
- **GoldSkyWars**: o perfil é lido sem cópia nos eventos de dano, na contagem e no HUD; só minérios colocados são guardados durante a partida.
- **GoldChat**: cada voto de enquete é uma linha própria. Votar não regrava todas as enquetes e não há limite de votos.
- **GoldStaff**: só quem foi punido fica na memória; os IPs e aparelhos de cada conta (em hash) são lidos do banco só na hora de punir.

### Corrigido
- **GoldCore (menus)**: um menu que teleportava para outro mundo, como entrar no SkyWars pelo menu, gerava erro ao fechar.
- **GoldSkyWars**:
  - depois do `/sw salvar` o mapa voltava sem os chunks das jaulas, e entrar na sala falhava no meio, deixando o jogador sem itens; agora a entrada é desfeita se algo falhar;
  - `/sw iniciar` dizia "começa em 5 s" mas mantinha a contagem que já estava rodando.
- **GoldStaff e GoldAuth**: com cliente sem o código da instalação (cliente modificado ou bot), a impressão do aparelho ficava igual para todos, e banir um expulsava todo mundo. Esse aparelho agora é ignorado no ban e nunca vira aparelho confiável do 2FA.
- **GoldComandos**: o OP suspenso voltava no join só pelo IP, antes do login. Agora volta só depois do login, de IP autorizado.
- **GoldStaff**: `/agendar` só pelo console ou por OP, porque um comando agendado roda como console. A checagem de hierarquia nega a ação quando dá erro.

---

## 0.4.3 — atualização de 04/11/2026

### Novo
- **GoldTags** (novo plugin, 32 no total): selecionar sua tag. Todo jogador tem a tag **Jogador** (padrão de quem entra), a do cargo enquanto tiver o cargo e as tags de evento que ganhou. `/tag` lista e abre um menu; `/tag <nome>` troca; `/tag automatica` volta para a do cargo. A staff cria e entrega tags de evento com prazo (`/tag criar`, `/tag dar`). Trocar de tag não mexe no cargo nem nas permissões.
- **GoldPerms**: o `{tag}` passa a mostrar a tag escolhida no GoldTags, e quem não tem tag de cargo aparece com `[Jogador]`.
- **GoldChat**: os formatos padrão usam `{tag}` (`{mctop}{tag} &7{nome}&8: &f{mensagem}`); sem tag, o espaço some.
- **GoldChat**: filtro de palavrões. Troca a palavra inteira por `*****` no chat e no `/tell`. Pega abreviações (fdp, pqp, vsf, tnc, vtnc, krl, pnc, mcc...), frases ("pau no cu", "vai tomar no cu"), acento, letra repetida, número no lugar de letra, letras separadas ("f d p") e frase colada. Palavras normais como "computador" e "cuidado" passam. `/chat palavra adicionar|remover|lista|testar`; liga e desliga no painel; permissão `goldchat.semfiltro` para quem pode falar sem filtro.
- **GoldWorld**: `/mundo lista` mostra os mundos carregados (com quantos jogadores) e os mapas da pasta `worlds/` que ainda não foram abertos, para entrar com `/mundo tp <nome>` sem precisar saber o nome.
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
