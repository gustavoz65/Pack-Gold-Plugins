# Reescrita dos plugins

Suíte Gradle com um jar por módulo, neste repositório (Pack-Gold-Plugins). O mapa abaixo preserva a decisão de reescrever os plugins em Java; os nomes indicam o escopo futuro, não funcionalidades já disponíveis.

| Módulo | Situação | Originais ou responsabilidade |
| --- | --- | --- |
| gold-api | Base inicial | Interfaces dos serviços comuns |
| GoldCore | Parcial | SQLite assíncrono, HMAC, viagens com transição e efeitos de partículas prontos; menus, hologramas e cooldowns pendentes; aFloatingText |
| GoldAuth | Inicial implementado e testado com jogadores simulados | ServerAuth, ChatLogin, OpLogin; falta validar cliente real e ampliar configuração |
| GoldPerms | Implementado | PurePerms |
| GoldVip | Pendente | Key, Vips, VipJoin |
| GoldEconomy | Base implementada (contas, cash, pagar, top) | EconomyAPI, Cash, TCashh, lxp, VenderAll, EconomySellPro, TopMoney |
| GoldShop | Pendente | EconomyShop, EconomyPShop, EconomyCShop, ShopI |
| GoldMarket | Pendente | AnuncioByReds; leilão no lugar de /olx |
| GoldEssentials | Pendente | BasicHomes, essentialsTP, Back, Fly, TPALL, MSpawns, RespawnLobby, ArenaFactions, TpFavela, Vip_and_Loja, IpGold |
| GoldChat | Pendente | PureChat, AntiSpam, DesabilitaChat, Limpar_Chat, DesativarTell, TellCustom, AutoMensagem, CustomAlerts, Bloozt_TIP |
| GoldProtect | Pendente | WorldProtect/ForceFieldSnipTM/Proteção, iProtector, AreaEfeitos, Ant-Void, anti-XRay |
| GoldFactions | Pendente | FactionsPro, TopFacs |
| GoldPvP | Pendente | CombateL, Perfil, QuebrarArmadura, Creeper |
| GoldCrates | Pendente | CaixaS, ImpleCaixaS |
| GoldKits | Pendente | AdvancedKits |
| GoldVault | Pendente | EnderChest |
| GoldBreaker | Pendente | BedrockBreaker, ObsidianBreaker, endbreaker, BedRockBreak, /reparar, /enviar |
| GoldEnchant | Pendente | Encantar, Mago |
| GoldWorld | Pendente | ManyWorlds, AlphaVille, RealTime, MegaFornalha, SilkSpawners, StackingMobs, AutoInv |
| GoldBuild | Pendente | MagicWE refeito do zero, BlockSniper, Montanhas |
| GoldNPC | Implementado (falta cliente real) | Slapper e SlapperRotation |
| GoldHG | Próximo | Hunger Games em mundo separado, entrada pelo GoldNPC; sopa com UX para celular (ver abaixo) |
| GoldHud | Pendente | superBAR, Cordenadas, itemID, Status Armor |
| GoldStaff | Pendente | Report, ajuda, GiveAllPN, CommandBlocker, Restart, ClearLagg, TimeCommander |

DevTools fica fora da suíte por ser ferramenta de empacotamento PHP. GoldScoreboard, GoldDaily, GoldAntiCheat e GoldMenu permanecem opcionais para uma segunda fase.

## GoldHG: sopa no celular

No HG clássico a sopa cura na hora ao usar o item. No celular isso é ruim: tocar no ar nem sempre dispara o uso, e trocar de slot e tocar de novo é lento. Plano:

- **Tocar em qualquer lugar:** a sopa funciona tocando no ar, num bloco ou até num jogador, sem segurar.
- **Modo "selecionar e tomar"** (opcional por jogador, `/sopa modo`): tocar no slot da sopa na hotbar já cura, se faltar vida. É o jeito mais rápido no touch.
- **Tigela some sozinha** (ou vai para um slot fixo), para a hotbar não encher de tigelas.
- **Não gasta sopa com vida cheia**, e um som/partícula curto confirma a cura.
- **Validação no servidor:** no máximo uma sopa a cada 3 ticks por jogador, então macro não dá vantagem e quem joga no touch não fica atrás.
