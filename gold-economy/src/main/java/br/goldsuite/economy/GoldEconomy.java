package br.goldsuite.economy;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.plugin.PluginBase;
import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class GoldEconomy extends PluginBase implements EconomyService {
    private AccountStore data;
    /** Jogadores com operacao pendente; impede que um cliente lote a fila de gravacao. */
    private final Set<String> busy = new HashSet<>();
    @Override public void onEnable() {
        M.B.attach(this);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo_antes_do")); }
        data = new AccountStore(core.getStore(), msg -> getLogger().info(msg));
        getServer().getScheduler().scheduleRepeatingTask(new cn.nukkit.scheduler.PluginTask<GoldEconomy>(this) { @Override public void onRun(int tick) { data.flush(); } }, 20);
        Settings.text(this, "banco.valores", "5000,25000,50000,100000,500000", "Valores dos botoes de deposito e saque do /banco (ate 5, separados por virgula)", v -> {
            List<Long> list = new ArrayList<>();
            for (String part : v.split("[,;\\s]+")) { try { long n = Long.parseLong(part.trim()); if (n > 0 && n <= Ledger.MAX_BALANCE / 100 && list.size() < 5) { list.add(n); } } catch (NumberFormatException ignored) { } }
            if (!list.isEmpty()) { bankValues = list; }
        });
        data.ready().whenComplete((unused, error) -> {
            if (!isEnabled()) { return; }
            if (error != null) { getLogger().error(M.t("falha_ao_carregar_economia"), error); getServer().getPluginManager().disablePlugin(this); }
            else {
                getLogger().info(data.size() + " conta(s) carregada(s).");
                getServer().getServiceManager().register(EconomyService.class, this, this);
                RankingService ranking = getServer().getServiceManager().load(RankingService.class);
                if (ranking != null) { ranking.register("money", this::topMoney); }
                Settings.placeholder(this, "money", p -> PlaceholderService.compact(balance(p.getName(), Currency.MONEY) / 100));
                Settings.placeholder(this, "money_exato", p -> java.math.BigDecimal.valueOf(balance(p.getName(), Currency.MONEY), 2).toPlainString());
                Settings.placeholder(this, "cash", p -> PlaceholderService.compact(balance(p.getName(), Currency.CASH)));
                Settings.placeholder(this, "banco", p -> PlaceholderService.compact(data.read(st -> st.bank(p.getName())) / 100));
            }
        });
    }
    private List<Long> bankValues = Arrays.asList(5000L, 25000L, 50000L, 100000L, 500000L);

    private static cn.nukkit.item.Item icon(int id, int meta, String name) { cn.nukkit.item.Item i = cn.nukkit.item.Item.get(id, meta, 1); i.setCustomName(name); return i; }

    /** Banco Central (como o OBancoCentral): saldo, deposito e saque em conta separada da carteira. */
    void openBank(Player p) {
        MenuService menus = getServer().getServiceManager().load(MenuService.class);
        if (menus == null) { return; }
        long wallet = balance(p.getName(), Currency.MONEY), bank = data.read(s -> s.bank(p.getName()));
        Map<Integer, cn.nukkit.item.Item> icons = new HashMap<>();
        icons.put(4, icon(340, 0, M.t("saldo_carteira_banco", Ledger.format(wallet, Currency.MONEY), Ledger.format(bank, Currency.MONEY))));
        for (int i = 0; i < bankValues.size(); i++) {
            long v = bankValues.get(i);
            icons.put(10 + i, icon(339, 0, M.t("depositar_carteira_banco", PlaceholderService.compact(v))));
            icons.put(19 + i, icon(339, 0, M.t("sacar_banco_carteira", PlaceholderService.compact(v))));
        }
        icons.put(16, icon(266, 0, M.t("depositar_tudo", Ledger.format(wallet, Currency.MONEY))));
        icons.put(25, icon(266, 0, M.t("sacar_tudo", Ledger.format(bank, Currency.MONEY))));
        menus.open(p, M.t("banco_central"), icons, (who, slot) -> {
            long amount; boolean deposit;
            if (slot >= 10 && slot < 10 + bankValues.size()) { amount = bankValues.get(slot - 10) * 100; deposit = true; }
            else if (slot >= 19 && slot < 19 + bankValues.size()) { amount = bankValues.get(slot - 19) * 100; deposit = false; }
            else if (slot == 16) { amount = balance(who.getName(), Currency.MONEY); deposit = true; }
            else if (slot == 25) { amount = data.read(s -> s.bank(who.getName())); deposit = false; }
            else { return; }
            bankMove(who, amount, deposit, true);
        });
    }

    void bankMove(Player p, long cents, boolean deposit, boolean reopen) {
        if (cents <= 0) { p.sendMessage(M.t("nada_para", (deposit ? M.t("depositar") : M.t("sacar")))); return; }
        CompletableFuture<Void> op = guarded(p, () -> data.change(s -> { if (deposit) { s.deposit(p.getName(), cents); } else { s.withdraw(p.getName(), cents); } }));
        if (op == null) { p.sendMessage(M.t("aguarde_operacao_anterior_terminar")); return; }
        op.whenComplete((v, e) -> getServer().getScheduler().scheduleTask(() -> {
            if (!isEnabled() || !p.isOnline()) { return; }
            p.sendMessage(e != null ? "\u00a7c" + message(e) : (deposit ? M.t("voce_depositou") : M.t("voce_sacou")) + Ledger.format(cents, Currency.MONEY) + (deposit ? M.t("no_banco") : M.t("do_banco")));
            if (reopen && e == null) { openBank(p); }
        }));
    }

    @Override public void onDisable() { if (data != null) { data.close(); } busy.clear(); }
    @Override public long balance(String player, Currency currency) { Objects.requireNonNull(currency); return data.read(s -> s.balance(player, currency)); }
    @Override public CompletableFuture<Void> credit(String player, Currency currency, long amount) { Objects.requireNonNull(currency); return data.change(s -> s.credit(player, currency, amount)); }
    @Override public CompletableFuture<Void> debit(String player, Currency currency, long amount) { Objects.requireNonNull(currency); return data.change(s -> s.debit(player, currency, amount)); }
    @Override public CompletableFuture<Void> transfer(String from, String to, Currency currency, long amount) { Objects.requireNonNull(currency); return data.change(s -> s.transfer(from, to, currency, amount)); }
    private List<RankingService.Entry> topMoney = Collections.emptyList();
    private long topMoneyAt;

    /** Maiores saldos para o podio dos NPCs: ordena so a cada 60 s (nada por consulta). */
    private List<RankingService.Entry> topMoney(int limit) {
        long now = System.currentTimeMillis();
        if (now - topMoneyAt > 60_000) {
            topMoneyAt = now;
            List<Map.Entry<String, Long>> all = data.read(s -> { List<Map.Entry<String, Long>> l = new ArrayList<>(s.accounts.size()); for (Map.Entry<String, Ledger.Account> e : s.accounts.entrySet()) { if (e.getValue().money > 0) { l.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue().money)); } } return l; });
            all.sort(Map.Entry.<String, Long>comparingByValue().reversed());
            List<RankingService.Entry> top = new ArrayList<>();
            for (int i = 0; i < Math.min(10, all.size()); i++) { top.add(new RankingService.Entry(all.get(i).getKey(), all.get(i).getValue(), M.t("podio_money", Ledger.format(all.get(i).getValue(), Currency.MONEY)))); }
            topMoney = top;
        }
        return topMoney.subList(0, Math.min(limit, topMoney.size()));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        if (sender instanceof Player && auth != null && !auth.isAuthenticated((Player) sender)) { sender.sendMessage(M.t("autentique_se_primeiro")); return true; }
        try {
            Currency currency = command.getName().equals("cash") ? Currency.CASH : Currency.MONEY;
            CompletableFuture<Void> operation;
            switch (command.getName()) {
                case "money": case "cash":
                    String target = args.length == 0 ? sender.getName() : args[0];
                    sender.sendMessage(M.t("saldo_de", target, Ledger.format(balance(target, currency), currency), (currency == Currency.CASH ? M.t("cash") : M.t("moedas")))); return true;
                case "topmoney":
                    List<Map.Entry<String, Long>> accounts = data.read(s -> {
                        List<Map.Entry<String, Long>> list = new ArrayList<>(s.accounts.size());
                        for (Map.Entry<String, Ledger.Account> e : s.accounts.entrySet()) { list.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue().money)); }
                        return list;
                    });
                    accounts.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
                    sender.sendMessage(M.t("maiores_saldos"));
                    for (int i = 0; i < Math.min(10, accounts.size()); i++) { sender.sendMessage((i + 1) + ". " + accounts.get(i).getKey() + ": " + Ledger.format(accounts.get(i).getValue(), Currency.MONEY)); }
                    return true;
                case "banco":
                    if (!(sender instanceof Player)) { return true; }
                    if (args.length == 0) { openBank((Player) sender); return true; }
                    if (args.length == 2 && (args[0].equalsIgnoreCase("depositar") || args[0].equalsIgnoreCase("sacar"))) {
                        bankMove((Player) sender, Ledger.parse(args[1], Currency.MONEY), args[0].equalsIgnoreCase("depositar"), false);
                        return true;
                    }
                    sender.sendMessage(M.t("carteira_banco_use_banco_banco_depositar", Ledger.format(balance(sender.getName(), Currency.MONEY), Currency.MONEY), Ledger.format(data.read(s -> s.bank(sender.getName())), Currency.MONEY)));
                    return true;
                case "pagar":
                    if (!(sender instanceof Player) || args.length != 2) { return false; }
                    if (!knownPlayer(args[0])) { sender.sendMessage(M.t("jogador_nunca_entrou_no_servidor")); return true; }
                    long payment = Ledger.parse(args[1], Currency.MONEY);
                    operation = guarded(sender, () -> transfer(sender.getName(), args[0], Currency.MONEY, payment)); break;
                case "trocarcash":
                    if (!(sender instanceof Player) || args.length != 1) { return false; }
                    long amount = Ledger.parse(args[0], Currency.CASH);
                    operation = guarded(sender, () -> data.change(s -> s.exchange(sender.getName(), amount))); break;
                case "economia":
                    if (!sender.hasPermission("goldeconomy.admin")) { sender.sendMessage(M.t("sem_permissao")); return true; }
                    if (args.length != 4 || !Arrays.asList("money", "cash").contains(args[1])) { return false; }
                    Currency selected = args[1].equals("cash") ? Currency.CASH : Currency.MONEY;
                    switch (args[0]) {
                        case "adicionar": operation = credit(args[2], selected, Ledger.parse(args[3], selected)); break;
                        case "retirar": operation = debit(args[2], selected, Ledger.parse(args[3], selected)); break;
                        case "definir": long value = Ledger.parseBalance(args[3], selected); operation = data.change(s -> s.set(args[2], selected, value)); break;
                        default: return false;
                    }
                    break;
                default: return false;
            }
            if (operation == null) { sender.sendMessage(M.t("aguarde_operacao_anterior_terminar_2")); return true; }
            operation.whenComplete((unused, error) -> { if (isEnabled()) { sender.sendMessage(error == null ? M.t("operacao_salva_com_sucesso") : M.t("operacao_recusada", message(error))); } });
        } catch (RuntimeException error) { sender.sendMessage(M.t("operacao_recusada", message(error))); }
        return true;
    }
    /** Uma operacao pendente por jogador; null quando ja existe outra em andamento. */
    private CompletableFuture<Void> guarded(CommandSender sender, java.util.function.Supplier<CompletableFuture<Void>> action) {
        String key = sender.getName().toLowerCase(Locale.ROOT);
        if (!busy.add(key)) { return null; }
        CompletableFuture<Void> operation;
        try { operation = action.get(); } catch (RuntimeException error) { busy.remove(key); throw error; }
        return operation.whenComplete((unused, error) -> busy.remove(key));
    }
    /** Evita contas para nomes inexistentes: o destino precisa estar online, ter dados salvos ou ja possuir conta. */
    private boolean knownPlayer(String name) {
        String key = Ledger.name(name);
        return getServer().getPlayerExact(name) != null
            || new File(getServer().getDataPath() + "players/" + key + ".dat").exists()
            || data.read(s -> s.accounts.containsKey(key));
    }
    private static String message(Throwable error) { while (error.getCause() != null) { error = error.getCause(); } return error.getMessage(); }
}
