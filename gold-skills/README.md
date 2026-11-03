# GoldSkills

Habilidades no estilo mcMMO para o MCPE 0.15.10. Substitui o mcMMO PT-BR, vBoosterMcMMO, MCBooster, DarkmcMMOUP, reStatsUP, MCTopTag e fyStats do pack Rankup 1.8. A curva de XP, os modificadores e as tabelas de XP por bloco são os do pack original.

## Habilidades

| Habilidade | Como ganha XP | Bônus |
|---|---|---|
| Mineração | Quebrar pedra e minérios com picareta | Drop duplo; poder **Super Picareta** |
| Lenhador | Cortar troncos com machado | Drop duplo; poder **Derrubador de Árvores** |
| Escavação | Terra, areia, cascalho etc. com pá | Tesouros; poder **Super Pá** |
| Herbalismo | Colher plantações maduras | Drop duplo, replantio (Terra Verde) |
| Espadas | Golpes com espada | Sangramento; poder **Golpes Serrilhados** |
| Machado | Golpes com machado | Crítico e dano extra; poder **Racha Crânio** |
| Arqueiro | Flechadas (mais XP de longe) | Dano extra e tontura em PvP |
| Desarmado | Golpes de mão vazia | Braço de ferro, desarme, desvio de flecha; poder **Fúria** |
| Acrobacia | Dano de queda | Rolamento (agachado = rolamento perfeito) e esquiva |
| Reparação | Tocar no **bloco de ferro** com a ferramenta gasta e o material no inventário | Repara mais quanto maior o nível |

- Blocos colocados por jogadores não dão XP (anti-farm). A lista fica em memória num conjunto compacto (`long` por bloco) e é salva compactada em `blocos-colocados.bin`.
- Nível máximo 1000 por habilidade. Os poderes (agachar segurando a ferramenta) vêm **desligados**; para ligar: `/painel set GoldSkills poderes 1`.
- Ao chegar nos níveis de anúncio (50, 100, 200...), todo o servidor recebe a mensagem.

## Comandos

| Comando | Quem | O que faz |
|---|---|---|
| `/skills [habilidade]` | todos | Menu das habilidades ou detalhes de uma |
| `/mcstats [jogador]` | todos | Níveis de todas as habilidades |
| `/mctop [habilidade] [página]` | todos | Ranking (poder total ou por habilidade) |
| `/mcrank [jogador]` | todos | Posições de um jogador |
| `/mcability` | todos | Liga/desliga a ativação dos poderes |
| `/booster` | todos | Compra booster de XP (moedas) |
| `/darbooster <jogador> <habilidade\|todas> <mult> <minutos>` | admin | Dá um booster |
| `/xprate <mult> <minutos>` / `/xprate reset` | admin | Evento de XP para todos |
| `/addlevels`, `/addxp`, `/mmoedit`, `/skillreset` | admin | Edição de níveis |

## Placeholders

`{poder}` (soma dos níveis), `{nivel_<habilidade>}` (ex.: `{nivel_mineracao}`) e `{mctop}` (tag do 1º colocado no ranking de poder, vazia para os outros; atualizada a cada 5 minutos). O formato padrão do GoldChat já usa `{mctop}`.

## Painel (`/painel`)

`poderes`, `poderes.recarga`, `booster.segundos`, `booster.multiplicador`, `booster.preco`, `booster.preco_todas`, `anuncio.niveis`, `anuncio.mensagem`, `toptag`.

## Por zona (GoldComandos)

Cada zona pode desligar partes do plugin com `/zona recurso <zona> <recurso>`: `goldskills.xp`, `goldskills.combate`, `goldskills.poderes`, `goldskills.reparacao`. Exemplo: no lobby do Rankup ninguém ganha XP nem usa poderes.

## Memória

Só os jogadores online têm o perfil completo em memória; o ranking guarda um `short[]` por jogador. Os perfis são gravados em lote a cada 30 s (só os alterados) e ao sair.
