package br.goldsuite.api;

/**
 * Configuracoes editaveis pelo painel do GoldCore (/painel, so OP): paleta de cores do servidor, prefixos,
 * mensagens e valores de cada plugin, e o webhook do Discord. Nenhum arquivo dentro dos plugins precisa ser editado.
 * Textos aceitam & para cores e os tokens da paleta: {primaria} {secundaria} {destaque} {sucesso} {erro} {texto}.
 */
public interface SettingsService {
    enum Kind { TEXTO, NUMERO }

    /** Declara uma configuracao (padrao e descricao). Chamado no onEnable de cada plugin. */
    void define(String plugin, String key, Kind kind, String defaultValue, String description);
    /** Valor atual com cores e tokens da paleta ja aplicados. */
    String text(String plugin, String key);
    long number(String plugin, String key);
    /** Avisa o plugin sempre que um valor dele mudar no painel. */
    void watch(String plugin, Runnable onChange);
    /** Aviso para a staff online e para o Discord (se configurado). */
    void alert(String source, String message);
    /** So para o Discord (se configurado), sem aviso no jogo. */
    default void discord(String source, String message) { }
}
