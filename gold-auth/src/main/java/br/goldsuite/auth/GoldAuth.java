package br.goldsuite.auth;

import br.goldsuite.api.AuthService;
import br.goldsuite.api.CoreService;
import br.goldsuite.api.EffectService;
import br.goldsuite.api.event.PlayerAuthEvent;
import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.PluginIdentifiableCommand;
import cn.nukkit.event.Event;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.inventory.InventoryOpenEvent;
import cn.nukkit.event.inventory.InventoryPickupItemEvent;
import cn.nukkit.event.inventory.InventoryTransactionEvent;
import cn.nukkit.event.player.*;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.plugin.PluginFailure;
import cn.nukkit.scheduler.AsyncTask;
import cn.nukkit.scheduler.PluginTask;
import cn.nukkit.utils.PlayerName;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Autenticacao local; nenhuma permissao administrativa dispensa o login. */
public final class GoldAuth extends PluginBase implements Listener, AuthService {
    private enum Mode { REGISTER, LOGIN, CHANGE }
    private final Map<Player, Session> sessions = new IdentityHashMap<>();
    private final Map<String, AttemptWindow> attempts = new HashMap<>();
    /** Hashes em paralelo; o resto espera numa fila curta em vez de falhar no pico de logins. */
    private static final int MAX_PARALLEL_HASHES = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    private static final int MAX_QUEUED_HASHES = 64;
    private final ArrayDeque<Runnable> pendingHashes = new ArrayDeque<>();
    private int activeHashes;
    private CoreService core;
    private Thread mainThread;

