# Arena Builder

Gera dois mapas prontos por código, num servidor de trabalho:
- `sw_ilhas`: SkyWars com 8 ilhas (2 baús e uma árvore em cada) e o meio com 4 baús.
- `bw_quatro`: BedWars com 4 times (lã, cama e forja), 4 ilhas de diamante, o meio com 2 esmeraldas e a sala de espera de vidro no alto.

```bash
javac --release 8 -cp nukkit-gold.jar -d out src/gold/arena/*.java
printf 'name: ArenaBuilder\nmain: gold.arena.ArenaBuilder\nversion: "1"\napi: ["1.0.0"]\n' > out/plugin.yml
(cd out && jar cf ../ArenaBuilder.jar .)
```

Coloque o `ArenaBuilder.jar` em `plugins/` de um servidor vazio e ligue-o. Os mundos são criados e salvos, e
`plugins/ArenaBuilder/pontos.txt` traz os comandos `/sw` e `/bw` prontos para colar no jogo, na ordem. Depois tire o jar e
copie as pastas `worlds/sw_ilhas` e `worlds/bw_quatro` para o servidor de jogo. Não é um plugin do servidor de jogo.
