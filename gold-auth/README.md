# GoldAuth

Primeiro módulo de jogo da GoldSuite. Implementa contas locais por nome para substituir inicialmente ServerAuth, ChatLogin e OpLogin, com login obrigatório também para operadores.

Instale `GoldCore-0.1.0.jar` e `GoldAuth-0.1.0.jar` em `plugins/` e reinicie. Os hashes são armazenados no SQLite do GoldCore, no espaço `goldauth`. O plugin não importa automaticamente contas ou arquivos PHP dos plugins antigos.

| Comando | Uso |
| --- | --- |
| `/registrar <senha> <confirmacao>` | Cria a conta e autentica após confirmar a escrita |
| `/login <senha>` | Autentica uma conta registrada; alias `/l` |
| `/senha <senha_atual> <nova_senha>` | Troca a senha de uma conta já autenticada |
| `/authreset <jogador>` | Remove o registro; exige console ou `goldauth.admin` e login prévio do operador |

Aliases adicionais: `/register`, `/reg` e `/changepassword`. Uma conta registrada pode informar sua senha pelo chat antes de autenticar; a mensagem é cancelada e seu conteúdo é removido do evento. Para criar a conta, use o comando de registro com confirmação.

## Proteções e escolhas

O hash usa **PBKDF2-HMAC-SHA256, 600.000 iterações e sal aleatório de 16 bytes**, com o provedor do JDK para manter o build sem outra dependência criptográfica. A comparação do resultado usa `MessageDigest.isEqual`; o formato armazena algoritmo, custo, sal e hash. A [referência de custo da OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#pbkdf2) documenta esse parâmetro. Senhas têm entre 8 e 128 caracteres.

Os cálculos de senha rodam em AsyncTask, no máximo metade dos núcleos ao mesmo tempo (mínimo de dois). Pedidos excedentes esperam numa fila de 64 posições; o jogador recebe um aviso em vez de ser recusado. Só senhas erradas contam no limite por IP: vinte em um minuto bloqueiam novas tentativas desse IP até a janela expirar. Logins corretos não contam, para não travar jogadores que dividem o mesmo IP (CGNAT de operadora). O contador sobrevive a reconexões enquanto o plugin está ativo. Cinco senhas incorretas encerram a sessão; o prazo inicial para autenticar é de 120 segundos. A tabela temporária de IPs tem limite de 2.048 entradas e expiração.

Antes do login, movimento, comandos externos, chat público, inventários, interação, descarte, coleta e dano são bloqueados. O filtro de pacotes cobre ações antigas que não passam por PlayerInteractEvent, como item frames. A janela do inventário próprio e o fluxo de respawn necessário ao cliente permanecem permitidos; transações de itens continuam bloqueadas.

A sessão pertence à instância Player, sem usar como prova de autenticação o UUID informado pelo cliente. Uma conexão com o nome de uma conta já autenticada é recusada antes de substituir a conexão existente. Respostas assíncronas de uma sessão encerrada são descartadas. Desativar GoldAuth encerra suas sessões, impedindo que jogadores pendentes continuem jogando durante a remoção da proteção.

O plugin não escreve senhas em logs. Outros plugins instalados precisam respeitar eventos cancelados e não registrar argumentos sensíveis. Esta é autenticação local do servidor; ela não estabelece uma identidade de conta Microsoft nem adiciona criptografia ao transporte antigo do protocolo 84.

## Integração

`AuthService`, disponibilizado pelo registro de serviços, permite consultar a sessão e solicitar registro, login ou troca de senha. Chame essa API na thread principal. Os resultados normais de CPU e banco voltam à thread principal; erros de serviço são futuros com falha e senhas incorretas resultam em `false`.

`PlayerAuthEvent` é publicado após o registro ou login efetivo. Módulos de kits, economia e permissões podem aguardar esse evento antes de realizar ações vinculadas à conta.

## Verificação

Testes executados no Windows/JDK 17: hash com sal individual, senha certa e errada, hash malformado/custo excessivo, janela de IP, registro e login no servidor isolado, reconexão com nome em outra capitalização, rejeição de senha antiga após troca, login pelo chat, reset administrativo, bloqueios anteriores ao login, cinco erros encerrando a sessão, API desativada e evento de autenticação sem duplicação. O teste também confere que as senhas usadas não aparecem no console. O prazo completo de 120 segundos ainda não foi exercitado num teste de cliente.

O teste integrado usa objetos Player simulados e SQLite real. O fluxo com a interface e a rede de um cliente MCPE 0.15.10 real ainda precisa ser validado.
