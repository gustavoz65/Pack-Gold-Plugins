package br.goldsuite.perms;

import br.goldsuite.api.AsyncStore;
import br.goldsuite.api.PersistentState;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class PermissionTest {
    public static void main(String[] args) {
        PermissionState state = new PermissionState();
        state.groups.get("padrao").permissions.put("gold.use", true);
        PermissionState.Group vip = new PermissionState.Group();
        vip.parent = "padrao";
        vip.permissions.put("gold.use", false);
        state.groups.put("vip", vip);
        state.members.put("jogador", new PermissionState.Member("vip", 2000));
        PermissionState.validate(state);
        check(!state.permissions("Jogador", 1000).get("gold.use"), "Negacao deve sobrepor heranca.");
        check(state.group("JOGADOR", 2000).equals("padrao"), "Expiracao offline nao foi aplicada.");
        state.groups.get("padrao").parent = "vip";
        rejects(() -> PermissionState.validate(state));
        state.groups.get("padrao").parent = "";
        state.groups.remove("vip");
        rejects(() -> PermissionState.validate(state));
        rejects(() -> PermissionState.player("../admin"));
        PermissionState links = new PermissionState();
        links.groups.put("vip", new PermissionState.Group());
        links.assign("Ana", "vip", 0);
        links.assign("Ana", "padrao", 0);
        check(links.members.isEmpty(), "Voltar a padrao deve remover o vinculo.");
        links.assign("Bia", "vip", 1500);
        links.assign("Caio", "vip", 0);
        links.prune(1500);
        check(!links.members.containsKey("bia") && links.members.containsKey("caio"), "Vinculo expirado nao foi descartado.");

        MemoryStore store = new MemoryStore();
        PersistentState<PermissionState> data = new PersistentState<>(store, "teste", PermissionState.class, PermissionState::new, PermissionState::validate);
        CompletableFuture<Void> first = data.change(s -> s.groups.put("um", new PermissionState.Group()));
        CompletableFuture<Void> second = data.change(s -> s.groups.put("dois", new PermissionState.Group()));
        check(!first.isDone() && !second.isDone(), "Escrita nao pode ser confirmada antes do armazenamento.");
        check(data.snapshot().groups.size() == 1, "Estado nao confirmado ficou visivel.");
        check(data.read(st -> st.groups.size()) == 1 && data.ready().isDone(), "Leitura ou carga dependem de gravacao pendente.");
        store.complete(true);
        check(first.isDone() && !second.isDone(), "Fila perdeu a ordem.");
        store.complete(true);
        check(data.snapshot().groups.size() == 3, "Duas alteracoes concorrentes perderam dados.");
        CompletableFuture<Void> failure = data.change(s -> s.groups.put("falha", new PermissionState.Group()));
        store.complete(false);
        check(failure.isCompletedExceptionally() && !data.snapshot().groups.containsKey("falha"), "Falha de disco publicou a alteracao.");
        CompletableFuture<Void> invalid = data.change(s -> s.groups.remove("padrao"));
        check(invalid.isCompletedExceptionally(), "Configuracao invalida foi aceita.");
        CompletableFuture<Void> recovered = data.change(s -> s.groups.put("recuperado", new PermissionState.Group()));
        store.complete(true);
        check(recovered.isDone() && !recovered.isCompletedExceptionally(), "Fila nao recuperou apos recusa.");
        PersistentState<PermissionState> reopened = new PersistentState<>(store, "teste", PermissionState.class, PermissionState::new, PermissionState::validate);
        check(reopened.snapshot().groups.containsKey("recuperado"), "Reabertura perdeu os dados persistidos.");
        data.close();
        check(data.change(s -> {}).isCompletedExceptionally(), "Modulo fechado aceitou escrita.");
        System.out.println("GoldPerms: heranca, negacoes, expiracao, persistencia, concorrencia e falha de gravacao aprovadas.");
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Entrada invalida aceita."); }
    private static final class MemoryStore implements AsyncStore {
        String value;
        String next;
        CompletableFuture<Void> write;
        @Override public CompletableFuture<Optional<String>> get(String namespace, String key) { return CompletableFuture.completedFuture(Optional.ofNullable(value)); }
        @Override public CompletableFuture<Void> put(String namespace, String key, String value) { next = value; write = new CompletableFuture<>(); return write; }
        @Override public CompletableFuture<Void> delete(String namespace, String key) { throw new UnsupportedOperationException(); }
        void complete(boolean success) {
            CompletableFuture<Void> current = write;
            if (success) { value = next; current.complete(null); } else { current.completeExceptionally(new IllegalStateException("Disco indisponivel no teste.")); }
        }
    }
}
