# GoldSpawners

Spawners do Rankup, leves para o celular e para o servidor.

- **10 tipos**, do mais barato ao mais caro: Galinha, Ovelha, Porco, Vaca, Zumbi, Esqueleto, Aranha, Creeper, Blaze e Golem de Ferro.
  O tipo vai no dano do item: `52:1` (galinha) ... `52:10` (golem). `/spawners tipos` mostra os drops de cada um.
- Só funcionam nos mundos do painel `mundos` (padrão `plots`); a proteção do plot decide quem pode colocar.
- Com um jogador a até `raio` blocos, nasce 1 mob a cada `intervalo` segundos, até `pilha_maxima`.
- A pilha é **um mob só de pacotes** ("32x Zumbi"): não existe no mundo, não tem IA, não é salvo no chunk. Cada toque mata um
  e os drops vão direto para o inventário (inventário cheio: não mata, nada se perde).
- Quebrar devolve o spawner ao dono (outro jogador não quebra). O gerador de zumbis do core é tirado do bloco.
- Limite por jogador: painel `limite_por_jogador` (padrão 20); `goldspawners.limite.<25|30|40|50|75|100|150|200>` aumenta.

Venda: os spawners e a compra dos drops ficam na **loja física** (placas do GoldShop criadas pelo console):
```
comprar placa comprar minas -30 66 -9 sul 52:5 1 6500 &eSpawner Zumbi
comprar placa vender minas -30 65 9 norte 367 1 3,50
```
Comandos: `/spawners` (os seus), `/spawners tipos`, `spawners dar <jogador> <tipo> [qtd]` (admin/console).
