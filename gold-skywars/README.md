# GoldSkyWars

SkyWars no estilo do MythicSkyWars, refeito do zero a partir da lógica do plugin de luizgeh (jaulas, contagem, baús com refil, último vivo vence, espectadores, mapa restaurado), com progressão, Poço das Almas, vantagens, jaulas, prestígio, votação da partida e minérios gerados por ilha.

## Falhas do original que foram corrigidas
- `/sw criar`, `/sw setspawn` e `/sw setlobby` **não tinham permissão**: qualquer jogador criava arenas.
- O nome da arena virava caminho de arquivo (`/sw criar ../../OutroPlugin/config`) e permitia **sobrescrever arquivos de outros plugins** (ex.: permissões). Agora o nome só aceita letras, números e `_`.
- O espectador ganhava voo e **continuava voando no lobby** depois da partida.
- O inventário era apagado ao entrar e nunca devolvido; agora só entra quem está com o inventário vazio (itens do lobby podem ficar).
- Laços com variável errada, contador de abates duplos que nunca funcionava e `return` que travava a sala.

## Partida
1. `/sw entrar [solo|dupla]` (ou NPC com `/npc comando sw entrar`): o jogador vai direto para uma **jaula de vidro** na ilha.
2. Com o mínimo de jogadores, contagem de 20 s (10 s com a sala cheia). Na jaula: baú da hotbar = **kits**, cama = sair.
3. As jaulas somem, cada um recebe o kit e os baús são preenchidos. 5 s sem dano de queda.
4. Refil dos baús em 3:00 e 6:00. Até 15:00; sem vencedor, empate.
5. Morte: itens caem (no vazio, não), vira **espectador** invisível voando sobre o centro (papel = jogar de novo, cama = sair). O abate vai para quem bateu por último (15 s), inclusive quem empurrou no vazio.
6. Vitória: dinheiro, efeito, anúncio global; todos voltam ao lobby e o mapa é restaurado em segundo plano.

## Baús
- **Ilha**: sempre uma arma/armadura e blocos, mais 2 a 4 itens.
- **Meio** (até 12 blocos do centro): diamante, encantados, pérolas, poções.
- **Insano** (`/sw insano`): tudo encantado e mais forte.

## Kits (19)
Padrão, Armeiro, Arqueiro, Jogador de Beisebol, Canhoneiro (TNT acende sozinha), Piromaníaco, Boneco de Neve, Batedor, Curandeiro, Troll, Enderman (**Pérola do Fim**: bola de neve que teleporta, já que o 0.15 não tem pérola), Fazendeiro, Ecologista, Espeleólogo, Cavaleiro, Sapo, Princesa, Granadeiro, Construtor. Comprados com moedas do SkyWars (preço do painel ÷ 100); preços no `/painel` (GoldSkyWars > `kit.<id>`).

## Progressão (estilo MythicSkyWars)
- Abate: 1 alma, XP e moedas do SkyWars; vitória: mais XP e moedas (painel: `xp.*`, `moedas.*`). Nível pela curva do Mythic (1000/2000/3000/3500, depois 5000 até 15000 por nível).
- `/sw` abre o menu. `/sw almas` (Poço das Almas: `almas.custo` por giro; prêmio repetido vira `almas.repetido` moedas), `/sw vantagens` (14 vantagens, 4 níveis, 500 moedas por nível; no máximo, tocar liga/desliga), `/sw jaulas` (vidro, ferro, folhas, gelo, ouro), `/sw prestigio` (Pedra a Lendário por nível).
- Custo e prêmio do poço ficam na mesma gravação: fechar o menu ou cair do servidor não perde nem duplica prêmio.
- Votação na sala de espera (papel "Votar" na hotbar ou `/sw votar`): baús básico/normal/insano, dia/noite, limpo/chuva, vida normal/dupla, modificador nenhum/velocidade/pulo. Empate fica no padrão.
- Placeholders: `{sw_nivel}`, `{sw_moedas}`, `{sw_almas}`, `{sw_prestigio}`. Efeitos visuais respeitam o `/fps`.

## Editor de mapa (`/swmap`, `goldskywars.admin`)
`/swmap` (resumo), `jaula`, `desfazer`, `meio`, `espectador`, `bau meio|ilha|auto|pronto` (toque nos baús), `time <1-4>`, `minimo <2-96>`, `nome <texto>`, `autor <texto>`, `ativar|desativar`, `salvar`. Só com a arena vazia.

## Minérios por ilha
`/sw ilhas` entrega o graveto. Toque em um bloco de cada ilha (até 24); a ilha é lida aos poucos (1.200 blocos por tick, raio de 48). `/gerar minerios` completa cada ilha até 4 diamantes, 5 ferros e 6 carvões em pedra/terra coberta (nada fica à mostra), confere o mapa antes de mudar qualquer bloco e salva a cópia. `/sw ilhas limpar` recomeça a seleção.

## Admin (`goldskywars.admin`)
No mundo do mapa: `/sw criar <nome> [solo|dupla]`, `/sw jaula` (em cada ponto de jaula, 1–3 blocos acima da ilha), `/sw meio`, `/sw insano`, `/sw salvar` (cópia do mapa). `jaula` e `meio` aceitam `x y z` no fim (para colar o `pontos.txt` do ArenaBuilder; com o meio marcado, a jaula fica virada para o centro). Também `/sw lista`, `/sw iniciar <arena>`, `/sw remover <arena>`.
Painel: mínimo de jogadores, contagem, duração, refis, prêmios, mundo do lobby.

Ranking `skywars` (vitórias) disponível para os NPCs: `/npc ranking skywars 1`.
