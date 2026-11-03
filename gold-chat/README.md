# GoldChat

Chat com formato por grupo, mensagens privadas, anti-spam, anti-link, mute e anúncios automáticos.

## Configurar (`goldchat.staff`)
- `/chat formatos` lista os formatos; `/chat formato <grupo> <formato>` troca (precisa ter `{mensagem}`; aceita `{nome}`, `{tag}`, `{grupo}`, `{mctop}` e os placeholders); `/chat formato <grupo> remover` (o `padrao` só pode ser trocado). Cargo sem formato próprio usa `tag nome: mensagem`.
- `/chat entrada <texto|padrao>` e `/chat saida <texto|padrao>`: mensagens de entrada e saída (`{nome}` vira o nick). Para esconder: painel GoldChat > `entrada_saida` = 0.
- `/chat anuncio lista | adicionar <texto> | remover <n> | intervalo <segundos>`: anúncios automáticos (até 50, de 30 a 3600 s).
- `/chat on|off`, `/limparchat`, `/mute <jogador> [minutos]`, `/desmute`, `/anuncio <texto>`.


## Do pack de rankup
- `/aviso <texto>`: aviso grande no chat e no meio da tela por alguns segundos (BaconAviso; o 0.15 não tem título).
- Painel GoldChat > `entrada_saida` = 0 esconde as mensagens de entrada e saída (OcultJoinQuit).
