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
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldEconomy."); }
        data = new AccountStore(core.getStore(), msg -> getLogger().info(msg));
        getServer().getScheduler().scheduleRepeatingTask(new cn.nukkit.scheduler.PluginTask<GoldEconomy>(this) { @Override public void onRun(int tick) { data.flush(); } }, 20);
        Settings.text(this, "banco.valores", "5000,25000,50000,100000,500000", "Valores dos botoes de deposito e saque do /banco (ate 5, separados por virgula)", v -> {
            List<Long> list = new ArrayList<>();
            for (String part : v.split("[,;\\s]+")) { try { long n = Long.parseLong(part.trim()); if (n > 0 && n <= Ledger.MAX_BALANCE / 100 && list.size() < 5) { list.add(n); } } catch (NumberFormatException ignored) { } }
            if (!list.isEmpty()) { bankValues = list; }
        });
        data.ready().whenComplete((unused, error) -> {
            if (!isEnabled()) { return; }
            if (error != null) { getLogger().error("Falha ao carregar economia.", error); getServer().getPluginManager().disablePlugin(this); }
            else {
                getLogger().info(data.size() + " conta(s) carregada(s).");
                getServer().getServiceManager().register(EconomyService.class, this, this);
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
        icons.put(4, icon(340, 0, "\u00a7aSaldo\n\u00a77Carteira: \u00a7f$" + Ledger.format(wallet, Currency.MONEY) + "\n\u00a77Banco: \u00a7f$" + Ledger.format(bank, Currency.MONEY)));
        for (int i = 0; i < bankValues.size(); i++) {
            long v = bankValues.get(i);
            icons.put(10 + i, icon(339, 0, "\u00a7aDepositar $" + PlaceholderService.compact(v) + "\n\u00a77Carteira -> banco"));
            icons.put(19 + i, icon(339, 0, "\u00a76Sacar $" + PlaceholderService.compact(v) + "\n\u00a77Banco -> carteira"));
        }
        icons.put(16, icon(266, 0, "\u00a7aDepositar tudo\n\u00a77$" + Ledger.format(wallet, Currency.MONEY)));
        icons.put(25, icon(266, 0, "\u00a76Sacar tudo\n\u00a77$" + Ledger.format(bank, Currency.MONEY)));
        menus.open(p, "Banco Central", icons, (who, slot) -> {
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
        if (cents <= 0) { p.sendMessage("\u00a7cNada para " + (deposit ? "depositar." : "sacar.")); return; }
        CompletableFuture<Void> op = guarded(p, () -> data.change(s -> { if (deposit) { s.deposit(p.getName(), cents); } else { s.withdraw(p.getName(), cents); } }));
        if (op == null) { p.sendMessage("\u00a7cAguarde a operacao anterior terminar."); return; }
        op.whenComplete((v, e) -> getServer().getScheduler().scheduleTask(() -> {
            if (!isEnabled() || !p.isOnline()) { return; }
            p.sendMessage(e != null ? "\u00a7c" + message(e) : (deposit ? "\u00a7aVoce depositou $" : "\u00a7aVoce sacou $") + Ledger.format(cents, Currency.MONEY) + (deposit ? " no banco." : " do banco."));
            if (reopen && e == null) { openBank(p); }
        }));
    }

    @Override public void onDisable() { if (data != null) { data.close(); } busy.clear(); }
    @Override public long balance(String player, Currency currency) { Objects.requireNonNull(currency); return data.read(s -> s.balance(player, currency)); }
    @Override public CompletableFuture<Void> credit(String player, Currency currency, long amount) { Objects.requireNonNull(currency); return data.change(s -> s.credit(player, currency, amount)); }
    @Override public CompletableFuture<Void> debit(String player, Currency currency, long amount) { Objects.requireNonNull(currency); return data.change(s -> s.debit(player, currency, amount)); }
    @Override public CompletableFuture<Void> transfer(String from, String to, Currency currency, long amount) { Objects.requireNonNull(currency); return data.change(s -> s.transfer(from, to, currency, amount)); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        if (sender instanceof Player && auth != null && !auth.isAuthenticated((Player) sender)) { sender.sendMessage("Autentique-se primeiro."); return true; }
        try {
            Currency currency = command.getName().equals("cash") ? Currency.CASH : Currency.MONEY;
            CompletableFuture<Void> operation;
            switch (command.getName()) {
                case "money": case "cash":
                    String target = args.length == 0 ? sender.getName() : args[0];
                    sender.sendMessage("Saldo de " + target + ": " + Ledger.format(balance(target, currency), currency) + (currency == Currency.CASH ? " cash" : " moedas")); return true;
                case "topmoney":
                    List<Map.Entry<String, Long>> accounts = data.read(s -> {
                        List<Map.Entry<String, Long>> list = new ArrayList<>(s.accounts.size());
                        for (Map.Entry<String, Ledger.Account> e : s.accounts.entrySet()) { list.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue().money)); }
                        return list;
                    });
                    accounts.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
                    sender.sendMessage("Maiores saldos:");
                    for (int i = 0; i < Math.min(10, accounts.size()); i++) { sender.sendMessage((i + 1) + ". " + accounts.get(i).getKey() + ": " + Ledger.format(accounts.get(i).getValue(), Currency.MONEY)); }
                    return true;
                case "banco":
                    if (!(sender instanceof Player)) { return true; }
                    if (args.length == 0) { openBank((Player) sender); return true; }
                    if (args.length == 2 && (args[0].equalsIgnoreCase("depositar") || args[0].equalsIgnoreCase("sacar"))) {
                        bankMove((Player) sender, Ledger.parse(args[1], Currency.MONEY), args[0].equalsIgnoreCase("depositar"), false);
                        return true;
                    }
                    sender.sendMessage("Carteira: $" + Ledger.format(balance(sender.getName(), Currency.MONEY), Currency.MONEY) + " | Banco: $" + Ledger.format(data.read(s -> s.bank(sender.getName())), Currency.MONEY) + ". Use /banco, /banco depositar <valor> ou /banco sacar <valor>.");
                    return true;
                case "pagar":
                    if (!(sender instanceof Player) || args.length != 2) { return false; }
                    if (!knownPlayer(args[0])) { sender.sendMessage("Jogador nunca entrou no servidor."); return true; }
                    long payment = Ledger.parse(args[1], Currency.MONEY);
                    operation = guarded(sender, () -> transfer(sender.getName(), args[0], Currency.MONEY, payment)); break;
                case "trocarcash":
                    if (!(sender instanceof Player) || args.length != 1) { return false; }
                    long amount = Ledger.parse(args[0], Currency.CASH);
                    operation = guarded(sender, () -> data.change(s -> s.exchange(sender.getName(), amount))); break;
                case "economia":
                    if (!sender.hasPermission("goldeconomy.admin")) { sender.sendMessage("Sem permissao."); return true; }
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
            if (operation == null) { sender.sendMessage("Aguarde a operacao anterior terminar."); return true; }
            operation.whenComplete((unused, error) -> { if (isEnabled()) { sender.sendMessage(error == null ? "Operacao salva com sucesso." : "Operacao recusada: " + message(error)); } });
        } catch (RuntimeException error) { sender.sendMessage("Operacao recusada: " + message(error)); }
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
