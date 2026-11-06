# Pedido do dono: estrutura de lobbies, mapas e modos do servidor Rush MC

Leia antes o `docs/CONTEXTO.md` (regras, arquitetura, testes, hospedagem). Este arquivo descreve **o que** o dono quer.
O dono não está no jogo para dizer onde fica cada coisa: descubra pelos mapas, pelos arquivos e pelo console, e só
pergunte o que não der para descobrir. Trabalhe direto na VPS (configuração do servidor) e no repositório Pack-Gold-Plugins
(código), e envie o código para o GitHub (`main`, commits como gustavoz65, sem assinatura de IA, sem force-push).

## Como trabalhar
1. **Antes de tudo**: backup do servidor pelo painel ou pela API (`POST /api/client/servers/<id>/backups`).
2. **Levantamento** (sem mudar nada):
   - liste os mundos em `worlds/` (e o que cada um parece ser: lobby, arena, mapa vazio);
   - liste as zonas atuais (`zona lista`), as arenas (`sw lista`, `bw lista`, `hg lista`), os NPCs (`npc lista`) e os warps;
   - leia o spawn de cada mundo no `level.dat`.

   Monte um **plano**: qual mundo vira qual lobby, o que falta de mapa e o que falta de código. Mostre ao dono e espere o "ok".
3. Faça **por fases**, nesta ordem: configuração com o que já existe → mapas → código novo. No fim de cada fase, mostre o que mudou.
4. **Código novo segue as regras do CONTEXTO**:
   - `M.t` e mensagens.yml para todo texto, `/painel` para as opções;
   - memória e CPU mínimas, pensado no celular;
   - `gradle check dist` passando e teste novo em `testes-servidor/` passando;
   - as regressões dos plugins tocados passando;
   - README, CHANGELOG e manual atualizados.

   Só então: commit, push, copiar os jars novos para `plugins/` do servidor, `chown`, e pedir autorização ao dono para reiniciar.
5. Se algum comando precisa de um jogador no lugar (ex.: `/npc criar`, `/setwarp`, `/sw criar`), **não espere o dono**:
   - use a versão com coordenadas, se existir (`swmap jaula x y z`, etc.);
   - se não existir, acrescente no plugin uma forma de fazer pelo console com `<mundo> <x> <y> <z> [yaw]`, com teste.
   - Escolha posições lendo o mapa (blocos ao redor do spawn) e mostre as coordenadas no plano.

## Lobbies (cada um é uma zona do GoldComandos, com seus mundos)
| Lobby | O que tem | Observações |
| --- | --- | --- |
| **LobbyRush** (hub, onde todos nascem) | NPCs e itens da hotbar que levam aos outros lobbies | Sem PvP, sem construir, sem livro do perfil |
| **Lobby PvP** | HG, NoDebuff, UHC, Practice, Ranked (1v1) | **Único lugar com o livro do perfil** do GoldPvP |
| **Lobby Minigames** | SkyWars (solo e dupla), BedWars, Party | Sem livro do perfil |
| **Factions** | Lobby próprio + mapa de facções | Sozinho: os comandos de facção só funcionam aqui |
| **Rankup** | Lobby próprio + mundo de plots | Sozinho: plots, economia de rankup, GoldSkills |

Para cada zona:
- `zona modo liberar` com a lista de comandos que fazem sentido ali. Sempre liberados: login, registrar, 2fa, tell, msg, r, suporte, ajuda, spawn, lobby, fps.
- Boas-vindas (`zona boasvindas`), linha de ajuda (`zona ajuda`) e barra própria (`zona hud`).
- Plugins e recursos desligados onde não fazem sentido:
  - `zona plugin`;
  - `zona recurso <zona> goldpvp.perfil` em todas as zonas **menos** a de PvP;
  - `ranked lobby <mundo do lobby pvp>`.
- **Itens da hotbar** (`zona item <zona> <slot> <id> <nome> <comando>`):
  - no LobbyRush, um "seletor de lobby" que abre um menu (ou um item por lobby);
  - em cada lobby, um item "Voltar ao hub".

  Lembre que no 0.15 o item age ao ser **selecionado** na hotbar.
