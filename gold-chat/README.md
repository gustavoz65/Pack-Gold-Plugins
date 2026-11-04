# GoldChat

Chat com formato por grupo, mensagens privadas, anti-spam, anti-link, mute e anúncios automáticos.

## Configurar (`goldchat.staff`)
- `/chat formatos` lista os formatos; `/chat formato <grupo> <formato>` troca (precisa ter `{mensagem}`; aceita `{nome}`, `{tag}`, `{grupo}`, `{mctop}` e os placeholders); `/chat formato <grupo> remover` (o `padrao` só pode ser trocado). Cargo sem formato próprio usa `tag nome: mensagem`.
- `/chat entrada <texto|padrao>` e `/chat saida <texto|padrao>`: mensagens de entrada e saída (`{nome}` vira o nick). Para esconder: painel GoldChat > `entrada_saida` = 0.
- `/chat anuncio lista | adicionar <texto> | remover <n> | intervalo <segundos>`: anúncios automáticos (até 50, de 30 a 3600 s).
- **Filtro de palavrões** (ligado por padrão; painel GoldChat > `filtro` e `filtro.caractere`):
  - Troca a palavra inteira por `*****` no chat e no `/tell`.
  - Pega abreviações (fdp, pqp, vsf, tnc, vtnc, krl, pnc, mcc...), frases ("pau no cu", "vai tomar no cu", "filho da puta"), acento, letra repetida ("porraaa"), número no lugar de letra ("p0rr4"), letras separadas ("f d p", "f.d.p") e frase colada ("paunocu").
  - Compara só palavras inteiras: "computador", "cuidado" e "disputa" passam normalmente.
  - `/chat palavra adicionar <termo>`: acrescenta um termo. Pode ser palavra exata, prefixo com `*` (ex.: `caralh*`) ou frase.
  - `/chat palavra remover <termo>`: também libera palavras da lista padrão.
  - `/chat palavra lista` mostra os termos; `/chat palavra testar <frase>` mostra como a frase fica.
  - Quem tem `goldchat.semfiltro` fala sem filtro (ninguém tem por padrão, nem o OP).
- `/chat on|off`, `/limparchat`, `/mute <jogador> [minutos]`, `/desmute`, `/anuncio <texto>`.


## Do pack de rankup
- `/aviso <texto>`: aviso grande no chat e no meio da tela por alguns segundos (BaconAviso; o 0.15 não tem título).
- Painel GoldChat > `entrada_saida` = 0 esconde as mensagens de entrada e saída (OcultJoinQuit).
