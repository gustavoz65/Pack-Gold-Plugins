# GoldParty

Party de minigames: cada partida sorteia **5 provas** (configurável) entre 10, dá pontos pela colocação em cada uma e no fim faz o ranking geral.

## Como funciona
1. `/party entrar` (ou NPC com `/npc comando party entrar`). Só entra quem não tem itens (itens do lobby com nome podem ficar).
2. Com **3 jogadores** (mínimo configurável) começa a contagem de 30 s. Com a sala cheia (8), cai para 10 s.
3. Cada prova tem apresentação (nome, regras e como vence), largada com contagem 3-2-1 e o resultado.
4. Pontos por colocação em cada prova: 10, 8, 6, 5, 4, 3, 2, 1 (empatados levam os pontos da melhor posição do grupo).
5. Ranking final: soma dos pontos, com desempate por quantas provas cada um venceu. O top 3 ganha dinheiro, o campeão ganha efeito e anúncio global.

**Salas simultâneas:** cada sala usa uma vaga própria do mundo `party`, com as suas cópias das arenas (até 6 salas por padrão). Uma sala jogando nunca impede outra de começar.

## Provas
| Prova | Tipo | Como funciona |
|---|---|---|
| Corrida Mortal | tempo | Parkour sobre o vazio até o bloco de ouro; caiu, volta ao último bloco de esmeralda |
| Corrida de Grappler | tempo | Subir um poço de vidro passando pelos buracos das tampas; a vara puxa até o bloco que você mira |
| Corrida de Kangaroo | tempo | Ilhas separadas por buracos e muros; o item Kangaroo dá impulso no chão e mais um no ar |
| Color Floor | sobrevivência | Uma cor é sorteada (aparece na hotbar), o resto do chão some; o tempo para achar a cor diminui a cada rodada |
| TNT Run | sobrevivência | Cada bloco pisado some logo depois; três camadas e o vazio embaixo |
| PvP MLG | sobrevivência | Ilhas no vazio, graveto de repulsão, espada, lã e balde de água (a água some em 3 s); tem dano de queda |
| Fisherman | sobrevivência | A vara puxa o adversário até você; tire todos da plataforma |
| Sumô | sobrevivência | Plataforma pequena e só empurrão |
| Water Drop | pontos | Pular do alto e cair na água; 3 rodadas com piscina menor e obstáculos |
| Arqueiro | pontos | Deathmatch de 2 minutos: arco com 2 flechas e espada de madeira, flecha mata na hora, cada abate dá mais uma flecha |

Nas corridas vale a ordem de chegada (quem não chegou é classificado pelo quanto avançou). Na sobrevivência, o último vivo vence e quem foi eliminado antes fica atrás (desempate por abates). Nas de pontos, vale a pontuação.

## Otimização
- Um único tick atende todas as salas; nada roda para salas vazias.
- Montar e limpar arenas passa por uma **fila de blocos com limite por tick** somando todas as salas (`blocos_por_tick`, padrão 3000), em rodízio: abrir várias salas ao mesmo tempo não causa pico de lag.
- As arenas fixas são montadas **uma vez por vaga** e ficam gravadas. A cada partida só é refeito o que muda (chão do Color Floor, camadas do TNT Run, piscina do Water Drop, blocos colocados no MLG).
- Mudanças de bloco sem física e sem atualização em cadeia, enviadas em lote pelo próprio mundo; flechas somem ao acertar; ninguém morre de verdade (a prova trata a "morte" sem tela de respawn).

## Comandos e painel
`/party entrar`, `/party sair`, `/party top`, `/party stats [nick]`, `/party salas`. Admin (`goldparty.admin`): `/party iniciar` (começa a sua sala mesmo sem o mínimo), `/party reconstruir`.
Painel (GoldParty): mínimo e máximo de jogadores, contagem, provas por partida, máximo de salas, blocos por tick, prêmios do top 3, provas desligadas (`sumo,water_drop`...) e mundo do lobby.
Ranking `party` (vitórias) para os NPCs: `/npc ranking party 1`.
