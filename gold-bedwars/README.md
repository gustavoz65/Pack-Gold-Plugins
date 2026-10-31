# GoldBedWars

BedWars no estilo dos servidores brasileiros grandes: até 8 times (solo, dupla ou quarteto), forja do time, geradores de diamante e esmeralda, loja por categorias, melhorias do time e camas que podem ser quebradas.

## Partida
1. `/bw entrar [solo|dupla|quarteto]` (ou NPC com `/npc comando bw entrar solo`). Na sala de espera: lã = **escolher time**, cama = sair. Quem não escolhe é colocado no time mais vazio.
2. Com o mínimo de jogadores, contagem de 20 s (10 s com a sala cheia). Times sem jogadores começam sem cama.
3. Todos nascem com armadura de couro na cor do time e espada de madeira. A armadura comprada é **permanente**. Picareta e machado caem um nível a cada morte.
4. Com a cama de pé, a morte renasce em 5 s. Sem cama, a morte é **final** e o jogador vira espectador. Os recursos do morto vão para quem matou.
5. Só os blocos colocados na partida podem ser quebrados. A cama inimiga pode; a do próprio time não. Explosões só destroem blocos macios colocados por jogadores (lã, madeira, argila); vidro e obsidiana resistem.
6. Eventos: Diamante II (6:00), Esmeralda II (12:00), Diamante III (18:00), Esmeralda III (24:00), **todas as camas destruídas** (30:00) e fim em empate (40:00).
7. Vitória: dinheiro, efeito, anúncio global; todos voltam ao lobby e o mapa é restaurado em segundo plano.

## Loja (`/bw loja` ou NPC, até 20 blocos do spawn do time)
- **Blocos**: lã (na cor do time), argila, vidro, pedra do End, escada, madeira, obsidiana.
- **Armas**: espadas de pedra, ferro e diamante; graveto de repulsão.
- **Armaduras** permanentes: malha, ferro e diamante.
- **Ferramentas**: picareta e machado em níveis; tesoura permanente.
- **Arcos**: flechas, arco, Força I, Força I + Impacto I.
- **Poções** (aplicadas na hora): Velocidade II, Super Pulo, Invisibilidade.
- **Utilidades**: maçã dourada, **bola de fogo** (blaze, toque para lançar), TNT que acende sozinha, **Pérola do Fim**, balde de água, **Ovo-Ponte** (constrói uma ponte de lã por onde passa) e esponja.

## Melhorias do time (`/bw melhorias`, pagas em diamante)
Espada Afiada, Proteção I–IV, Pressa I–II, Forja I–IV (mais ferro e ouro; esmeralda na base a partir do nível III), Piscina de Cura (regeneração perto da base) e Armadilha (cegueira e lentidão no primeiro inimigo que entrar na base).

## Admin (`goldbedwars.admin`)
No mundo do mapa: `/bw criar <nome> [solo|dupla|quarteto]`, `/bw espera`, `/bw time <cor> spawn|cama|forja`, `/bw diamante`, `/bw esmeralda`, `/bw desfazer <diamante|esmeralda>`, `/bw salvar`. Os comandos de ponto usam a posição do admin ou as coordenadas `x y z` no fim (para colar o `pontos.txt` do ArenaBuilder). Na `cama`, o ponto pode ficar a até 3 blocos da cama.
Cores: vermelho, azul, verde, amarelo, ciano, branco, rosa e cinza. Também `/bw lista`, `/bw iniciar <arena>`, `/bw remover <arena>`.
Jogadores: `/bw sair`, `/bw stats [nick]`, `/bw top`.
Painel: mínimo de jogadores, contagem, prêmios (abate, abate final, cama e vitória) e mundo do lobby.

Ranking `bedwars` (vitórias) disponível para os NPCs: `/npc ranking bedwars 1`.
