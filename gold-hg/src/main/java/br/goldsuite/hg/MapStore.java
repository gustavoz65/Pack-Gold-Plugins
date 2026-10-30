package br.goldsuite.hg;

import cn.nukkit.Server;
import cn.nukkit.scheduler.AsyncTask;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.CompletableFuture;

/** Copia de mundos fora da thread principal; a conclusao volta na thread principal (onCompletion). */
final class MapStore {
    private final Server server;
    private final Path backups;

    MapStore(Server server, Path dataFolder) { this.server = server; this.backups = dataFolder.resolve("mapas"); }

    Path world(String name) { return Paths.get(server.getDataPath(), "worlds", name); }
    boolean hasBackup(String name) { return Files.isDirectory(backups.resolve(name)); }

    /** Salva uma copia do mundo (o mundo deve ter sido salvo antes). */
    CompletableFuture<Void> backup(String name) { return copy(world(name), backups.resolve(name)); }

    /** Substitui o mundo pela copia salva (o mundo precisa estar descarregado). */
    CompletableFuture<Void> restore(String name) { return copy(backups.resolve(name), world(name)); }

    private CompletableFuture<Void> copy(Path from, Path to) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        server.getScheduler().scheduleAsyncTask(new AsyncTask() {
            private Throwable error;
            @Override public void onRun() {
                try {
                    if (!Files.isDirectory(from)) { throw new IOException("Pasta inexistente: " + from); }
                    delete(to);
                    Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
                        @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                            Files.createDirectories(to.resolve(from.relativize(dir).toString())); return FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING); return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (IOException | UncheckedIOException failure) { error = failure; }
            }
            @Override public void onCompletion(Server server) {
                if (error == null) { result.complete(null); } else { result.completeExceptionally(error); }
            }
        });
        return result;
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path)) { return; }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException { Files.delete(dir); return FileVisitResult.CONTINUE; }
        });
    }
}
