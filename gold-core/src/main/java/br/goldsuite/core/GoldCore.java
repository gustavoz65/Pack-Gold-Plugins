package br.goldsuite.core;

import br.goldsuite.api.AsyncStore;
import br.goldsuite.api.CoreService;
import br.goldsuite.api.EffectService;
import br.goldsuite.api.ItemSigner;
import br.goldsuite.api.TravelService;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityLevelChangeEvent;
import cn.nukkit.event.player.PlayerDeathEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.Player;
import cn.nukkit.plugin.PluginBase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class GoldCore extends PluginBase implements CoreService, AsyncStore, Listener {
    private CompletableFuture<Void> ready;
    private AsyncDispatcher dispatcher;
    private SqliteBackend backend;
    private ItemSigner signer;
    private EffectEngine effects;
    private TravelEngine travel;

    @Override
    public void onEnable() {
        this.ready = new CompletableFuture<>();
        this.dispatcher = new AsyncDispatcher(this);
        Path directory = getDataFolder().toPath();
        this.backend = new SqliteBackend(directory.resolve("goldsuite.db"));
        getServer().getServiceManager().register(CoreService.class, this, this);
        // Efeitos e viagens nao dependem do banco: ficam disponiveis desde o inicio.
        this.effects = new EffectEngine(this);
        this.travel = new TravelEngine(this, this.effects);
        getServer().getServiceManager().register(EffectService.class, this.effects, this);
        getServer().getServiceManager().register(TravelService.class, this.travel, this);
        getServer().getPluginManager().registerEvents(this, this);
        this.dispatcher.submit(() -> {
            this.backend.initialize();
            Path keyFile = directory.resolve("assinatura.key");
            if (!Files.exists(keyFile)) {
                byte[] secret = new byte[32];
                new SecureRandom().nextBytes(secret);
                Files.write(keyFile, secret, StandardOpenOption.CREATE_NEW);
            }
            byte[] secret = Files.readAllBytes(keyFile);
            if (secret.length != 32) { throw new IllegalStateException("A chave de assinatura precisa ter 32 bytes."); }
            return secret;
        }).whenComplete((secret, error) -> {
            if (error != null || !isEnabled()) {
                Throwable cause = error == null ? new IllegalStateException("GoldCore foi desativado durante o inicio.") : error;
                this.ready.completeExceptionally(cause);
                if (isEnabled()) {
                    getLogger().error("Nao foi possivel preparar o armazenamento da GoldSuite.", cause);
                    getServer().getPluginManager().disablePlugin(this);
                }
                return;
            }
            this.signer = new HmacItemSigner(secret);
            this.ready.complete(null);
            getLogger().info("Base da GoldSuite pronta: SQLite assincrono e assinatura de itens.");
        });
    }

    @Override public CompletableFuture<Void> whenReady() { return this.ready; }
    @Override public AsyncStore getStore() { return this; }
    @Override public ItemSigner getItemSigner() {
        if (this.signer == null) { throw new IllegalStateException("Aguarde whenReady antes de acessar a assinatura de itens."); }
        return this.signer;
    }

    @Override public CompletableFuture<Optional<String>> get(String namespace, String key) {
        return this.ready.thenCompose(ignored -> this.dispatcher.submit(() -> this.backend.get(namespace, key)));
    }
    @Override public CompletableFuture<Void> put(String namespace, String key, String value) {
        return this.ready.thenCompose(ignored -> this.dispatcher.submit(() -> { this.backend.put(namespace, key, value); return null; }));
    }
    @Override public CompletableFuture<Void> delete(String namespace, String key) {
        return this.ready.thenCompose(ignored -> this.dispatcher.submit(() -> { this.backend.delete(namespace, key); return null; }));
    }

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) { this.travel.cancel(event.getPlayer()); this.effects.stop(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void death(PlayerDeathEvent event) { this.travel.cancel(event.getEntity()); this.effects.stop(event.getEntity()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void level(EntityLevelChangeEvent event) {
        if (event.getEntity() instanceof Player && !this.travel.isTraveling((Player) event.getEntity())) { this.effects.stop((Player) event.getEntity()); }
    }

    @Override
    public void onDisable() {
        if (this.travel != null) { this.travel.close(); }
        if (this.effects != null) { this.effects.close(); }
        if (this.dispatcher != null && !this.dispatcher.closeAndDrain()) {
            getLogger().warning("O armazenamento nao terminou todas as operacoes no prazo de desligamento.");
        }
        if (this.ready != null && !this.ready.isDone()) { this.ready.completeExceptionally(new IllegalStateException("GoldCore encerrado.")); }
    }
}
