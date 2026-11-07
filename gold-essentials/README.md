

## Do pack de rankup
- `/config`: o jogador desliga quem pode mandar tell, tpa e coins para ele (OPreferencias). A staff (`goldessentials.admin`) continua podendo.
- `/luz`: lanterna (visão noturna) para quem tem `goldessentials.luz` (dar ao VIP). Espera configurável no painel.
- `/setloja` e `/loja <jogador>`: cada VIP (`goldessentials.setloja`) marca a própria loja; qualquer um vai até ela com contagem e espera de 30 s entre usos (T_SetLoja).
- `/mundovip`: leva ao mundo VIP quem tem `goldessentials.mundovip`; quem não tem recebe a mensagem do painel. Admin: `/mundovip definir` e `/mundovip remover` (LRMundoVIP).

## Warps pelo console
`setwarp <nome> <mundo> <x> <y> <z> [yaw]` cria a warp sem ninguém no jogo (no jogo, `/setwarp <nome>` usa a sua posição ou as mesmas coordenadas). `delwarp <nome>` apaga e `warp` lista com as coordenadas.
