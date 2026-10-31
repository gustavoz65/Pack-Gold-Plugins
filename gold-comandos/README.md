# GoldComandos

Gerencia os comandos por mundo (como os plugins de command block por mundo), filtra o `/help`, dá boas-vindas e itens por lobby e protege o OP.

## Zonas
Uma **zona** é um grupo de mundos com as mesmas regras (ex.: `pvp`, `minigames`, `lobby`). Cada mundo fica em uma zona só.

- **Modo liberar:** só os comandos da lista funcionam na zona.
- **Modo bloquear:** os comandos da lista não funcionam (o resto funciona).
- **Sempre liberados**, em qualquer zona: login, registrar, 2fa, suporte, finalizar, avaliar, ajuda, help, spawn, lobby, tell, msg, r, equipe, cargos e report (`/zona sempre <comando>` alterna).
- Apelidos e `plugin:comando` também são bloqueados, pelo nome real do comando.
- Staff com `goldcomandos.bypass` (OP por padrão) usa tudo. Para liberar um comando a um cargo: `/grupo permissao <cargo> goldcomandos.cmd.<comando> true`.

`/help` (e `/?`) mostra **só** os comandos que o jogador tem permissão de usar **e** que a zona dele libera, com descrição e páginas (`/help 2`). As linhas extras da zona (`/zona ajuda`) aparecem no topo.

**Boas-vindas:** na primeira vez que o jogador entra na zona (`{nome}` vira o nick), no chat e no meio da tela. `/zona boasvindas_sempre <zona> on` mostra a cada entrada.

**Itens da hotbar:** cada zona pode ter itens que executam comandos (ex.: bússola "Jogos" que abre `/sw entrar solo`). Basta selecionar o item na hotbar, que a seleção volta para o slot anterior. Os itens não caem no chão e somem ao sair da zona. O livro do perfil do ranked continua pelo `/ranked lobby <mundo>` do GoldPvP.

### Exemplo: lobby de PvP
```
/zona criar pvp
/zona mundo pvp lobby_pvp
/zona modo pvp liberar
/zona cmd pvp kit kits perfil ranked topkills
/zona boasvindas pvp &6Bem-vindo ao &cLobby PvP&6, {nome}!
/zona ajuda pvp &7Aqui: /kit, /ranked e /perfil.
/ranked lobby lobby_pvp
```
### Exemplo: minigames
```
/zona criar minigames
/zona mundo minigames lobby_jogos
/zona modo minigames bloquear
/zona cmd minigames fly gm tpa
/zona item minigames 1 345 &eSkyWars sw entrar solo
/zona item minigames 2 355 &cBedWars bw entrar solo
/zona boasvindas minigames &bBem-vindo aos Minigames, {nome}!
```
Outros: `/zona lista`, `/zona ver <zona>`, `/zona apagar <zona>`, `/zona itemremover <zona> <slot>`. A mensagem de bloqueio fica no `/painel`.

## OP protegido
- `/op` e `/deop` **pelo jogo** só funcionam para quem está na lista, e só do IP cadastrado. Pelo console funcionam sempre.
- A lista só é mexida **pelo console**: `opseguro add <nick> <ip>`, `opseguro remover <nick> [ip]`, `opseguro lista`.
- Com a lista preenchida, um OP que entra de um IP fora dela **perde o OP na hora** (aviso para a staff e no Discord). O OP volta sozinho quando ele entra do IP certo. `opseguro exigir off` desliga essa parte.
- Tentativa de `/op` sem permissão também gera alerta.
