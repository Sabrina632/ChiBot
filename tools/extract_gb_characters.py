#!/usr/bin/env python3
"""Extrai o dataset de personagens de jogos dos dumps SQL do giant-bomb-wiki.

Baixa os dumps de gb_api_db_init/ do repo Giant-Bomb-Dot-Com/giant-bomb-wiki
(com cache local em tools/.gb_dumps/), filtra personagens notaveis (descricao
wiki >= 1000 chars, nao deletados, com imagem real), resolve a franquia como
"serie", classifica o genero por cascata (rotulo do dump -> pronomes da
descricao -> palavras de genero -> fallback masculino) e grava
src/main/resources/harem/game_characters.tsv.gz.

Uso: python tools/extract_gb_characters.py
"""
import gzip
import html
import re
import sys
import urllib.request
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
SAIDA = RAIZ / "src" / "main" / "resources" / "harem" / "game_characters.tsv.gz"
CACHE = Path(__file__).resolve().parent / ".gb_dumps"
BASE = ("https://raw.githubusercontent.com/Giant-Bomb-Dot-Com/"
        "giant-bomb-wiki/main/gb_api_db_init/")
DUMPS = ("14_character.sql.gz", "18_image.sql.gz",
         "08_franchise.sql.gz", "19_relations.sql.gz")

MIN_DESC = 1000      # corte de notabilidade (entra no pool)
NOTAVEL_DESC = 5000  # corte do bonus de kakera (flag notable)
BARRA = chr(92)      # backslash (escape do MySQL)

# Ordem real das colunas no dump de wiki_character (18 colunas: as 17 do
# schema + mw_formatted_description inserida apos description pela migracao).
C_ID, C_IMAGE_ID, C_GENDER, C_NAME, C_DESC, C_DELETED = 0, 1, 3, 7, 12, 17

MASC = re.compile(r"\b(he|him|his|himself)\b", re.I)
FEM = re.compile(r"\b(she|her|hers|herself)\b", re.I)
MWORD = re.compile(
    r"\b(mr|mister|sir|lord|king|prince|father|dad|brother|son|boy|man|male"
    r"|guy|duke|emperor|baron|monk|priest|god)\b", re.I)
FWORD = re.compile(
    r"\b(mrs|ms|miss|lady|queen|princess|mother|mom|sister|daughter|girl"
    r"|woman|female|duchess|empress|baroness|nun|priestess|goddess|maiden"
    r"|witch)\b", re.I)
TAG = re.compile(r"<[^>]+>")


def baixar(nome):
    """Baixa um dump pro cache (se ainda nao estiver la) e devolve o texto."""
    CACHE.mkdir(exist_ok=True)
    destino = CACHE / nome
    if not destino.exists():
        print(f"baixando {nome}...")
        urllib.request.urlretrieve(BASE + nome, destino)
    with gzip.open(destino, "rt", encoding="utf-8", errors="replace") as f:
        return f.read()


def tuplas(sql, tabela):
    """Itera as tuplas dos INSERT INTO `tabela` VALUES do dump.

    Parser minimo de tuplas do mysqldump: respeita aspas simples com escape
    por backslash; valores nao citados viram string crua ('NULL' inclusive).
    """
    padrao = re.compile(
        r"INSERT INTO `" + tabela + r"` VALUES\n(.*?);\n", re.S)
    for bloco in padrao.finditer(sql):
        corpo = bloco.group(1)
        i, n = 0, len(corpo)
        while i < n:
            if corpo[i] != "(":
                i += 1
                continue
            i += 1
            linha, cru = [], []
            while i < n:
                c = corpo[i]
                if c == "'":
                    i += 1
                    buf = []
                    while True:
                        c = corpo[i]
                        if c == BARRA:
                            buf.append(corpo[i + 1])
                            i += 2
                        elif c == "'":
                            i += 1
                            break
                        else:
                            buf.append(c)
                            i += 1
                    linha.append("".join(buf))
                    cru = None
                elif c == ",":
                    if cru is not None:
                        linha.append("".join(cru).strip())
                    cru = []
                    i += 1
                elif c == ")":
                    if cru is not None:
                        linha.append("".join(cru).strip())
                    i += 1
                    break
                else:
                    if cru is None:
                        cru = []
                    cru.append(c)
                    i += 1
            yield linha


