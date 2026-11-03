# -*- coding: utf-8 -*-
import json, os, re, glob, sys, datetime
from xml.sax.saxutils import escape
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import cm
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_CENTER
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (BaseDocTemplate, PageTemplate, Frame, Paragraph, Spacer, PageBreak, Table, TableStyle,
                                KeepTogether, NextPageTemplate, Preformatted, CondPageBreak, Flowable)
from reportlab.platypus.tableofcontents import TableOfContents

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from conteudo import ORDEM, GUIAS, PLACEHOLDERS, CORES

REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, 'Manual-Nukkit-Gold-0.4.3.pdf')
D = json.load(open(os.path.join(HERE, 'dump.json')))

F = '/usr/share/fonts/truetype/dejavu/'
pdfmetrics.registerFont(TTFont('Sans', F + 'DejaVuSans.ttf'))
pdfmetrics.registerFont(TTFont('Sans-Bold', F + 'DejaVuSans-Bold.ttf'))
pdfmetrics.registerFont(TTFont('Sans-It', F + ('DejaVuSans-Oblique.ttf' if os.path.exists(F + 'DejaVuSans-Oblique.ttf') else 'DejaVuSans.ttf')))
pdfmetrics.registerFont(TTFont('Mono', F + 'DejaVuSansMono.ttf'))
from reportlab.pdfbase.pdfmetrics import registerFontFamily
registerFontFamily('Sans', normal='Sans', bold='Sans-Bold', italic='Sans-It', boldItalic='Sans-Bold')

GOLD = colors.HexColor('#B8860B'); DARK = colors.HexColor('#1F2328'); GREY = colors.HexColor('#57606A')
LIGHT = colors.HexColor('#F6F8FA'); LINE = colors.HexColor('#D0D7DE'); TIP = colors.HexColor('#FFF8E1')

st = {
    'body': ParagraphStyle('body', fontName='Sans', fontSize=9.5, leading=13.5, textColor=DARK, spaceAfter=5),
    'small': ParagraphStyle('small', fontName='Sans', fontSize=8, leading=10.5, textColor=DARK),
    'smallb': ParagraphStyle('smallb', fontName='Sans-Bold', fontSize=8, leading=10.5, textColor=colors.white),
    'mono': ParagraphStyle('mono', fontName='Mono', fontSize=7.8, leading=10.2, textColor=DARK),
    'h0': ParagraphStyle('h0', fontName='Sans-Bold', fontSize=22, leading=27, textColor=GOLD, spaceAfter=10),
    'h1': ParagraphStyle('h1', fontName='Sans-Bold', fontSize=17, leading=22, textColor=DARK, spaceBefore=4, spaceAfter=8),
    'h2': ParagraphStyle('h2', fontName='Sans-Bold', fontSize=11.5, leading=15, textColor=GOLD, spaceBefore=9, spaceAfter=4),
    'lead': ParagraphStyle('lead', fontName='Sans', fontSize=10.5, leading=15, textColor=GREY, spaceAfter=8),
    'bullet': ParagraphStyle('bullet', fontName='Sans', fontSize=9.5, leading=13.5, textColor=DARK, leftIndent=12, bulletIndent=2, spaceAfter=2.5),
    'step': ParagraphStyle('step', fontName='Sans', fontSize=9.5, leading=13.5, textColor=DARK, leftIndent=18, bulletIndent=0, spaceAfter=3.5),
    'code': ParagraphStyle('code', fontName='Mono', fontSize=8.3, leading=11.2, textColor=DARK),
    'toc0': ParagraphStyle('toc0', fontName='Sans-Bold', fontSize=10.5, leading=15, leftIndent=0, spaceBefore=5),
    'toc1': ParagraphStyle('toc1', fontName='Sans', fontSize=9, leading=12.5, leftIndent=16),
}

def P(text, style='body'): return Paragraph(text, st[style])
def E(text): return escape(text)

def code_block(lines):
    t = Table([[Preformatted('\n'.join(lines), st['code'])]], colWidths=[17 * cm])
    t.setStyle(TableStyle([('BACKGROUND', (0, 0), (-1, -1), LIGHT), ('BOX', (0, 0), (-1, -1), 0.5, LINE),
                           ('LEFTPADDING', (0, 0), (-1, -1), 8), ('RIGHTPADDING', (0, 0), (-1, -1), 8), ('TOPPADDING', (0, 0), (-1, -1), 6), ('BOTTOMPADDING', (0, 0), (-1, -1), 6)]))
    return [t, Spacer(1, 6)]

