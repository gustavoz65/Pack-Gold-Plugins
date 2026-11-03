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
    static final String SECURITY = "goldauth-2fa";
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
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
        M.B.attach(this);
        this.mainThread = Thread.currentThread();
        this.core = getServer().getServiceManager().load(CoreService.class);
        if (this.core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo_antes_do")); }
        getServer().getServiceManager().register(AuthService.class, this, this);
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : getServer().getOnlinePlayers().values()) { session(player); }
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldAuth>(this) {
            @Override public void onRun(int tick) {
                long now = System.nanoTime();
                attempts.values().removeIf(window -> window.expired(now));
                for (Session session : new ArrayList<>(sessions.values())) {
                    if (!session.authenticated && now - session.created >= 120_000_000_000L) {
                        session.player.close("", M.t("tempo_para_autenticar_esgotado"));
                    }
                }
            }
        }, 20);
        getLogger().info("Autenticacao local ativada. Registro e login sao obrigatorios, inclusive para operadores.");
    }

    @Override
    public void onDisable() {
        for (Session session : new ArrayList<>(this.sessions.values())) {
            session.loaded.completeExceptionally(new IllegalStateException(M.t("autenticacao_encerrada")));
            session.player.close("", M.t("autenticacao_reiniciada_entre_novamente"));
        }
        this.sessions.clear();
        this.attempts.clear();
        this.pendingHashes.clear();
    }

    private void requireMainThread() {
        if (Thread.currentThread() != this.mainThread) { throw new IllegalStateException(M.t("api_de_autenticacao_deve_ser_chamada")); }
    }

    private Session session(Player player) {
        requireMainThread();
        Session existing = this.sessions.get(player);
        if (existing != null) { return existing; }
        if (!PlayerName.isValid(player.getName())) { throw new IllegalArgumentException(M.t("jogador_sem_nome_valido")); }
        Session session = new Session(player);
        this.sessions.put(player, session);
        CompletableFuture<java.util.Optional<String>> security = this.core.getStore().get(SECURITY, session.name);
        this.core.getStore().get("goldauth", session.name).thenCombine(security, (stored, twoFactor) -> new Object[] {stored, twoFactor}).whenComplete((both, error) -> {
            if (!current(session)) { session.loaded.completeExceptionally(new IllegalStateException(M.t("sessao_encerrada"))); return; }
            if (error != null) {
                session.loaded.completeExceptionally(error);
                player.close("", M.t("nao_foi_possivel_carregar_sua_conta"));
                return;
            }
            @SuppressWarnings("unchecked") java.util.Optional<String> stored = (java.util.Optional<String>) both[0];
            @SuppressWarnings("unchecked") java.util.Optional<String> twoFactor = (java.util.Optional<String>) both[1];
            session.passwordHash = stored.orElse(null);
            if (twoFactor.isPresent()) {
                try { session.security = GSON.fromJson(twoFactor.get(), Security.class); session.security.validate(); }
                catch (RuntimeException bad) { getLogger().warning("2FA invalido de " + session.name + "; mantendo bloqueio ate a staff resetar."); session.security = new Security(); session.security.secret = "INVALIDOINVALIDO"; }
            }
            session.loaded.complete(null);
            player.sendMessage(session.passwordHash == null ? M.t("registre_se_registrar_senha_confirmacao") : M.t("entre_com_login_senha_voce_tambem"));
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
            rejected.completeExceptionally(new IllegalStateException(M.t("goldauth_esta_desativado")));
            return rejected;
        }
        Session session = session(player);
        if (session.busy) { return reject(player, M.t("aguarde_verificacao_anterior")); }
        try { PasswordHasher.validate(input); if (mode == Mode.CHANGE) { PasswordHasher.validate(replacementInput); } }
        catch (IllegalArgumentException error) { return reject(player, error.getMessage()); }
        char[] password = input.clone();
        char[] replacement = replacementInput == null ? null : replacementInput.clone();
        session.busy = true;
        CompletableFuture<Boolean> result = session.loaded.thenCompose(ignored -> {
            if (!current(session)) { return CompletableFuture.completedFuture(false); }
            if (mode == Mode.REGISTER && session.passwordHash != null) { return reject(player, M.t("esta_conta_ja_possui_registro_use")); }
            if (mode != Mode.REGISTER && session.passwordHash == null) { return reject(player, M.t("registre_sua_conta_primeiro_com_registrar")); }
            if (mode == Mode.CHANGE && !session.authenticated) { return reject(player, M.t("entre_na_conta_antes_de_alterar")); }
            if (mode != Mode.CHANGE && session.authenticated) { return reject(player, M.t("voce_ja_esta_autenticado")); }
            long now = System.nanoTime();
            AttemptWindow window = this.attempts.get(player.getAddress());
            if (window == null) {
                if (this.attempts.size() >= 2048) { return reject(player, M.t("autenticacao_ocupada_aguarde_tente_novamente")); }
                window = new AttemptWindow(now);
                this.attempts.put(player.getAddress(), window);
            }
            if (!window.allowed(now)) { return reject(player, M.t("muitas_senhas_erradas_deste_ip_aguarde")); }
            final AttemptWindow ipWindow = window;
            return hash(player, password, replacement, session.passwordHash, mode).thenCompose(answer -> {
                if (!current(session)) { return CompletableFuture.completedFuture(false); }
                if (!answer.matches) {
                    ipWindow.fail(System.nanoTime());
                    player.sendMessage(M.t("senha_incorreta"));
                    if (++session.failures >= 5) { player.close("", M.t("limite_de_senhas_incorretas_atingido")); }
                    return CompletableFuture.completedFuture(false);
                }
                if (mode == Mode.LOGIN) {
                    // Senha certa num aparelho desconhecido de conta com 2FA: falta o codigo do app.
                    if (session.security != null && session.security.enabled() && !session.security.trusts(session.device)) {
                        session.pending2fa = true;
                        player.sendMessage(M.t("aparelho_novo_nesta_conta_digite_codigo"));
                        return CompletableFuture.completedFuture(true);
                    }
                    authenticated(session, PlayerAuthEvent.Method.LOGIN);
                    return CompletableFuture.completedFuture(true);
                }
                return this.core.getStore().put("goldauth", session.name, answer.hash).thenApply(written -> {
                    if (!current(session)) { return false; }
                    session.passwordHash = answer.hash;
                    if (mode == Mode.REGISTER) { authenticated(session, PlayerAuthEvent.Method.REGISTER); }
                    else { player.sendMessage(M.t("senha_alterada_com_sucesso")); }
                    return true;
                });
            });
        });
        return result.whenComplete((success, error) -> {
            Arrays.fill(password, '\0');
            if (replacement != null) { Arrays.fill(replacement, '\0'); }
            session.busy = false;
            if (error != null && current(session)) { player.sendMessage(M.t("nao_foi_possivel_concluir_autenticacao_tente")); }
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
            player.sendMessage(M.t("verificando_sua_senha_aguarde_alguns_segundos"));
        } else {
            result.completeExceptionally(new IllegalStateException(M.t("fila_de_verificacao_de_senhas_cheia")));
        }
        return result;
    }

    private void authenticated(Session session, PlayerAuthEvent.Method method) {
        session.authenticated = true;
        session.pending2fa = false;
        session.failures = 0;
        session.player.sendMessage(method == PlayerAuthEvent.Method.REGISTER ? M.t("conta_registrada_autenticada_proteja_sua_conta") : M.t("login_realizado_com_sucesso"));
        if (method == PlayerAuthEvent.Method.LOGIN && (session.security == null || !session.security.enabled())) { session.player.sendMessage(M.t("dica_ative_verificacao_em_duas_etapas")); }
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
                sender.sendMessage(M.t("sem_permissao_ou_autenticacao_para_remover")); return true;
            }
            if (args.length != 1 || !PlayerName.isValid(args[0])) { return false; }
            String name = args[0].toLowerCase(Locale.ROOT);
            for (Session session : new ArrayList<>(this.sessions.values())) {
                if (session.name.equals(name)) { this.sessions.remove(session.player); session.player.close("", M.t("seu_registro_foi_removido_pela_administracao")); }
            }
            this.core.getStore().delete(SECURITY, name);
            this.core.getStore().delete("goldauth", name).whenComplete((ignored, error) -> sender.sendMessage(error == null ? M.t("registro_removido_jogador_precisara_registrar_um") : M.t("nao_foi_possivel_remover_registro")));
            return true;
        }
        if (command.getName().equals("2fa")) { return twoFactorCommand(sender, args); }
        if (!(sender instanceof Player)) { sender.sendMessage(M.t("este_comando_precisa_ser_usado_dentro")); return true; }
        Player player = (Player) sender;
        CompletableFuture<Boolean> operation;
        if (command.getName().equals("registrar")) {
            if (args.length != 2) { return false; }
            if (!args[0].equals(args[1])) { player.sendMessage(M.t("confirmacao_nao_corresponde_senha")); return true; }
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
                event.setCancelled(); event.setKickMessage(M.t("esta_conta_ja_esta_autenticada_no")); return;
            }
        }
    }

    @EventHandler public void join(PlayerJoinEvent event) { session(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        Session session = this.sessions.remove(event.getPlayer());
        if (session != null) { session.loaded.completeExceptionally(new IllegalStateException(M.t("jogador_desconectado"))); }
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
                && (command.getName().equals("login") || command.getName().equals("registrar") || command.getName().equals("2fa"))) { return; }
        event.setCancelled();
        event.getPlayer().sendMessage(M.t("autentique_se_antes_de_usar_comandos"));
    }
    @EventHandler(priority = EventPriority.LOWEST) public void chat(PlayerChatEvent event) {
        if (isAuthenticated(event.getPlayer())) { return; }
        boolean previouslyCancelled = event.isCancelled();
        String password = event.getMessage();
        event.setMessage("");
        event.setCancelled();
        Session session = this.sessions.get(event.getPlayer());
        if (!previouslyCancelled && session != null && session.pending2fa) { twoFactorLogin(session, password); return; }
        if (!previouslyCancelled && session != null && session.loaded.isDone() && session.passwordHash != null) {
            login(event.getPlayer(), password.toCharArray()).exceptionally(error -> false);
        } else { event.getPlayer().sendMessage(M.t("use_registrar_senha_confirmacao_ou_aguarde")); }
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

    // ---------- verificacao em duas etapas ----------

    /** Codigo do app depois da senha certa num aparelho novo. */
    private void twoFactorLogin(Session session, String code) {
        Player player = session.player;
        AttemptWindow window = this.attempts.get(player.getAddress());
        if (window == null) {
            if (this.attempts.size() >= 2048) { player.sendMessage(M.t("autenticacao_ocupada_aguarde_tente_novamente")); return; }
            window = new AttemptWindow(System.nanoTime());
            this.attempts.put(player.getAddress(), window);
        }
        if (!window.allowed(System.nanoTime())) { player.sendMessage(M.t("muitos_codigos_errados_deste_ip_aguarde")); return; }
        if (!Totp.verify(session.security.secret, code, System.currentTimeMillis())) {
            window.fail(System.nanoTime());
            player.sendMessage(M.t("codigo_incorreto_use_codigo_atual_do"));
            if (++session.failures >= 5) { player.close("", M.t("limite_de_codigos_incorretos_atingido")); }
            return;
        }
        session.security.trust(session.device);
        this.core.getStore().put(SECURITY, session.name, GSON.toJson(session.security));
        player.sendMessage(M.t("aparelho_confirmado_nas_proximas_vezes_neste"));
        authenticated(session, PlayerAuthEvent.Method.LOGIN);
    }

    private boolean twoFactorCommand(CommandSender sender, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("reset") && sender.hasPermission("goldauth.admin")) {
            if (sender instanceof Player && !isAuthenticated((Player) sender)) { return true; }
            String name = args[1].toLowerCase(Locale.ROOT);
            for (Session s : this.sessions.values()) { if (s.name.equals(name)) { s.security = null; } }
            this.core.getStore().delete(SECURITY, name).whenComplete((v, e) -> sender.sendMessage(e == null ? M.t("2fa_de_removido_ele_entra_so", name) : M.t("nao_foi_possivel_remover")));
            return true;
        }
        if (!(sender instanceof Player)) { sender.sendMessage(M.t("use_2fa_reset_jogador")); return true; }
        Player player = (Player) sender;
        Session session = this.sessions.get(player);
        if (session == null) { return true; }
        if (session.pending2fa) { if (args.length >= 1) { twoFactorLogin(session, String.join("", args)); } else { player.sendMessage(M.t("use_2fa_codigo_de_6_numeros")); } return true; }
        if (!session.authenticated) { player.sendMessage(M.t("entre_na_conta_primeiro_com_login")); return true; }
        String sub = args.length == 0 ? "ajuda" : args[0].toLowerCase(Locale.ROOT);
        boolean on = session.security != null && session.security.enabled();
        switch (sub) {
            case "ativar": {
                if (on) { player.sendMessage(M.t("verificacao_em_duas_etapas_ja_esta")); return true; }
                session.pendingSecret = Totp.newSecret();
                String s = session.pendingSecret;
                player.sendMessage(M.t("1_instale_google_authenticator_authy_ou"));
                player.sendMessage(M.t("2_toque_em_adicionar_conta_inserir"));
                player.sendMessage("§a§l" + s.substring(0, 8) + " " + s.substring(8, 16) + " " + s.substring(16, 24) + " " + s.substring(24));
                player.sendMessage(M.t("3_confirme_com_2fa_confirmar_codigo"));
                return true;
            }
            case "confirmar": {
                if (session.pendingSecret == null) { player.sendMessage(M.t("use_2fa_ativar_primeiro")); return true; }
                if (args.length < 2 || !Totp.verify(session.pendingSecret, args[1], System.currentTimeMillis())) { player.sendMessage(M.t("codigo_incorreto_confira_chave_digitada_no")); return true; }
                Security security = new Security();
                security.secret = session.pendingSecret;
                security.trust(session.device);
                this.core.getStore().put(SECURITY, session.name, GSON.toJson(security)).whenComplete((v, e) -> {
                    if (e != null) { player.sendMessage(M.t("nao_foi_possivel_salvar_tente_de")); return; }
                    session.security = security; session.pendingSecret = null;
                    player.sendMessage(M.t("verificacao_em_duas_etapas_ativada_em"));
                });
                return true;
            }
            case "desativar": {
                if (!on) { player.sendMessage(M.t("verificacao_em_duas_etapas_nao_esta")); return true; }
                if (args.length < 2 || !Totp.verify(session.security.secret, args[1], System.currentTimeMillis())) { player.sendMessage(M.t("use_2fa_desativar_codigo_atual_do")); return true; }
                this.core.getStore().delete(SECURITY, session.name).whenComplete((v, e) -> { if (e == null) { session.security = null; player.sendMessage(M.t("verificacao_em_duas_etapas_desativada")); } });
                return true;
            }
            case "aparelhos": {
                if (!on) { player.sendMessage(M.t("ative_primeiro_2fa_ativar")); return true; }
                if (args.length >= 2 && args[1].equalsIgnoreCase("limpar")) {
                    session.security.trusted.clear();
                    session.security.trust(session.device);
                    this.core.getStore().put(SECURITY, session.name, GSON.toJson(session.security));
                    player.sendMessage(M.t("pronto_so_este_aparelho_continua_confiavel"));
                } else { player.sendMessage(M.t("aparelhos_confiaveis_2fa_aparelhos_limpar_remove", session.security.trusted.size())); }
                return true;
            }
            default:
                player.sendMessage(M.t("verificacao_em_duas_etapas", (on ? M.t("ativa") : M.t("desativada"))));
                player.sendMessage(M.t("2fa_ativar_2fa_confirmar_codigo_2fa"));
                return true;
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
        /** 2FA da conta (null = nunca ativou), senha certa aguardando o codigo, segredo novo aguardando confirmacao. */
        private Security security;
        private boolean pending2fa;
        private String pendingSecret;
        private final String device;
        private Session(Player player) { this.player = player; this.name = player.getName().toLowerCase(Locale.ROOT); this.device = Totp.device(player.getDeviceFingerprint()); }
    }
    private static final class HashAnswer {
        private final boolean matches;
        private final String hash;
        private HashAnswer(boolean matches, String hash) { this.matches = matches; this.hash = hash; }
    }
}
