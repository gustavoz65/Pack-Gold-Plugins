# Testes de servidor (jogadores falsos)

Regra do projeto: nenhuma mudança é entregue sem passar num servidor de verdade com jogadores simulados.
Cada pasta é um plugin de teste: ele sobe um Nukkit Gold limpo com todos os jars de `dist/`, cria jogadores falsos
(`SourceInterface` de mentira), executa os passos e escreve `XXX_OK` ou `XXX_FALHOU [motivos]` no log.

## Rodar
```bash
cd Pack-Gold-Plugins && gradle -q --offline check dist   # (ou ./gradlew check dist)
cd testes-servidor
NUKKIT_JAR=/caminho/nukkit-gold-0.15.10.jar ./run-one.sh sm TesteSkyMythic
```
- O servidor de teste fica em `work/srv-<pasta>/` (log em `log.txt`). Ele usa a porta 19132: **nunca rode na mesma
  máquina e porta de um servidor em produção ligado** sem trocar a porta (edite `server.properties` gerado ou rode em outra pasta
  com `server-port` diferente).
- `KEEP_AUTH=1` mantém o GoldAuth (só o `al/TesteLoginLobby` precisa).
- `WORLDS="<pasta>/sw_ilhas <pasta>/bw_quatro"` copia mapas. Os mapas são gerados pelo `ferramentas/arena-builder`.

| Pasta | Classe | Precisa de |
| --- | --- | --- |
| ts | TesteSuporte | — |
| tc | TesteComandos | — |
| tp | TesteParty | — (demora ~5 min) |
| td | TesteDevice | — |
| tr | TesteRankup | — |
| tk | TesteSkills | — |
| tm | TesteMensagens | — |
| tw | TesteBuild2 | — |
| tt | TesteTags | WORLDS sw_ilhas bw_quatro |
| gt | TesteJogos | WORLDS sw_ilhas bw_quatro |
| sm | TesteSkyMythic | WORLDS sw_ilhas |
| pu | TestePunicoes | — |
| al | TesteLoginLobby | KEEP_AUTH=1 |
| mn | TesteMenus | — (clica pelo pacote real do cliente) |
| dump | Dump | gera `dump.json` (comandos, permissões, painel) para o manual: copie `work/srv-dump/dump.json` para `docs/manual/` |

## Pegadinhas do harness
- `Server.getOnlinePlayers()` devolve uma **cópia**: para o jogador falso ficar online, coloque-o em `Server.playerList` por reflexão (veja `pu/TestePunicoes`).
- O texto capturado vem sem cores (`TextFormat.clean`). O chat tem anti-spam de 1,5 s entre falas.
- `menuClick` chama o `onClick` direto; para testar o caminho real do celular use o `ContainerSetSlotPacket` como em `mn/TesteMenus`.
- `Player.getDeviceFingerprint()` usa `uuid` (campo de `EntityHuman`) + `randomClientId`: preencha os dois no jogador falso.
- Nada de `.get()` bloqueante na thread principal: as respostas do banco (`AsyncStore`) chegam na thread principal e travariam.
