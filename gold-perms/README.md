# GoldPerms

Grupos persistentes com herança simples, negações explícitas e vínculos por nome normalizado. Substitui a base de grupos do PurePerms sem executar código PHP.

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
