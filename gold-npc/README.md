# GoldNPC

NPCs no estilo Slapper das versões novas: humanos com skin ou criaturas, nome em várias linhas com contadores ao vivo, cabeça que segue o jogador e clique que leva a outro mundo com uma transição à escolha ou executa um comando.

## Por que é leve

- **Só pacotes.** O NPC não é uma entidade do mundo: não faz tick, não tem física, não é salvo no chunk. O servidor guarda só os dados e a lista de quem o está vendo.
- **Enviado só para quem está perto** (40 blocos) e só depois que o chunk do NPC chegou ao cliente.
- **Olhar o jogador** é calculado a cada 2 ticks, só para quem está a até 8 blocos, e só envia pacote quando o ângulo muda mais de 3 graus.
- **Contadores** (`{online}`, `{mundo:hg}`) atualizam a cada 2 s e só reenviam o nome quando o texto muda.
- **Skins deduplicadas por SHA-256:** dez NPCs com a mesma skin guardam e carregam uma cópia só. Ficam fora do documento principal no SQLite.
- **Clique:** o filtro do pacote é a primeira linha do listener, e há intervalo de 0,8 s por jogador.

## Comandos (`goldnpc.admin`, padrão operador; alias `/slapper`)

```text
/npc criar humano &6HG|&f{mundo:hg} jogando   cria no seu lugar, com a sua skin, e seleciona
/npc criar villager Loja                      criaturas: villager, zumbi, esqueleto, creeper, golem, bruxa...
/npc selecionar                               depois toque no NPC (pensado para o celular)
/npc selecionar 3                             ou pelo id
/npc destino mundo hg                         spawn seguro do mundo hg
/npc destino aqui                             a sua posição atual
/npc destino servidor hg2                     reservado para o proxy (ainda recusado na 0.15.10)
/npc destino nenhum
/npc transicao espiral                        instantanea, portal, espiral, escurecer, contagem, explosao
/npc comando warp loja                        roda como o jogador que clicou; {jogador} vira o nome dele
/npc comando limpar
/npc nome &aLinha 1|&fLinha 2                 | quebra a linha, & vira cor; até 5 linhas
/npc skin [jogador]   /npc item   /npc mover   /npc olhar sim|nao   /npc tipo <tipo>
/npc lista   /npc info   /npc tp [id]   /npc remover
```

## Transições

| Transição | O que o jogador vê | Duração |
| --- | --- | --- |
| instantanea | Teleporte com som de "pop" | imediato |
| portal | Partículas de portal girando, tela ondulando, som de enderman | 1,5 s |
| espiral | Hélice dupla de chamas e encantamento subindo; anel ao chegar | 1,5 s |
| escurecer | Tela escurece, teleporta no escuro e clareia | 1 s |
| contagem | "Teleportando em 3, 2, 1" com clique; cancela se andar | 3 s |
| explosao | Clarão de explosão (sem dano) e teleporte | 0,1 s |

O comando roda com as permissões de quem clicou, nunca como console, então um NPC não vira atalho para comandos de admin. Jogadores precisam estar autenticados no GoldAuth para usar NPCs.

Limites: 256 NPCs, nomes até 256 caracteres, comandos até 200.

Testado: validação, serialização, nomes e destinos (`NpcTest`), mais compilação contra o core. Falta testar no cliente MCPE real.
