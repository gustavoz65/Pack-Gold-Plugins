package br.goldsuite.chat;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import cn.nukkit.item.Item;
import java.util.*;

final class Polls {
    private final GoldChat plugin;
    final PersistentState<PollState> state;
    private long interval = 180, elapsed;
    private int cursor;
    Polls(GoldChat plugin, CoreService core) {
        this.plugin = plugin;
        state = new PersistentState<>(core.getStore(), "goldchat-enquetes", PollState.class, PollState::new, PollState::validate);
        Settings.number(plugin, "enquetes.intervalo", 180, "Segundos entre anuncios de enquetes (0 desliga)", v -> interval = v <= 0 ? 0 : Math.max(30, Math.min(3600, v)));
    }
    void tick() {
        if (!state.isLoaded() || interval == 0 || (elapsed += 10) < interval) { return; }
        elapsed = 0;
        List<String> open = state.read(s -> {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, PollState.Poll> e : s.polls.entrySet()) { if (e.getValue().open(System.currentTimeMillis())) { out.add(e.getKey()); } }
            return out;
        });
        if (!open.isEmpty()) { announce(open.get(Math.floorMod(cursor++, open.size()))); }
    }
    private void announce(String id) {
        PollState.Poll p = state.read(s -> s.polls.get(id));
        if (p != null && p.open(System.currentTimeMillis())) {
            plugin.getServer().broadcastMessage(M.t("enquete_anuncio", p.question, id));
        }
    }
    boolean command(CommandSender sender, String command, String[] args) {
        if (!state.isLoaded()) { sender.sendMessage(M.t("enquete_carregando")); return true; }
        try {
            if (command.equals("entrar")) {
                if (!(sender instanceof Player) || args.length == 0) { sender.sendMessage(M.t("enquete_entrar")); return true; }
                Player player = (Player) sender; authenticated(player);
                String id = PollState.id(args[0]);
                if (args.length > 1) { vote(player, id, Integer.parseInt(args[1]) - 1); } else { menu(player, id); }
                return true;
            }
            String action = args.length == 0 ? "lista" : args[0].toLowerCase(Locale.ROOT);
            if (action.equals("lista")) { sender.sendMessage(M.t("enquete_lista", state.<String>read(s -> String.join(", ", s.polls.keySet())))); return true; }
            String id = PollState.id(args.length > 1 ? args[1] : "");
            if (action.equals("resultado")) {
                PollState.Poll p = poll(id);
                int[] totals = p.totals();
                sender.sendMessage(M.t("enquete_resultado", p.question, p.votes.size()));
                for (int i = 0; i < totals.length; i++) { sender.sendMessage(M.t("enquete_total", p.options.get(i), totals[i])); }
                return true;
            }
            if (!sender.hasPermission("goldchat.enquetes")) { sender.sendMessage(M.t("enquete_sem_permissao")); return true; }
            if (action.equals("anunciar")) { announce(id); return true; }
            if (action.equals("criar")) {
                if (args.length < 4) { throw new IllegalArgumentException(M.t("enquete_uso")); }
                int minutes = Integer.parseInt(args[2]);
                if (minutes < 1 || minutes > 10080) { throw new IllegalArgumentException(M.t("enquete_uso")); }
                String[] parts = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).split("\\|", -1);
                PollState.Poll p = new PollState.Poll();
                p.question = parts[0].trim(); p.closes = System.currentTimeMillis() + minutes * 60_000L;
                for (int i = 1; i < parts.length; i++) { p.options.add(parts[i].trim()); }
                state.change(s -> { if (s.polls.containsKey(id)) { throw new IllegalArgumentException(M.t("enquete_existe")); } s.polls.put(id, p); })
                    .whenComplete((v, e) -> complete(sender, e, () -> { announce(id); }));
            } else if (action.equals("encerrar") || action.equals("apagar")) {
                state.change(s -> {
                    PollState.Poll p = s.polls.get(id);
                    if (p == null) { throw new IllegalArgumentException(M.t("enquete_inexistente")); }
                    if (action.equals("apagar")) { if (p.open(System.currentTimeMillis())) { throw new IllegalArgumentException(M.t("enquete_encerre_primeiro")); } s.polls.remove(id); }
                    else { p.closed = true; }
                }).whenComplete((v, e) -> complete(sender, e, () -> {}));
            } else { sender.sendMessage(M.t("enquete_uso")); }
        } catch (RuntimeException e) { sender.sendMessage(e instanceof NumberFormatException ? M.t("enquete_opcao_invalida") : e.getMessage()); }
        return true;
    }
    private PollState.Poll poll(String id) {
        PollState.Poll p = state.read(s -> s.polls.get(id));
        if (p == null) { throw new IllegalArgumentException(M.t("enquete_inexistente")); } return p;
    }
    private void authenticated(Player p) {
        AuthService auth = plugin.getServer().getServiceManager().load(AuthService.class);
        if (auth != null && !auth.isAuthenticated(p)) { throw new IllegalArgumentException(M.t("enquete_login")); }
    }
    private void vote(Player player, String id, int option) {
        authenticated(player);
        String account = player.getName().toLowerCase(Locale.ROOT);
        // Revalida dentro da fila: dois cliques antes do SQLite terminar nao geram dois votos.
        state.change(s -> {
            PollState.Poll poll = s.polls.get(id);
            if (poll == null) { throw new IllegalArgumentException(M.t("enquete_inexistente")); }
            poll.vote(account, option, System.currentTimeMillis());
        }).whenComplete((v, e) -> complete(player, e, () -> {}));
    }
    private void complete(CommandSender sender, Throwable error, Runnable after) {
        if (!plugin.isEnabled()) { return; }
        sender.sendMessage(error == null ? M.t("enquete_salvo") : M.t("enquete_falha", GoldChat.root(error)));
        if (error == null) { after.run(); }
    }
    private void menu(Player p, String id) {
        PollState.Poll poll = poll(id);
        if (!poll.open(System.currentTimeMillis())) { throw new IllegalArgumentException(M.t("enquete_fechada")); }
        Map<Integer, Item> icons = new HashMap<>();
        p.sendMessage(M.t("enquete_pergunta", poll.question));
        for (int i = 0; i < poll.options.size(); i++) {
            Item item = Item.get(35, i, 1); item.setCustomName(poll.options.get(i)); icons.put(i + 9, item);
            p.sendMessage(M.t("enquete_opcao", i + 1, poll.options.get(i), id));
        }
        MenuService menus = plugin.getServer().getServiceManager().load(MenuService.class);
        if (menus != null) { menus.open(p, M.t("enquete_menu", id), icons, (who, slot) -> {
            if (icons.containsKey(slot)) { try { vote(who, id, slot - 9); } catch (RuntimeException e) { who.sendMessage(e.getMessage()); } }
        }); }
    }
    void close() { state.close(); }
}

