# -*- coding: utf-8 -*-
"""Texto do manual. Cada guia: resumo, passos (lista numerada), secoes [(titulo, [itens])] e dicas.
Itens que comecam com '$ ' viram bloco de comando; '- ' vira marcador; o resto vira paragrafo."""

ORDEM = [
    ("Base", ["GoldCore", "GoldAuth", "GoldPerms", "GoldEconomy", "GoldComandos"]),
    ("Lobby e jogadores", ["GoldEssentials", "GoldChat", "GoldHud", "GoldNPC", "GoldProtect", "GoldWorld", "GoldDevice", "GoldCosmetics"]),
    ("Equipe e suporte", ["GoldStaff"]),
    ("Economia, loja e VIP", ["GoldShop", "GoldMarket", "GoldKits", "GoldVault", "GoldVip", "GoldKeys", "GoldCrates", "GoldEnchant", "GoldBreaker"]),
    ("Modos de jogo", ["GoldPvP", "GoldFactions", "GoldSkills", "GoldHG", "GoldSkyWars", "GoldBedWars", "GoldParty"]),
    ("Ferramentas", ["GoldBuild"]),
]

GUIAS = {
"GoldCore": dict(
    resumo="Base de todos os plugins: banco SQLite assíncrono, assinatura de itens, menus, painel de configuração (/painel), placeholders, viagens com efeito e o sistema de mensagens. Precisa estar instalado; todos os outros dependem dele.",
    passos=[
        "Entre no servidor como OP e digite /painel. O menu mostra o Servidor (paleta de cores e alertas) e cada plugin.",
        "Em Servidor, defina a paleta: cor.primaria, cor.secundaria, cor.destaque, cor.sucesso, cor.erro e cor.texto. Tudo que usa {primaria}, {erro}... muda junto.",
        "Ligue o Discord: /painel discord <url do webhook> e teste com /painel discord teste. Alertas da staff e das keys vão para o canal.",
        "Revise os prefixos de cada plugin (chave prefixo) para combinar com o nome do servidor.",
        "Para trocar qualquer mensagem: /painel msg (veja o capítulo Mensagens).",
    ],
    secoes=[
        ("Pelo console", [
            "$ painel ver GoldHud",
            "$ painel set GoldHud linha1 &a$ {money} &8| &bCash &f{cash}",
            "$ painel set GoldHud linha1 padrao",
            "$ painel discord off",
            "- No jogo, toque numa opção do menu e digite o valor no chat: padrao restaura, cancelar sai.",
        ]),
    ],
    dicas=["Chaves de API (ex.: groq_chave) aparecem mascaradas no painel.", "As permissões goldcore.painel e goldcore.alertas são só de OP por padrão; dê goldcore.alertas aos cargos da staff que devem receber avisos."],
),
"GoldAuth": dict(
    resumo="Registro e login obrigatórios (inclusive para OP), com senha forte (PBKDF2), limite de tentativas por IP e verificação em duas etapas (2FA) com aparelhos confiáveis.",
    passos=[
        "Não há nada para configurar: ao instalar, todo jogador precisa usar /registrar <senha> <confirmacao> na primeira vez e /login <senha> nas próximas.",
        "Antes do login o jogador não anda, não fala, não usa comandos e não mexe em itens. Ele tem 120 s para entrar; 5 senhas erradas derrubam a conexão.",
        "Recomende o 2FA para a equipe: /2fa ativar, cadastre a chave no Google Authenticator, Authy ou Microsoft Authenticator e confirme com /2fa confirmar <codigo>.",
        "Se alguém perder o celular: /2fa reset <jogador> (staff). Se esquecer a senha: /authreset <jogador> apaga o registro para ele se registrar de novo.",
    ],
    secoes=[("2FA no dia a dia", [
        "- No aparelho confiável basta o /login. Num aparelho novo, depois da senha o servidor pede o código de 6 números (/2fa <codigo> ou digitado no chat).",
        "- /2fa aparelhos mostra os aparelhos; /2fa aparelhos limpar deixa só o atual; /2fa desativar <codigo> desliga.",
    ])],
    dicas=["As senhas nunca aparecem no console nem nos logs.", "Coloque login, registrar e 2fa nos comandos sempre liberados do GoldComandos (já vêm assim)."],
),
"GoldPerms": dict(
    resumo="Grupos com herança, permissões, cargos hierárquicos da equipe (peso e tag) e VIP com prazo.",
    passos=[
        "Na primeira vez são criados os cargos ajudante (20), moderador (40), admin (60), gerente (80) e dono (100), cada um herdando o anterior, além do vip (10). Veja com /cargos.",
        "Coloque você como dono: /grupo jogador <seu_nick> dono (pelo console ou como OP).",
        "Monte a equipe com /cargo <jogador> <cargo> [dias]. Cada um só dá cargos abaixo do próprio e só mexe em quem está abaixo.",
        "Ajuste tags e descrições: /grupo tag <grupo> <tag>, /grupo descricao <grupo> <texto>, /grupo peso <grupo> <0-1000>, /grupo staff <grupo> on|off.",
        "Dê permissões: /grupo permissao <grupo> <permissao> true|false|remover. Exemplo para o VIP: goldessentials.fly, goldessentials.luz, goldessentials.setloja, goldessentials.mundovip, goldessentials.homes.vip.",
    ],
    secoes=[("Exemplo de VIP", [
        "$ /grupo criar vip",
        "$ /grupo herdar vip padrao",
        "$ /grupo permissao vip goldessentials.fly true",
        "$ /grupo permissao vip goldessentials.homes.vip true",
        "$ /grupo tag vip &6[VIP]",
        "$ /grupo jogador Gustavo vip",
        "- /grupo jogador <nome> padrao remove o vínculo; /grupo padroes recria os cargos que você apagou.",
    ]), ("Regras da hierarquia", [
        "- Ninguém pune (/kick, /ban, /mute...) quem tem cargo igual ou acima do seu, nem um OP.",
        "- Comando bloqueado no /bloquearcmd pode ser liberado a um cargo: /grupo permissao moderador goldstaff.cmd.<comando> true.",
        "- Curingas (*) não são expandidos: use os nomes exatos das permissões (lista completa no apêndice).",
    ])],
    dicas=["A tag do cargo aparece no chat pelo {tag} dos formatos do GoldChat e na barra do GoldHud."],
),
"GoldEconomy": dict(
    resumo="Moedas (money), cash, transferências, ranking e o Banco Central (/banco). Valores em centavos inteiros: nada se perde em arredondamento.",
    passos=[
        "Não há saldo inicial automático. Para dar dinheiro: /economia adicionar money <jogador> <valor> (ou cash).",
        "Ajuste os botões do /banco no painel: GoldEconomy > banco.valores (até 5 valores separados por vírgula, em moedas).",
        "Para um NPC bancário: crie um NPC e use /npc comando banco.",
        "Cada cash vale 100 moedas no /trocarcash.",
    ],
    secoes=[("Administração", [
        "$ /economia adicionar money Gustavo 100.50",
        "$ /economia retirar cash Gustavo 2",
        "$ /economia definir money Gustavo 0",
        "- Placeholders: {money} (1.2k, 3.4M), {money_exato}, {cash}, {banco}.",
    ])],
    dicas=["Uma linha por conta no banco de dados: não há limite de contas.", "Os preços dos outros plugins no painel estão em centavos (10000 = $100,00), salvo quando a descrição diz cash ou moedas."],
),
"GoldComandos": dict(
    resumo="Controle por mundo (como o command block por mundo): quais comandos funcionam em cada lugar, /help filtrado, boas-vindas e itens da hotbar por lobby, liga/desliga outros plugins e recursos por zona, barra própria por zona e OP protegido por IP.",
    passos=[
        "Crie uma zona (grupo de mundos com as mesmas regras): /zona criar <zona> e adicione mundos com /zona mundo <zona> <mundo>.",
        "Escolha o modo: /zona modo <zona> liberar (só os comandos da lista funcionam) ou bloquear (os da lista não funcionam).",
        "Monte a lista: /zona cmd <zona> <comando> [comando...] (alterna cada um).",
        "Boas-vindas e ajuda: /zona boasvindas <zona> <texto com {nome}> e /zona ajuda <zona> <linha> (aparece no topo do /help).",
        "Itens da hotbar que executam comandos: /zona item <zona> <slot 1-9> <id> <nome> <comando>.",
        "Desligue plugins e recursos que não fazem sentido ali: /zona plugin <zona> <Plugin> e /zona recurso <zona> <plugin.recurso> (lista em /zona recursos).",
        "Barra própria da zona: /zona hud <zona> <1|2> <texto|padrao|nenhuma>.",
        "Proteja o OP pelo console: opseguro add <nick> <ip>.",
    ],
    secoes=[("Exemplo: lobby de PvP", [
        "$ /zona criar pvp",
        "$ /zona mundo pvp lobby_pvp",
        "$ /zona modo pvp liberar",
        "$ /zona cmd pvp kit kits perfil ranked topkills",
        "$ /zona boasvindas pvp &6Bem-vindo ao &cLobby PvP&6, {nome}!",
        "$ /ranked lobby lobby_pvp",
    ]), ("Exemplo: lobby do Rankup sem PvP, facções e SkyWars", [
        "$ /zona criar rankup",
        "$ /zona mundo rankup lobby_rankup",
        "$ /zona plugin rankup GoldFactions",
        "$ /zona plugin rankup GoldSkyWars",
        "$ /zona recurso rankup goldpvp.perfil",
        "$ /zona recurso rankup goldskills.xp",
        "$ /zona hud rankup 1 &a&lRANKUP &7| &fMoney: &a{money}",
    ]), ("Recursos por zona", [
        "- goldpvp.perfil: livro do perfil do PvP/ranked na hotbar.",
        "- goldfactions.territorio: aviso de território e mini mapa das facções.",
        "- goldskills.xp, goldskills.combate, goldskills.poderes, goldskills.reparacao: partes do GoldSkills.",
        "- goldhud.barra: barra de informações acima da hotbar.",
    ]), ("OP protegido", [
        "- /op e /deop pelo jogo só funcionam para quem está na lista e do IP cadastrado; pelo console sempre funcionam.",
        "- Com a lista preenchida, um OP que entra de outro IP perde o OP na hora (aviso para a staff e no Discord) e recebe de volta ao entrar do IP certo.",
        "$ opseguro add Gustavo 200.150.10.20",
        "$ opseguro remover Gustavo",
        "$ opseguro exigir off",
    ])],
    dicas=["Sempre liberados em qualquer zona: login, registrar, 2fa, suporte, finalizar, avaliar, ajuda, help, spawn, lobby, tell, msg, r, equipe, cargos e report (/zona sempre <comando> alterna).",
           "Staff com goldcomandos.bypass usa tudo. Para liberar um comando a um cargo: /grupo permissao <cargo> goldcomandos.cmd.<comando> true.",
           "Outros: /zona lista, /zona ver <zona>, /zona apagar <zona>, /zona itemremover <zona> <slot>, /zona boasvindas_sempre <zona> on."],
),
"GoldEssentials": dict(
    resumo="Comandos do dia a dia: spawn, homes, warps, tpa, back, fly, preferências (/config), lanterna (/luz), lojas de jogadores (/setloja e /loja) e mundo VIP.",
    passos=[
        "Vá ao ponto de nascimento do lobby e use /setspawn.",
        "Crie warps: /setwarp <nome> (apague com /delwarp). Jogadores usam /warp para ver e ir.",
        "Dê ao VIP as permissões dos extras pelo GoldPerms: goldessentials.fly, goldessentials.luz, goldessentials.setloja, goldessentials.mundovip e goldessentials.homes.vip (5 homes em vez de 2).",
        "Mundo VIP: vá ao local e use /mundovip definir. Quem não é VIP recebe a mensagem do painel (mundovip.sem_vip; | quebra a linha).",
        "Ajuste as esperas no painel: loja.espera e luz.espera.",
    ],
    secoes=[("Jogadores", [
        "- /sethome [nome], /home [nome], /delhome <nome>.",
        "- /tpa <jogador>, /tpahere <jogador>, /tpaccept, /tpdeny.",
        "- /config: cada um desliga quem pode mandar tell, tpa e coins para ele (a staff continua podendo).",
        "- /loja <jogador>: vai até a loja que o VIP marcou com /setloja.",
    ])],
    dicas=["goldessentials.instantaneo teleporta sem contagem (staff).", "/tpall traz todos até você (admin)."],
),
"GoldChat": dict(
    resumo="Formato do chat por grupo, tell, anti-spam, anti-link, mute, avisos e anúncios automáticos.",
    passos=[
        "Veja os formatos com /chat formatos e troque com /chat formato <grupo> <formato>. O formato precisa de {mensagem} e aceita {nome}, {tag}, {grupo}, {mctop} e os placeholders.",
        "Mensagens de entrada e saída: /chat entrada <texto> e /chat saida <texto> ({nome} vira o nick). Para esconder: painel GoldChat > entrada_saida = 0.",
        "Anúncios automáticos: /chat anuncio adicionar <texto>, /chat anuncio lista, /chat anuncio remover <n> e /chat anuncio intervalo <segundos>.",
        "Dê goldchat.cores e goldchat.links a quem pode usar cores e links no chat.",
    ],
    secoes=[("Exemplos", [
        "$ /chat formato padrao {mctop}&7{nome}&8: &f{mensagem}",
        "$ /chat formato vip {mctop}&6[VIP] &e{nome}&8: &f{mensagem}",
        "$ /chat entrada &a+ &f{nome} &7entrou",
        "$ /chat anuncio adicionar &eUse &f/kits &epara ver os kits!",
        "- Moderação: /mute <jogador> [minutos], /desmute, /chat off, /limparchat, /aviso <texto> (no chat e no meio da tela), /anuncio <texto>.",
    ])],
    dicas=["Cargos sem formato próprio usam 'tag nome: mensagem' com a tag do GoldPerms.", "Mais de 70% de maiúsculas viram minúsculas e links são bloqueados para quem não tem goldchat.links."],
),
"GoldHud": dict(
    resumo="Barra acima da hotbar no estilo scoreboard (o 0.15 não tem placar lateral), com duas linhas e placeholders.",
    passos=[
        "No painel: GoldHud > linha1 e linha2. Use nenhuma para esconder uma linha.",
        "Placeholders: {money} {money_exato} {cash} {banco} {grupo} {tag} {clan} {clan_tag} {kills} {deaths} {kdr} {online} {max} {x} {y} {z} {dir} {mundo} {ping} {armadura} e os do GoldSkills.",
        "Para esconder a barra num mundo: /hud mundo <mundo>. Para uma barra diferente por zona: /zona hud (GoldComandos).",
    ],
    secoes=[("Padrão", [
        "$ linha1: &a$ {money} &8| &bCash &f{cash} &8| {tag}",
        "$ linha2: &e{x} {y} {z} &7{dir} &8| &7Clan &f{clan} &8| &7KDR &f{kdr}{armadura}",
        "- Cada jogador liga ou desliga a própria barra com /hud. /id mostra o id do item na mão.",
    ])],
    dicas=["Os textos são compilados uma vez; a cada segundo só os placeholders usados são calculados."],
),
"GoldNPC": dict(
    resumo="NPCs estilo Slapper: humanos com skin ou criaturas, nome em várias linhas com contadores ao vivo, clique que leva a um mundo com transição ou executa comando, e pódio do ranking com a skin do top 3.",
    passos=[
        "Fique no lugar e crie: /npc criar humano &6HG|&f{mundo:hg} jogando (| quebra a linha, até 5 linhas). Para criaturas: /npc criar villager Loja.",
        "O NPC criado fica selecionado. Para outro: /npc selecionar e toque nele (ou /npc selecionar <id>).",
        "Defina o clique: /npc comando <comando> (roda como o jogador; {jogador} vira o nick) ou /npc destino mundo <mundo> com /npc transicao <efeito>.",
        "Ajustes: /npc nome, /npc skin [jogador], /npc item, /npc mover, /npc olhar sim|nao, /npc tipo <tipo>.",
    ],
    secoes=[("NPCs prontos para os outros plugins", [
        "$ /npc comando hg entrar",
        "$ /npc comando sw entrar solo",
        "$ /npc comando bw entrar solo",
        "$ /npc comando party entrar",
        "$ /npc comando banco",
        "$ /npc ranking ranked 1",
        "- Rankings: ranked, pvp, skywars, bedwars, party. Faça um pódio com três NPCs nas posições 1, 2 e 3.",
        "- Transições: instantanea, portal, espiral, escurecer, contagem, explosao.",
        "- /npc lista, /npc info, /npc tp [id], /npc remover.",
    ])],
    dicas=["O NPC é só pacote: não faz tick, não tem física e só é enviado a quem está a até 40 blocos.", "O comando roda com as permissões de quem clicou, nunca como console."],
),
"GoldProtect": dict(
    resumo="Áreas protegidas, proteção de mundos inteiros, anti-void, alerta de X-ray e anti-dupe.",
    passos=[
        "Proteja o lobby inteiro: /mundoprotecao <mundo> construir off, /mundoprotecao <mundo> pvp off, /mundoprotecao <mundo> dano off (off = bloqueado).",
        "Para um pedaço do mapa: /area pos1 e /area pos2 nos cantos, depois /area criar <nome>.",
        "Ajuste a área: /area flag <area> <construir|pvp|interagir|dano> <on|off>, /area membro <area> adicionar <jogador>, /area efeito <area> <id> [nivel], /area mensagem <area> <texto>.",
        "Lobbies sobre o vazio: /antivoid [mundo] salva quem cai.",
        "Anti-dupe no painel: antidupe.carrinho, antidupe.barco, antidupe.moldura_no_veiculo e antidupe.avisar_staff.",
    ],
    secoes=[("Consultas", ["- /area info, /area lista, /area remover <area>."])],
    dicas=["Quem tem goldprotect.admin ignora as proteções (para construir o lobby)."],
),
"GoldWorld": dict(
    resumo="Gerenciador de mundos (criar, carregar, teleportar), hora real, auto-inventário, mobs empilhados e /fundir.",
    passos=[
        "/mundo lista mostra os mundos; /mundo tp <mundo> leva até ele.",
        "/mundo criar <mundo> [normal|plano] cria um mundo; /mundo carregar e /mundo descarregar.",
        "/mundo horareal <mundo> segue a hora do Brasil; /mundo autoinv <mundo> manda os drops direto para o inventário; /mundo empilhar junta mobs iguais.",
        "Dê goldworld.fundir ao VIP para usar /fundir (funde os minérios do inventário).",
    ],
    secoes=[],
    dicas=["Mapas prontos (HG, sw_ilhas, bw_quatro) vão em worlds/ do servidor."],
),
"GoldDevice": dict(
    resumo="Detecta a plataforma (Windows 10 ou celular), limita os cliques por segundo de cada uma e oferece o modo leve /fps para celulares fracos.",
    passos=[
        "Limites de CPS no painel: cps.windows, cps.celular e cps.outros.",
        "plataforma.padrao define o que assumir quando não dá para saber (android ou windows).",
        "fps.sugerir_celular = 1 sugere o /fps a quem entra pelo celular.",
        "Para corrigir alguém: /plataforma definir <jogador> <windows|android|ios> (golddevice.admin).",
    ],
    secoes=[],
    dicas=["O 0.15 não informa o sistema: o Windows 10 é reconhecido pela conta Xbox e pela mira dos cliques (no PC todo clique sai do centro da tela)."],
),
"GoldCosmetics": dict(
    resumo="Cosméticos: pingentes 3D (chaveiro), trilhas, efeitos de abate, cor do nome e a montaria Nuvem Voadora. Parte é comprada e parte vem do ranked.",
    passos=[
        "Ajuste os preços no painel: preco.<id> (centavos para dinheiro; a nuvem é em cash).",
        "Configure as mensagens de saldo: msg.sem_saldo e msg.recarregar (como o jogador recarrega o cash).",
        "Jogadores abrem com /cosmeticos (ou pelo botão do perfil do PvP).",
        "Para dar um cosmético: /cosmeticos dar <jogador> <id> (lista de ids em /cosmeticos lista).",
    ],
    secoes=[],
    dicas=["Os modelos 3D aparecem só no lobby (mundo padrão), só para quem está perto e somem com TPS baixo."],
),
"GoldStaff": dict(
    resumo="Suporte com triagem por IA (Groq), atendimento isolado entre jogador e staff, métricas da equipe, denúncias, ajuda, bloqueio de comandos, restart, clearlag e comandos agendados.",
    passos=[
        "Crie uma chave em console.groq.com e cole no painel: GoldStaff > groq_chave (fica mascarada).",
        "Ajuste: groq_modelo (padrão openai/gpt-oss-20b, barato), triagem_perguntas (1 a 6), suporte_espera e suporte_prefixo.",
        "Dê goldstaff.suporte aos cargos que atendem (do ajudante para cima já têm) e goldstaff.metricas a gerentes e donos.",
        "Opcional: discord_tickets = 1 manda o resumo de cada atendimento para o Discord do painel.",
        "Edite o /ajuda e bloqueie comandos perigosos para jogadores com /bloquearcmd <comando>.",
    ],
    secoes=[("Como o atendimento funciona", [
        "- O jogador usa /suporte [mensagem]. A IA faz poucas perguntas e escreve um resumo com categoria e prioridade.",
        "- A equipe recebe o aviso e usa /atender <n>. Jogador e staff passam a ver só um ao outro, como uma party.",
        "- /finalizar [resultado] encerra; o jogador avalia com /avaliar 1 a 5.",
        "- Fila e histórico: /tickets, /tickets ver <n>, /tickets fechar <n> [motivo]. Métricas: /tickets metricas [dias].",
        "- Sem chave da Groq, o pedido vai direto para a equipe.",
    ]), ("Outras ferramentas", [
        "$ /restart auto 12",
        "$ /agendar 30 say Visite nossa loja!",
        "$ /bloquearcmd plugins",
        "- /report e /reports, /giveall <id> [qtd], /clearlag.",
    ])],
    dicas=["Para liberar um comando bloqueado a um cargo: /grupo permissao <cargo> goldstaff.cmd.<comando> true."],
),
"GoldShop": dict(
    resumo="Lojas por placa (comprar e vender do servidor), lojas de jogadores com baú e /vender pela tabela de preços.",
    passos=[
        "Placa de compra (staff): linha 1 [comprar], linha 2 id[:meta], linha 3 quantidade, linha 4 preço.",
        "Placa de venda (staff): o mesmo com [vender] na linha 1.",
        "Loja de jogador: placa encostada ou em cima de um baú com [loja] na linha 1; o dono abastece o baú.",
        "Tabela do /vender: /preco <id[:meta]> <valor por unidade> (ou remover).",
    ],
    secoes=[("Exemplo", [
        "$ [comprar]",
        "$ 364",
        "$ 16",
        "$ 50",
        "- Jogadores tocam na placa para comprar ou vender. /vender vende o item da mão; /vender tudo vende o inventário.",
    ])],
    dicas=["Use /id para descobrir o id do item na mão."],
),
"GoldMarket": dict(
    resumo="Leilão de itens entre jogadores (/leilao).",
    passos=[
        "Não precisa de configuração. Jogadores vendem com /leilao vender <preco> segurando o item.",
        "/leilao abre o mercado, /leilao meus mostra os anúncios, /leilao cancelar <id> retira e /leilao coletar pega itens e dinheiro pendentes.",
    ],
    secoes=[], dicas=["Staff com goldmarket.admin cancela anúncios de outros."],
),
"GoldKits": dict(
    resumo="Kits com recarga, restritos por permissão e menu /kits.",
    passos=[
        "Monte no seu inventário os itens do kit.",
        "Use /kit criar <nome> <minutos de recarga> [restrito].",
        "Kit restrito exige a permissão goldkits.kit.<nome>; dê pelo GoldPerms (ex.: ao VIP).",
        "Apague com /kit apagar <nome>.",
    ],
    secoes=[("Exemplo", ["$ /kit criar inicial 60", "$ /kit criar vip 1440 restrito", "$ /grupo permissao vip goldkits.kit.vip true"])],
    dicas=["Jogadores usam /kits (menu) ou /kit <nome>."],
),
"GoldVault": dict(
    resumo="Baú pessoal virtual (/ec).",
    passos=["Todos com goldvault.usar (padrão) abrem com /ec. A staff com goldvault.admin abre o de outros com /ec <jogador>."],
    secoes=[], dicas=[],
),
"GoldVip": dict(
    resumo="VIP por chaves com prazo: gera chaves, dá VIP direto e mostra o tempo restante.",
    passos=[
        "Crie o grupo VIP no GoldPerms (veja o GoldPerms).",
        "Gere chaves: /vip gerar <grupo> <dias> [quantidade]. Veja as geradas em /vip chaves.",
        "O jogador ativa com /vip ativar <chave> e confere com /vip tempo.",
        "Para dar direto: /vip dar <jogador> <grupo> <dias> (0 = permanente). Anuncie com /vip anunciar <grupo>.",
    ],
    secoes=[], dicas=["Para vender VIP por Pix com pedido e confirmação, use o GoldKeys."],
),
"GoldKeys": dict(
    resumo="Keys de liberação seguras: o jogador pede, paga pelo Pix, a staff confirma e ele resgata uma única vez cash, dinheiro, cosmético e/ou VIP.",
    passos=[
        "Crie produtos: /key produto criar <id> <preco em reais> <nome> (ex.: 19.90).",
        "Defina o que cada produto entrega: /key produto <id> cash <n> | dinheiro <valor> | cosmetico <id> | vip <grupo> <dias>.",
        "No painel: msg.pix (chave Pix e link do Discord), validade_horas, pedido_horas, pedidos_por_jogador.",
        "Ligue o Discord no /painel para receber cada pedido.",
        "Quando o pagamento chegar: /key pago <pedido>. Para cancelar: /key cancelar <pedido>. Pendentes: /key pedidos.",
    ],
    secoes=[("Exemplo", [
        "$ /key produto criar vip30 19.90 VIP 30 dias",
        "$ /key produto vip30 vip vip 30",
        "$ /key produto vip30 cash 500",
        "- Jogador: /key produtos, /key comprar vip30, paga e depois /key resgatar GOLD-XXXXX-...",
        "- Keys de presente: /key gerar <produto> [qtd] (o código aparece só para quem gerou).",
    ])],
    dicas=["O banco guarda só o hash do código: quem copiar o banco não resgata nada."],
),
"GoldCrates": dict(
    resumo="Caixas com chave (baú no mundo) e caixas misteriosas (item assinado que o jogador abre quando quiser), com prêmios por peso, raridade e anúncio.",
    passos=[
        "Crie a caixa: /caixa criar <nome> e mude o nome exibido com /caixa nome <caixa> <nome>.",
        "Prêmios de item: segure o item e use /caixa premio <caixa> <peso>. Prêmios de comando: /caixa premiocomando <caixa> <peso> <nome_do_premio> <comando> ({jogador} vira o nick).",
        "Caixa com chave no mundo: /caixa vincular <caixa> e toque num baú; entregue chaves com /caixa chave <jogador> <caixa> [qtd].",
        "Caixa misteriosa (item): /caixa misteriosa <jogador> <caixa> [qtd].",
        "Raridade no painel: misteriosa.raro e misteriosa.muito_raro (décimos de %), misteriosa.anunciar e misteriosa.mensagem.",
    ],
    secoes=[("Exemplo", [
        "$ /caixa criar basica",
        "$ /caixa premiocomando basica 5 &2$100.000 economia adicionar money {jogador} 100000",
        "$ /caixa misteriosa Gustavo basica 3",
        "- /caixa lista, /caixa limpar <caixa>, /caixa apagar <caixa>.",
    ])],
    dicas=["A caixa misteriosa é assinada: um baú renomeado na bigorna com o mesmo nome não abre nada."],
),
"GoldEnchant": dict(
    resumo="Encantador por menu (/encantar) com preço por nível.",
    passos=["Jogadores seguram o item e usam /encantar.", "Staff define os preços: /encantar preco <id do encantamento> <valor por nível>.", "Staff aplica qualquer nível: /encantar <id> <nivel>."],
    secoes=[], dicas=[],
),
"GoldBreaker": dict(
    resumo="Reparo pago (/reparar) e envio de itens (/enviar).",
    passos=["/reparar repara o item da mão (pago). /reparar tudo exige goldbreaker.reparar.tudo; goldbreaker.reparar.gratis repara sem pagar (dê ao VIP se quiser).", "/enviar <jogador> manda o item da mão para outro jogador."],
    secoes=[], dicas=[],
),
"GoldPvP": dict(
    resumo="Perfil, rankings, ranked 1v1 com elos estilo Valorant (Ferro a Divino), anti-fraude e recompensas em cosméticos.",
    passos=[
        "Marque os mundos de lobby onde o livro do perfil aparece: /ranked lobby <mundo>.",
        "Crie a arena do ranked: em cada ponto de nascimento, /ranked arena <nome> a e /ranked arena <nome> b.",
        "Monte no inventário o kit do ranked e use /ranked kit.",
        "Anti-fraude no painel: fraude.advertencias_para_expulsar, fraude.advertencias_para_bloquear, fraude.advertencia e fraude.expulsao.",
    ],
    secoes=[("Jogadores", ["- /ranked entra na fila, /perfil [jogador], /topkills, /topranked, /elos, /elochat."])],
    dicas=["Num lobby onde o perfil não deve aparecer, use /zona recurso <zona> goldpvp.perfil.", "Contas do mesmo IP nunca são pareadas no ranked e abates entre elas não contam."],
),
"GoldFactions": dict(
    resumo="Facções com poder, território por chunk, invasão de borda, chat interno e mini mapa no mundo.",
    passos=[
        "Escolha os mundos onde se pode dominar: /f mundo <mundo> (nenhum marcado = todos).",
        "Ajuste os pesos do poder: /f peso <terras|membros|spawners|abates|mortes> <0-1000>.",
        "Mini mapa no lobby: fique no lugar e use /f mapa definir [raio 4-24]; /f mapa centro e /f mapa remover.",
    ],
    secoes=[("Jogadores", ["- /f criar, convidar, aceitar, sair, expulsar, promover, rebaixar, lider, desfazer.", "- /f claim (dominar), unclaim (abandonar), sethome, home, chat, info, poder, top, mapa."])],
    dicas=["/f recontar refaz a contagem de spawners nos chunks carregados.", "Em lobbies sem facções: /zona plugin <zona> GoldFactions."],
),
"GoldSkills": dict(
    resumo="Habilidades estilo mcMMO: mineração, lenhador, escavação, herbalismo, espadas, machado, arqueiro, desarmado, acrobacia e reparação, com poderes opcionais, boosters, anúncio de nível e ranking.",
    passos=[
        "Funciona ao instalar. Blocos colocados por jogadores não dão XP.",
        "Poderes (agachar com a ferramenta) vêm desligados: painel GoldSkills > poderes = 1 para ligar; poderes.recarga define a recarga.",
        "Boosters: booster.multiplicador, booster.segundos, booster.preco e booster.preco_todas.",
        "Anúncios: anuncio.niveis e anuncio.mensagem. Tag do 1º colocado: toptag (placeholder {mctop}).",
        "Reparo: a ferramenta gasta, com o material no inventário, tocando no bloco de ferro.",
    ],
    secoes=[("Admin", ["$ /darbooster Gustavo todas 2 30", "$ /xprate 2 60", "$ /mmoedit Gustavo mineracao 100", "- /addlevels, /addxp, /skillreset, /xprate reset."]),
            ("Jogadores", ["- /skills, /mcstats, /mctop [habilidade], /mcrank, /mcability, /booster."])],
    dicas=["Desligue partes por zona: goldskills.xp, goldskills.combate, goldskills.poderes, goldskills.reparacao."],
),
"GoldHG": dict(
    resumo="Hunger Games clássico do PC: sala de espera, 36 kits, feast, batalha final, sopa pensada para celular e mapa restaurado sozinho.",
    passos=[
        "Copie o mapa para worlds/ e vá até ele.",
        "Crie a arena: /hg criar <mundo> (salva uma cópia do mapa).",
        "Em cada nascimento: /hg spawn (até 24; /hg desfazer remove o último).",
        "No centro: /hg final (feast e batalha final).",
        "Regras: /hg regras minimo|espera|pvp <valor>.",
        "Depois de editar o mapa: /hg salvar.",
        "NPC de entrada: /npc comando hg entrar.",
    ],
    secoes=[("Admin", ["- /hg iniciar [arena] começa em 10 s; /hg batalhafinal; /hg remover <arena>.", "- /sopa mundo <mundo> liga a sopa instantânea numa arena de PvP fora do HG."])],
    dicas=["Durante a partida só funcionam /hg, /kit, /sopa, /tell e o login."],
),
"GoldSkyWars": dict(
    resumo="SkyWars estilo Hypixel: jaulas, kits, baús de ilha e do meio com refil, espectadores e mapa restaurado.",
    passos=[
        "Vá ao mundo do mapa e crie: /sw criar <nome> [solo|dupla].",
        "Em cada ponto de jaula (1 a 3 blocos acima da ilha): /sw jaula.",
        "No centro: /sw meio. Para o modo insano: /sw insano.",
        "Salve a cópia do mapa: /sw salvar.",
        "No painel: minimo_jogadores, contagem, duracao, refil_1, refil_2, premio_abate, premio_vitoria, mundo_lobby e os preços kit.<id>.",
        "NPC: /npc comando sw entrar solo.",
    ],
    secoes=[("ArenaBuilder", ["- jaula e meio aceitam x y z no fim: cole os comandos do pontos.txt do mapa pronto sw_ilhas."])],
    dicas=["/sw lista, /sw iniciar <arena>, /sw remover <arena>. Ranking skywars para NPC."],
),
"GoldBedWars": dict(
    resumo="BedWars com até 8 times, forja, diamante e esmeralda, loja por categorias, melhorias do time e eventos.",
    passos=[
        "No mundo do mapa: /bw criar <nome> [solo|dupla|quarteto].",
        "Sala de espera: /bw espera.",
        "Para cada cor: /bw time <cor> spawn, /bw time <cor> cama, /bw time <cor> forja.",
        "Geradores: /bw diamante e /bw esmeralda (/bw desfazer diamante|esmeralda).",
        "Salve: /bw salvar.",
        "No painel: minimo_jogadores, contagem, premio_abate, premio_abate_final, premio_cama, premio_vitoria e mundo_lobby.",
        "NPCs: /npc comando bw entrar solo, /npc comando bw loja e /npc comando bw melhorias.",
    ],
    secoes=[("Cores", ["- vermelho, azul, verde, amarelo, ciano, branco, rosa e cinza."])],
    dicas=["Os comandos de ponto aceitam x y z no fim (pontos.txt do mapa pronto bw_quatro)."],
),
"GoldParty": dict(
    resumo="Party de minigames: cada partida sorteia 5 provas entre 10, com pontos por colocação e ranking final. Várias salas jogam ao mesmo tempo.",
    passos=[
        "Funciona ao instalar: o mundo party e as arenas são criados sozinhos.",
        "No painel: minimo_jogadores, maximo_jogadores, contagem, provas_por_partida, maximo_salas, blocos_por_tick, premio_1/2/3, provas_desligadas e mundo_lobby.",
        "NPC: /npc comando party entrar.",
    ],
    secoes=[("Provas", ["- corrida_mortal, grappler, kangaroo, color_floor, tnt_run, pvp_mlg, fisherman, sumo, water_drop, arqueiro (ids para provas_desligadas)."]),
            ("Comandos", ["- /party entrar, sair, top, stats [nick], salas. Admin: /party iniciar e /party reconstruir."])],
    dicas=["blocos_por_tick limita a montagem das arenas somando todas as salas: abrir várias salas não causa lag."],
),
"GoldBuild": dict(
    resumo="Edição de mundo (estilo WorldEdit) em fatias de até 4000 blocos por tick.",
    passos=["Marque os cantos: /we pos1 e /we pos2.", "/we set <bloco>, /we walls <bloco>, /we replace <de> <para>, /we esfera <raio> <bloco> (onde você mira), /we undo."],
    secoes=[], dicas=["Só goldbuild.admin. Limite de 500 mil blocos por operação."],
),
}

