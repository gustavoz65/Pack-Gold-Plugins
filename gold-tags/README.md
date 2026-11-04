# GoldTags

Selecionar sua tag. Todo jogador guarda as tags que já tem e escolhe qual aparece no chat, na barra (`{tag}`) e em tudo que usa a tag. **Trocar de tag não muda cargo nem permissão**: o VIP que escolhe "Jogador" continua VIP.

## Tags de cada jogador
- **Jogador**: todo mundo tem, sempre. É a tag padrão de quem entra (texto no `/painel` > GoldTags > `tag_padrao`).
- **A do cargo** (VIP, ADMIN...): vale enquanto ele tiver o cargo no GoldPerms. Se o VIP vencer e a tag escolhida era a do VIP, ele volta sozinho para a automática.
- **Tags de evento**: a staff cria e entrega, com prazo opcional.

## Jogadores
- `/tag`: mostra "Suas tags: Jogador, VIP (usando: ...)" e abre um menu onde basta tocar na tag (pensado para o celular).
- `/tag <nome>`: usa a tag, ex.: `/tag Jogador` ou `/tag VIP`.
- `/tag automatica`: volta para a tag do cargo.

## Staff (`goldtags.admin`)
```
/tag criar campeao &d[Campeao]
/tag dar Gustavo campeao 30      (dias; sem dias = para sempre)
/tag tirar Gustavo campeao
/tag lista
/tag ver Gustavo
/tag apagar campeao
```

## Como conversa com os outros plugins
- O GoldPerms pergunta ao GoldTags antes de devolver a tag: o `{tag}` do chat (GoldChat), da barra (GoldHud) e de qualquer formato passa a mostrar a escolhida.
- Clã (`«TAG»` do GoldFactions), elo do ranked (GoldPvP) e a tag do top de habilidades (`{mctop}`) continuam do jeito que estão: a tag escolhida só ocupa o lugar da tag de cargo.
- Os formatos de chat devem usar `{tag}`. Os padrões novos já usam: `{mctop}{tag} &7{nome}&8: &f{mensagem}`. Num formato antigo com a tag escrita à mão (ex.: `&6[VIP] {nome}`), quem escolheu outra tag aparece com a escolhida mesmo assim.
- Só os jogadores online ficam em memória; cada mudança grava uma linha no banco do GoldCore.