    @Override
    public void onEnable() {
        this.mainThread = Thread.currentThread();
        this.core = getServer().getServiceManager().load(CoreService.class);
        if (this.core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldAuth."); }
        getServer().getServiceManager().register(AuthService.class, this, this);
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : getServer().getOnlinePlayers().values()) { session(player); }
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldAuth>(this) {
            @Override public void onRun(int tick) {
                long now = System.nanoTime();
                attempts.values().removeIf(window -> window.expired(now));
                for (Session session : new ArrayList<>(sessions.values())) {
                    if (!session.authenticated && now - session.created >= 120_000_000_000L) {
                        session.player.close("", "Tempo para autenticar esgotado.");
                    }
                }
            }
        }, 20);
        getLogger().info("Autenticacao local ativada. Registro e login sao obrigatorios, inclusive para operadores.");
    }

    @Override
    public void onDisable() {
        for (Session session : new ArrayList<>(this.sessions.values())) {
            session.loaded.completeExceptionally(new IllegalStateException("Autenticacao encerrada."));
            session.player.close("", "Autenticacao reiniciada. Entre novamente.");
        }
        this.sessions.clear();
        this.attempts.clear();
        this.pendingHashes.clear();
    }

    private void requireMainThread() {
        if (Thread.currentThread() != this.mainThread) { throw new IllegalStateException("A API de autenticacao deve ser chamada na thread principal."); }
    }

    private Session session(Player player) {
        requireMainThread();
        Session existing = this.sessions.get(player);
        if (existing != null) { return existing; }
        if (!PlayerName.isValid(player.getName())) { throw new IllegalArgumentException("Jogador sem nome valido."); }
        Session session = new Session(player);
        this.sessions.put(player, session);
        this.core.getStore().get("goldauth", session.name).whenComplete((stored, error) -> {
            if (!current(session)) { session.loaded.completeExceptionally(new IllegalStateException("Sessao encerrada.")); return; }
            if (error != null) {
                session.loaded.completeExceptionally(error);
                player.close("", "Nao foi possivel carregar sua conta. Tente novamente.");
                return;
            }
            session.passwordHash = stored.orElse(null);
            session.loaded.complete(null);
            player.sendMessage(session.passwordHash == null ? "Registre-se: /registrar <senha> <confirmacao>" : "Entre com /login <senha>. Voce tambem pode digitar a senha no chat.");
        });
        return session;
    }

    private boolean current(Session session) {
        return isEnabled() && this.sessions.get(session.player) == session && session.player.isConnected();
    }

    @Override
    public boolean isAuthenticated(Player player) {
        requireMainThread();
        Session session = this.sessions.get(player);
        return session != null && current(session) && session.authenticated;
    }

    @Override public CompletableFuture<Boolean> register(Player player, char[] password) { return operate(player, password, null, Mode.REGISTER); }
    @Override public CompletableFuture<Boolean> login(Player player, char[] password) { return operate(player, password, null, Mode.LOGIN); }
    @Override public CompletableFuture<Boolean> changePassword(Player player, char[] current, char[] replacement) { return operate(player, current, replacement, Mode.CHANGE); }

    private CompletableFuture<Boolean> reject(Player player, String message) {
        player.sendMessage(message);
        return CompletableFuture.completedFuture(false);
    }

    private CompletableFuture<Boolean> operate(Player player, char[] input, char[] replacementInput, Mode mode) {
        requireMainThread();
        if (!isEnabled()) {
            CompletableFuture<Boolean> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(new IllegalStateException("GoldAuth esta desativado."));
            return rejected;
        }
        Session session = session(player);
        if (session.busy) { return reject(player, "Aguarde a verificacao anterior."); }
        try { PasswordHasher.validate(input); if (mode == Mode.CHANGE) { PasswordHasher.validate(replacementInput); } }
        catch (IllegalArgumentException error) { return reject(player, error.getMessage()); }
        char[] password = input.clone();
        char[] replacement = replacementInput == null ? null : replacementInput.clone();
        session.busy = true;
        CompletableFuture<Boolean> result = session.loaded.thenCompose(ignored -> {
            if (!current(session)) { return CompletableFuture.completedFuture(false); }
            if (mode == Mode.REGISTER && session.passwordHash != null) { return reject(player, "Esta conta ja possui registro. Use /login."); }
            if (mode != Mode.REGISTER && session.passwordHash == null) { return reject(player, "Registre sua conta primeiro com /registrar."); }
            if (mode == Mode.CHANGE && !session.authenticated) { return reject(player, "Entre na conta antes de alterar sua senha."); }
            if (mode != Mode.CHANGE && session.authenticated) { return reject(player, "Voce ja esta autenticado."); }
            long now = System.nanoTime();
            AttemptWindow window = this.attempts.get(player.getAddress());
            if (window == null) {
                if (this.attempts.size() >= 2048) { return reject(player, "Autenticacao ocupada. Aguarde e tente novamente."); }
                window = new AttemptWindow(now);
                this.attempts.put(player.getAddress(), window);
            }
            if (!window.allowed(now)) { return reject(player, "Muitas senhas erradas deste IP. Aguarde um minuto."); }
            final AttemptWindow ipWindow = window;
            return hash(player, password, replacement, session.passwordHash, mode).thenCompose(answer -> {
                if (!current(session)) { return CompletableFuture.completedFuture(false); }
                if (!answer.matches) {
                    ipWindow.fail(System.nanoTime());
                    player.sendMessage("Senha incorreta.");
                    if (++session.failures >= 5) { player.close("", "Limite de senhas incorretas atingido."); }
                    return CompletableFuture.completedFuture(false);
                }
                if (mode == Mode.LOGIN) {
                    authenticated(session, PlayerAuthEvent.Method.LOGIN);
                    return CompletableFuture.completedFuture(true);
                }
                return this.core.getStore().put("goldauth", session.name, answer.hash).thenApply(written -> {
                    if (!current(session)) { return false; }
                    session.passwordHash = answer.hash;
                    if (mode == Mode.REGISTER) { authenticated(session, PlayerAuthEvent.Method.REGISTER); }
                    else { player.sendMessage("Senha alterada com sucesso."); }
                    return true;
                });
            });
        });
        return result.whenComplete((success, error) -> {
            Arrays.fill(password, '\0');
            if (replacement != null) { Arrays.fill(replacement, '\0'); }
            session.busy = false;
            if (error != null && current(session)) { player.sendMessage("Nao foi possivel concluir a autenticacao. Tente novamente."); }
        });
    }

    private CompletableFuture<HashAnswer> hash(Player player, char[] password, char[] replacement, String encoded, Mode mode) {
        CompletableFuture<HashAnswer> result = new CompletableFuture<>();
        Runnable start = () -> {
            ++this.activeHashes;
            getServer().getScheduler().executeAsyncTask(new AsyncTask() {
                private HashAnswer answer;
                private Throwable error;
                @Override public void onRun() {
                    try {
                        boolean matches = mode == Mode.REGISTER || PasswordHasher.verify(password, encoded);
                        this.answer = new HashAnswer(matches, matches && mode != Mode.LOGIN ? PasswordHasher.hash(mode == Mode.CHANGE ? replacement : password) : null);
                    } catch (Throwable failure) { PluginFailure.rethrowIfFatal(failure); this.error = failure; }
                }
                @Override public void onCompletion(Server server) {
                    // Roda na thread principal: libera a vaga e chama o proximo da fila.
                    --activeHashes;
                    Runnable next = pendingHashes.poll();
                    if (next != null) { next.run(); }
                    if (this.error == null) { result.complete(this.answer); } else { result.completeExceptionally(this.error); }
                }
            });
        };
        if (this.activeHashes < MAX_PARALLEL_HASHES) {
            start.run();
        } else if (this.pendingHashes.size() < MAX_QUEUED_HASHES) {
            this.pendingHashes.add(start);
            player.sendMessage("Verificando sua senha, aguarde alguns segundos...");
        } else {
            result.completeExceptionally(new IllegalStateException("Fila de verificacao de senhas cheia."));
        }
        return result;
    }

    private void authenticated(Session session, PlayerAuthEvent.Method method) {
        session.authenticated = true;
        session.failures = 0;
        session.player.sendMessage(method == PlayerAuthEvent.Method.REGISTER ? "Conta registrada e autenticada." : "Login realizado com sucesso.");
        getServer().getPluginManager().callEvent(new PlayerAuthEvent(session.player, method));
        // Boas-vindas visual: o GoldCore limita alcance, quantidade e densidade conforme o TPS.
        EffectService effects = getServer().getServiceManager().load(EffectService.class);
        if (effects != null && session.player.spawned) { effects.follow(session.player, EffectService.Shape.CELEBRACAO, 40); }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        requireMainThread();
        if (command.getName().equals("authreset")) {
            if (!sender.hasPermission("goldauth.admin") || (sender instanceof Player && !isAuthenticated((Player) sender))) {
                sender.sendMessage("Sem permissao ou autenticacao para remover contas."); return true;
            }
            if (args.length != 1 || !PlayerName.isValid(args[0])) { return false; }
            String name = args[0].toLowerCase(Locale.ROOT);
            for (Session session : new ArrayList<>(this.sessions.values())) {
                if (session.name.equals(name)) { this.sessions.remove(session.player); session.player.close("", "Seu registro foi removido pela administracao."); }
            }
            this.core.getStore().delete("goldauth", name).whenComplete((ignored, error) -> sender.sendMessage(error == null ? "Registro removido. O jogador precisara registrar uma nova senha." : "Nao foi possivel remover o registro."));
            return true;
        }
        if (!(sender instanceof Player)) { sender.sendMessage("Este comando precisa ser usado dentro do jogo."); return true; }
        Player player = (Player) sender;
        CompletableFuture<Boolean> operation;
        if (command.getName().equals("registrar")) {
            if (args.length != 2) { return false; }
            if (!args[0].equals(args[1])) { player.sendMessage("A confirmacao nao corresponde a senha."); return true; }
            operation = register(player, args[0].toCharArray());
        } else if (command.getName().equals("login")) {
            if (args.length != 1) { return false; }
            operation = login(player, args[0].toCharArray());
        } else if (command.getName().equals("senha")) {
            if (args.length != 2) { return false; }
            operation = changePassword(player, args[0].toCharArray(), args[1].toCharArray());
        } else { return false; }
        // A mensagem de erro nao inclui argumentos nem a senha digitada.
        operation.exceptionally(error -> false);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void preLogin(PlayerPreLoginEvent event) {
        for (Session session : this.sessions.values()) {
            if (session.authenticated && session.player.isConnected() && session.player != event.getPlayer()
                    && session.name.equals(event.getPlayer().getName().toLowerCase(Locale.ROOT))) {
                event.setCancelled(); event.setKickMessage("Esta conta ja esta autenticada no servidor."); return;
            }
        }
    }

    @EventHandler public void join(PlayerJoinEvent event) { session(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        Session session = this.sessions.remove(event.getPlayer());
        if (session != null) { session.loaded.completeExceptionally(new IllegalStateException("Jogador desconectado.")); }
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void move(PlayerMoveEvent event) {
        if (!isAuthenticated(event.getPlayer()) && event.getFrom().distanceSquared(event.getTo()) > 0.0001) { event.setCancelled(); }
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void commands(PlayerCommandPreprocessEvent event) {
        if (isAuthenticated(event.getPlayer())) { return; }
        String label = event.getMessage().split("\\s+", 2)[0];
        if (label.startsWith("/")) { label = label.substring(1); }
        Command command = getServer().getCommandMap().getCommand(label.toLowerCase(Locale.ROOT));
        if (command instanceof PluginIdentifiableCommand && ((PluginIdentifiableCommand) command).getPlugin() == this
                && (command.getName().equals("login") || command.getName().equals("registrar"))) { return; }
        event.setCancelled();
        event.getPlayer().sendMessage("Autentique-se antes de usar comandos.");
    }
    @EventHandler(priority = EventPriority.LOWEST) public void chat(PlayerChatEvent event) {
        if (isAuthenticated(event.getPlayer())) { return; }
        boolean previouslyCancelled = event.isCancelled();
        String password = event.getMessage();
        event.setMessage("");
        event.setCancelled();
        Session session = this.sessions.get(event.getPlayer());
        if (!previouslyCancelled && session != null && session.loaded.isDone() && session.passwordHash != null) {
            login(event.getPlayer(), password.toCharArray()).exceptionally(error -> false);
        } else { event.getPlayer().sendMessage("Use /registrar <senha> <confirmacao> ou aguarde o carregamento da conta."); }
    }
    private void block(Event event, Player player) { if (player != null && !isAuthenticated(player)) { event.setCancelled(); } }
    @EventHandler(priority = EventPriority.HIGHEST) public void interact(PlayerInteractEvent event) { block(event, event.getPlayer()); }
    @EventHandler(priority = EventPriority.HIGHEST) public void breaking(BlockBreakEvent event) { block(event, event.getPlayer()); }
    @EventHandler(priority = EventPriority.HIGHEST) public void placing(BlockPlaceEvent event) { block(event, event.getPlayer()); }
    @EventHandler(priority = EventPriority.HIGHEST) public void dropping(PlayerDropItemEvent event) { block(event, event.getPlayer()); }
    @EventHandler(priority = EventPriority.HIGHEST) public void opening(InventoryOpenEvent event) {
        // A janela do inventario proprio faz parte da inicializacao do Player.
        if (event.getInventory() != event.getPlayer().getInventory()) { block(event, event.getPlayer()); }
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void transacting(InventoryTransactionEvent event) {
        if (event.getTransaction() instanceof cn.nukkit.inventory.SimpleTransactionGroup) {
            block(event, ((cn.nukkit.inventory.SimpleTransactionGroup) event.getTransaction()).getSource());
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void packets(cn.nukkit.event.server.DataPacketReceiveEvent event) {
        if (isAuthenticated(event.getPlayer())) { return; }
        // Algumas acoes antigas nao passam por PlayerInteractEvent, como item frames.
        switch (event.getPacket().pid()) {
            case cn.nukkit.network.protocol.ProtocolInfo.PLAYER_ACTION_PACKET:
                if (((cn.nukkit.network.protocol.PlayerActionPacket) event.getPacket()).action == cn.nukkit.network.protocol.PlayerActionPacket.ACTION_RESPAWN) { return; }
                event.setCancelled(); return;
            case cn.nukkit.network.protocol.ProtocolInfo.USE_ITEM_PACKET:
            case cn.nukkit.network.protocol.ProtocolInfo.REMOVE_BLOCK_PACKET:
            case cn.nukkit.network.protocol.ProtocolInfo.INTERACT_PACKET:
            case cn.nukkit.network.protocol.ProtocolInfo.DROP_ITEM_PACKET:
            case cn.nukkit.network.protocol.ProtocolInfo.CONTAINER_SET_SLOT_PACKET:
            case cn.nukkit.network.protocol.ProtocolInfo.CONTAINER_SET_CONTENT_PACKET:
            case cn.nukkit.network.protocol.ProtocolInfo.CRAFTING_EVENT_PACKET:
            case cn.nukkit.network.protocol.ProtocolInfo.BLOCK_ENTITY_DATA_PACKET:
            case cn.nukkit.network.protocol.ProtocolInfo.ITEM_FRAME_DROP_ITEM_PACKET:
            case cn.nukkit.network.protocol.ProtocolInfo.MOB_EQUIPMENT_PACKET:
            case cn.nukkit.network.protocol.ProtocolInfo.MOB_ARMOR_EQUIPMENT_PACKET:
            case cn.nukkit.network.protocol.ProtocolInfo.REPLACE_SELECTED_ITEM_PACKET:
                event.setCancelled(); return;
            default: return;
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void picking(InventoryPickupItemEvent event) {
        if (event.getInventory().getHolder() instanceof Player) { block(event, (Player) event.getInventory().getHolder()); }
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void damage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) { block(event, (Player) event.getEntity()); }
        if (event instanceof EntityDamageByEntityEvent && ((EntityDamageByEntityEvent) event).getDamager() instanceof Player) {
            block(event, (Player) ((EntityDamageByEntityEvent) event).getDamager());
        }
    }

    private static final class Session {
        private final Player player;
        private final String name;
        private final long created = System.nanoTime();
        private final CompletableFuture<Void> loaded = new CompletableFuture<>();
        private String passwordHash;
        private boolean authenticated;
        private boolean busy;
        private int failures;
        private Session(Player player) { this.player = player; this.name = player.getName().toLowerCase(Locale.ROOT); }
    }
    private static final class HashAnswer {
        private final boolean matches;
        private final String hash;
        private HashAnswer(boolean matches, String hash) { this.matches = matches; this.hash = hash; }
    }
}