PLACEHOLDERS = {
    "nome": "nick do jogador", "online": "jogadores online", "max": "máximo de jogadores", "mundo": "mundo atual", "x": "coordenada X", "y": "coordenada Y", "z": "coordenada Z",
    "dir": "direção (N, S, L, O...)", "ping": "ping em ms", "money": "moedas compactas (1.2k, 3.4M)", "money_exato": "moedas exatas", "cash": "cash", "banco": "saldo no Banco Central",
    "grupo": "grupo do GoldPerms", "tag": "tag do cargo", "clan": "nome da facção", "clan_tag": "tag da facção", "kills": "abates", "deaths": "mortes", "kdr": "abates por morte",
    "armadura": "durabilidade da armadura (GoldHud)", "poder": "soma dos níveis de habilidade", "mctop": "tag do 1º do ranking de habilidades",
}
for s in ["mineracao", "lenhador", "escavacao", "herbalismo", "espadas", "machado", "arqueiro", "desarmado", "acrobacia", "reparacao"]:
    PLACEHOLDERS["nivel_" + s] = "nível de " + s

CORES = [("&0", "preto"), ("&1", "azul escuro"), ("&2", "verde escuro"), ("&3", "ciano escuro"), ("&4", "vermelho escuro"), ("&5", "roxo"), ("&6", "dourado"), ("&7", "cinza"),
         ("&8", "cinza escuro"), ("&9", "azul"), ("&a", "verde"), ("&b", "ciano"), ("&c", "vermelho"), ("&d", "rosa"), ("&e", "amarelo"), ("&f", "branco"),
         ("&l", "negrito"), ("&o", "itálico"), ("&n", "sublinhado"), ("&m", "riscado"), ("&k", "embaralhado"), ("&r", "volta ao normal")]
