package br.goldsuite.hg;

import cn.nukkit.Server;
import br.goldsuite.api.WorldCopies;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Copias em staging, exclusao mutua por caminho e preservacao da ultima copia valida. */
final class MapStore {
    private final WorldCopies copies;
    MapStore(Server server, Path folder) { copies = new WorldCopies(server, folder); }
    boolean hasBackup(String name) { return copies.hasBackup(name); }
    CompletableFuture<Void> backup(String name) { return copies.backup(name); }
    CompletableFuture<Void> restore(String name) { return copies.restore(name); }
}
