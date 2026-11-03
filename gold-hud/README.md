

## Barra estilo scoreboard
O 0.15 não tem placar lateral, então a barra acima da hotbar faz esse papel, com duas linhas configuráveis no `/painel` (GoldHud > `linha1` e `linha2`).
Placeholders: `{money}` (1.2k, 3.4M), `{money_exato}`, `{cash}`, `{banco}`, `{grupo}`, `{tag}`, `{clan}`, `{clan_tag}`, `{kills}`, `{deaths}`, `{kdr}`, `{online}`, `{max}`, `{x}` `{y}` `{z}`, `{dir}`, `{mundo}`, `{ping}`, `{armadura}`.
Padrão: `&a$ {money} &8| &bCash &f{cash} &8| {tag}` e `&e{x} {y} {z} &7{dir} &8| &7Clan &f{clan} &8| &7KDR &f{kdr}{armadura}`. Use `nenhuma` para esconder uma linha.
Os modelos são compilados uma vez; a cada segundo só os placeholders usados são calculados.
