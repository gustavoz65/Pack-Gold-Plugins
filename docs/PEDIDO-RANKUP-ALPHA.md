# Pedido do dono: Rankup na primeira alpha

Leia antes `docs/CONTEXTO.md` (regras, arquitetura, testes, hospedagem) e `docs/pack-rankup-1.8.md` (o que já foi trazido do pack 1.8).

**Alpha** = um jogador novo consegue jogar o ciclo completo sozinho, no celular, sem ajuda da staff, por pelo menos 30 minutos:
1. entra no lobby do Rankup;
2. vai para a mina do seu rank, minera e vende;
3. sobe de rank e libera a mina seguinte;
4. compra um plot e constrói nele;
5. sai e volta, e tudo continua lá.

Nada de bug que trave esse ciclo, nada de lag, e as limitações conhecidas ficam listadas.

## O que já existe (reaproveite, não refaça)
- **GoldEconomy**: money, cash e `/banco`.
- **GoldShop**: `/comprar` (loja em menu) e `/vender`, com a tabela de venda.
- **GoldCrates**: `/caixas`.
- **GoldMarket**: `/leilao`.
- **GoldSkills**: XP de mineração e outras habilidades, boosters, `/mctop`.
- **GoldKits**: kits por permissão.
- **GoldEnchant**: encantar.
- **GoldHud**: barra com placeholders.
- **GoldEssentials**: homes, warps, `/setloja`, mundo VIP, `/fly` VIP.
- **GoldVip** e **GoldKeys**.
- **GoldProtect**: áreas e proteção por mundo.
- **GoldComandos**: zona `rankup` com comandos liberados, itens da hotbar, HUD própria.
- **GoldNPC**: NPCs criados pelo console com coordenadas.
- **GoldChat**: formatos com `{tag}` e placeholders.

## O que falta (código novo)
### 1. GoldRankup (plugin novo): a escada de ranks
- Lista de ranks editável no painel ou num arquivo de config recarregável. Cada rank tem:
  - id e nome com cor (ex.: `&7[Pedra]`);
  - preço em money;
  - permissões que o rank dá (ex.: `goldminas.mina.ferro`, `goldkits.kit.ferro`, limite de plots);
  - a mina que libera.
- Sugestão inicial: uns 20 ranks em grupos (Pedra, Carvão, Ferro, Ouro, Lápis, Redstone, Diamante, Esmeralda, Obsidiana, Lendário), com custo crescente.
- `/rankup`: menu de confirmação mostrando o rank atual, o próximo, o preço e o saldo. Confirmar desconta e sobe.
  - Valida e grava numa transação só (sem cobrar duas vezes nem subir sem pagar).
  - Anúncio no chat, com efeito que respeita o `/fps`.
- `/ranks`: menu com todos os ranks, o atual destacado e o progresso em `%` até o próximo.
- Placeholders `{rank}`, `{rank_proximo}`, `{rank_preco}` e `{rank_progresso}`, para o chat (formato da zona rankup) e o HUD.
- Rank guardado por jogador num `RecordStore` lazy (só os online na memória).
- As permissões entram por anexo do próprio plugin ou pelo `PermissionService`. **Nunca** dê OP nem cargo de staff.
- Admin: `/rankadmin set <jogador> <rank>` e `/rankadmin reset <jogador>`. A permissão é só de OP.
- Prestígio fica para depois da alpha. Deixe o modelo pronto para ele.

### 2. Minas que se regeneram (no GoldRankup ou num plugin próprio, ex.: GoldMinas)
- Mina = área (cubo) num mundo `minas`, com a **composição** de blocos em % (ex.: 60% pedra, 25% carvão, 15% ferro) e o rank mínimo para entrar.
- **Regeneração**:
  - a cada N minutos **ou** quando X% foi minerado, o que vier primeiro;
  - aviso 10 s antes;
  - quem está dentro vai para o topo da mina;
  - os blocos são repostos **limitados por tick** (ex.: 2.000/tick no total) e só em chunks carregados;
  - sem varrer o mundo inteiro.
- Dentro da mina só se quebram os blocos da mina: nada de colocar bloco, nem quebrar a borda ou o teto. Fora das minas, o mundo é protegido.
- `/minas`: menu com as minas, as liberadas (pelo rank) e as bloqueadas mostrando o rank necessário. Tocar leva à mina.
- O NPC "Minas" do lobby abre esse menu.
- Admin: `/mina criar <nome>` (pelos pontos 1 e 2 ou por coordenadas no console), `/mina composicao`, `/mina rank`, `/mina tempo` e `/mina resetar`.
- Mapa das minas:
  - use as minas do pack de referência, se servirem (veja abaixo);
  - senão, gere um mundo `minas` com uma mina por grupo de rank, num pátio com o caminho entre elas;
  - mostre o desenho de cima antes de aplicar.
- O GoldSkills continua dando XP de mineração nos blocos da mina. Eles são gerados, não colocados por jogador, então o anti-farm não pode bloquear.

### 3. GoldPlots (plugin novo): terrenos infinitos
- Mundo de plots **infinito**: o gerador desenha a grade pela coordenada, sem pré-gerar.
  - Tamanho do plot, largura e blocos da rua, borda e piso no painel.
  - **Use as medidas da pasta `plotworld`/`terrenos` do pack de referência** e mostre antes de programar.
