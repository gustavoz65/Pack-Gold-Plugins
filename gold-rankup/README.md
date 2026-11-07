# GoldRankup

Rankup: escada de 20 ranks pagos com money (Pedra I a Lendário II), permissões por rank e minas que se regeneram.

## Jogadores
- `/rankup`: confirmação com o rank atual, o próximo, o preço e o saldo. Cobrança e subida numa operação só: o toque duplo não cobra duas vezes e, se a gravação falhar, o dinheiro volta.
- `/ranks`: todos os ranks, com o atual destacado e o progresso em % até o próximo (tocar no próximo abre o `/rankup`).
- `/minas`: as minas liberadas e as bloqueadas (com o rank necessário). Tocar leva à borda da mina.
- Placeholders: `{rank}`, `{rank_proximo}`, `{rank_preco}` e `{rank_progresso}` (chat e HUD).

## Ranks
Cada rank tem tag colorida, preço (painel `preco.<id>`, em reais), a mina que libera e permissões (somadas às dos anteriores, por anexo do plugin; nunca OP, staff, curinga ou bypass): `goldrankup.mina.<grupo>`, `goldplots.limite.<n>` (1 / 2 a partir de Ferro / 3 de Redstone / 4 de Obsidiana) e `goldkits.kit.ferramentas_<grupo>`.
O rank fica num registro por jogador (só os online na memória). Admin (só OP): `/rankadmin set <jogador> <rank>` e `/rankadmin reset <jogador>`.

## Minas
- Caixa com composição em % (`id[:meta]=peso`), rank mínimo, tempo (min) e porcentagem minerada para regenerar, o que vier primeiro, com aviso 10 s antes; quem está dentro vai para a borda.
- Reposição limitada por tick (`minas.blocos_por_tick`, somando todas) e só com os chunks da mina carregados.
- Dentro da mina só se quebram os blocos dela, sem colocar bloco; fora das minas o mundo é protegido. Fortuna multiplica carvão, lápis, redstone, diamante e esmeralda; o GoldSkills dá XP (os blocos são gerados, não colocados).
- `/mina gerarmundo <mundo>` monta pátio, avenida e as 10 minas (15x15, 20 de fundo) já configuradas. Outros: `criar`, `composicao`, `rank`, `tempo`, `porcentagem`, `topo`, `nome`, `resetar`, `apagar`, `lista`.
