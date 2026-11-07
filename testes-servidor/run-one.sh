#!/bin/bash
# Roda um teste de servidor com jogadores falsos.
# uso: ./run-one.sh <pasta> <Classe>        ex.: ./run-one.sh sm TesteSkyMythic
# Variaveis:
#   NUKKIT_JAR  jar do Nukkit Gold (padrao: ../../Nukkit-Gold-Edition/builds/nukkit-gold-0.15.10.jar)
#   WORLDS      pastas de mapas a copiar para o servidor de teste (separadas por espaco)
#   KEEP_AUTH=1 mantem o GoldAuth (por padrao ele sai, para os jogadores falsos nao precisarem de login)
#   PORT        porta UDP do servidor de teste (padrao 19140, para nao brigar com a 19132 de producao)
set -e
d=$1; c=$2
HERE=$(cd "$(dirname "$0")" && pwd); PACK=$(cd "$HERE/.." && pwd)
NUKKIT_JAR=${NUKKIT_JAR:-$PACK/../Nukkit-Gold-Edition/builds/nukkit-gold-0.15.10.jar}
[ -f "$NUKKIT_JAR" ] || { echo "NUKKIT_JAR nao encontrado: $NUKKIT_JAR"; exit 1; }
ls "$PACK"/dist/*.jar >/dev/null 2>&1 || { echo "Rode antes: gradle check dist (na raiz do Pack-Gold-Plugins)"; exit 1; }
cd "$HERE"; mkdir -p work
CP=$NUKKIT_JAR:$(ls "$PACK"/dist/*.jar | tr '\n' ':')
rm -rf "$d/out/teste" && javac --release 8 -nowarn -cp "$CP" -d "$d/out" "$d/src/teste/$c.java" 2>&1 | grep -v "Picked up\|^Note" || true
(cd "$d/out" && jar cf "../../work/$c.jar" .)
srv=work/srv-$d; rm -rf "$srv" && mkdir -p "$srv/plugins" && cp "$PACK"/dist/*.jar "$srv/plugins/"
[ -n "$KEEP_AUTH" ] || rm -f "$srv"/plugins/GoldAuth-*.jar
cp "work/$c.jar" "$srv/plugins/"
if [ -n "$WORLDS" ]; then mkdir -p "$srv/worlds" && cp -r $WORLDS "$srv/worlds/"; fi
# Porta propria (padrao 19140): nunca a 19132 de um servidor em producao na mesma maquina.
printf 'server-port=%s\nmotd=Teste\nwhite-list=off\n' "${PORT:-19140}" > "$srv/server.properties"
cd "$srv" && ( printf 'bra\n'; for i in $(seq 1 400); do sleep 1; grep -q FIM_TESTE log.txt 2>/dev/null && break; done; sleep 2; printf 'stop\n'; sleep 8 ) | timeout 440 java -Xmx1G -jar "$NUKKIT_JAR" > log.txt 2>&1 || true
echo "== $c: $(grep -oE '[A-Z]+_(OK|FALHOU).*' log.txt | tail -1)"
