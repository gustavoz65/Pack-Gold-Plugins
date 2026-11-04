package br.goldsuite.api;

import cn.nukkit.Server;
import cn.nukkit.scheduler.AsyncTask;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Copia para staging antes de substituir o destino. Uma operacao por caminho, inclusive entre plugins. */
public final class WorldCopies {
    private static final Set<Path> BUSY = new HashSet<>();
    private final Server server;
    private final Path worlds, backups;
    public WorldCopies(Server server, Path dataFolder) {
        this.server = server;
        worlds = Paths.get(server.getDataPath(), "worlds").toAbsolutePath().normalize();
        backups = dataFolder.resolve("mapas").toAbsolutePath().normalize();
    }
    private static Path resolve(Path root, String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,32}") || name.contains("..")) { throw new IllegalArgumentException("Nome de mundo invalido."); }
        Path path = root.resolve(name).normalize();
        if (!path.getParent().equals(root)) { throw new IllegalArgumentException("Caminho fora da pasta."); }
        return path;
    }
    public boolean hasBackup(String name) { return Files.isDirectory(resolve(backups, name), LinkOption.NOFOLLOW_LINKS); }
    public CompletableFuture<Void> backup(String name) { return copy(resolve(worlds, name), resolve(backups, name)); }
    public CompletableFuture<Void> restore(String name) { return copy(resolve(backups, name), resolve(worlds, name)); }
    private CompletableFuture<Void> copy(Path from, Path to) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (BUSY) {
            if (BUSY.contains(from) || BUSY.contains(to)) { return PersistentState.failed(new IllegalStateException("Copia deste mundo em andamento.")); }
            BUSY.add(from); BUSY.add(to);
        }
        try {
            server.getScheduler().executeAsyncTask(new AsyncTask() {
                Throwable error;
                @Override public void onRun() { try { replace(from, to); } catch (Exception e) { error = e; } }
                @Override public void onCompletion(Server s) {
                    synchronized (BUSY) { BUSY.remove(from); BUSY.remove(to); }
                    if (error == null) { result.complete(null); } else { result.completeExceptionally(error); }
                }
            });
        } catch (RuntimeException e) {
            synchronized (BUSY) { BUSY.remove(from); BUSY.remove(to); }
            result.completeExceptionally(e);
        }
        return result;
    }
    public static void replace(Path source, Path destination) throws IOException {
        final Path from = source.toAbsolutePath().normalize(), to = destination.toAbsolutePath().normalize();
        if (from.equals(to) || from.startsWith(to) || to.startsWith(from) || to.getParent() == null) { throw new IOException("Caminhos sobrepostos."); }
        noLinks(from); noLinks(to);
        if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Copia de origem ausente."); }
        Files.createDirectories(to.getParent());
        final Path stage = Files.createTempDirectory(to.getParent(), ".gold-copy-");
        Path old = to.resolveSibling(to.getFileName() + ".previous-" + UUID.randomUUID());
        boolean movedOld = false, installed = false;
        try {
            Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink()) { throw new IOException("Link no mundo."); }
                    Files.createDirectories(stage.resolve(from.relativize(dir))); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() || attrs.isSymbolicLink()) { throw new IOException("Arquivo especial no mundo."); }
                    Files.copy(file, stage.resolve(from.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
            if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) { Files.move(to, old); movedOld = true; }
            try { Files.move(stage, to); installed = true; }
            catch (IOException e) { if (movedOld) { Files.move(old, to); movedOld = false; } throw e; }
        } finally {
            if (!installed) { remove(stage, to.getParent()); }
        }
        // O destino ja esta valido. A copia anterior nunca e apagada antes disso.
        if (movedOld) { remove(old, to.getParent()); }
    }
    private static void noLinks(Path p) throws IOException {
        for (Path part = p; part != null; part = part.getParent()) { if (Files.isSymbolicLink(part)) { throw new IOException("Link no caminho do mundo."); } }
    }
    private static void remove(Path path, Path root) throws IOException {
        if (!path.toAbsolutePath().normalize().startsWith(root) || path.equals(root)) { throw new IOException("Limpeza fora da pasta."); }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { return; }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException { if (error != null) { throw error; } Files.delete(dir); return FileVisitResult.CONTINUE; }
        });
    }
}

