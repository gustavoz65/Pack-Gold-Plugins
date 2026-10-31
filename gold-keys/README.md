# GoldKeys

Keys de liberação: um código único entrega cash, dinheiro, cosméticos e/ou VIP.

## Segurança
- Código `GOLD-XXXXX-XXXXX-XXXXX-XXXXX`: cerca de 100 bits do `SecureRandom` (impossível adivinhar ou repetir).
- O banco guarda só o **SHA-256** do código: quem copiar o banco não consegue resgatar nada.
- Ciclo: **PENDENTE** (pedido) → **PAGA** (staff confirmou; vale `validade_horas`) → **RESGATADA** (uma única vez). Pedido sem pagamento é cancelado depois de `pedido_horas`.
- O resgate grava "RESGATADA" antes de entregar e trava o código durante o processo: nunca entrega duas vezes.
- Key de pedido só pode ser resgatada por quem pediu; keys geradas pela staff (presente) valem para qualquer um.
- 5 códigos inválidos em 1 minuto bloqueiam novas tentativas.

## Jogador
`/key produtos`, `/key comprar <produto>` (recebe o pedido e a key, que só vale depois do pagamento), `/key resgatar <codigo>`.

## Staff (`goldkeys.admin`)
- `/key produto criar <id> <preco> <nome>` e `/key produto <id> cash <n> | dinheiro <valor> | cosmetico <id> | vip <grupo> <dias> | remover`
- `/key pedidos`, `/key pago <pedido>` (confirma o Pix), `/key cancelar <pedido>`
- `/key gerar <produto> [qtd]`: keys de presente, os códigos aparecem só para quem gerou.

Cada pedido, confirmação e resgate vai para a staff online e para o Discord (`/painel discord <webhook>`).

## Painel (`/painel` > GoldKeys)
`validade_horas`, `pedido_horas`, `pedidos_por_jogador`, `msg.pix` (instruções de pagamento: chave Pix, link do Discord).

## Próximo passo
Pagamento automático: quando o provedor do Pix for escolhido (ex.: Mercado Pago), o webhook dele confirma o pedido sozinho, sem `/key pago`.