def tip_box(title, items):
    body = [P('<b>%s</b>' % E(title), 'small')] + [P('• ' + fmt(i), 'small') for i in items]
    t = Table([[body]], colWidths=[17 * cm])
    t.setStyle(TableStyle([('BACKGROUND', (0, 0), (-1, -1), TIP), ('BOX', (0, 0), (-1, -1), 0.5, GOLD),
                           ('LEFTPADDING', (0, 0), (-1, -1), 8), ('RIGHTPADDING', (0, 0), (-1, -1), 8), ('TOPPADDING', (0, 0), (-1, -1), 5), ('BOTTOMPADDING', (0, 0), (-1, -1), 6)]))
    return [Spacer(1, 3), t, Spacer(1, 6)]

STOP = set('e ou com para de do da dos das no na nos nas que se o a os as em pelo pela so só sempre ate até um uma ao aos e/ou depois antes'.split())

def fmt(text):
    """Escapa, mantem <b> e destaca comandos (/algo <arg> [opcional] sub) e {placeholders} em fonte mono."""
    t = E(text).replace('&lt;b&gt;', '<b>').replace('&lt;/b&gt;', '</b>')
    def cmd(m):
        parts = m.group(0).split(' ')
        keep = [parts[0]]; plain = 0
        for w in parts[1:]:
            if w.startswith('&lt;') or w.startswith('['): keep.append(w); continue
            if w.lower() in STOP or plain >= 2 or not re.match(r'^[a-z0-9_.|:\-]+$', w): break
            keep.append(w); plain += 1
        head = ' '.join(keep)
        return '<font face="Mono" size="8.6">' + head + '</font>' + m.group(0)[len(head):]
    t = re.sub(r'(?<![\w/;<])/[a-z][\w]*(?: (?:&lt;[^&]*?&gt;|\[[^\]]*\]|[a-z0-9_.|:\-]+))*', cmd, t)
    t = re.sub(r'(\{[a-z0-9_]+\})', r'<font face="Mono" size="8.6">\1</font>', t)
    return t

def table(header, rows, widths, mono_cols=()):
    data = [[P(E(h), 'smallb') for h in header]]
    for r in rows:
        data.append([P(E(str(c)), 'mono' if i in mono_cols else 'small') for i, c in enumerate(r)])
    t = Table(data, colWidths=[w * cm for w in widths], repeatRows=1)
    t.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), DARK), ('VALIGN', (0, 0), (-1, -1), 'TOP'),
        ('ROWBACKGROUNDS', (0, 1), (-1, -1), [colors.white, LIGHT]), ('LINEBELOW', (0, 0), (-1, -1), 0.3, LINE),
        ('LEFTPADDING', (0, 0), (-1, -1), 5), ('RIGHTPADDING', (0, 0), (-1, -1), 5), ('TOPPADDING', (0, 0), (-1, -1), 3.5), ('BOTTOMPADDING', (0, 0), (-1, -1), 3.5)]))
    return [t, Spacer(1, 8)]

def items(lst):
    out = []; code = []
    def flush():
        if code: out.extend(code_block(code)); code.clear()
    for it in lst:
        if it.startswith('$ '): code.append(it[2:]); continue
        flush()
        if it.startswith('- '): out.append(Paragraph(fmt(it[2:]), st['bullet'], bulletText='•'))
        else: out.append(P(fmt(it)))
    flush()
    return out

