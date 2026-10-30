package br.goldsuite.api.event;

import cn.nukkit.Player;
import cn.nukkit.event.HandlerList;
import cn.nukkit.event.player.PlayerEvent;

/** Publicado na thread principal apenas depois de autenticar a sessao. */
public final class PlayerAuthEvent extends PlayerEvent {
    public enum Method { REGISTER, LOGIN }
    private static final HandlerList handlers = new HandlerList();
    private final Method method;
    public PlayerAuthEvent(Player player, Method method) { this.player = player; this.method = method; }
    public static HandlerList getHandlers() { return handlers; }
    public Method getMethod() { return this.method; }
}
