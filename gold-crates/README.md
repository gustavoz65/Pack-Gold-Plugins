

## Compra (`/caixas`)
`/caixa preco <caixa> <valor> [money|cash]` põe a caixa à venda (0 tira). Jogadores abrem `/caixas`, tocam na caixa e recebem a caixa misteriosa na hora.
Pelo console: `caixa premioitem <caixa> <peso> <id[:meta]> [qtd]` e `caixa premiocomando <caixa> <peso> <nome> <comando>` (ícone de papel).

## Caixas misteriosas
Como o yCaixasMisteriosas: a caixa é um item (baú com nome) que o jogador abre quando quiser.
- `/caixa misteriosa <jogador> <caixa> [qtd]` entrega a caixa. O item é **assinado**: um baú renomeado na bigorna com o mesmo nome não abre nada.
- Tocar no chão com a caixa abre direto. Com `hotbar.ao_selecionar = 1` no painel do GoldCore, selecionar a caixa na hotbar abre a confirmação (Abrir, Ver prêmios, Cancelar). Ela nunca vira um baú no chão.
- Prêmios de item (`/caixa premio <caixa> <peso>`) ou de comando (`/caixa premiocomando <caixa> <peso> <nome_do_premio> <comando>`, `{jogador}` vira o nick), por exemplo `/caixa premiocomando basica 5 &2$100.000 economia adicionar money {jogador} 100000`.
- Raridade pela chance: até 5% é RARO e até 2,5% é MUITO RARO (com raio só visual), com anúncio para todos. Limites e mensagem no `/painel` (GoldCrates).
- `/caixa nome <caixa> <nome de exibição>` muda o nome mostrado.
