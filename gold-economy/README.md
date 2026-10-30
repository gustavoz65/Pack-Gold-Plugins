# GoldEconomy

Base de contas offline, moedas, cash e transferências. O dinheiro usa centavos inteiros para evitar arredondamento de ponto flutuante. Cada transferência grava débito e crédito no mesmo documento SQLite antes de confirmar sucesso; duas solicitações de gasto do mesmo saldo são serializadas.

```text
/money [jogador]
/cash [jogador]
/pagar <jogador> <valor>
/trocarcash <quantidade>
/topmoney
/economia adicionar money Gustavo 100.50
/economia retirar cash Gustavo 2
/economia definir money Gustavo 0,00
```

Administração exige `goldeconomy.admin` (padrão operador). Os demais comandos são públicos, mas jogadores precisam se autenticar quando GoldAuth está ativo. `/trocarcash` troca cada cash por 100 moedas; a operação inteira é persistida junta. Nomes não diferenciam maiúsculas e minúsculas. O destinatário de um pagamento pode estar offline, mas precisa já ter entrado no servidor ou possuir conta; isso impede encher o limite de contas com nomes falsos. Cada jogador tem no máximo uma operação de `/pagar` ou `/trocarcash` pendente. Não existe saldo inicial automático.

Limites: 10.000 contas, 1 MiB para o documento e 1.000.000.000.000 unidades mínimas por moeda. Valores negativos, zero em pagamentos, notação científica, excesso de casas decimais e pagamentos para si mesmo são recusados. O top ordena apenas ao executar o comando; hologramas e cache de ranking ainda serão integrados.

`EconomyService` oferece consulta, crédito, débito e transferência na thread principal com confirmação assíncrona. Não use débito seguido de entrega de item sem um protocolo de recuperação: lojas e leilão precisam tratar o intervalo entre persistência e inventário. Vendas de itens e compra de XP ainda não fazem parte desta base.

Testado: conservação do dinheiro, destinatário no limite, saldo insuficiente, cash, valores malformados, precisão decimal, serialização e duas transferências concorrentes no servidor real com SQLite. Cliente MCPE real pendente.
