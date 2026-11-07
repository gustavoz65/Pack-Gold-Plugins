# GoldPlots

Terrenos infinitos para o Rankup, com as medidas do plotworld do pack: plot 42x42, borda de 1 com laje de madeira, rua de 11 (célula de 55), chão em y 64.

- O mundo `plots` é o plotworld do pack convertido; além dele o gerador `goldplots` continua a mesma grade pela coordenada (nada pré-gerado, nada na memória). `grade.*` no painel, `grade.deslocamento = 5` casa com o pack (não mude depois de criar o mundo).
- `/plot` abre o menu (tudo pelo celular): comprar este, plot automático (o livre mais perto do centro), meus plots, informações, membros (adicionar quem está online / tirar), limpar (com confirmação) e ir aos plots.
- Comandos: `/plot comprar|auto|home [n]|info|add <jogador>|remover <jogador>|limpar|ir`.
- Compra: reserva o plot na hora (dois compradores ao mesmo tempo: só um leva), cobra e grava; se a gravação falhar, devolve. Preço no painel (`preco`, padrão $1.500).
- Limite: `goldplots.limite.<n>` (o GoldRankup dá 1 a 4 pelo rank; VIP pode ganhar mais), `limite_padrao` no painel.
- Só dono e membros constroem, mexem em blocos e usam baldes; na rua e na borda ninguém. PvP: `mundoprotecao plots pvp off`.
- `/plot limpar` repõe o plot como o gerador, `limpar.blocos_por_tick` por tick.
- Só os plots comprados são guardados (um registro por id `x;z`).


## Dois mapas: plots e terrenos
- `/plot` leva ao mapa de plots (ao seu primeiro plot, se tiver); já no mapa abre o menu. `/plot menu` abre em qualquer lugar.
- `/terreno` faz o mesmo no mapa de terrenos (gerador `goldterrenos`: terreno 42, borda de pedra com laje, rua de quartzo de 5), com compra e registros próprios. Painel: `terrenos.mundo`, `terrenos.preco`.
