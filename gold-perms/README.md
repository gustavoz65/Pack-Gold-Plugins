# GoldPerms

Grupos persistentes com herança simples, negações explícitas e vínculos por nome normalizado. Substitui a base de grupos do PurePerms sem executar código PHP.

## Cargos da equipe (hierarquia)

Na primeira vez o GoldPerms cria os cargos que faltam, cada um herdando tudo do anterior:

| Cargo | Peso | Tag | O que ganha |
|---|---|---|---|
| ajudante | 20 | `&e[AJUDANTE]` | Atende o `/suporte`, silencia no chat |
| moderador | 40 | `&2[MOD]` | Reports, kick, teleporte, links no chat |
| admin | 60 | `&c[ADMIN]` | Ban/unban, modo de jogo, voo, ferramentas da staff (`goldstaff.admin`) |
| gerente | 80 | `&5[GERENTE]` | Métricas do suporte, `/cargo` (dá cargos abaixo do dele), alertas, warps, kits, VIP |
| dono | 100 | `&4[DONO]` | Tudo, inclusive `/painel` e `/grupo` |

O `vip` existente ganha peso 10 e a tag `&6[VIP]`. Nada que você já configurou é trocado: só os cargos que faltam são criados (`/grupo padroes` cria de novo os que você apagou).

Regras da hierarquia:
- `/cargo <jogador> <cargo> [dias]` só dá cargos **abaixo** do seu e só mexe em quem está abaixo de você (`/cargo remover <jogador>` volta para padrão). Console e OP podem tudo.
- Ninguém pune (`/kick`, `/ban`, `/mute`...) quem tem cargo igual ou acima do seu, nem um OP.
- Comando bloqueado no `/bloquearcmd` pode ser liberado para um cargo: `/grupo permissao moderador goldstaff.cmd.<comando> true`.

Jogadores: `/cargos` mostra a hierarquia, `/cargos <cargo>` lista o que o cargo pode fazer (com a descrição de cada permissão) e `/equipe` mostra quem da equipe está online.
Admin: `/grupo peso <grupo> <0-1000>`, `/grupo tag <grupo> <tag|nenhuma>`, `/grupo staff <grupo> on|off`, `/grupo descricao <grupo> <texto>`.
A tag aparece no chat do GoldChat (`{tag}` nos formatos; cargo sem formato próprio usa `tag nome: mensagem`).

Comandos administrativos (console ou `goldperms.admin`, padrão operador):

```text
/grupo criar vip
/grupo herdar vip padrao
/grupo permissao vip goldessentials.fly true
/grupo permissao vip exemplo.negado false
/grupo permissao vip exemplo.negado remover
/grupo jogador Gustavo vip
/grupo ver Gustavo
/grupo listar
/grupo apagar vip
```

Um grupo referenciado por jogadores ou outros grupos não pode ser apagado; vínculos expirados são descartados a cada gravação e `/grupo jogador <nome> padrao` remove o vínculo. `padrao` sempre existe. A herança rejeita ciclos; permissões do filho prevalecem sobre as do pai. Use nomes exatos de permissões: curingas não são expandidos. Permissões padrão e de operador do Nukkit continuam seguindo as regras do core.

O serviço `PermissionService` permite atribuir expiração absoluta em milissegundos para integração com VIP. Expiração zero significa permanente. Grupos expirados voltam a `padrao` mesmo quando o jogador estava offline. As permissões online são reaplicadas no máximo a cada segundo e após autenticar; o módulo não concede seus vínculos antes do GoldAuth autenticar a sessão.

Alterações são gravadas assincronamente no GoldCore antes da publicação. Uma fila serial impede que duas gravações simultâneas sobrescrevam mudanças umas das outras. Falha de disco mantém o estado anterior. Limites: 128 grupos, 512 permissões por grupo, 10.000 vínculos e 1 MiB por estado. Esta base usa um documento único; bancos maiores exigirão migração de armazenamento.

Testado: herança, negações, expiração, rejeição de ciclos e vínculos inválidos, fila concorrente, falha de escrita, reabertura e API no servidor com SQLite. Fluxo visual no MCPE real pendente.