class Doc(BaseDocTemplate):
    def __init__(self, fn):
        super().__init__(fn, pagesize=A4, leftMargin=2 * cm, rightMargin=2 * cm, topMargin=2.2 * cm, bottomMargin=2 * cm,
                         title='Manual de Configuração - Nukkit Gold 0.4.3', author='Nukkit Gold', subject='Configuração dos 31 plugins Gold')
        fr = Frame(self.leftMargin, self.bottomMargin, self.width, self.height, id='f')
        self.addPageTemplates([PageTemplate('capa', [fr], onPage=self.cover), PageTemplate('normal', [fr], onPage=self.page)])
        self.chapter = ''
    def cover(self, c, d):
        w, h = A4
        c.saveState()
        c.setFillColor(DARK); c.rect(0, 0, w, h, fill=1, stroke=0)
        c.setFillColor(GOLD); c.rect(0, h * 0.52, w, 0.5 * cm, fill=1, stroke=0)
        c.setFont('Sans-Bold', 34); c.setFillColor(colors.white); c.drawString(2 * cm, h * 0.62, 'Manual de Configuração')
        c.setFont('Sans', 16); c.setFillColor(GOLD); c.drawString(2 * cm, h * 0.58, 'Nukkit Gold 0.4.3  ·  MCPE 0.15.10')
        c.setFont('Sans', 11); c.setFillColor(colors.HexColor('#C9D1D9'))
        y = h * 0.45
        for line in ['Os 31 plugins Gold: o que configurar, em que ordem e com quais comandos.',
                     'Comandos, permissões e chaves do /painel extraídos de um servidor rodando esta versão.',
                     'Inclui mensagens editáveis, placeholders, receitas de lobby e solução de problemas.']:
            c.drawString(2 * cm, y, line); y -= 0.65 * cm
        c.setFont('Sans', 9); c.drawString(2 * cm, 2 * cm, 'Gerado em ' + datetime.date.today().strftime('%d/%m/%Y'))
        c.restoreState()
    def beforeDocument(self):
        self.chapter = ''
    def page(self, c, d):
        c.saveState()
        c.setFont('Sans', 7.5); c.setFillColor(GREY)
        c.drawString(2 * cm, A4[1] - 1.3 * cm, 'Nukkit Gold 0.4.3 · Manual de Configuração')
        c.drawRightString(A4[0] - 2 * cm, A4[1] - 1.3 * cm, self.chapter)
        c.setStrokeColor(LINE); c.line(2 * cm, A4[1] - 1.45 * cm, A4[0] - 2 * cm, A4[1] - 1.45 * cm)
        c.drawCentredString(A4[0] / 2, 1.1 * cm, str(d.page))
        c.restoreState()
    def afterFlowable(self, f):
        if isinstance(f, Paragraph) and f.style.name in ('h0', 'h1'):
            text = f.getPlainText()
            level = 0 if f.style.name == 'h0' else 1
            if level == 0: self.chapter = text
            key = 'k%d' % id(f)
            self.canv.bookmarkPage(key)
            self.canv.addOutlineEntry(text, key, level=level, closed=level == 0)
            self.notify('TOCEntry', (level, text, self.page, key))

story = [NextPageTemplate('normal'), PageBreak()]
toc = TableOfContents(); toc.levelStyles = [st['toc0'], st['toc1']]; toc.dotsMinLevel = 0
st['htoc'] = ParagraphStyle('htoc', parent=st['h0'])
story += [P('Sumário', 'htoc'), toc, PageBreak()]

def chapter(title, lead=None):
    story.append(P(E(title), 'h0'))
    if lead: story.append(P(fmt(lead), 'lead'))

def section(title):
    story.append(CondPageBreak(3 * cm)); story.append(P(E(title), 'h1'))

def sub(title): story.append(KeepTogether([P(E(title), 'h2')]))

