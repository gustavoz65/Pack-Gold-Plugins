# GoldCosmetics

`/cosmeticos` (ou o botão no perfil do PvP) abre o menu. O ícone **Como usar** explica: toque num cosmético que você tem para **USAR**, toque de novo no que está **[USANDO]** (ele brilha) para **TIRAR**. Cada categoria tem o botão vermelho **Tirar** e o menu principal tem **Tirar tudo** (ou `/cosmeticos tirar`). Os da loja pedem confirmação; se a gravação falhar, o dinheiro volta.

| Tipo | Onde aparece | Loja | Só no ranked |
|---|---|---|---|
| Pingente (chaveiro 3D) | item com brilho flutuando e girando ao lado do ombro (lobby) | Coração, Estrela, Música, Raio, Caveira, Coroa | um por elo: Ferro (barra de ferro), Bronze (tijolo), Prata (quartzo), Ouro (barra de ouro), Platina (lápis-lazúli), Diamante, Ascendente (esmeralda), Imortal (redstone), Divino (cenoura dourada); 10/50/100 vitórias |
| Trilha | partículas nos pés ao andar | Fumaça, Respingos, Redstone, Encantada, Portal, Corações, Chamas | Divina |
| Efeito de abate | onde a vítima morreu | Corações, Lava, Explosão | Coroa Imortal |
| Cor do nome | nome no chat | Verde, Azul, Rosa, Amarelo, Vermelho | Dourado Divino |
| Montaria 3D | modelo de blocos que segue o jogador (lobby) | **Nuvem Voadora** (paga em **cash**) | - |

O chat mostra só a cor do nome (e o elo, pelo GoldPvP); os pingentes aparecem em 3D.

**Modelos 3D:** a nuvem e os chaveiros são blocos/itens que existem só em pacotes: o servidor não cria entidade, física nem colisão. Vão só para quem está a até 32 blocos, são reenviados apenas quando o dono anda ou gira, aparecem só no lobby (mundo padrão) e somem com TPS abaixo de 15. O cliente 0.15 aplica gravidade em itens e blocos soltos, então cada tick o modelo é reposicionado e a velocidade zerada (no máximo 4 cm de queda entre ticks, suavizada pela interpolação). O chaveiro fica no lado superior direito, na altura da cabeça.

**Preços e mensagens no /painel** (GoldCosmetics): `preco.<id>` de cada item da loja (centavos para dinheiro, inteiro para cash), `msg.sem_saldo` e `msg.recarregar` (como o jogador recarrega o cash).

Admin: `/cosmeticos lista`, `/cosmeticos dar <jogador> <id>`.

Desempenho: as trilhas rodam a cada 4 ticks, só para quem se mexeu, e param sozinhas com TPS abaixo de 16. Os dados ficam em cache enquanto o jogador está online, e as gravações de cada jogador entram em fila (liberar, comprar e equipar nunca se atropelam).

Outros plugins liberam recompensas pelo `CosmeticService` (gold-api).
