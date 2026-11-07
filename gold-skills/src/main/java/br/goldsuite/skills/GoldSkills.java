package br.goldsuite.skills;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.EntityLiving;
import cn.nukkit.entity.projectile.EntityArrow;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.entity.EntityDamageByChildEntityEvent;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.*;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.potion.Effect;
import cn.nukkit.scheduler.PluginTask;
import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Habilidades no estilo do mcMMO do pack (traduzido PT-BR), feitas para o 0.15: XP por bloco e combate com os mesmos
 * numeros, niveis ate 1000 (poder ate 7000), drops duplos, tesouros, sangramento, criticos, rolamento, reparo na
 * bigorna de ferro e poderes ativados agachando com a ferramenta. Inclui boosters (vBoosterMcMMO/MCBooster), anuncio de
 * nivel (DarkmcMMOUP/reStatsUP), tag do top 1 (MCTopTag) e o menu /skills (fyStats).
 * Memoria: so quem esta online tem o perfil completo; o ranking guarda so os niveis de todos.
 */
public final class GoldSkills extends PluginBase implements Listener {
    static String PREFIX = M.t("habilidades");
    static final String NS = "goldskills", GLOBAL = "goldskills-global";
    static final int N = Skill.ALL.length;

    /** Perfil de quem esta online. */
    static final class State {
        final int[] level = new int[N], xp = new int[N];
        final long[] cooldown = new long[N];
        /** Boosters: indice da habilidade (N = todas) -> {multiplicador x10, expira em}. */
        final Map<Integer, long[]> boosters = new HashMap<>(2);
        boolean loaded, dirty, abilities = true;
        Skill ready, active;
        long readyUntil, activeUntil, lastPopup;
        int power() { int t = 0; for (int l : level) { t += l; } return t; }
    }

    private CoreService core;
    private final Map<Player, State> states = new HashMap<>();
    /** Niveis de todos os jogadores (para /mctop, /mcrank e a tag do top 1): 2 bytes por habilidade. */
    private final Map<String, short[]> index = new HashMap<>();
    private final PlacedBlocks placed = new PlacedBlocks();
    private File placedFile;
    private boolean placedDirty;
    /** Sangramento: entidade -> {ticks restantes, dano x10}. */
    private final Map<Entity, int[]> bleeding = new HashMap<>();
    private double globalRate = 1;
    private long globalUntil;
    private String topName = "";
    private final Map<String, List<Map.Entry<String, Integer>>> topCache = new HashMap<>();
    private long topCacheAt;

    // painel
    private boolean abilitiesOn;
    private int abilityCooldown = 240, boosterSeconds = 300, boosterMult = 2;
    private long boosterPrice = 10000, boosterAllPrice = 50000;
    private Set<Integer> milestones = new HashSet<>();
    private String announce = "", topTag = "";