# ---------------- Parte 1 ----------------
chapter('1. Antes de começar', 'Este manual acompanha a versão 0.4.3 do Nukkit Gold com os 31 plugins Gold. As tabelas de comandos, permissões e opções do painel foram extraídas de um servidor rodando esta versão, então batem exatamente com o que você vai ver no jogo.')
section('Como ler este manual')
story += items([
    'Cada plugin tem o seu capítulo com: para que serve, passo a passo de configuração, exemplos prontos para copiar, a tabela de comandos, as opções do /painel, as permissões e quantas mensagens dele podem ser editadas.',
    '- Comandos aparecem em fonte de código. <obrigatório> e [opcional] seguem o padrão do /help.',
    '- Valores de dinheiro no /painel estão em centavos (10000 = $100,00), salvo quando a descrição diz cash ou moedas.',
    '- Tudo que começa com & é cor (veja a tabela de cores) e tudo entre chaves, como {nome}, é preenchido pelo servidor.',
])
section('Instalação')
story += items([
    '<b>Windows:</b> rode NukkitGold-Setup-0.4.3.exe. O instalador já traz o Java e os 31 plugins; abra o servidor pelo atalho criado.',
    '<b>Linux/VPS:</b> extraia NukkitGold-0.4.3-linux-x64.tar.gz e rode ./start.sh dentro da pasta NukkitGold (o Java vem junto, em runtime/).',
    '<b>Atualizar um servidor existente:</b> pare o servidor (stop), apague os Gold*.jar antigos de plugins/, copie os novos do Pack-Gold-Plugins-0.4.3.zip e os mapas de worlds/, e ligue de novo. As configurações e os dados ficam no banco e são mantidos.',
    '- Na primeira vez cada plugin cria a sua pasta em plugins/ (com o mensagens.yml) e os dados vão para o SQLite do GoldCore.',
])
section('Primeiro acesso como dono')
story += items([
    '- No console, dê OP a você: op <seu_nick>.',
    '- Entre no jogo, registre-se (/registrar <senha> <senha>) e ative o 2FA (/2fa ativar).',
    '- Coloque-se no cargo de dono: /grupo jogador <seu_nick> dono.',
    '- No console, proteja o OP pelo seu IP: opseguro add <seu_nick> <seu_ip>. A partir daí, um OP que entra de outro IP perde o OP na hora.',
    '- Abra o /painel e siga a ordem recomendada abaixo.',
])
section('Ordem recomendada')
rows = []
n = 1
for grupo, plugins in ORDEM:
    for p in plugins:
        rows.append([str(n), grupo, p, re.split(r'(?<=[.:])\s', GUIAS[p]['resumo'])[0]])
        n += 1
story += table(['#', 'Etapa', 'Plugin', 'Para que serve'], rows, [0.8, 3.2, 3, 10], mono_cols=())
story.append(PageBreak())

# ---------------- Parte 2: conceitos ----------------
chapter('2. Conceitos que valem para todos', 'Painel, cores, placeholders, mensagens, permissões e zonas: entendendo isto, todo o resto é aplicar.')
section('O /painel')
story += items([
    'O /painel (só para quem tem goldcore.painel, o OP por padrão) abre um menu com o Servidor e todos os plugins. Toque numa opção e digite o novo valor no chat; padrao volta ao original e cancelar sai. Nada precisa ser editado dentro dos jars.',
    '$ /painel                               menu (no jogo)',
    '$ painel ver <plugin>                   lista as opções (console)',
    '$ painel set <plugin> <chave> <valor>   troca um valor (padrao restaura)',
    '$ painel discord <url|off|teste>        webhook do Discord',
    '$ painel msg [plugin] [chave|busca] [texto]   mensagens (veja abaixo)',
    '$ painel mensagens recarregar           relê os mensagens.yml',
])
sub('Opções do Servidor')
rows = [[s['key'], s['def'], s['desc']] for s in D['settings'] if s['plugin'] == 'Servidor']
story += table(['Chave', 'Padrão', 'Descrição'], rows, [3.3, 3.2, 10.5], mono_cols=(0, 1))
section('Cores')
story += items(['Use & seguido do código em qualquer texto do painel, das mensagens, de NPCs, do chat da staff e das zonas. Para escrever um & de verdade num mensagens.yml, use &&.'])
half = (len(CORES) + 1) // 2
rows = []
for i in range(half):
    a = CORES[i]; b = CORES[i + half] if i + half < len(CORES) else ('', '')
    rows.append([a[0], a[1], b[0], b[1]])
