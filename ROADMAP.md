# Reescrita dos plugins

Suíte Gradle com um jar por módulo, neste repositório (Pack-Gold-Plugins). O mapa abaixo preserva a decisão de reescrever os plugins em Java; todos os módulos foram implementados (versão atual 0.3.1); falta testá-los com o cliente MCPE real.

| Módulo | Situação | Originais ou responsabilidade |
| --- | --- | --- |
| gold-api | Base inicial | Interfaces dos serviços comuns |
| GoldCore | Implementado (falta cliente real) | SQLite assíncrono, HMAC, viagens com transição e efeitos de partículas prontos; menus em baú virtual prontos; hologramas e cooldowns pendentes; aFloatingText |
| GoldAuth | Implementado (falta cliente real) | ServerAuth, ChatLogin, OpLogin; falta validar cliente real e ampliar configuração |
| GoldPerms | Implementado (falta cliente real) | PurePerms |
| GoldVip | Implementado (falta cliente real) | Key, Vips, VipJoin |
| GoldEconomy | Implementado (falta cliente real) | EconomyAPI, Cash, TCashh, lxp, VenderAll, EconomySellPro, TopMoney |
| GoldShop | Implementado (falta cliente real) | EconomyShop, EconomyPShop, EconomyCShop, ShopI |
| GoldMarket | Implementado (falta cliente real) | AnuncioByReds; leilão no lugar de /olx |
| GoldEssentials | Implementado (falta cliente real) | BasicHomes, essentialsTP, Back, Fly, TPALL, MSpawns, RespawnLobby, ArenaFactions, TpFavela, Vip_and_Loja, IpGold |
| GoldChat | Implementado (falta cliente real) | PureChat, AntiSpam, DesabilitaChat, Limpar_Chat, DesativarTell, TellCustom, AutoMensagem, CustomAlerts, Bloozt_TIP |
| GoldProtect | Implementado (falta cliente real) | WorldProtect/ForceFieldSnipTM/Proteção, iProtector, AreaEfeitos, Ant-Void, anti-XRay |
| GoldFactions | Implementado (falta cliente real): poder com pesos, invasão, mini mapa | FactionsPro, TopFacs |
| GoldPvP | Implementado (falta cliente real) | CombateL, Perfil, QuebrarArmadura, Creeper |
| GoldCrates | Implementado (falta cliente real) | CaixaS, ImpleCaixaS |
| GoldKits | Implementado (falta cliente real) | AdvancedKits |
| GoldVault | Implementado (falta cliente real) | EnderChest |
| GoldBreaker | Implementado (falta cliente real) | BedrockBreaker, ObsidianBreaker, endbreaker, BedRockBreak, /reparar, /enviar |
| GoldEnchant | Implementado (falta cliente real) | Encantar, Mago |
| GoldWorld | Implementado (falta cliente real) | ManyWorlds, AlphaVille, RealTime, MegaFornalha, SilkSpawners, StackingMobs, AutoInv |
| GoldBuild | Implementado (falta cliente real) | MagicWE refeito do zero, BlockSniper, Montanhas |
| GoldNPC | Implementado (falta cliente real) | Slapper e SlapperRotation |
| GoldHG | Implementado (falta cliente real) | HG-PLUS reescrito: 36 kits, feast, bússola, sopa para celular, reset do mapa |
| GoldHud | Implementado (falta cliente real) | superBAR, Cordenadas, itemID, Status Armor |
| GoldStaff | Implementado (falta cliente real) | Report, ajuda, GiveAllPN, CommandBlocker, Restart, ClearLagg, TimeCommander |

DevTools fica fora da suíte por ser ferramenta de empacotamento PHP. GoldScoreboard, GoldDaily, GoldAntiCheat e GoldMenu permanecem opcionais para uma segunda fase.

A sopa para celular está descrita em [gold-hg](gold-hg/README.md).
