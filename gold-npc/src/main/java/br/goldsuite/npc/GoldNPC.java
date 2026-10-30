package br.goldsuite.npc;

import br.goldsuite.api.*;
import br.goldsuite.api.TravelService.Transition;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.data.Skin;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityLevelChangeEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.player.PlayerTeleportEvent;
import cn.nukkit.event.server.DataPacketReceiveEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.network.protocol.InteractPacket;
import cn.nukkit.network.protocol.ProtocolInfo;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import cn.nukkit.utils.PlayerName;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GoldNPC extends PluginBase implements Listener {
    private static final String SKINS = "goldnpc-skins";
    private static final Pattern WORLD_COUNT = Pattern.compile("\\{mundo:([A-Za-z0-9_][A-Za-z0-9_.-]{0,31})}");
    private static final long CLICK_COOLDOWN_MS = 800;

    private CoreService core;
    private PersistentState<NpcState> data;
    private final Map<String, Skin> skins = new HashMap<>();
    private final Map<Integer, NpcView> views = new HashMap<>();
    private final Map<Long, NpcView> byEid = new HashMap<>();
    private final Map<Player, Long> lastClick = new IdentityHashMap<>();
    private final Map<Player, Integer> selected = new IdentityHashMap<>();
    private final Set<Player> picking = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean anyLooking, anyDynamic;
    private static final String PLAYER_SKINS = "goldnpc-jogadores";
    /** NPCs de ranking: id -> jogador mostrado agora, e a skin/nome usados na visao. */
    private final Map<Integer, String> rankShown = new HashMap<>();
    private final Map<Integer, Skin> rankSkin = new HashMap<>();
    private final Map<Integer, String> rankName = new HashMap<>();

    @Override public void onEnable() {
        core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldNPC."); }
        data = new PersistentState<>(core.getStore(), "goldnpc", NpcState.class, NpcState::new, NpcState::validate);
        getServer().getPluginManager().registerEvents(this, this);
        data.ready().thenCompose(ignored -> loadSkins()).whenComplete((unused, error) -> {
            if (!isEnabled()) { return; }
            if (error != null) { getLogger().error("Falha ao carregar NPCs.", error); getServer().getPluginManager().disablePlugin(this); return; }
            rebuildAll();
            getLogger().info(views.size() + " NPC(s) carregado(s).");
        });
        // Visibilidade a cada 10 ticks, olhar a cada 2 e placeholders a cada 40; cada tarefa sai cedo se nao houver trabalho.
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldNPC>(this) { @Override public void onRun(int tick) { visibility(); } }, 10);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldNPC>(this) { @Override public void onRun(int tick) { if (anyLooking) { for (NpcView view : views.values()) { if (view.data.lookAt && !view.viewers.isEmpty()) { view.look(); } } } } }, 2);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldNPC>(this) { @Override public void onRun(int tick) { rankings(); } }, 20 * 30);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldNPC>(this) { @Override public void onRun(int tick) { if (anyDynamic) { for (NpcView view : views.values()) { if (view.dynamicName && view.data.ranking.isEmpty()) { view.updateName(placeholders(view.data.name)); } } } } }, 40);
    }

    @Override public void onDisable() {
        for (NpcView view : views.values()) { view.despawnAll(); }
        views.clear(); byEid.clear(); skins.clear(); lastClick.clear(); selected.clear(); picking.clear();
        if (data != null) { data.close(); }
    }

    // ---------- carga e visoes ----------

    /** Carrega so as skins usadas, uma vez cada (varios NPCs podem compartilhar a mesma). */
    private CompletableFuture<Void> loadSkins() {
        Set<String> hashes = new HashSet<>();
        data.read(s -> { for (NpcState.Npc npc : s.npcs.values()) { if (!npc.skin.isEmpty()) { hashes.add(npc.skin); } } return null; });
        CompletableFuture<Void> all = CompletableFuture.completedFuture(null);
        for (String hash : hashes) {
            all = all.thenCompose(ignored -> core.getStore().get(SKINS, hash)).thenAccept(value -> {
                if (value.isPresent()) { Skin skin = decodeSkin(value.get()); if (skin != null) { skins.put(hash, skin); } }
                else { getLogger().warning("Skin " + hash.substring(0, 8) + " ausente; o NPC usara a skin padrao."); }
            });
        }
        return all;
    }

    private void rebuildAll() {
        for (NpcView view : views.values()) { view.despawnAll(); }
        views.clear(); byEid.clear();
        data.read(s -> { for (Map.Entry<Integer, NpcState.Npc> e : s.npcs.entrySet()) { addView(e.getKey(), e.getValue()); } return null; });
        refreshFlags();
        visibility();
    }

    private void rebuild(int id) {
        NpcView old = views.remove(id);
        if (old != null) { old.despawnAll(); byEid.remove(old.eid); }
        NpcState.Npc npc = data.read(s -> s.npcs.get(id));
        if (npc != null) { addView(id, npc); }
        refreshFlags();
        visibility();
    }

    private void addView(int id, NpcState.Npc npc) {
        Skin skin = npc.type.equals("humano") ? rankSkin.getOrDefault(id, skins.getOrDefault(npc.skin, DefaultSkin.get())) : null;
        NpcView view = new NpcView(id, npc, skin, rankName.getOrDefault(id, placeholders(npc.name)));
        views.put(id, view);
        byEid.put(view.eid, view);
    }

    private void refreshFlags() {
        anyLooking = false; anyDynamic = false;
        for (NpcView view : views.values()) { anyLooking |= view.data.lookAt; anyDynamic |= view.dynamicName; }
    }

    private void visibility() {
        if (views.isEmpty()) { return; }
        for (NpcView view : views.values()) {
            for (Player viewer : new ArrayList<>(view.viewers.keySet())) { if (!view.canShowTo(viewer)) { view.despawnFrom(viewer); } }
            Level level = getServer().getLevelByName(view.data.world);
            if (level == null) { continue; }
            for (Player player : level.getPlayers().values()) {
                if (!view.viewers.containsKey(player) && view.canShowTo(player)) { view.spawnTo(player); }
            }
        }
    }

    private String placeholders(String name) {
        if (name.indexOf('{') < 0) { return name; }
        String result = name.replace("{online}", Integer.toString(getServer().getOnlinePlayers().size())).replace("{max}", Integer.toString(getServer().getMaxPlayers()));
        Matcher matcher = WORLD_COUNT.matcher(result);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            Level level = getServer().getLevelByName(matcher.group(1));
            matcher.appendReplacement(out, Integer.toString(level == null ? 0 : level.getPlayers().size()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    // ---------- eventos ----------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void packet(DataPacketReceiveEvent event) {
        // Chamado para todo pacote recebido: o filtro por id precisa vir antes de qualquer outra coisa.
        if (event.getPacket().pid() != ProtocolInfo.INTERACT_PACKET) { return; }
        NpcView view = byEid.get(((InteractPacket) event.getPacket()).target);
        if (view == null) { return; }
        event.setCancelled();
        click(event.getPlayer(), view);
    }

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) { forget(event.getPlayer(), false); }

    /** Guarda a skin de quem entra (uma copia por conteudo) para os NPCs de ranking mostrarem quem esta offline. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(cn.nukkit.event.player.PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Skin skin = p.getSkin();
        if (skin == null || !skin.isValid()) { return; }
        String name = p.getName().toLowerCase(Locale.ROOT);
        saveSkin(skin).thenCompose(hash -> core.getStore().put(PLAYER_SKINS, name, hash)).whenComplete((v, e) -> {
            if (e == null && rankShown.containsValue(name)) { rankings(); }
        });
    }

    // ---------- NPCs de ranking ----------

    /** Atualiza nome e skin dos NPCs ligados a uma posicao de ranking; so reconstroi quando o dono da posicao muda. */
    private void rankings() {
        RankingService ranking = getServer().getServiceManager().load(RankingService.class);
        if (ranking == null || !data.isLoaded()) { return; }
        for (NpcView view : new ArrayList<>(views.values())) {
            String spec = view.data.ranking;
            if (spec.isEmpty()) { continue; }
            int id = view.id, colon = spec.indexOf(':');
            String board = spec.substring(0, colon);
            int position = Integer.parseInt(spec.substring(colon + 1));
            List<RankingService.Entry> top = ranking.top(board, position);
            RankingService.Entry entry = top.size() >= position ? top.get(position - 1) : null;
            String name = medal(position) + (entry == null ? "\u00a77(vago)" : "\u00a7f" + entry.player + "\n\u00a7e" + entry.label);
            String player = entry == null ? null : entry.player;
            if (Objects.equals(player, rankShown.get(id)) && name.equals(rankName.get(id))) { continue; }
            if (Objects.equals(player, rankShown.get(id))) { rankName.put(id, name); view.updateName(name); continue; }
            if (player == null) { rankShown.remove(id); rankSkin.remove(id); rankName.put(id, name); rebuild(id); continue; }
            rankShown.put(id, player);
            rankName.put(id, name);
            core.getStore().get(PLAYER_SKINS, player).thenCompose(hash -> hash.isPresent() ? skinFor(hash.get()) : CompletableFuture.completedFuture((Skin) null))
                    .whenComplete((skin, e) -> {
                        if (!isEnabled() || !player.equals(rankShown.get(id))) { return; }
                        if (skin != null) { rankSkin.put(id, skin); } else { rankSkin.remove(id); }
                        rebuild(id);
                    });
        }
    }

    private static String medal(int position) {
        return (position == 1 ? "\u00a76\u00a7l#1 " : position == 2 ? "\u00a77\u00a7l#2 " : position == 3 ? "\u00a7c\u00a7l#3 " : "\u00a7e#" + position + " ") + "\u00a7r";
    }

    /** Skin pelo hash: da memoria, ou carregada uma vez do banco. */
    private CompletableFuture<Skin> skinFor(String hash) {
        Skin cached = skins.get(hash);
        if (cached != null) { return CompletableFuture.completedFuture(cached); }
        return core.getStore().get(SKINS, hash).thenApply(v -> {
            Skin skin = v.isPresent() ? decodeSkin(v.get()) : null;
            if (skin != null) { skins.put(hash, skin); }
            return skin;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void level(EntityLevelChangeEvent event) { if (event.getEntity() instanceof Player) { forget((Player) event.getEntity(), true); } }

    /** Teleporte longo: o cliente descarta os chunks antigos, entao o NPC sera reenviado quando o chunk voltar. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        if (event.getFrom().level != event.getTo().level || event.getFrom().distanceSquared(event.getTo()) > 32 * 32) { forget(event.getPlayer(), true); }
    }

    private void forget(Player player, boolean keepSelection) {
        for (NpcView view : views.values()) { if (keepSelection) { view.despawnFrom(player); } else { view.viewers.remove(player); } }
        if (!keepSelection) { lastClick.remove(player); selected.remove(player); picking.remove(player); }
    }

    private void click(Player player, NpcView view) {
        long now = System.currentTimeMillis();
        Long last = lastClick.get(player);
        if (last != null && now - last < CLICK_COOLDOWN_MS) { return; }
        lastClick.put(player, now);
        if (picking.remove(player)) {
            selected.put(player, view.id);
            player.sendMessage("NPC #" + view.id + " selecionado. " + describe(view.id, view.data));
            return;
        }
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        if (auth != null && !auth.isAuthenticated(player)) { player.sendTip("Autentique-se primeiro."); return; }
        NpcState.Npc npc = view.data;
        if (!npc.command.isEmpty()) {
            getServer().dispatchCommand(player, npc.command.replace("{jogador}", player.getName()));
        }
        if (npc.destination != null) {
            TravelService travel = getServer().getServiceManager().load(TravelService.class);
            if (travel == null || travel.isTraveling(player)) { return; }
            travel.travel(player, npc.destination, npc.transition()).whenComplete((ok, error) -> {
                if (error != null && player.isOnline()) { player.sendMessage("Nao foi possivel viajar: " + root(error)); }
            });
        }
    }

    // ---------- comandos ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("goldnpc.admin")) { sender.sendMessage("Sem permissao."); return true; }
        if (!(sender instanceof Player)) { sender.sendMessage("Use dentro do jogo."); return true; }
        Player player = (Player) sender;
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        if (auth != null && !auth.isAuthenticated(player)) { player.sendMessage("Autentique-se primeiro."); return true; }
        if (args.length == 0) { help(player); return true; }
        try {
            String rest = join(args, 1);
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "criar": {
                    if (args.length < 2) { player.sendMessage("Uso: /npc criar <tipo> [nome]"); return true; }
                    String type = NpcState.type(args[1]);
                    String name = NpcState.name(args.length > 2 ? join(args, 2) : player.getName());
                    CompletableFuture<String> skin = type.equals("humano") ? saveSkin(player.getSkin()) : CompletableFuture.completedFuture("");
                    int[] created = new int[1];
                    result(player, skin.thenCompose(hash -> data.change(s -> {
                        if (s.npcs.size() >= NpcState.MAX_NPCS) { throw new IllegalArgumentException("Limite de NPCs atingido."); }
                        NpcState.Npc npc = new NpcState.Npc();
                        npc.type = type; npc.name = name; npc.skin = hash;
                        place(npc, player);
                        created[0] = s.nextId++;
                        s.npcs.put(created[0], npc);
                    })), () -> { selected.put(player, created[0]); rebuild(created[0]); player.sendMessage("NPC #" + created[0] + " criado e selecionado. Configure com /npc destino e /npc transicao."); });
                    return true;
                }
                case "selecionar":
                    if (args.length == 2) { int id = parseId(args[1]); data.read(s -> s.require(id)); selected.put(player, id); player.sendMessage("NPC #" + id + " selecionado."); }
                    else { picking.add(player); player.sendMessage("Toque no NPC que deseja selecionar."); }
                    return true;
                case "lista":
                    data.read(s -> { if (s.npcs.isEmpty()) { player.sendMessage("Nenhum NPC criado."); } for (Map.Entry<Integer, NpcState.Npc> e : s.npcs.entrySet()) { player.sendMessage(describe(e.getKey(), e.getValue())); } return null; });
                    return true;
                case "info": { int id = selection(player); player.sendMessage(describe(id, data.read(s -> s.require(id)))); return true; }
                case "nome": { String name = NpcState.name(rest); edit(player, npc -> npc.name = name); return true; }
                case "tipo": { String type = NpcState.type(args.length > 1 ? args[1] : ""); edit(player, npc -> npc.type = type); return true; }
                case "transicao": { Transition t = Transition.parse(args.length > 1 ? args[1] : ""); edit(player, npc -> npc.transition = t.name()); return true; }
                case "olhar": { boolean on = args.length > 1 && (args[1].equalsIgnoreCase("sim") || args[1].equalsIgnoreCase("on")); edit(player, npc -> npc.lookAt = on); return true; }
                case "mover": edit(player, npc -> place(npc, player)); return true;
                case "item": { Item hand = player.getInventory().getItemInHand(); edit(player, npc -> { npc.itemId = hand.getId(); npc.itemMeta = Math.max(0, hand.getDamage()); }); return true; }
                case "ranking": {
                    if (args.length < 2) { player.sendMessage("Uso: /npc ranking <quadro> <posicao 1-10> | nenhum. Quadros: " + rankingBoards()); return true; }
                    String spec;
                    if (args[1].equalsIgnoreCase("nenhum")) { spec = ""; }
                    else {
                        String board = args[1].toLowerCase(Locale.ROOT);
                        if (!rankingBoards().contains(board)) { player.sendMessage("Quadro inexistente. Disponiveis: " + rankingBoards()); return true; }
                        int position = args.length > 2 ? parseId(args[2]) : 1;
                        if (position < 1 || position > 10) { player.sendMessage("Posicao de 1 a 10."); return true; }
                        spec = board + ":" + position;
                    }
                    int id = selection(player);
                    result(player, data.change(s -> { NpcState.Npc npc = s.require(id); npc.ranking = spec; if (!spec.isEmpty()) { npc.type = "humano"; } }), () -> {
                        rankShown.remove(id); rankSkin.remove(id); rankName.remove(id);
                        rebuild(id); rankings();
                        player.sendMessage(spec.isEmpty() ? "NPC desligado do ranking." : "NPC #" + id + " agora mostra a posicao " + spec.replace(":", " #") + ". Atualiza a cada 30 s.");
                    });
                    return true;
                }
                case "comando": {
                    if (args.length < 2) { player.sendMessage("Uso: /npc comando <comando sem /> ou /npc comando limpar"); return true; }
                    String cmd = args[1].equalsIgnoreCase("limpar") ? "" : NpcState.command(rest);
                    edit(player, npc -> npc.command = cmd); return true;
                }
                case "destino": { Destination destination = destination(player, args); edit(player, npc -> npc.destination = destination); return true; }
                case "skin": {
                    Player source = args.length > 1 ? getServer().getPlayerExact(args[1]) : player;
                    if (source == null) { player.sendMessage("Jogador precisa estar online."); return true; }
                    int id = selection(player);
                    result(player, saveSkin(source.getSkin()).thenCompose(hash -> data.change(s -> s.require(id).skin = hash)), () -> rebuild(id));
                    return true;
                }
                case "tp": {
                    int id = args.length > 1 ? parseId(args[1]) : selection(player);
                    NpcState.Npc npc = data.read(s -> s.require(id));
                    TravelService travel = getServer().getServiceManager().load(TravelService.class);
                    travel.travel(player, Destination.position(npc.world, npc.x, npc.y, npc.z, npc.yaw, npc.pitch), Transition.INSTANTANEA);
                    return true;
                }
                case "remover": {
                    int id = selection(player);
                    result(player, data.change(s -> { s.require(id); s.npcs.remove(id); }), () -> { selected.values().removeIf(v -> v == id); rebuild(id); player.sendMessage("NPC #" + id + " removido."); });
                    return true;
                }
                default: help(player); return true;
            }
        } catch (RuntimeException error) { player.sendMessage("Erro: " + root(error)); return true; }
    }

    private Destination destination(Player player, String[] args) {
        String kind = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (kind) {
            case "mundo": if (args.length < 3) { break; } return Destination.world(args[2]);
            case "aqui": return Destination.position(player.level.getFolderName(), player.x, player.y, player.z, (float) player.yaw, (float) player.pitch);
            case "servidor": if (args.length < 3) { break; } return Destination.server(args[2]);
            case "nenhum": return null;
            default:
        }
        throw new IllegalArgumentException("Uso: /npc destino mundo <mundo> | aqui | servidor <nome> | nenhum");
    }

    private void edit(Player player, java.util.function.Consumer<NpcState.Npc> mutation) {
        int id = selection(player);
        result(player, data.change(s -> mutation.accept(s.require(id))), () -> { rebuild(id); player.sendMessage("NPC #" + id + " atualizado."); });
    }

    private void result(Player player, CompletableFuture<?> operation, Runnable success) {
        operation.whenComplete((unused, error) -> {
            if (!isEnabled()) { return; }
            if (error != null) { if (player.isOnline()) { player.sendMessage("Erro: " + root(error)); } }
            else { success.run(); }
        });
    }

    private int selection(Player player) {
        Integer id = selected.get(player);
        if (id == null) { throw new IllegalArgumentException("Selecione um NPC: /npc selecionar (e toque nele) ou /npc selecionar <id>."); }
        return id;
    }

    private static void place(NpcState.Npc npc, Player player) {
        Destination.position(player.level.getFolderName(), player.x, player.y, player.z, 0, 0);
        npc.world = player.level.getFolderName();
        npc.x = player.x; npc.y = player.y; npc.z = player.z;
        npc.yaw = (float) player.yaw; npc.pitch = 0;
    }

    /** Salva a skin uma vez por conteudo (SHA-256) e devolve o hash. */
    private CompletableFuture<String> saveSkin(Skin skin) {
        if (skin == null || !skin.isValid()) { return PersistentState.failed(new IllegalArgumentException("Skin invalida.")); }
        String hash = sha256(skin.getData());
        if (skins.containsKey(hash)) { return CompletableFuture.completedFuture(hash); }
        Skin copy = new Skin(skin.getData().clone(), skin.getModel());
        return core.getStore().put(SKINS, hash, skin.getModel() + "\n" + Base64.getEncoder().encodeToString(copy.getData())).thenApply(ignored -> { skins.put(hash, copy); return hash; });
    }

    private Skin decodeSkin(String stored) {
        int split = stored.indexOf('\n');
        if (split < 1) { return null; }
        try {
            Skin skin = new Skin(Base64.getDecoder().decode(stored.substring(split + 1)), stored.substring(0, split));
            return skin.isValid() ? skin : null;
        } catch (IllegalArgumentException error) { return null; }
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) { hex.append(String.format("%02x", b)); }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private List<String> rankingBoards() {
        RankingService ranking = getServer().getServiceManager().load(RankingService.class);
        return ranking == null ? Collections.emptyList() : ranking.boards();
    }

    private static String describe(int id, NpcState.Npc npc) {
        return "#" + id + " " + npc.type + " em " + npc.world + String.format(Locale.ROOT, " %.0f %.0f %.0f", npc.x, npc.y, npc.z)
                + " | destino: " + (npc.destination == null ? "nenhum" : npc.destination) + " | transicao: " + npc.transition.toLowerCase(Locale.ROOT)
                + (npc.command.isEmpty() ? "" : " | comando: /" + npc.command) + " | nome: " + npc.name.replace("\n", " | ");
    }

    private static void help(Player player) {
        player.sendMessage("NPC: /npc criar <tipo> [nome], selecionar [id], lista, info, nome <texto, | quebra linha>, tipo <tipo>, skin [jogador], item, mover,"
                + " olhar sim|nao, ranking <quadro> <posicao>|nenhum, destino mundo <m>|aqui|servidor <s>|nenhum, transicao <instantanea|portal|espiral|escurecer|contagem|explosao>, comando <cmd>|limpar, tp [id], remover");
    }

    private static int parseId(String text) {
        try { return Integer.parseInt(text); } catch (NumberFormatException error) { throw new IllegalArgumentException("Id invalido."); }
    }
    private static String join(String[] args, int from) { return from >= args.length ? "" : String.join(" ", Arrays.copyOfRange(args, from, args.length)); }
    private static String root(Throwable error) { while (error.getCause() != null) { error = error.getCause(); } return error.getMessage(); }
}