story += table(['Código', 'Efeito', 'Código', 'Efeito'], rows, [2, 6.5, 2, 6.5], mono_cols=(0, 2))
sub('Paleta do servidor')
story += items(['Em vez de cores fixas você pode usar {primaria}, {secundaria}, {destaque}, {sucesso}, {erro} e {texto}. Elas seguem as opções cor.* do Servidor: mudar a paleta muda todas as mensagens que usam os tokens, sem editar uma por uma.'])
section('Placeholders')
story += items(['Valem na barra do GoldHud, nos formatos do chat, nos nomes dos NPCs, nas linhas das zonas e em qualquer texto que mostre informações do jogador. O GoldNPC também aceita {online} e {mundo:<nome>} (jogadores naquele mundo).'])
rows = [['{%s}' % k, PLACEHOLDERS.get(k, '')] for k in sorted(D['placeholders'], key=lambda k: (k.startswith('nivel_'), k))]
story += table(['Placeholder', 'Mostra'], rows, [5, 12], mono_cols=(0,))
section('Mensagens: tudo é editável')
story += items([
    'Nenhuma mensagem fica presa no código. Na primeira vez que liga, cada plugin grava plugins/<Plugin>/mensagens.yml com todas as mensagens dele: chat, popups, avisos, erros de comando, títulos e itens de menu, descrições de kits e itens da loja.',
    '$ # {0} = target, {1} = Ledger.format(...), {2} = (moedas ou cash)',
    '$ saldo_de: "Saldo de {0}: {1} {2}"',
    '- Edite só o texto entre aspas. {0}, {1}... são os valores preenchidos pelo plugin; o comentário logo acima diz o que é cada um. Pode mudar a ordem ou tirar valores.',
    '- Cores com & e a paleta {primaria}... funcionam. && mostra um &.',
    '- Depois de editar o arquivo: /painel mensagens recarregar. Nomes de itens e de kits criados na inicialização valem depois de reiniciar.',
    '- Apagar uma linha volta ao texto padrão. Ao atualizar o plugin, as mensagens novas entram no fim do arquivo sem mexer nas suas.',
    'Pelo jogo, sem abrir arquivo:',
    '$ /painel msg                                   plugins e quantas mensagens cada um tem',
    '$ /painel msg GoldEconomy saldo                 busca por chave ou por texto',
    '$ /painel msg GoldEconomy saldo_de              mostra o texto atual, o padrão e os valores',
    '$ /painel msg GoldEconomy saldo_de &bCarteira de {0}: &f{1} {2}',
    '$ /painel msg GoldEconomy saldo_de padrao       volta ao original',
])
msgcount = {}
for f in glob.glob(REPO + '/gold-*/src/main/resources/br/goldsuite/*/mensagens.yml'):
    mod = os.path.relpath(f, REPO).split('/')[0]
    name = re.search(r'^name:\s*(\S+)', open(REPO + '/' + mod + '/src/main/resources/plugin.yml').read(), re.M).group(1)
    keys = [l.split(': ', 1)[0] for l in open(f, encoding='utf-8') if not l.startswith('#') and ': ' in l]
    msgcount[name] = keys
story += items(['Total nesta versão: <b>%d mensagens</b> em %d plugins.' % (sum(len(v) for v in msgcount.values()), len(msgcount))])
section('Permissões, grupos e cargos')
story += items([
    'As permissões são dadas por grupo no GoldPerms (/grupo permissao <grupo> <permissao> true). Os cargos da equipe (ajudante, moderador, admin, gerente, dono) já vêm criados e cada um herda o anterior. A lista completa de permissões está no Apêndice A.',
    '- Permissões marcadas como op são só do OP até você dar a um grupo.',
    '- Ninguém pune quem tem cargo igual ou acima do seu.',
])
section('Zonas: o que aparece em cada mapa')
story += items([
    'O GoldComandos agrupa mundos em zonas. Em cada zona você decide quais comandos funcionam, quais plugins ficam desligados (/zona plugin), quais recursos somem (/zona recurso), a barra própria (/zona hud), as boas-vindas e os itens da hotbar. Exemplo: no lobby do Rankup não aparece o perfil de PvP nem nada de facções ou SkyWars. Detalhes no capítulo do GoldComandos.',
])
story.append(PageBreak())

# ---------------- Parte 3: plugins ----------------
cmds = {}
for c in D['commands']: cmds.setdefault(c['plugin'], []).append(c)
sets = {}
for s in D['settings']: sets.setdefault(s['plugin'], []).append(s)
perms = {}
for p in D['perms']: perms.setdefault(p['plugin'], []).append(p)

