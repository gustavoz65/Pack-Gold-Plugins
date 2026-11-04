package br.goldsuite.api;

/**
 * Tags escolhidas pelos jogadores (GoldTags). O GoldPerms consulta este servico no getTag: a tag escolhida aparece no
 * chat, na barra e em tudo que usa {tag}, sem mudar o cargo nem as permissoes de ninguem.
 */
public interface TagService {
    /** Tag escolhida pelo jogador, com cores (§), ou null para usar a tag do cargo. */
    String chosen(String player);
    /** Tag de quem nao tem cargo com tag (ex.: [Jogador]), com cores (§). */
    String fallback();
}
