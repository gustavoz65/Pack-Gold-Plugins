# Lobby Builder

Gera o lobby principal (vilarejo medieval numa ilha flutuante) por código, num servidor de trabalho.

```bash
javac --release 8 -cp nukkit-gold.jar -d out src/gold/lobby/*.java
printf 'name: LobbyBuilder\nmain: gold.lobby.LobbyBuilder\nversion: "1"\napi: ["1.0.0"]\n' > out/plugin.yml
(cd out && jar cf ../LobbyBuilder.jar .)
```

Coloque o `LobbyBuilder.jar` em `plugins/` de um servidor vazio e ligue-o: o mundo `lobby` é criado, salvo, e as prévias
(`lobby-cima.png`, `lobby-perspectiva.png`) e os pontos dos NPCs (`pontos.txt`) ficam em `plugins/LobbyBuilder/`. Depois tire o jar.
Não é um plugin do servidor de jogo.
