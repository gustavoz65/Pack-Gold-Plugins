# GoldCosmetics

`/cosmeticos` (ou o botão no perfil do PvP) abre o menu com 4 categorias. Toque num item para equipar/tirar; os da loja pedem confirmação e são pagos com o dinheiro do GoldEconomy (se a gravação falhar, o dinheiro volta).

| Tipo | Onde aparece | Loja | Só no ranked |
|---|---|---|---|
| Pingente | antes do nome no chat | Coração, Estrela, Música, Raio, Caveira, Coroa | um por elo (Ferro ao Imortal), Divino, 10/50/100 vitórias |
| Trilha | partículas nos pés ao andar | Fumaça, Respingos, Redstone, Encantada, Portal, Corações, Chamas | Divina |
| Efeito de abate | onde a vítima morreu | Corações, Lava, Explosão | Coroa Imortal |
| Cor do nome | nome no chat | Verde, Azul, Rosa, Amarelo, Vermelho | Dourado Divino |

Admin: `/cosmeticos lista`, `/cosmeticos dar <jogador> <id>`.

Desempenho: as trilhas rodam a cada 4 ticks, só para quem se mexeu, e param sozinhas com TPS abaixo de 16. Os dados ficam em cache enquanto o jogador está online, e as gravações de cada jogador entram em fila (liberar, comprar e equipar nunca se atropelam).

Outros plugins liberam recompensas pelo `CosmeticService` (gold-api).
