# GoldShop

Placas de compra e venda do servidor, lojas de baú de jogadores, `/vender` pela tabela de preços e a loja do servidor em menu.

## Loja do servidor (`/comprar`)
Menu pensado para o celular: as categorias aparecem com ícone, e tocar num item compra na hora com dinheiro (GoldEconomy).
O menu tem botão de saldo, "Voltar" e "Vender tudo" (vende o que tiver preço no `/preco`).

Montagem pelo console (ou com `goldshop.admin`):
```
comprar categoria blocos 1 &eBlocos          cria/atualiza a categoria (ícone id[:meta] e título)
comprar add blocos 1 64 50                   64 pedras por $50
comprar remover blocos 2                     tira o 2º item (veja comprar lista)
comprar apagar blocos
comprar lista
```
Até 14 categorias e 18 itens por categoria. Preços em reais (`50`, `10,50`).

## Placas
`[comprar]` / `[vender]` (staff) e `[loja]` (jogador, encostada num baú), com item, quantidade e preço nas linhas 2 a 4. Tocar duas vezes confirma.

## /vender
`/vender` vende o item da mão e `/vender tudo` o inventário, pela tabela de `/preco <id[:meta]> <valor|remover>`.
