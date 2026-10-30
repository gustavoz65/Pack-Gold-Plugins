# GoldHG

Hunger Games no estilo do HG clássico do Java 1.8, feito para o MCPE 0.15.10. Partiu das regras do HG-PLUS (créditos: Rev!i!Games, yHerobrine, Joseper e LucasGamer610), reescrito do zero em Java.

## Partida

1. **Sala:** até 24 jogadores. Com o mínimo (2), começa a contagem de 3 min. Os jogadores ficam congelados no spawn e escolhem o kit tocando no **baú da hotbar**.
2. **Início:** inventário limpo, bússola, itens do kit, baús do mapa reabastecidos.
3. **Invencibilidade:** 3 min sem dano.
4. **Feast:** aparece no centro faltando 13 min (anunciado 5 min, 1 min, 30 s e 10 s antes, com as coordenadas): plataforma com 8 baús de equipamento bom.
5. **Reabastecimento** dos baús faltando 7 min, e **batalha final** faltando 3 min: todos são levados em círculo para o centro.
6. **Fim:** o último vivo vence (prêmio pelo GoldEconomy e partículas); se o tempo acabar, ninguém vence. O mapa é restaurado da cópia em segundo plano.

A **bússola** aponta para o inimigo mais próximo e mostra nome e distância. Quem morre nasce no lobby. Quem desconecta no meio perde os itens da partida ao voltar (fica salvo, mesmo se o servidor reiniciar). Durante a partida só funcionam `/hg`, `/kit`, `/sopa`, `/tell` e o login.

## Sopa pensada para celular

- **Toque em qualquer lugar** com a sopa na mão: no ar, num bloco ou batendo em alguém. Cura 3,5 corações na hora.
- **`/sopa`** liga o modo "selecionar e tomar": só de tocar no slot da sopa na hotbar ela já é tomada (o jeito mais rápido no touch).
- A tigela some (não enche a hotbar) e não gasta sopa com a vida cheia.
- Limite de 1 sopa a cada 3 ticks no servidor: macro não dá vantagem sobre quem joga no celular.
- **Recraft:** tigela + cacto ou tigela + cacau também viram sopa.
- `/sopa mundo <mundo>` (admin) liga a sopa instantânea também numa arena de PvP fora do HG.

## Kits (36 + Sem kit + Surpresa)

Menu em baú virtual com duas páginas (`/kits` ou o baú da hotbar); `/kit <nome>` também funciona.

| Categoria | Kits |
| --- | --- |
| Mobilidade | Kangaroo (pata de coelho: o fogo de artifício não existe na 0.15), Grappler, Ninja, Flash, Blink, Switcher, Fisherman, Forger, Meteor |
| Combate | Anchor, Boxer, Viper, Snail, Critical, Barbarian, Monk, Magma, Reaper, Hulk, Thor, Gladiator, Stomper |
| Defesa | Achilles, Tank, Turtle, Neo |
| Farm | Cultivator, Specialist, Lumberjack, Demoman, Miner |
| Estratégia | Endermage, Phantom, Grandpa, Spy, Spider |

Adaptações à 0.15.10: a vara de pesca existe, mas sem anzol, então o Grappler e o Fisherman funcionam pela mira (puxam o bloco ou o jogador para onde você aponta). O Hulk agarra e arremessa, em vez de carregar nas costas. O Cultivator acelera mudas e plantações (cogumelos ficam de fora). As descrições completas aparecem no menu.

## Desempenho

- Nada é lido do disco durante a partida. Configuração em memória; quem está em qual partida, kit e recargas ficam em mapas por jogador (consulta O(1) em cada evento).
- Uma tarefa de 1 segundo, que só percorre arenas com gente.
- O backup e a restauração do mapa rodam fora da thread principal.

## Comandos

Jogadores: `/hg entrar [arena]`, `/hg sair`, `/hg lista`, `/kits`, `/kit <nome>`, `/sopa`.

Admin (`goldhg.admin`):

```text
/hg criar <mundo>      salva uma cópia do mapa (worlds/<mundo>) e cria a arena
/hg spawn              salva sua posição como próximo spawn (até 24)
/hg desfazer           remove o último spawn
/hg final              centro do feast e da batalha final
/hg salvar [arena]     atualiza a cópia do mapa depois de editar
/hg iniciar [arena]    começa em 10 s
/hg batalhafinal       batalha final em 10 s
/hg remover <arena>
```

Para entrar pelo NPC: `/npc comando hg entrar`.

Testado: regras, serialização, 38 kits e validações (`HgTest`); servidor real com o mapa do HG (criação da arena, cópia do mapa e persistência após reiniciar). As partidas e os kits ainda precisam ser testados com jogadores no cliente MCPE.
