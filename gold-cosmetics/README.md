# GoldCosmetics

`/cosmeticos` (ou o botão no perfil do PvP) abre o menu com 4 categorias. Toque num item para equipar/tirar; os da loja pedem confirmação e são pagos com o dinheiro do GoldEconomy (se a gravação falhar, o dinheiro volta).

| Tipo | Onde aparece | Loja | Só no ranked |
|---|---|---|---|
| Pingente (chaveiro 3D) | item com brilho flutuando e girando ao lado do ombro (lobby) | Coração, Estrela, Música, Raio, Caveira, Coroa | um por elo: Ferro (barra de ferro), Bronze (tijolo), Prata (quartzo), Ouro (barra de ouro), Platina (lápis-lazúli), Diamante, Ascendente (esmeralda), Imortal (redstone), Divino (cenoura dourada); 10/50/100 vitórias |
| Trilha | partículas nos pés ao andar | Fumaça, Respingos, Redstone, Encantada, Portal, Corações, Chamas | Divina |
| Efeito de abate | onde a vítima morreu | Corações, Lava, Explosão | Coroa Imortal |
| Cor do nome | nome no chat | Verde, Azul, Rosa, Amarelo, Vermelho | Dourado Divino |
| Montaria 3D | modelo de blocos que segue o jogador (lobby) | **Nuvem Voadora** (paga em **cash**) | - |

O chat mostra só a cor do nome (e o elo, pelo GoldPvP); os pingentes aparecem em 3D.

**Modelos 3D:** a nuvem e os chaveiros são blocos/itens que existem só em pacotes: o servidor não cria entidade, física nem colisão. Vão só para quem está a até 32 blocos, são reenviados apenas quando o dono anda ou gira, aparecem só no lobby (mundo padrão) e somem com TPS abaixo de 15. Ainda precisam de teste no cliente 0.15 (o cliente pode puxar os blocos para baixo entre as atualizações).

**Preços e mensagens no /painel** (GoldCosmetics): `preco.<id>` de cada item da loja (centavos para dinheiro, inteiro para cash), `msg.sem_saldo` e `msg.recarregar` (como o jogador recarrega o cash).

Admin: `/cosmeticos lista`, `/cosmeticos dar <jogador> <id>`.

Desempenho: as trilhas rodam a cada 4 ticks, só para quem se mexeu, e param sozinhas com TPS abaixo de 16. Os dados ficam em cache enquanto o jogador está online, e as gravações de cada jogador entram em fila (liberar, comprar e equipar nunca se atropelam).

Outros plugins liberam recompensas pelo `CosmeticService` (gold-api).