def limpar(campo):
    """Sanitiza um campo pro TSV: sem HTML entities, TABs ou quebras."""
    campo = html.unescape(campo)
    return re.sub(r"[\t\r\n]+", " ", campo).strip()


def genero(rotulo, descricao_sem_html, nome):
    """Cascata de genero: rotulo -> pronomes -> palavras -> Male."""
    if rotulo == "1":
        return "Male"
    if rotulo == "2":
        return "Female"
    m = len(MASC.findall(descricao_sem_html))
    f = len(FEM.findall(descricao_sem_html))
    if m != f:
        return "Male" if m > f else "Female"
    pista = nome + " " + descricao_sem_html[:400]
    if len(FWORD.findall(pista)) > len(MWORD.findall(pista)):
        return "Female"
    return "Male"


def main():
    imagens = {}  # image_id -> url
    for linha in tuplas(baixar("18_image.sql.gz"), "image"):
        if len(linha) >= 4:
            imagens[linha[0]] = linha[3]

    franquias = {}  # franchise_id -> nome (indice 4 no dump de wiki_franchise)
    for linha in tuplas(baixar("08_franchise.sql.gz"), "wiki_franchise"):
        if len(linha) >= 5:
            franquias[linha[0]] = linha[4]

    # personagem -> franquia de menor id (wiki_assoc_character_franchise:
    # colunas id, character_id, franchise_id, description)
    franquia_de = {}
    relations = baixar("19_relations.sql.gz")
    for linha in tuplas(relations, "wiki_assoc_character_franchise"):
        if len(linha) >= 3 and linha[2] in franquias:
            atual = franquia_de.get(linha[1])
            if atual is None or int(linha[2]) < int(atual):
                franquia_de[linha[1]] = linha[2]

    saida = []
    fem = masc = notaveis = 0
    for r in tuplas(baixar("14_character.sql.gz"), "wiki_character"):
        if len(r) != 18 or r[C_DELETED] == "1":
            continue
        # Os cortes de notabilidade valem sobre o HTML cru da descricao —
        # foi assim que os numeros do spec (7197/1936/5261) foram medidos.
        descricao_html = r[C_DESC] or ""
        if len(descricao_html) < MIN_DESC:
            continue
        url = imagens.get(r[C_IMAGE_ID], "")
        if not url or "default" in url.lower():
            continue
        nome = limpar(r[C_NAME])
        if not nome:
            continue
        g = genero(r[C_GENDER], TAG.sub(" ", descricao_html), nome)
        fid = franquia_de.get(r[C_ID])
        serie = limpar(franquias[fid]) if fid else "Origem desconhecida"
        notavel = "1" if len(descricao_html) >= NOTAVEL_DESC else "0"
        url = url.replace("/original/", "/scale_medium/")
        saida.append((int(r[C_ID]), nome, g, serie, limpar(url), notavel))
        fem += g == "Female"
        masc += g == "Male"
        notaveis += notavel == "1"

    saida.sort()
    SAIDA.parent.mkdir(parents=True, exist_ok=True)
    # mtime=0 deixa o .gz deterministico (mesmo input -> mesmo arquivo no git)
    with open(SAIDA, "wb") as f:
        with gzip.GzipFile(fileobj=f, mode="wb", mtime=0) as gz:
            for linha in saida:
                gz.write(("\t".join(str(c) for c in linha) + "\n")
                         .encode("utf-8"))

    print(f"{len(saida)} personagens -> {SAIDA}")
    print(f"waifus: {fem} | husbandos: {masc} | notaveis (kakera x3): {notaveis}")
    conhecidos = {177: "Mario", 337: "King Bowser Koopa"}
    for cid, esperado in conhecidos.items():
        achado = next((l for l in saida if l[0] == cid), None)
        print(f"spot-check {esperado}: {achado}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
