# Conversor de mapas do PC (1.8) para o Nukkit 0.15

Mapas de SkyWars baixados para o Minecraft Java 1.8 (formato Anvil com ids numéricos) viram mundos do servidor.

```bash
python3 -I pc18.py "<pasta do mapa PC>" "<pasta de saida>/sw_nome" sw_nome     # converte
python3 -I swdetect.py "<pasta de saida>/sw_nome"                               # jaulas/ilhas e centro (JSON)
```

O que a conversão faz:
- **blocos que mudam de significado no 0.15**: vidro colorido (95) vira vidro; painel colorido (160) vira painel; barreira, estandarte, portal e bloco de comando viram ar; lajes de madeira (125/126 no PC) viram 157/158; ativador e dropper trocam de id; cercas coloridas (188–192) viram a cerca 85 com o tipo de madeira;
- **baús, fornalhas e funis**: ficam vazios (no PC o item é texto e trava o tick no 0.15; o SkyWars reabastece os baús);
- **placas**: o texto em JSON do PC vira texto simples;
- **vasos**: convertidos para o formato do 0.15;
- **spawners, entidades e TileTicks**: removidos;
- `level.dat`: nome do mundo e gerador `void`.

O `swdetect.py` acha as **jaulas de vidro** do mapa (grupo de vidro de até 5x5x6) e, sem jaulas, as **ilhas** pelos baús agrupados (nasce na superfície da ilha, do lado do centro). O centro é a média dos spawns.
Confira os pontos antes de usar (o `arenas-nostalgic.txt` traz os comandos de console usados no Rush MC).
