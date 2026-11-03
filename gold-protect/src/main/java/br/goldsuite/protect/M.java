package br.goldsuite.protect;

import br.goldsuite.api.Messages;

/** Mensagens do plugin: textos em mensagens.yml (editaveis em plugins/<Plugin>/mensagens.yml e no /painel msg). */
final class M {
    static final Messages B = Messages.resource(M.class, "GoldProtect");

    private M() {}

    static String t(String key, Object... args) { return B.t(key, args); }
}
