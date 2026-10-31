# GoldStaff

Suporte com triagem por IA, métricas da equipe, denúncias, ajuda, bloqueio de comandos, restart, clearlag e comandos agendados.

## /suporte (tickets)

1. O jogador digita `/suporte` (ou `/suporte <mensagem>`). O chat dele é limpo e passa a ser **só do atendimento**: ele não vê mais o chat do servidor nem anúncios. As respostas dos comandos que ele digita continuam chegando.
2. **Triagem com a IA da Groq:** o assistente faz até 2 perguntas curtas (configurável) (o que aconteceu, onde, quem estava envolvido) e escreve um resumo com categoria (bug, denúncia, compra, conta, punição, dúvida, outro) e prioridade. Se for só uma dúvida sobre os comandos do `/ajuda` e o jogador confirmar que resolveu, o ticket fecha sozinho sem chamar a equipe. O assistente nunca promete devolução, desban ou cargos e nunca pede senha.
3. A equipe online (todo cargo com `goldstaff.suporte`, do ajudante para cima) recebe um bloco no chat:
   ```
   ----- SUPORTE #12 -----
   Jogador: ana | Categoria: bug | Prioridade: alta
   Resumo: Ana caiu no void no SkyWars por bug e perdeu os itens.
   Digite /atender 12 para atender
   ```
   Enquanto houver ticket na fila, a equipe é lembrada a cada 2 minutos e ao entrar no servidor.
4. `/atender 12`: o chat do staff e o do jogador são limpos e os dois **só veem um ao outro**, como uma party. O staff recebe o resumo da IA e as últimas mensagens do jogador.
5. `/finalizar [resultado]` (o staff ou o jogador) encerra, solta os dois do chat isolado e pede a nota (`/avaliar 1 a 5`).

Enquanto espera na fila, o jogador pode digitar (fica anotado no ticket), usar `/suporte sair` para voltar ao chat normal sem perder a vez, ou `/suporte cancelar`.
Se o staff sair do servidor no meio, o ticket volta para a fila e é anunciado de novo. Se o jogador sair, o atendimento é finalizado. Se ele sair enquanto espera e não voltar em 15 min, o ticket fecha.
Sem chave da Groq, ou com a IA fora do ar, o pedido vai direto para a equipe com o que o jogador escreveu.

**Equipe:** `/tickets` (fila), `/tickets ver <n>` (resumo, quem atendeu, nota e as últimas 25 mensagens), `/tickets fechar <n> [motivo]`.
**Gerente e dono** (`goldstaff.metricas`): `/tickets metricas [dias]` mostra, por membro da equipe, quantos atendimentos fez no período e no total, a espera média até ser atendido, a duração média e a nota média. Também mostra quantos tickets o assistente resolveu sozinho.

**Painel** (`/painel` > GoldStaff):
- `groq_chave`: a chave da API (`gsk_...`). Aparece mascarada no painel. Use `padrao` para apagar.
- `groq_modelo`: o modelo da Groq (padrão `openai/gpt-oss-20b`, pequeno e barato).
- `triagem_perguntas`: quantas perguntas a IA pode fazer (1 a 6).
- `suporte_espera`: segundos entre um ticket e outro do mesmo jogador.
- `discord_tickets`: `1` manda o resumo de cada atendimento finalizado para o webhook do Discord do painel.
- `suporte_prefixo`: prefixo das mensagens.

A IA roda fora da thread principal, com fila limitada e tempo máximo de resposta: o servidor nunca trava esperando a Groq.

## Outras ferramentas
- `/report <jogador> <motivo>` e `/reports [fechar <id>]` (`goldstaff.reports`).
- `/ajuda` (texto editável), `/giveall`, `/clearlag`, `/agendar`, `/restart [segundos]`, `/restart auto <horas>`.
- `/bloquearcmd <comando>` bloqueia um comando para quem não é staff. Para liberar a um cargo: `/grupo permissao <cargo> goldstaff.cmd.<comando> true`.
- Ninguém consegue punir (`/kick`, `/ban`, `/mute`...) quem tem cargo igual ou acima do seu (veja os cargos no GoldPerms).
