# GoldFactions

## Poder
Poder = terras x 10 + membros x 20 + spawners x 50 + abates x 5 - mortes x 8 (mínimo 0).
Os pesos mudam com `/f peso <terras|membros|spawners|abates|mortes> <0-1000>`.
Spawners só contam dentro de chunks dominados: são contados ao dominar e acompanhados a cada colocação/quebra. `/f recontar` refaz a contagem nos chunks carregados.

## Território
- Limite de chunks: 2 + 3 por membro (máx. 60).
- `/f mundo <mundo>` liga/desliga mundos onde se pode dominar (nenhum = todos).
- Invasão: com poder maior que o dono, `/f claim` toma um chunk da **borda** do território inimigo (anúncio global).
- Ao cruzar a divisa aparece `[TAG] nome - poder X` ou `Zona livre`.

## Mapas
- `/f mapa`: mapa 11x11 em texto, cores por facção.
- Mini mapa no mundo (admin): fique onde quer o mapa e use `/f mapa definir [raio 4-24]`. Cada bloco de lã é um chunk do mundo das facções; o bloco de ouro é o centro. O nome e o poder de cada facção flutuam sobre o território dela. `/f mapa centro` muda o centro para o seu chunk, `/f mapa remover` desliga.
- O mapa se atualiza até 1 vez por segundo após mudanças e a cada minuto.

## Chat
Tag `«TAG»` antes do nome. `/f chat` alterna o chat interno.
