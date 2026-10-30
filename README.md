# Pack-Gold-Plugins

Plugins Java da GoldSuite para o [Nukkit Gold](https://github.com/gustavoz65/Nukkit-Gold-Edition) (MCPE 0.15.10, protocolo 84). Este repositório tem só os plugins; o core do servidor fica no repositório do Nukkit Gold.

| Plugin | Função | Comandos principais |
| --- | --- | --- |
| GoldCore | SQLite assíncrono, assinatura de itens, viagens com transição, partículas e menus em baú | (base de todos) |
| GoldAuth | Registro e login, partículas ao entrar | `/registrar`, `/login` |
| GoldPerms | Grupos, herança e expiração | `/grupo` |
| GoldEconomy | Money, cash e ranking | `/money`, `/pagar`, `/topmoney` |
| GoldEssentials | Homes, warps, spawn, tpa, back, fly | `/home`, `/warp`, `/spawn`, `/tpa`, `/back` |
| GoldChat | Formato por grupo, anti-spam, privado, mute | `/tell`, `/r`, `/mute`, `/chat` |
| GoldProtect | Áreas, proteção por mundo, anti-void, alerta de x-ray | `/area`, `/mundoprotecao`, `/antivoid` |
| GoldStaff | Report, ajuda, clearlag, restart, agendamentos | `/report`, `/ajuda`, `/restart`, `/agendar` |
| GoldKits | Kits com recarga | `/kits`, `/kit` |
| GoldVip | Chaves de VIP e anúncio de VIP | `/vip ativar`, `/vip tempo` |
| GoldShop | Placas de compra e venda, lojas de jogador, `/vender` | `[comprar]`, `[vender]`, `[loja]` |
| GoldMarket | Leilão | `/leilao` |
| GoldVault | Baú pessoal | `/ec` |
| GoldHud | Barra com coordenadas, dinheiro e armadura | `/hud`, `/id` |
| GoldPvP | Combat log, perfil detalhado (livro na hotbar), ranked 1v1 com ELO e tiers | `/perfil`, `/ranked`, `/topranked`, `/topkills` |
| GoldCrates | Caixas com chaves assinadas | `/caixa` |
| GoldFactions | Facções com poder (terras, membros, spawners, abates, mortes), invasão de borda, mini mapa no mundo | `/f` |
| GoldEnchant | Encantador pago | `/encantar` |
| GoldBreaker | Obsidiana e bedrock com TNT, reparo, envio | `/reparar`, `/enviar` |
| GoldWorld | Mundos, hora real, fundir, spawners, mobs empilhados | `/mundo`, `/fundir` |
| GoldBuild | Edição de mundo em fatias por tick | `/we` |
| GoldNPC | NPCs estilo Slapper com transições | `/npc` |
| GoldHG | Hunger Games com 36 kits | `/hg`, `/hgkits`, `/sopa` |

Guias detalhados: [GoldPvP](gold-pvp/README.md), [GoldFactions](gold-factions/README.md), [GoldNPC](gold-npc/README.md), [GoldHG](gold-hg/README.md), [GoldAuth](gold-auth/README.md), [GoldPerms](gold-perms/README.md), [GoldEconomy](gold-economy/README.md).

O [roadmap](ROADMAP.md) mostra a origem de cada módulo.

## Compilar

1. Baixe o core usado só para compilar (fica em `lib/`, fora do Git):

```bash
bash scripts/obter-nukkit.sh
```

```powershell
.\scripts\obter-nukkit.ps1
```

2. Compile, rode os testes e gere os jars em `dist/` (`GoldCore-0.2.0.jar`, `GoldAuth-0.2.0.jar`...):

```bash
bash gradlew --offline check dist
```

```powershell
.\gradlew.bat --offline check dist
```

Use um JDK 17 ou 21 (Gradle 8.14.3); as classes saem com alvo Java 8. Para usar outro jar do core: `-PnukkitJar=caminho/do/nukkit-gold.jar`.

## Instalar

Copie os jars de `dist/` para a pasta `plugins/` do servidor e reinicie. O GoldCore é obrigatório para todos os outros. Ele cria `plugins/GoldCore/goldsuite.db` e `plugins/GoldCore/assinatura.key`; a chave precisa acompanhar os dados nos backups, porque trocá-la invalida os itens assinados.

## Viagens e efeitos (GoldCore)

`TravelService` leva um jogador a um mundo, a uma posição ou (futuramente) a outro servidor, com uma transição: `INSTANTANEA`, `PORTAL`, `ESPIRAL`, `ESCURECER`, `CONTAGEM` ou `EXPLOSAO`. Destinos do tipo servidor são recusados com uma mensagem clara: a 0.15.10 não tem TransferPacket, então isso exige um proxy próprio, ainda não feito.

`EffectService` toca partículas com orçamento: só jogadores a até 24 blocos recebem, há no máximo 48 efeitos simultâneos e 320 partículas por tick, e a densidade cai sozinha quando o TPS baixa (abaixo de 18 vai pela metade; abaixo de 12, o mínimo). A tarefa de 1 tick só existe enquanto houver efeitos ou viagens em andamento.

```java
TravelService travel = getServer().getServiceManager().load(TravelService.class);
travel.travel(player, Destination.world("hg"), TravelService.Transition.ESPIRAL)
    .whenComplete((chegou, erro) -> { /* thread principal */ });
```

## Usar os serviços

Os módulos usam `depend: [GoldCore]` e `compileOnly project(':gold-api')`. As interfaces são carregadas pelo GoldCore, evitando cópias conflitantes em cada plugin.

```java
CoreService core = getServer().getServiceManager().load(CoreService.class);
core.whenReady()
    .thenCompose(ignorado -> core.getStore().put("meu_modulo", "chave", "valor"))
    .thenCompose(ignorado -> core.getStore().get("meu_modulo", "chave"))
    .thenAccept(valor -> getLogger().info(valor.orElse("Sem registro")));
```

Chame a API a partir da thread principal. O trabalho de disco/banco roda em `AsyncTask.onRun`; as conclusões normais são entregues em `onCompletion` na thread principal. Uma operação recusada por encerramento ou fila cheia retorna um futuro com falha. Trate os erros com `exceptionally` ou `whenComplete` e confira se seu plugin continua ativo antes de usar Player/Level em callbacks.

A fila serial é limitada a 1.024 operações pendentes e processa lotes de até 64 operações por tarefa. Isso limita a fila e permite entregar resultados durante carga contínua. No desligamento, o GoldCore recusa novas operações e aguarda por até cinco segundos as escritas já enfileiradas. Se esse prazo não bastar, ele registra um aviso; a escrita só está confirmada quando seu futuro conclui com sucesso.

## Itens especiais

`getItemSigner().sign(item, "goldcrates:chave")` devolve uma cópia assinada. `verify(item, finalidade)` verifica uma HMAC-SHA256 sobre ID, metadado e NBT canônico. A quantidade fica fora, para que pilhas assinadas possam ser divididas e juntadas; em itens com durabilidade o desgaste também fica fora, senão uma espada assinada deixaria de valer no primeiro golpe. A comparação é constante em tempo para assinaturas do mesmo tamanho; um nome visual sozinho não autoriza efeitos especiais.

Alterar o ID, o metadado de itens sem durabilidade ou o NBT exige gerar uma nova assinatura no servidor. Quem precisa limitar a quantidade (uma chave por jogador, por exemplo) deve conferir o count no próprio plugin. A assinatura impede adulteração dos dados assinados, mas não identifica cada exemplar como um item único nem substitui a conservação de itens do core. O trânsito desses itens pelo cliente MCPE real ainda precisa ser testado.

## Testes realizados

- Gradle `check` executa `gold-core:regression`, um programa Java de testes, sem JUnit ou downloads de frameworks.
- SQLite real: gravação, reabertura, exclusão, UTF-8 e chave com texto semelhante a SQL injection.
- HMAC: finalidade diferente, quantidade alterada, NBT alterado e chave de outro servidor.
- Plugin carregado no servidor: inicialização assíncrona, registro e remoção do serviço, retorno na thread principal e recusa após desativação.
- Escrita enfileirada imediatamente antes de desativar o GoldCore: valor conferido no banco após o processo do servidor encerrar.

Menus virtuais, hologramas, cooldowns e os demais módulos seguem pendentes no [roadmap](ROADMAP.md).

## Dependência incluída

O [SQLite JDBC 3.54.1.0](https://github.com/xerial/sqlite-jdbc/releases/tag/3.54.1.0) vem em `lib/`, com as licenças originais dentro do jar. O SHA-256 conferido contra o arquivo de checksums oficial é `f8dce420a6016278497f4f0560f65602b9db858de141b541cf91a4644ebe4639`. A [licença do driver](https://github.com/xerial/sqlite-jdbc/blob/3.54.1.0/LICENSE) e os avisos incorporados permanecem preservados no plugin empacotado.
