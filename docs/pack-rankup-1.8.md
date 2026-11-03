# Pack de Rankup 1.8 (Java) → Gold 0.15.10

Análise do pack enviado (31 plugins Bukkit 1.8, sem código-fonte). Cada jar foi decompilado e lido; **nenhum jar do pack roda no servidor**: o que vale a pena foi reescrito na Gold para o MCPE 0.15.10, com as mesmas funções.

## Segurança
| Plugin | Achado | Risco |
|---|---|---|
| **FCEncantar** | `AutoUpdater` com strings ofuscadas: a cada 10 s consulta `http://fcplugins.esy.es/plugins/FCEncantar/v.txt` e, havendo versão nova, **baixa e instala sozinho** um `.jar` desse site (AutoDownload ligado). Quem controlar o domínio (hospedagem gratuita) põe qualquer código no servidor. | **Alto: não usar** |
| TintaScore, aStack | Strings ofuscadas, mas sem rede, sem executar comandos e sem acesso a arquivos fora da pasta. | Baixo |
| VipZero | Consulta a API da CurseForge para ver se há versão nova (não baixa nada). Integrações PagSeguro/PayPal antigas. | Baixo |
| TitleAPI | Consulta `api.connorlinfoot.com` para avisar versão nova a OPs (não baixa). | Baixo |
| yCaixasMisteriosas | Se o autor (`yExtremeDev`) entrar, recebe só uma mensagem de agradecimento. Não dá permissão nem OP. | Nenhum |
| mcMMO, ChestShop | Plugins conhecidos; envio de estatísticas de uso (metrics). | Baixo |
| Demais | Sem rede, sem execução de comandos do sistema, sem backdoor de nick/OP. | Nenhum |

## Estado
- **Fase 1 (pronta):** HUD estilo scoreboard com placeholders, Banco Central, caixas misteriosas, preferências, /luz, /aviso, ocultar entrada e saída, /setloja e /loja, mundo VIP, anti-dupe. A economia passou a gravar uma linha por conta (sem o limite de 10 mil contas).
- **Fase 2 (pronta):** GoldSkills (mcMMO para o 0.15) com boosters, anúncio de nível, top tag `{mctop}` e menu de habilidades. O GoldComandos passou a controlar os outros plugins por zona (`/zona plugin`, `/zona recurso`, `/zona hud`).

## Comparativo
| Plugin 1.8 | O que faz | Na Gold | Decisão |
|---|---|---|---|
| TintaScore, vScore | Scoreboard: grupo, coins, clã, KDR, online, IP | GoldHud (só dinheiro) | **Feito**: HUD de 2 linhas configuráveis com money, cash, grupo, clã, KDR, online (0.15 não tem scoreboard lateral) |
| OBancoCentral | NPC "Bancário": saldo, depósito e saque em conta separada | — | **Feito** (GoldEconomy: `/banco`) |
| yCaixasMisteriosas | Caixas-item com prêmios por chance, raridades, anúncio e raio | GoldCrates (caixas com chave) | **Feito**: caixas misteriosas no GoldCrates |
| OPreferencias | `/config`: bloquear tell, tpa e recebimento de coins | — | **Feito** (GoldEssentials `/config`) |
| reLuz | `/luz` visão noturna VIP | — | **Feito** (GoldEssentials) |
| BaconAviso (+TitleAPI) | `/aviso` com título na tela | `/anuncio` (só chat) | **Feito** `/aviso` no GoldChat (popup + chat; 0.15 não tem título) |
| P227OcultJoinQuit | Esconde entrada/saída | Mensagens fixas | **Feito** (GoldChat, painel `entrada_saida`) |
| T_SetLoja | `/setloja` e `/loja <jogador>` (VIP) com espera | — | **Feito** (GoldEssentials) |
| LRMundoVIPV2 | `/vip` leva ao mundo VIP | — | **Feito** (GoldEssentials `/mundovip`) |
| TintaDupe | Anti-dupe: bloqueia carrinho, barco, funil, moldura em veículo | — | **Feito** (GoldProtect; 0.15 não tem funil) |
| mcMMO PT-BR | Habilidades (mineração, lenhador, escavação, herbalismo, espadas, machado, arqueiro, desarmado, acrobacia, reparação, domesticação...), níveis, poderes, ranking | — | **Feito** (GoldSkills) |
| vBoosterMcMMO, MCBooster | Boosters de XP | — | **Feito** (GoldSkills) |
| DarkmcMMOUP, reStatsUP | Anúncio ao subir de nível | — | **Feito** (GoldSkills) |
| MCTopTag | Tag no chat do 1º do ranking de habilidades | — | **Feito** (GoldSkills) |
| fyStats | Menu de habilidades | — | **Feito** (GoldSkills) |
| fyStatsView | Info do jogador ao passar o mouse no chat (1.8) | — | Não existe no 0.15 (sem hover) |
| BauVirtual | Baú virtual | GoldVault | Já coberto |
| ChestShop | Lojas de baú com placa | GoldShop | Já coberto |
| FCEncantar | Menu de encantar | GoldEnchant | Já coberto (e o original é perigoso) |
| VipZero | VIP com keys e dias | GoldVip + GoldKeys | Já coberto (Pix em vez de PagSeguro/PayPal) |
| T_Tickets | Tickets | GoldStaff `/suporte` com IA | Já coberto (melhor) |
| RushBroadcaster | Mensagens automáticas | GoldChat | Já coberto |
| PTPAJson | `/tpa` com botões clicáveis | GoldEssentials `/tpa` | Já coberto (0.15 não tem chat clicável) |
| RelussionFly | `/voar` | GoldEssentials `/fly` | Já coberto (alias `/voar`) |
| MobStack, aStack | Mobs/itens empilhados | GoldWorld | Já coberto |
| vAntBugCommands, reCustomMessages | Bloqueia comandos bugados, mensagem de comando desconhecido | GoldStaff/GoldComandos | Já coberto |