- Comandos:
  - `/plot comprar`: o plot onde o jogador está; preço no painel, pago em money;
  - `/plot auto`: o plot livre mais perto do centro, já comprado;
  - `/plot home [n]` e `/plot info`;
  - `/plot add|remover <jogador>`: membros;
  - `/plot limpar`: reposição limitada por tick;
  - `/plot menu`: tudo isso em menu, pensado para o celular.
- Limite de plots por rank e por VIP (`goldplots.limite.<n>`).
- Só o dono e os membros constroem. Ninguém constrói na rua. Sem PvP no mundo de plots.
- Só os plots comprados ficam guardados (`RecordStore` por id do plot). Nada de mapa inteiro na memória.
- O NPC "Plots" do lobby abre o `/plot menu`.

### 4. Ajustes nos plugins existentes
- **Kits por rank**: o GoldKits já usa permissão. Crie kits de ferramentas por grupo de rank, com recarga.
- **Tabela de venda** dos blocos das minas no GoldShop, coerente com o preço dos ranks.
- **Picareta**: o GoldEnchant precisa funcionar com eficiência e fortuna nas minas.
- **Zona `rankup`** no GoldComandos:
  - comandos liberados (rankup, ranks, minas, plot, vender, comprar, caixas, banco, leilao, kit, home, warp, tpa, mctop e os sempre liberados);
  - HUD (`{rank}`, `{money}`, `{rank_progresso}`);
  - hotbar: menu do Rankup, Minas e Plots;
  - boas-vindas.

  O livro do PvP, as facções e o SkyWars ficam desligados nessa zona.
- **Formato do chat** da zona rankup com `{rank}`.

## Economia (proponha os números antes de aplicar)
Monte uma tabela com:
- o money inicial;
- o preço de venda de cada bloco das minas;
- quanto um jogador ganha por minuto em cada mina, com e sem picareta encantada;
- o preço de cada rank;
- o preço do plot e das caixas.

Mire em: primeiro rank em ~10 minutos de jogo, metade dos ranks em ~5 horas e o último em ~20 horas. Mostre a tabela ao dono antes de configurar. Tudo fica ajustável no painel.

## Pack de referência (/root/plotworld.zip, do Minecraft Java)
- Extraia em `/root/referencias/plotworld/`. **Não execute nada** de lá (jar, sh, bat); só leia os dados.
- Veja a versão de cada mapa (DataVersion no `level.dat`):
  - até a 1.12: converta como os mapas de SkyWars (conversor já feito);
  - 1.13 em diante: formato com paleta por nome, que precisa de conversão de verdade.
- Liste o que serve para:
  - o lobby do Rankup (o atual é a cópia do `mapas/Spawn`);
  - as minas;
  - o mundo de plots (medidas).

  Para cada um, diga se tem placa ou nome de outro servidor. Não copie nada para `worlds/` sem aprovação.

## Lobby do Rankup
- NPCs:
  - Minas e Plots (hoje mostram "Em breve");
  - Rankup/Ranks;
  - Loja, Caixas, Banco e Leilão;
  - pódio de money e de `mctop`, se houver ranking registrado.

  Mostre as coordenadas antes de criar.
- Converta o vidro do PC (bloco 95, que no 0.15 vira bedrock invisível) no RankupLobby.
- `antivoid`, proteção total e sem fome (já existe "fome por mundo" no GoldProtect).

## Testes obrigatórios (testes-servidor)
Um teste novo, `TesteRankupAlpha`, cobrindo:
- **o ciclo completo**: jogador novo → minas → minerar → vender → `/rankup` → mina nova liberada → `/plot auto` → construir no plot → outro jogador não constrói lá → sair e voltar com rank e plot mantidos;
- **segurança**: rankup sem dinheiro recusa; dois toques rápidos não cobram duas vezes; mina bloqueada para rank baixo; não dá para colocar bloco na mina;
- **regeneração da mina**: dispara por tempo e por %, respeita o limite por tick e tira quem está dentro;
- **plots**: dois jogadores comprando o mesmo plot ao mesmo tempo, só um leva; `/plot limpar` repõe; limite de plots respeitado;
- **regressões** dos plugins tocados: as do GoldShop, GoldKits, GoldComandos, GoldSkills e GoldEconomy.

Antes de tudo, investigue as 2 falhas do `TesteSkyMythic`: na versão `cec12b5` ele passava inteiro.

## Ordem e entregas
1. **Levantamento**:
   - pack de referência;
   - medidas dos plots;
   - proposta de mapas (lobby, minas, plots);
   - tabela de economia.

   **Espere o "ok".**
2. GoldRankup (ranks), com teste.
3. Minas, com teste.
4. GoldPlots, com teste.
5. Configuração no servidor:
   - zona, HUD, chat, kits, tabela de venda, NPCs;
   - backup antes;
   - jars;
   - chown;
   - pedir para reiniciar.
6. Relatório final da alpha: o que funciona, o que foi testado, limitações e próximos passos.

Atualize `CHANGELOG.md`, os READMEs, o manual (`docs/manual/conteudo.py` + PDF) e `docs/CONTEXTO.md`. Commit e push na `main` a cada etapa (autor gustavoz65, sem assinatura de IA, sem force-push).