cap = 3
for grupo, plugins in ORDEM:
    chapter('%d. %s' % (cap, grupo)); cap += 1
    for i, p in enumerate(plugins):
        g = GUIAS[p]
        if i > 0: story.append(PageBreak())
        section(p)
        story.append(P(fmt(g['resumo']), 'lead'))
        if g['passos']:
            sub('Passo a passo')
            for n, s in enumerate(g['passos'], 1): story.append(Paragraph(fmt(s), st['step'], bulletText='%d.' % n))
        for t, its in g['secoes']:
            sub(t); story += items(its)
        if g['dicas']: story += tip_box('Dicas', g['dicas'])
        if cmds.get(p):
            sub('Comandos')
            rows = []
            for c in sorted(cmds[p], key=lambda c: c['name']):
                al = (' (também /' + ', /'.join(c['aliases'].split(',')) + ')') if c['aliases'] else ''
                rows.append([c['usage'] or '/' + c['name'], c['desc'] + al, 'só console' if c['name'] == 'opseguro' else (c['perm'] or 'todos')])
            story += table(['Uso', 'O que faz', 'Permissão'], rows, [6.3, 6.9, 3.8], mono_cols=(0, 2))
        if sets.get(p):
            sub('Opções do /painel (%s)' % p)
            rows = [[s['key'], s['def'].replace('\u00a7', '&') if s['def'] else '(vazio)', s['desc']] for s in sets[p]]
            story += table(['Chave', 'Padrão', 'Descrição'], rows, [4.2, 4, 8.8], mono_cols=(0, 1))
        if perms.get(p):
            sub('Permissões')
            rows = [[x['name'], {'op': 'só OP', 'true': 'todos', 'false': 'ninguém', 'notop': 'quem não é OP'}.get(str(x['def']).lower(), str(x['def'])), x['desc']] for x in perms[p]]
            story += table(['Permissão', 'Padrão', 'Para que'], rows, [5.5, 2.2, 9.3], mono_cols=(0,))
        if msgcount.get(p):
            ks = msgcount[p]
            story += items(['<b>Mensagens:</b> %d editáveis em plugins/%s/mensagens.yml ou com /painel msg %s. Algumas chaves: %s.' % (len(ks), p, p, ', '.join(ks[:6]))])
    story.append(PageBreak())

# ---------------- Receitas ----------------
chapter('%d. Receitas prontas' % cap, 'Combinações de vários plugins para montar o servidor rápido. Troque os nomes dos mundos pelos seus.'); cap += 1
section('Lobby principal')
story += items([
    '$ /setspawn',
    '$ /mundoprotecao lobby construir off',
    '$ /mundoprotecao lobby pvp off',
    '$ /mundoprotecao lobby dano off',
    '$ /antivoid lobby',
    '$ /zona criar lobby',
    '$ /zona mundo lobby lobby',
    '$ /zona modo lobby bloquear',
    '$ /zona cmd lobby fly gm',
    '$ /zona boasvindas lobby &6Bem-vindo, {nome}!',
    '$ /zona item lobby 1 345 &eJogos warp jogos',
    '$ /npc criar humano &6SkyWars|&f{mundo:sw_ilhas} jogando',
    '$ /npc comando sw entrar solo',
    '$ /npc criar humano &b#1 Ranked',
    '$ /npc ranking ranked 1',
])
section('Lobby do Rankup (sem PvP, facções e minigames)')
story += items([
    '$ /zona criar rankup',
    '$ /zona mundo rankup lobby_rankup',
    '$ /zona plugin rankup GoldFactions',
    '$ /zona plugin rankup GoldSkyWars',
    '$ /zona plugin rankup GoldBedWars',
    '$ /zona plugin rankup GoldHG',
    '$ /zona recurso rankup goldpvp.perfil',
    '$ /zona recurso rankup goldskills.xp',
    '$ /zona hud rankup 1 &a&lRANKUP &7| &fMoney: &a{money}',
    '$ /zona hud rankup 2 &fCash: &e{cash} &7| &fPoder: &b{poder}',
    '$ /npc criar villager &aBanco Central',
    '$ /npc comando banco',
])
section('Área de minigames')
story += items([
    '$ /zona criar minigames',
    '$ /zona mundo minigames lobby_jogos',
    '$ /zona modo minigames bloquear',
    '$ /zona cmd minigames fly gm tpa',
    '$ /zona item minigames 1 345 &eSkyWars sw entrar solo',
    '$ /zona item minigames 2 355 &cBedWars bw entrar solo',
    '$ /zona item minigames 3 399 &dParty party entrar',
    '- Copie os mapas sw_ilhas e bw_quatro para worlds/ e cole os comandos de arenas-comandos.txt (vem no zip dos plugins).',
])
section('VIP vendido por Pix')
story += items([
    '$ /grupo criar vip',
    '$ /grupo permissao vip goldessentials.fly true',
    '$ /grupo permissao vip goldessentials.luz true',
    '$ /grupo permissao vip goldkits.kit.vip true',
    '$ /painel set GoldKeys msg.pix {destaque}Pix: chave@exemplo.com &7- mande o comprovante no Discord',
    '$ /key produto criar vip30 19.90 VIP 30 dias',
    '$ /key produto vip30 vip vip 30',
    '- O jogador usa /key comprar vip30, paga e você confirma com /key pago <pedido>. Ele resgata com /key resgatar <codigo>.',
])
section('Equipe e suporte')
story += items([
    '$ /cargo Ana ajudante',
    '$ /cargo Bia moderador 30',
    '$ /painel set GoldStaff groq_chave gsk_...',
    '$ /painel set GoldStaff discord_tickets 1',
    '$ /grupo permissao gerente goldcore.alertas true',
])
story.append(PageBreak())

