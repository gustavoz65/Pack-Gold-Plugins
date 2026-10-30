# GoldPvP

## Perfil
Livro "Perfil e Ranked" no slot 9 da hotbar (mundos de lobby). **Basta selecionar o livro** para abrir o perfil; a hotbar volta para o slot anterior, então é só tocar nele de novo para reabrir (no 0.15 tocar no ar com um livro não chega ao servidor).
O perfil mostra abates, mortes, KDR, sequência, elo e pontos, pico, posição, V/D/E, dano, e tem botões para: jogar ranked, **elo no chat** (liga/desliga), **lista de elos**, top 10 e cosméticos.
`/perfil [jogador]`, `/elos`, `/elochat`.

## Elos (estilo Valorant)
| Elo | Pontos |
|---|---|
| Ferro 1-3, Bronze 1-3, Prata 1-3, Ouro 1-3, Platina 1-3, Diamante 1-3, Ascendente 1-3 | 100 PR por divisão (0 a 2099) |
| Imortal 1/2/3 | a partir de 2100; os PR acumulam sem teto (Imortal 2 com 90 PR, Imortal 3 com 200 PR) |
| Divino | só os **10 Imortais com mais PR** do servidor; quem passar um Divino toma a vaga dele |

- 5 partidas classificatórias definem o elo inicial (no máximo Ascendente 1).
- Vitória dá de 12 a 30 PR, derrota tira de 10 a 28. O MMR oculto (ELO) decide quanto: quem joga acima do próprio elo sobe mais rápido.
- Subir para Ascendente ou acima é anunciado no servidor.

## Anti-fraude
- Contas do **mesmo IP** nunca são pareadas no ranked, e abates entre elas no mundo aberto não contam.
- A **mesma dupla** só se enfrenta 2 vezes a cada 30 min no ranked.
- O mesmo jogador matando a mesma vítima conta no máximo 3 vezes a cada 10 min.
- **Partida entregue** (derrota em menos de 25 s causando menos de 2 corações) ou duelo parado (empate sem luta) é anulada, sem pontos.
- Cada fraude vira advertência (a staff com `goldpvp.admin` é avisada). **3 advertências em 24 h expulsam do servidor; a 4ª bloqueia o ranked por 24 h.**

## Ranked 1v1
- `/ranked` entra/sai da fila (ou pelo menu). O pareamento usa o MMR, abrindo a janela com a espera.
- O inventário é guardado antes do kit e devolvido no fim (inclusive após queda do servidor).
- 3 s de contagem, 180 s de limite (empate). Sair na contagem cancela sem pontos.

## Admin
`/ranked arena <nome> a|b|remover`, `/ranked kit`, `/ranked lobby <mundo>`.

## Recompensas
Com o GoldCosmetics instalado, cada elo alcançado libera o pingente dele; Imortal libera o efeito de abate "Coroa Imortal"; Divino libera pingente, trilha e cor do nome exclusivos; 10, 50 e 100 vitórias liberam pingentes.

## NPC do top 3
`/npc ranking ranked 1` faz o NPC usar a skin e o nome do 1º colocado (quadros `ranked` e `pvp`).