- **NPCs** (GoldNPC):
  - LobbyRush: um por lobby, com o contador de jogadores do mundo (`{mundo:<nome>}`);
  - Lobby PvP: HG, NoDebuff, UHC, Practice, Ranked e pódio do ranking `ranked`;
  - Lobby Minigames: SkyWars solo, SkyWars dupla, BedWars, Party e pódios `skywars`, `bedwars` e `party`.

  Use skins e nomes com cor e quebra de linha (`|`).
- Proteção: `mundoprotecao <mundo> construir|pvp|dano off` em todos os lobbies, e `antivoid` nos lobbies sobre o vazio.
- Teleporte entre lobbies: warps (`setwarp`) ou `npc destino mundo` com transição.

## Mapas
- **SkyWars**: já existem algumas arenas. Confira cada uma com `/swmap` e corrija o que faltar:
  - jaulas, meio, espectador, tipo de baú e time;
  - minérios por ilha (graveto + `/gerar minerios`; com as ilhas marcadas pelas coordenadas, se precisar adicionar um modo console);
  - `swmap salvar` em cada uma.
- **BedWars**: não há mapa.
  - Gere o `bw_quatro` com `ferramentas/arena-builder`, num servidor de trabalho separado e numa porta diferente, nunca no de produção.
  - Copie o mapa para `worlds/` e configure com os comandos do `pontos.txt`.
  - Se der, faça mais um mapa de 8 times.
- **Factions**: não há mapa.
  - Crie um mundo normal (`mundo criar factions normal`) e um lobby/spawn protegido.
  - `f mundo factions`; mini mapa (`f mapa definir`) no lobby de factions.
- **Rankup**: não há mapa. Mundo de plots do GoldPlots (abaixo) + lobby de rankup com a loja, o banco (`/banco`) e as caixas.
- **Party**: o mundo `party` que existe "só tem void".
  - As provas são montadas por código quando uma sala abre (`party entrar`). Confira primeiro: entre numa sala com o teste `tp`, ou `party salas`, `party reconstruir` e o log.
  - O dono quer **um mapa próprio para cada prova** (veja "Código novo").
- **HG**: o mapa `HG` vem no pack. Confira se a arena existe (`hg criar`, spawns, `hg final`, `hg salvar`).

## Código novo
1. **GoldPlots** (plugin novo, para o Rankup):
   - mundo de plots **infinito**: o gerador desenha a grade por coordenada, sem pré-gerar nada;
   - plot e rua com tamanho no painel, borda de laje e piso de grama;
   - comandos: `/plot comprar` (no plot onde está, preço no painel, pago com money do GoldEconomy), `/plot auto` (vai ao plot livre mais perto do centro e compra), `/plot home [n]`, `/plot info`, `/plot add|remover <jogador>`, `/plot limpar`, `/plot menu` (menu para o celular);
   - limite de plots por permissão (`goldplots.limite.<n>`, VIP com mais);
   - só dono e membros constroem, e ninguém constrói na rua.
   - Guarde só os plots comprados (RecordStore por id do plot), nada de mapa inteiro na memória.
   - Testes no `testes-servidor`.
2. **Modos de PvP** (no GoldPvP ou num plugin novo, ex.: GoldPractice), com fila 1v1 casual e ranked por modo, arenas por modo (várias arenas iguais rodando juntas) e menu de filas para o celular:
   - **NoDebuff**: armadura de diamante, espada, poções de cura arremessáveis e comida;
   - **UHC**: armadura completa de diamante, espada e arco de diamante, flechas, blocos e **32 maçãs douradas normais**; vida não regenera sozinha;
   - **Practice**: kit simples, mas o **knockback é diferente**, para conseguir combar. Knockback horizontal e vertical e intervalo entre hits configuráveis por modo no painel.
   - O HG continua sendo o GoldHG.
3. **Party com mapa por prova**: cada uma das 10 provas com o seu cenário próprio, temático. Pode ser um mapa salvo por prova ou um cenário gerado por código. Mantenha a montagem limitada por tick (`blocos_por_tick`) e várias salas ao mesmo tempo.
4. **Separação por lobby** (pendência 5 do CONTEXTO): chat, entrada e saída, anúncios e mortes só para quem está no mesmo lobby; staff com "ver tudo".

## Entrega
- Ao terminar cada fase:
  - lista do que foi configurado (comandos usados) e do que foi programado;
  - testes que passaram;
  - o que ficou pendente e por quê.
- Atualize `docs/CONTEXTO.md` (pendências), `CHANGELOG.md` e o manual. Commit e push na `main`.