# ---------------- Problemas ----------------
chapter('%d. Solução de problemas' % cap); cap += 1
probs = [
    ('"Esse comando não está disponível aqui."', 'A zona do mundo bloqueia o comando. Veja com /zona ver <zona>; libere com /zona cmd, troque o modo ou dê goldcomandos.cmd.<comando> ao cargo.'),
    ('Um comando sumiu do /help', 'O /help só mostra o que o jogador pode usar ali: confira a permissão e a zona (inclusive /zona plugin).'),
    ('O OP perdeu o OP ao entrar', 'O IP dele não está na lista do opseguro. No console: opseguro add <nick> <ip> (ou opseguro exigir off).'),
    ('Editei o mensagens.yml e nada mudou', 'Use /painel mensagens recarregar. Se o arquivo tiver erro (aspas sem fechar), o console avisa e o plugin usa os textos padrão; corrija a linha indicada.'),
    ('A barra (HUD) não aparece', 'Veja se o mundo não está em /hud mundo, se a zona não desligou goldhud.barra e se o jogador não desligou com /hud.'),
    ('A IA do suporte não responde', 'Confira groq_chave e groq_modelo no painel. Sem IA o ticket vai direto para a equipe, então nada fica parado.'),
    ('A arena não começa', 'Confira o mínimo de jogadores no painel do minigame (minimo_jogadores) ou force com /sw iniciar, /bw iniciar, /hg iniciar ou /party iniciar.'),
    ('O livro do perfil aparece onde não devia', 'Tire o mundo de /ranked lobby ou use /zona recurso <zona> goldpvp.perfil.'),
    ('O jogador diz que está no PC mas aparece celular', 'Corrija com /plataforma definir <jogador> windows; o servidor aprende pela mira com o tempo.'),
    ('Dinheiro no painel parece 100 vezes maior', 'Os prêmios e preços do painel estão em centavos: 10000 = $100,00.'),
]
for t, d in probs:
    story.append(KeepTogether([P('<b>%s</b>' % E(t)), P(fmt(d))]))
story.append(PageBreak())

# ---------------- Apêndices ----------------
chapter('Apêndice A. Todas as permissões')
rows = [[x['name'], x['plugin'], {'op': 'só OP', 'true': 'todos', 'false': 'ninguém'}.get(str(x['def']).lower(), str(x['def'])), x['desc']] for x in sorted(D['perms'], key=lambda x: x['name'])]
extra = [['goldcomandos.cmd.<comando>', 'GoldComandos', 'ninguém', 'Libera um comando bloqueado pela zona'],
         ['goldstaff.cmd.<comando>', 'GoldStaff', 'ninguém', 'Libera um comando do /bloquearcmd'],
         ['goldkits.kit.<kit>', 'GoldKits', 'ninguém', 'Usa um kit restrito']]
story += table(['Permissão', 'Plugin', 'Padrão', 'Para que'], rows + extra, [5.3, 2.7, 1.8, 7.2], mono_cols=(0,))
story.append(PageBreak())
chapter('Apêndice B. Todos os comandos')
rows = [['/' + c['name'], c['plugin'], c['desc']] for c in sorted(D['commands'], key=lambda c: c['name'])]
story += table(['Comando', 'Plugin', 'O que faz'], rows, [3.4, 3, 10.6], mono_cols=(0,))
story.append(PageBreak())
chapter('Apêndice C. Mensagens por plugin')
rows = [[k, str(len(v)), 'plugins/%s/mensagens.yml' % k] for k, v in sorted(msgcount.items())]
story += table(['Plugin', 'Mensagens', 'Arquivo'], rows, [4, 2.5, 10.5], mono_cols=(2,))

doc = Doc(OUT)
doc.multiBuild(story)
print(OUT)
