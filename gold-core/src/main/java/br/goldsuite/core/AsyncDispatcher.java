package br.goldsuite.core;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.plugin.PluginFailure;
import cn.nukkit.scheduler.AsyncTask;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Uma fila serial limitada evita criar uma thread de banco por requisicao. */
final class AsyncDispatcher {
    private final Plugin owner;
    private final ArrayDeque<Job<?>> queue = new ArrayDeque<>();
    private boolean running;
    private boolean closed;

    AsyncDispatcher(Plugin owner) { this.owner = owner; }

    synchronized <T> CompletableFuture<T> submit(Callable<T> operation) {
        Job<T> job = new Job<>(operation);
        if (this.closed || this.queue.size() >= 1024) {
            job.future.completeExceptionally(new IllegalStateException("Armazenamento encerrado ou fila cheia."));
            return job.future;
        }
        this.queue.add(job);
        if (!this.running) {
            this.running = true;
            startWorker();
        }
        return job.future;
    }

    private void startWorker() {
        this.owner.getServer().getScheduler().executeAsyncTask(new Worker());
    }

    private final class Worker extends AsyncTask {
        private final List<Job<?>> finished = new ArrayList<>();

        @Override public void onRun() {
            // Lotes curtos permitem entregar resultados mesmo sob carga continua.
            for (int processed = 0; processed < 64; processed++) {
                Job<?> next;
                synchronized (AsyncDispatcher.this) {
                    next = queue.poll();
                    if (next == null) {
                        running = false;
                        AsyncDispatcher.this.notifyAll();
                        return;
                    }
                }
                next.run();
                this.finished.add(next);
            }
            synchronized (AsyncDispatcher.this) {
                if (queue.isEmpty()) {
                    running = false;
                    AsyncDispatcher.this.notifyAll();
                } else {
                    startWorker();
                }
            }
        }

        @Override public void onCompletion(Server server) {
            for (Job<?> completed : this.finished) { completed.complete(); }
        }
    }

    synchronized boolean closeAndDrain() {
        this.closed = true;
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (this.running || !this.queue.isEmpty()) {
            long left = end - System.nanoTime();
            if (left <= 0) { return false; }
            try { TimeUnit.NANOSECONDS.timedWait(this, left); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
        }
        return true;
    }

    private static final class Job<T> {
        private final Callable<T> operation;
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private T result;
        private Throwable error;
        private Job(Callable<T> operation) { this.operation = operation; }
        private void run() {
            try { this.result = this.operation.call(); }
            catch (Throwable failure) { PluginFailure.rethrowIfFatal(failure); this.error = failure; }
        }
        private void complete() {
            if (this.error != null) { this.future.completeExceptionally(this.error); }
            else { this.future.complete(this.result); }
        }
    }
}
