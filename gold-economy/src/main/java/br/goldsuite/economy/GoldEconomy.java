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
    private PersistentState<Ledger> data;
    /** Jogadores com operacao pendente; impede que um cliente lote a fila de gravacao. */
    private final Set<String> busy = new HashSet<>();
    @Override public void onEnable() {
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldEconomy."); }
        data = new PersistentState<>(core.getStore(), "goldeconomy", Ledger.class, Ledger::new, Ledger::validate);
        data.ready().whenComplete((unused, error) -> {
            if (!isEnabled()) { return; }
            if (error != null) { getLogger().error("Falha ao carregar economia.", error); getServer().getPluginManager().disablePlugin(this); }
            else { getServer().getServiceManager().register(EconomyService.class, this, this); }
        });
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
