package br.goldsuite.api;

import java.util.concurrent.CompletableFuture;

/** Dinheiro em centavos e cash inteiro; valores nunca negativos. */
public interface EconomyService {
    enum Currency { MONEY, CASH }
    long balance(String player, Currency currency);
    CompletableFuture<Void> transfer(String from, String to, Currency currency, long amount);
    CompletableFuture<Void> credit(String player, Currency currency, long amount);
    CompletableFuture<Void> debit(String player, Currency currency, long amount);
}