    @Override public void onEnable() {
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo_antes_do")); }
        Settings.number(this, "poderes", 0, "1 = liga os poderes (agachar com a ferramenta); 0 = desligados como no mcMMO do pack", v -> abilitiesOn = v == 1);
        Settings.number(this, "poderes.recarga", 240, "Segundos de recarga de cada poder", v -> abilityCooldown = (int) Math.max(10, v));
        Settings.number(this, "booster.segundos", 300, "Duracao do booster comprado (segundos)", v -> boosterSeconds = (int) Math.max(30, v));
        Settings.number(this, "booster.multiplicador", 2, "Multiplicador de XP do booster comprado", v -> boosterMult = (int) Math.max(2, Math.min(10, v)));
        Settings.number(this, "booster.preco", 10000, "Preco do booster de uma habilidade (moedas)", v -> boosterPrice = Math.max(0, v));
        Settings.number(this, "booster.preco_todas", 50000, "Preco do booster de todas as habilidades (moedas)", v -> boosterAllPrice = Math.max(0, v));
        Settings.text(this, "anuncio.niveis", "50,100,200,300,400,500,600,700,800,900,1000", "Niveis anunciados para todos (separados por virgula; vazio = nenhum)", v -> {
            Set<Integer> s = new HashSet<>();
            for (String part : v.split("[,;\\s]+")) { try { s.add(Integer.parseInt(part.trim())); } catch (NumberFormatException ignored) { } }
            milestones = s;
        });
        Settings.text(this, "anuncio.mensagem", "&6&lHABILIDADES: &f{jogador} &eatingiu o nivel &6{nivel} &ena habilidade de &6{habilidade}&e!", "Anuncio de nivel ({jogador}, {nivel}, {habilidade})", v -> announce = v);
        Settings.text(this, "toptag", "&9[⚒]", "Tag do 1o no ranking de poder (placeholder {mctop} no chat)", v -> topTag = v);
        Settings.placeholder(this, "poder", p -> { State s = states.get(p); return s == null ? "0" : String.valueOf(s.power()); });
        Settings.placeholder(this, "mctop", p -> !topName.isEmpty() && p.getName().equalsIgnoreCase(topName) ? topTag.replace('&', '§') + " " : "");
        for (Skill sk : Skill.ALL) { Settings.placeholder(this, "nivel_" + sk.id(), p -> { State s = states.get(p); return s == null ? "0" : String.valueOf(s.level[sk.ordinal()]); }); }
        getDataFolder().mkdirs();
        placedFile = new File(getDataFolder(), "blocos-colocados.bin");
        try { placed.load(placedFile); } catch (Exception e) { getLogger().warning("Blocos colocados nao carregados: " + e.getMessage()); }
        core.getStore().all(NS).thenAccept(rows -> {
            for (Map.Entry<String, String> e : rows.entrySet()) { short[] lv = levelsOf(e.getValue()); if (lv != null) { index.put(e.getKey(), lv); } }
            getLogger().info(index.size() + " perfil(is) de habilidades no ranking; " + placed.size() + " bloco(s) colocado(s) rastreado(s).");
            RankingService ranking = getServer().getServiceManager().load(RankingService.class);
            if (ranking != null) {
                ranking.register("mctop", limit -> {
                    List<RankingService.Entry> out = new ArrayList<>();
                    for (Map.Entry<String, Integer> e : ranking(null)) { if (out.size() >= limit) { break; } out.add(new RankingService.Entry(e.getKey(), e.getValue(), M.t("podio_mctop", e.getValue()))); }
                    return out;
                });
            }
            refreshTop();
        });
        core.getStore().get(GLOBAL, "xprate").thenAccept(v -> v.ifPresent(s -> { String[] p = s.split(":"); try { globalRate = Double.parseDouble(p[0]); globalUntil = Long.parseLong(p[1]); } catch (RuntimeException ignored) { } }));
        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : getServer().getOnlinePlayers().values()) { load(p); }
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldSkills>(this) { @Override public void onRun(int t) { bleed(); } }, 40);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldSkills>(this) { @Override public void onRun(int t) { save(false); } }, 20 * 30);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldSkills>(this) { @Override public void onRun(int t) { refreshTop(); } }, 20 * 300);
    }

    @Override public void onDisable() { save(true); states.clear(); bleeding.clear(); }

    // ---------- perfil ----------

    /** "nivel:xp" x10 ; boosters "i:mult:expira" separados por | */
    static String encode(State s) {
        StringBuilder b = new StringBuilder(80);
        for (int i = 0; i < N; i++) { b.append(i == 0 ? "" : ",").append(s.level[i]).append(':').append(s.xp[i]); }
        b.append(';').append(s.abilities ? 1 : 0).append(';');
        long now = System.currentTimeMillis();
        boolean first = true;
        for (Map.Entry<Integer, long[]> e : s.boosters.entrySet()) { if (e.getValue()[1] > now) { b.append(first ? "" : "|").append(e.getKey()).append(':').append(e.getValue()[0]).append(':').append(e.getValue()[1]); first = false; } }
        return b.toString();
    }

    static void decode(String v, State s) {
        String[] parts = v.split(";", -1);
        String[] skills = parts[0].split(",");
        for (int i = 0; i < N && i < skills.length; i++) {
            String[] lx = skills[i].split(":");
            s.level[i] = Math.max(0, Math.min(Xp.LEVEL_CAP, Integer.parseInt(lx[0])));
            s.xp[i] = Math.max(0, Integer.parseInt(lx[1]));
        }
        if (parts.length > 1 && !parts[1].isEmpty()) { s.abilities = !parts[1].equals("0"); }
        if (parts.length > 2 && !parts[2].isEmpty()) {
            for (String bst : parts[2].split("\\|")) { String[] f = bst.split(":"); if (f.length == 3) { s.boosters.put(Integer.parseInt(f[0]), new long[] {Long.parseLong(f[1]), Long.parseLong(f[2])}); } }
        }
    }

    static short[] levelsOf(String v) {
        try { State s = new State(); decode(v, s); short[] out = new short[N]; for (int i = 0; i < N; i++) { out[i] = (short) s.level[i]; } return out; } catch (RuntimeException e) { return null; }
    }

    private static String key(Player p) { return p.getName().toLowerCase(Locale.ROOT); }

    private void load(Player p) {
        State s = new State();
        states.put(p, s);
        core.getStore().get(NS, key(p)).thenAccept(v -> {
            if (states.get(p) != s) { return; }
            if (v.isPresent()) { try { decode(v.get(), s); } catch (RuntimeException e) { getLogger().warning("Perfil de habilidades invalido de " + p.getName() + "; comecando do zero."); } }
            s.loaded = true;
        });
    }

    /** Grava os perfis alterados num lote (e o arquivo de blocos colocados). */
    private void save(boolean all) {
        Map<String, String> rows = new HashMap<>();
        for (Map.Entry<Player, State> e : states.entrySet()) { if (e.getValue().loaded && (e.getValue().dirty || all)) { rows.put(key(e.getKey()), encode(e.getValue())); e.getValue().dirty = false; } }
        if (!rows.isEmpty()) { core.getStore().putAll(NS, rows); }
        if (placedDirty) { placedDirty = false; try { placed.save(placedFile); } catch (Exception e) { getLogger().warning("Blocos colocados nao gravados: " + e.getMessage()); } }
    }

    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent e) { load(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent e) {
        State s = states.remove(e.getPlayer());
        if (s != null && s.loaded) { core.getStore().put(NS, key(e.getPlayer()), encode(s)); }
        bleeding.remove(e.getPlayer());
    }

    // ---------- XP ----------

    private double multiplier(State s, Skill skill) {
        long now = System.currentTimeMillis();
        double m = globalUntil > now ? globalRate : 1;
        long best = 10;
        for (Map.Entry<Integer, long[]> e : s.boosters.entrySet()) { if ((e.getKey() == N || e.getKey() == skill.ordinal()) && e.getValue()[1] > now) { best = Math.max(best, e.getValue()[0]); } }
        return m * best / 10.0;
    }

    void addXp(Player p, Skill skill, double base) {
        State s = states.get(p);
        if (s == null || !s.loaded || base <= 0 || !Settings.allowed(p, "goldskills.xp")) { return; }
        int i = skill.ordinal();
        if (s.level[i] >= Xp.LEVEL_CAP || s.power() >= Xp.POWER_CAP) { return; }
        int gain = (int) Math.round(base * multiplier(s, skill));
        if (gain <= 0) { return; }
        s.xp[i] += gain;
        s.dirty = true;
        while (s.level[i] < Xp.LEVEL_CAP && s.power() < Xp.POWER_CAP && s.xp[i] >= Xp.needed(s.level[i])) {
            s.xp[i] -= Xp.needed(s.level[i]);
            s.level[i]++;
            levelUp(p, skill, s.level[i]);
        }
        if (s.level[i] >= Xp.LEVEL_CAP || s.power() >= Xp.POWER_CAP) { s.xp[i] = 0; }
        index.computeIfAbsent(key(p), k -> new short[N])[i] = (short) s.level[i];
        long now = System.currentTimeMillis();
        if (now - s.lastPopup > 600) {
            s.lastPopup = now;
            p.sendTip(M.t("xp", gain, skill.label, s.level[i], percent(s, i)));
        }
    }

    private static int percent(State s, int i) { return s.level[i] >= Xp.LEVEL_CAP ? 100 : (int) (100L * s.xp[i] / Xp.needed(s.level[i])); }

    private void levelUp(Player p, Skill skill, int level) {
        p.sendMessage(PREFIX + M.t("subiu_para_nivel", skill.label, level));
        if (milestones.contains(level) && !announce.isEmpty()) {
            getServer().broadcastMessage(announce.replace('&', '§').replace("{jogador}", p.getName()).replace("{nivel}", String.valueOf(level)).replace("{habilidade}", skill.label));
        }
    }

    int level(Player p, Skill skill) { State s = states.get(p); return s == null ? 0 : s.level[skill.ordinal()]; }

    private static boolean roll(double percent) { return ThreadLocalRandom.current().nextDouble() * 100 < percent; }

    // ---------- poderes (agachar com a ferramenta prepara; o proximo golpe/bloco ativa) ----------

    private static Skill toolSkill(Item hand) {
        if (hand.isPickaxe()) { return Skill.MINERACAO; }
        if (hand.isAxe()) { return Skill.LENHADOR; }
        if (hand.isShovel()) { return Skill.ESCAVACAO; }
        if (hand.isHoe()) { return Skill.HERBALISMO; }
        if (hand.isSword()) { return Skill.ESPADAS; }
        if (hand.getId() == 0) { return Skill.DESARMADO; }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void sneak(PlayerToggleSneakEvent e) {
        if (!abilitiesOn || !e.isSneaking()) { return; }
        Player p = e.getPlayer();
        State s = states.get(p);
        Skill skill = toolSkill(p.getInventory().getItemInHand());
        if (s == null || !s.loaded || !s.abilities || skill == null || !Settings.allowed(p, "goldskills.poderes")) { return; }
        long now = System.currentTimeMillis();
        if (s.activeUntil > now) { return; }
        if (s.cooldown[skill.ordinal()] > now) { p.sendTip(M.t("poder_recarregando", ((s.cooldown[skill.ordinal()] - now) / 1000 + 1))); return; }
        s.ready = skill; s.readyUntil = now + 4000;
        p.sendTip(M.t("voce_prepara", (skill == Skill.DESARMADO ? M.t("os_punhos") : M.t("ferramenta"))));
    }

    /** Ativa o poder preparado (do machado: no golpe vira Racha Cranio, no tronco vira Derrubador). */
    private boolean activate(Player p, State s, Skill used) {
        long now = System.currentTimeMillis();
        if (s.ready == null || s.readyUntil < now) { return false; }
        Skill skill = s.ready == Skill.LENHADOR && used == Skill.MACHADO ? Skill.MACHADO : s.ready;
        if (skill != used && !(s.ready == Skill.LENHADOR && used == Skill.MACHADO)) { return false; }
        s.ready = null;
        int seconds = 2 + s.level[skill.ordinal()] / 50;
        s.active = skill; s.activeUntil = now + seconds * 1000L;
        s.cooldown[skill.ordinal()] = now + (seconds + abilityCooldown) * 1000L;
        if (skill == Skill.MINERACAO || skill == Skill.ESCAVACAO || skill == Skill.HERBALISMO || skill == Skill.DESARMADO) {
            p.addEffect(Effect.getEffect(Effect.HASTE).setAmplifier(2).setDuration(seconds * 20).setVisible(false));
        }
        p.sendMessage(PREFIX + M.t("ativado", skill.ability.toUpperCase(Locale.ROOT), seconds));
        return true;
    }

    private boolean active(State s, Skill skill) { return s != null && s.active == skill && s.activeUntil > System.currentTimeMillis(); }

    // ---------- blocos ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        Block b = e.getBlock();
        if (Xp.tracked(b.getId())) { placed.add(placed.key(b.level.getFolderName(), b.getFloorX(), b.getFloorY(), b.getFloorZ())); placedDirty = true; }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent e) {
        Block b = e.getBlock();
        int id = b.getId(), meta = b.getDamage();
        if (!Xp.tracked(id)) { return; }
        if (placed.remove(placed.key(b.level.getFolderName(), b.getFloorX(), b.getFloorY(), b.getFloorZ()))) { placedDirty = true; return; }
        Player p = e.getPlayer();
        State s = states.get(p);
        if (s == null || !s.loaded || p.isCreative()) { return; }
        Item hand = e.getItem();
        Skill skill = Xp.skillOf(id, meta, hand.isPickaxe(), hand.isAxe(), hand.isShovel());
        if (skill == null) { return; }
        if (abilitiesOn) { activate(p, s, skill); }
        int lvl = s.level[skill.ordinal()];
        addXp(p, skill, Xp.blockXp(skill, id, meta) * skill.modifier);
        Vector3 at = new Vector3(b.x + 0.5, b.y + 0.5, b.z + 0.5);
        if (skill == Skill.LENHADOR && active(s, Skill.LENHADOR)) { treeFeller(p, b, hand); return; }
        if (skill != Skill.ESCAVACAO) {
            int extra = active(s, skill) ? 2 : roll(Xp.chance(lvl, 100, 1000)) ? 1 : 0;
            if (extra > 0) { drop(b, hand, at, extra); }
        } else {
            if (active(s, Skill.ESCAVACAO)) { drop(b, hand, at, 2); }
            for (Xp.Treasure t : Xp.TREASURES) {
                if (lvl >= t.level && Xp.from(t, id) && roll(t.chance)) { b.level.dropItem(at, Item.get(t.id, t.meta, 1)); addXp(p, skill, t.xp); }
            }
        }
        if (skill == Skill.HERBALISMO) { greenThumb(p, b, lvl, active(s, Skill.HERBALISMO)); }
    }

    /** Drops extras do bloco (drop duplo ou triplo do poder). */
    private static void drop(Block b, Item hand, Vector3 at, int times) {
        int[][] drops = b.getDrops(hand);
        if (drops == null) { return; }
        for (int t = 0; t < times; t++) { for (int[] d : drops) { if (d[0] != 0 && d[2] > 0) { b.level.dropItem(at, Item.get(d[0], d[1], d[2])); } } }
    }

    /** Polegar Verde: replanta a planta colhida usando uma semente do inventario. */
    private void greenThumb(Player p, Block b, int lvl, boolean always) {
        int seed = b.getId() == 59 ? 295 : b.getId() == 141 ? 391 : b.getId() == 142 ? 392 : b.getId() == 244 ? 458 : -1;
        if (seed < 0 || !(always || roll(Xp.chance(lvl, 100, 1500)))) { return; }
        Item need = Item.get(seed, 0, 1);
        if (!p.getInventory().contains(need)) { return; }
        p.getInventory().removeItem(need);
        Level lv = b.level;
        Vector3 pos = new Vector3(b.x, b.y, b.z);
        int crop = b.getId();
        getServer().getScheduler().scheduleDelayedTask(() -> { if (lv.getBlockIdAt((int) pos.x, (int) pos.y, (int) pos.z) == 0) { lv.setBlock(pos, Block.get(crop, 1), true, true); } }, 1);
    }

    /** Derrubador de Arvores: quebra os troncos ligados (ate 500) gastando a durabilidade do machado. */
    private void treeFeller(Player p, Block start, Item axe) {
        Level lv = start.level;
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(new int[] {start.getFloorX(), start.getFloorY(), start.getFloorZ()});
        int broken = 0, durability = axe.getMaxDurability() > 0 ? axe.getMaxDurability() - axe.getDamage() : 500;
        while (!queue.isEmpty() && broken < 500 && broken < durability) {
            int[] c = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int x = c[0] + dx, y = c[1] + dy, z = c[2] + dz;
                        long k = ((long) x << 38) ^ ((long) y << 26) ^ (z & 0x3ffffff);
                        if (y < 0 || y > 127 || !seen.add(k)) { continue; }
                        int id = lv.getBlockIdAt(x, y, z);
                        if (id != 17 && id != 162) { continue; }
                        Block log = lv.getBlock(new Vector3(x, y, z));
                        if (placed.remove(placed.key(lv.getFolderName(), x, y, z))) { placedDirty = true; continue; }
                        if (!(x == start.getFloorX() && y == start.getFloorY() && z == start.getFloorZ())) {
                            drop(log, axe, new Vector3(x + 0.5, y + 0.5, z + 0.5), 1);
                            lv.setBlock(new Vector3(x, y, z), Block.get(0), true, true);
                            addXp(p, Skill.LENHADOR, Xp.blockXp(Skill.LENHADOR, id, log.getDamage()) * Skill.LENHADOR.modifier);
                            broken++;
                        }
                        queue.add(new int[] {x, y, z});
                    }
                }
            }
        }
        if (broken > 0 && axe.getMaxDurability() > 0) {
            Item hand = p.getInventory().getItemInHand();
            if (hand.getId() == axe.getId()) {
                hand.setDamage(Math.min(hand.getMaxDurability(), hand.getDamage() + broken));
                p.getInventory().setItemInHand(hand.getDamage() >= hand.getMaxDurability() ? Item.get(0) : hand);
            }
        }
    }

    // ---------- combate ----------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent e) {
        Entity victim = e.getEntity();
        // Acrobacia: rolamento na queda.
        if (e.getCause() == EntityDamageEvent.CAUSE_FALL && victim instanceof Player) { fall((Player) victim, e); return; }
        if (!(e instanceof EntityDamageByEntityEvent)) { return; }
        Entity damager = ((EntityDamageByEntityEvent) e).getDamager();
        boolean arrow = e instanceof EntityDamageByChildEntityEvent && ((EntityDamageByChildEntityEvent) e).getChild() instanceof EntityArrow;
        if (victim instanceof Player) { defend((Player) victim, e, arrow); if (e.isCancelled()) { return; } }
        if (!(damager instanceof Player) || damager == victim) { return; }
        Player p = (Player) damager;
        State s = states.get(p);
        if (s == null || !s.loaded || !Settings.allowed(p, "goldskills.combate")) { return; }
        boolean pvp = victim instanceof Player;
        double mult = pvp ? Xp.combat(63) : Xp.combat(victim.getNetworkId()); // Player.getNetworkId() e -1 no 0.15
        Item hand = p.getInventory().getItemInHand();
        Skill skill = arrow ? Skill.ARQUEIRO : hand.isSword() ? Skill.ESPADAS : hand.isAxe() ? Skill.MACHADO : hand.getId() == 0 ? Skill.DESARMADO : null;
        if (skill == null) { return; }
        int lvl = s.level[skill.ordinal()];
        if (abilitiesOn && !arrow) { activate(p, s, skill); }
        float dmg = e.getDamage();
        switch (skill) {
            case ESPADAS:
                if (roll(Xp.chance(lvl, 75, 750))) { bleed(victim, lvl >= 750 ? 3 : 2, pvp ? 1 : 2); if (pvp) { ((Player) victim).sendTip(M.t("voce_esta_sangrando")); } }
                if (active(s, Skill.ESPADAS)) { splash(p, victim, dmg / 4, true); }
                break;
            case MACHADO:
                dmg += Math.min(4, lvl / 50f);
                if (roll(Xp.chance(lvl, 37.5, 750))) { dmg *= pvp ? 1.5f : 2f; p.sendTip(M.t("golpe_critico")); }
                if (roll(25)) { dmg += 2; }
                if (active(s, Skill.MACHADO)) { splash(p, victim, dmg / 2, false); }
                break;
            case DESARMADO:
                dmg += Math.min(8, 3 + lvl / 50f);
                if (active(s, Skill.DESARMADO)) { dmg *= 1.5f; }
                if (pvp && roll(Xp.chance(lvl, 33, 1000))) { disarm((Player) victim); }
                break;
            case ARQUEIRO:
                dmg = Math.min(dmg * (1 + Math.min(2f, lvl / 50 * 0.1f)), Math.max(dmg, 9f));
                if (pvp && roll(Xp.chance(lvl, 50, 1000))) { ((Player) victim).addEffect(Effect.getEffect(Effect.NAUSEA).setDuration(200)); dmg += 4; ((Player) victim).sendTip(M.t("voce_ficou_tonto")); }
                break;
            default:
        }
        e.setDamage(dmg);
        if (mult > 0) {
            double xp = Math.min(dmg, victim.getHealth()) * mult;
            if (skill == Skill.ARQUEIRO) { xp *= 1 + Math.min(50, p.distance(victim)) * 0.0025; }
            addXp(p, skill, xp * skill.modifier);
        }
    }

    /** Esquiva (acrobacia) e desvio de flechas (desarmado) de quem apanha. */
    private void defend(Player p, EntityDamageEvent e, boolean arrow) {
        State s = states.get(p);
        if (s == null || !s.loaded || !Settings.allowed(p, "goldskills.combate")) { return; }
        if (arrow && p.getInventory().getItemInHand().getId() == 0 && roll(Xp.chance(s.level[Skill.DESARMADO.ordinal()], 50, 1000))) {
            e.setCancelled(); p.sendTip(M.t("flecha_desviada")); return;
        }
        if (roll(Xp.chance(s.level[Skill.ACROBACIA.ordinal()], 20, 800))) {
            float half = e.getDamage() / 2;
            e.setDamage(half);
            p.sendTip(M.t("esquiva"));
            addXp(p, Skill.ACROBACIA, half * 5 * Skill.ACROBACIA.modifier);
        }
    }

    private void fall(Player p, EntityDamageEvent e) {
        State s = states.get(p);
        if (s == null || !s.loaded || !Settings.allowed(p, "goldskills.combate")) { return; }
        int lvl = s.level[Skill.ACROBACIA.ordinal()];
        float dmg = e.getDamage();
        boolean graceful = p.isSneaking() && roll(Xp.chance(lvl, 100, 500));
        if (graceful || roll(Xp.chance(lvl, 100, 1000))) {
            float left = Math.max(0, dmg - (graceful ? 14 : 7));
            if (left <= 0) { e.setCancelled(); } else { e.setDamage(left); }
            p.sendTip(graceful ? M.t("rolamento_perfeito") : M.t("rolamento"));
            addXp(p, Skill.ACROBACIA, dmg * 2 * Skill.ACROBACIA.modifier);
        } else if (dmg < p.getHealth()) {
            addXp(p, Skill.ACROBACIA, dmg * Skill.ACROBACIA.modifier);
        }
    }

    private void bleed(Entity e, int ticks, int damage) { int[] b = bleeding.get(e); if (b == null || b[0] < ticks) { bleeding.put(e, new int[] {ticks, damage}); } }

    /** A cada 2 s: dano de sangramento. */
    private void bleed() {
        if (bleeding.isEmpty()) { return; }
        for (Iterator<Map.Entry<Entity, int[]>> it = bleeding.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Entity, int[]> en = it.next();
            Entity e = en.getKey();
            if (e.closed || !e.isAlive() || en.getValue()[0]-- <= 0) { it.remove(); continue; }
            e.attack(new EntityDamageEvent(e, EntityDamageEvent.CAUSE_MAGIC, en.getValue()[1]));
        }
    }

    /** Dano em area dos poderes de combate (nao atinge o proprio jogador nem o alvo principal). */
    private void splash(Player p, Entity main, float dmg, boolean bleed) {
        for (Entity o : p.level.getNearbyEntities(main.boundingBox.grow(2.5, 1, 2.5), main)) {
            if (o == p || !(o instanceof EntityLiving) || o.closed) { continue; }
            o.attack(new EntityDamageEvent(o, EntityDamageEvent.CAUSE_MAGIC, dmg));
            if (bleed) { bleed(o, 5, o instanceof Player ? 1 : 2); }
        }
    }

    private void disarm(Player v) {
        Item hand = v.getInventory().getItemInHand();
        if (hand.getId() == 0) { return; }
        v.getInventory().setItemInHand(Item.get(0));
        v.level.dropItem(v.add(0, 1, 0), hand);
        v.sendTip(M.t("voce_foi_desarmado"));
    }

    // ---------- reparacao na bigorna de ferro ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void repair(PlayerInteractEvent e) {
        if (e.getAction() != PlayerInteractEvent.RIGHT_CLICK_BLOCK || e.getBlock() == null || e.getBlock().getId() != 42) { return; }
        Player p = e.getPlayer();
        State s = states.get(p);
        Item hand = p.getInventory().getItemInHand();
        int[] mat = Xp.repairMaterial(hand.getId());
        if (s == null || !s.loaded || mat == null || hand.getMaxDurability() <= 0 || !Settings.allowed(p, "goldskills.reparacao")) { return; }
        e.setCancelled();
        if (hand.getDamage() <= 0) { p.sendTip(M.t("este_item_nao_precisa_de_reparo")); return; }
        Item material = Item.get(mat[0], 0, 1);
        if (!p.getInventory().contains(material)) { p.sendTip(M.t("voce_precisa_de_para_reparar", material.getName())); return; }
        int lvl = s.level[Skill.REPARACAO.ordinal()], max = hand.getMaxDurability();
        double restore = max / (double) Xp.recipeUnits(hand.getId()) * (1 + Math.min(2.0, lvl / 1000.0 * 2.0));
        if (roll(Xp.chance(lvl, 100, 1000))) { restore *= 2; p.sendTip(M.t("super_reparo")); }
        int before = hand.getDamage(), after = Math.max(0, (int) Math.round(before - restore));
        p.getInventory().removeItem(material);
        hand.setDamage(after);
        p.getInventory().setItemInHand(hand);
        addXp(p, Skill.REPARACAO, (before - after) / (double) max * 1000 * mat[1] * Skill.REPARACAO.modifier);
        p.sendMessage(PREFIX + M.t("item_reparado_de_durabilidade", (int) (100.0 * (max - after) / max)));
    }

    // ---------- ranking ----------

    private void refreshTop() {
        String best = ""; int bestPower = -1;
        for (Map.Entry<String, short[]> e : index.entrySet()) { int t = 0; for (short l : e.getValue()) { t += l; } if (t > bestPower) { bestPower = t; best = e.getKey(); } }
        topName = bestPower > 0 ? best : "";
        topCache.clear();
    }

    /** Ranking de uma habilidade (null = poder total), guardado por 60 s. */
    private List<Map.Entry<String, Integer>> ranking(Skill skill) {
        long now = System.currentTimeMillis();
        if (now - topCacheAt > 60_000) { topCache.clear(); topCacheAt = now; }
        return topCache.computeIfAbsent(skill == null ? "poder" : skill.id(), k -> {
            List<Map.Entry<String, Integer>> list = new ArrayList<>(index.size());
            for (Map.Entry<String, short[]> e : index.entrySet()) {
                int v = 0;
                if (skill == null) { for (short l : e.getValue()) { v += l; } } else { v = e.getValue()[skill.ordinal()]; }
                if (v > 0) { list.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), v)); }
            }
            list.sort((a, b) -> b.getValue() - a.getValue());
            return list;
        });
    }

    // ---------- menus ----------

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 20, 22, 24};

    private void menu(Player p) {
        MenuService menus = getServer().getServiceManager().load(MenuService.class);
        State s = states.get(p);
        if (menus == null || s == null || !s.loaded) { p.sendMessage(PREFIX + M.t("perfil_carregando")); return; }
        Map<Integer, Item> icons = new HashMap<>();
        int rank = position(ranking(null), key(p));
        Item power = Item.get(340, 0, 1);
        power.setCustomName(M.t("poder_total_posicao_no_ranking_toque", s.power(), (rank > 0 ? "#" + rank : "-")));
        icons.put(4, power);
        for (Skill sk : Skill.ALL) {
            int i = sk.ordinal();
            Item it = Item.get(sk.icon, 0, 1);
            it.setCustomName(M.t("nivel_xp", sk.label, s.level[i], s.xp[i], Xp.needed(s.level[i]), percent(s, i), (sk.ability != null ? M.t("poder", sk.ability) : "")));
            icons.put(SLOTS[i], it);
        }
        Item boost = Item.get(384, 0, 1);
        boost.setCustomName(M.t("boosters_compre_xp_em_dobro"));
        icons.put(26, boost);
        menus.open(p, M.t("habilidades_2"), icons, (who, slot) -> {
            if (slot == 26) { boosterMenu(who); return; }
            for (Skill sk : Skill.ALL) { if (SLOTS[sk.ordinal()] == slot) { details(who, who.getName(), sk); } }
        });
    }

    private static int position(List<Map.Entry<String, Integer>> list, String name) {
        for (int i = 0; i < list.size(); i++) { if (list.get(i).getKey().equals(name)) { return i + 1; } }
        return 0;
    }

    /** Detalhes de uma habilidade com as chances no nivel atual (como /mining do mcMMO). */
    private void details(CommandSender to, String player, Skill sk) {
        Player target = getServer().getPlayerExact(player);
        State s = target == null ? null : states.get(target);
        int lvl = s != null ? s.level[sk.ordinal()] : 0;
        to.sendMessage(M.t("nivel", sk.label, lvl));
        switch (sk) {
            case MINERACAO: case LENHADOR: case HERBALISMO: to.sendMessage(M.t("drop_duplo", fmt(Xp.chance(lvl, 100, 1000)))); break;
            case ESCAVACAO: to.sendMessage(M.t("tesouros_liberados", Xp.TREASURES.stream().filter(t -> lvl >= t.level).count(), Xp.TREASURES.size())); break;
            case ESPADAS: to.sendMessage(M.t("sangramento", fmt(Xp.chance(lvl, 75, 750)))); break;
            case MACHADO: to.sendMessage(M.t("critico_dano_extra", fmt(Xp.chance(lvl, 37.5, 750)), fmt(Math.min(4, lvl / 50.0)))); break;
            case ARQUEIRO: to.sendMessage(M.t("tiro_certeiro_atordoar", (int) (Math.min(2.0, lvl / 50 * 0.1) * 100), fmt(Xp.chance(lvl, 50, 1000)))); break;
            case DESARMADO: to.sendMessage(M.t("braco_de_ferro_desarmar_desviar_flechas", fmt(Math.min(8, 3 + lvl / 50.0)), fmt(Xp.chance(lvl, 33, 1000)), fmt(Xp.chance(lvl, 50, 1000)))); break;
            case ACROBACIA: to.sendMessage(M.t("rolamento_esquiva", fmt(Xp.chance(lvl, 100, 1000)), fmt(Xp.chance(lvl, 20, 800)))); break;
            case REPARACAO: to.sendMessage(M.t("maestria_super_reparo_toque_num_bloco", (int) (Math.min(2.0, lvl / 500.0) * 100), fmt(Xp.chance(lvl, 100, 1000)))); break;
            default:
        }
        if (sk.ability != null) { to.sendMessage(M.t("poder_2", sk.ability, (abilitiesOn ? M.t("agache_com_ferramenta_use_dura", (2 + lvl / 50)) : M.t("desligado_no_servidor")))); }
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.1f", v); }

    private void boosterMenu(Player p) {
        MenuService menus = getServer().getServiceManager().load(MenuService.class);
        State s = states.get(p);
        if (menus == null || s == null) { return; }
        Map<Integer, Item> icons = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Skill sk : Skill.ALL) {
            long[] b = s.boosters.get(sk.ordinal());
            Item it = Item.get(sk.icon, 0, 1);
            it.setCustomName(M.t("por_min_preco", sk.label, boosterMult, boosterSeconds / 60, boosterPrice, (b != null && b[1] > now ? M.t("ativo_mais", ((b[1] - now) / 1000)) : "")));
            icons.put(SLOTS[sk.ordinal()], it);
        }
        Item all = Item.get(399, 0, 1);
        all.setCustomName(M.t("todas_as_habilidades_por_min_preco", boosterMult, boosterSeconds / 60, boosterAllPrice));
        icons.put(4, all);
        menus.open(p, M.t("boosters_de_xp"), icons, (who, slot) -> {
            if (slot == 4) { buy(who, N, boosterAllPrice); return; }
            for (Skill sk : Skill.ALL) { if (SLOTS[sk.ordinal()] == slot) { buy(who, sk.ordinal(), boosterPrice); } }
        });
    }

    private void buy(Player p, int target, long price) {
        EconomyService eco = getServer().getServiceManager().load(EconomyService.class);
        if (eco == null) { p.sendMessage(PREFIX + M.t("economia_indisponivel")); return; }
        long cents = price * 100;
        if (eco.balance(p.getName(), EconomyService.Currency.MONEY) < cents) { p.sendMessage(PREFIX + M.t("dinheiro_insuficiente_custa", price)); return; }
        eco.debit(p.getName(), EconomyService.Currency.MONEY, cents).whenComplete((v, e) -> getServer().getScheduler().scheduleTask(() -> {
            if (!isEnabled() || !p.isOnline()) { return; }
            if (e != null) { p.sendMessage(PREFIX + M.t("compra_recusada")); return; }
            giveBooster(p, target, boosterMult, boosterSeconds);
        }));
    }

    void giveBooster(Player p, int target, int mult, int seconds) {
        State s = states.get(p);
        if (s == null) { return; }
        long now = System.currentTimeMillis();
        long[] cur = s.boosters.get(target);
        long until = (cur != null && cur[1] > now && cur[0] == mult * 10L ? cur[1] : now) + seconds * 1000L;
        s.boosters.put(target, new long[] {mult * 10L, until});
        s.boosters.values().removeIf(b -> b[1] <= now);
        s.dirty = true;
        p.sendMessage(PREFIX + M.t("booster_em_ativo_por_min", mult, (target == N ? M.t("todas_as_habilidades") : Skill.ALL[target].label), (until - now) / 60000));
    }

    // ---------- comandos ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player p = sender instanceof Player ? (Player) sender : null;
        try {
            switch (command.getName()) {
                case "skills":
                    if (args.length > 0) { details(sender, p != null ? p.getName() : "", Skill.parse(args[0])); return true; }
                    if (p != null) { menu(p); }
                    return true;
                case "mcstats": {
                    Player t = args.length > 0 ? getServer().getPlayerExact(args[0]) : p;
                    State s = t == null ? null : states.get(t);
                    if (s == null) { sender.sendMessage(PREFIX + M.t("jogador_offline")); return true; }
                    sender.sendMessage(M.t("habilidades_de_poder", t.getName(), s.power()));
                    for (Skill sk : Skill.ALL) { sender.sendMessage("§e" + sk.label + ": §f" + s.level[sk.ordinal()] + " §7(" + s.xp[sk.ordinal()] + "/" + Xp.needed(s.level[sk.ordinal()]) + ")"); }
                    return true;
                }
                case "mctop": {
                    Skill sk = args.length > 0 && !args[0].matches("\\d+") ? Skill.parse(args[0]) : null;
                    int page = Math.max(1, args.length > 0 && args[args.length - 1].matches("\\d+") ? Integer.parseInt(args[args.length - 1]) : 1);
                    List<Map.Entry<String, Integer>> list = ranking(sk);
                    sender.sendMessage(M.t("top_pagina", (sk == null ? M.t("poder_3") : sk.label), page));
                    for (int i = (page - 1) * 10; i < Math.min(list.size(), page * 10); i++) { sender.sendMessage("§e" + (i + 1) + ". §f" + list.get(i).getKey() + " §7- " + list.get(i).getValue()); }
                    if (list.isEmpty()) { sender.sendMessage(M.t("ninguem_no_ranking_ainda")); }
                    return true;
                }
                case "mcrank": {
                    String name = (args.length > 0 ? args[0] : sender.getName()).toLowerCase(Locale.ROOT);
                    sender.sendMessage(M.t("posicoes_de", name));
                    sender.sendMessage(M.t("poder_4", position(ranking(null), name)));
                    for (Skill sk : Skill.ALL) { int pos = position(ranking(sk), name); sender.sendMessage("§e" + sk.label + ": §f" + (pos > 0 ? "#" + pos : "-")); }
                    return true;
                }
                case "mcability": {
                    if (p == null) { return true; }
                    State s = states.get(p);
                    if (s == null) { return true; }
                    s.abilities = !s.abilities; s.dirty = true;
                    p.sendMessage(PREFIX + M.t("ativacao_de_poderes", (s.abilities ? M.t("ligada") : M.t("desligada"))));
                    return true;
                }
                case "booster": if (p != null) { boosterMenu(p); } return true;
                case "darbooster": {
                    Player t = online(arg(args, 0));
                    int target = arg(args, 1).equalsIgnoreCase("todas") ? N : Skill.parse(args[1]).ordinal();
                    int mult = Math.max(2, Math.min(10, Integer.parseInt(arg(args, 2))));
                    int minutes = Math.max(1, Math.min(10080, Integer.parseInt(arg(args, 3))));
                    giveBooster(t, target, mult, minutes * 60);
                    sender.sendMessage(PREFIX + M.t("booster_entregue"));
                    return true;
                }
                case "xprate": {
                    if (args.length > 0 && args[0].equalsIgnoreCase("reset")) { globalRate = 1; globalUntil = 0; core.getStore().delete(GLOBAL, "xprate"); getServer().broadcastMessage(PREFIX + M.t("evento_de_xp_acabou")); return true; }
                    double rate = Double.parseDouble(arg(args, 0));
                    int minutes = Integer.parseInt(arg(args, 1));
                    if (rate < 1 || rate > 10 || minutes < 1 || minutes > 10080) { throw new IllegalArgumentException(M.t("multiplicador_de_1_10_minutos_de")); }
                    globalRate = rate; globalUntil = System.currentTimeMillis() + minutes * 60_000L;
                    core.getStore().put(GLOBAL, "xprate", rate + ":" + globalUntil);
                    getServer().broadcastMessage(PREFIX + M.t("evento_xp_em_todas_as_habilidades", fmt(rate), minutes));
                    return true;
                }
                case "addlevels": case "mmoedit": case "addxp": {
                    Player t = online(arg(args, 0));
                    State s = states.get(t);
                    if (s == null || !s.loaded) { throw new IllegalArgumentException(M.t("perfil_carregando_2")); }
                    boolean all = arg(args, 1).equalsIgnoreCase("todas");
                    int value = Integer.parseInt(arg(args, 2));
                    for (Skill sk : Skill.ALL) {
                        if (!all && sk != Skill.parse(args[1])) { continue; }
                        int i = sk.ordinal();
                        if (command.getName().equals("addxp")) { addXpRaw(t, sk, value); continue; }
                        int lvl = command.getName().equals("mmoedit") ? value : s.level[i] + value;
                        s.level[i] = Math.max(0, Math.min(Xp.LEVEL_CAP, lvl)); s.xp[i] = 0;
                        index.computeIfAbsent(key(t), k -> new short[N])[i] = (short) s.level[i];
                    }
                    s.dirty = true;
                    sender.sendMessage(PREFIX + M.t("habilidades_de_atualizadas", t.getName()));
                    return true;
                }
                case "skillreset": {
                    Player t = online(arg(args, 0));
                    State s = states.get(t);
                    if (s == null) { throw new IllegalArgumentException(M.t("perfil_carregando_2")); }
                    for (Skill sk : Skill.ALL) { if (args.length < 2 || sk == Skill.parse(args[1])) { s.level[sk.ordinal()] = 0; s.xp[sk.ordinal()] = 0; } }
                    short[] lv = index.get(key(t)); if (lv != null) { for (int i = 0; i < N; i++) { lv[i] = (short) s.level[i]; } }
                    s.dirty = true;
                    sender.sendMessage(PREFIX + M.t("habilidades_zeradas"));
                    return true;
                }
                default: return false;
            }
        } catch (NumberFormatException e) { sender.sendMessage(PREFIX + M.t("numero_invalido")); return true; }
        catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + e.getMessage()); return true; }
    }

    /** XP de admin: ignora boosters e zonas. */
    private void addXpRaw(Player p, Skill skill, int amount) {
        double saved = globalRate; long until = globalUntil;
        globalRate = 1; globalUntil = 0;
        State s = states.get(p);
        Map<Integer, long[]> boosters = new HashMap<>(s.boosters);
        s.boosters.clear();
        try { addXp(p, skill, amount); } finally { globalRate = saved; globalUntil = until; s.boosters.putAll(boosters); }
    }

    private Player online(String name) { Player p = getServer().getPlayerExact(name); if (p == null) { throw new IllegalArgumentException(M.t("jogador_offline")); } return p; }
    private static String arg(String[] a, int i) { if (a.length <= i) { throw new IllegalArgumentException(M.t("faltam_argumentos")); } return a[i]; }
}
