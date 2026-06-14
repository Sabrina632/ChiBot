Emojis customizados do harem (estilo Mudae)
============================================

Coloque aqui os PNGs com os NOMES EXATOS abaixo (minusculo, com ".png").
No boot, o ChiBot sobe sozinho os que ainda nao existem na aplicacao
(HaremEmojis.sync) e usa eles no kakera. Enquanto um arquivo nao estiver
aqui, aquele caso cai no emoji unicode 💎/💗 normalmente.

Recomendado: PNG quadrado ~128x128, fundo transparente, < 256 KB.

Kakera colorido por faixa de valor (o valor vai de 15 a 1200):

  arquivo          cor          faixa de valor
  ---------------  -----------  ----------------
  kakera.png       roxo         15  – 149   (tambem usado em saldos/custos)
  kakera_b.png     azul         150 – 299
  kakera_c.png     ciano        300 – 449
  kakera_g.png     verde        450 – 599
  kakera_y.png     amarelo      600 – 749
  kakera_o.png     laranja      750 – 899
  kakera_r.png     vermelho     900 – 1049
  kakera_w.png     arco-iris    1050 – 1200

(A captura de waifu/husbando agora e por REACAO: a pessoa reage com
qualquer emoji no roll pra casar — nao tem mais botao de coracao.)

Badges da torre por nivel (0..6) — estrelas coloridas do Mudae:

  arquivo               nivel da torre
  --------------------  ---------------
  torre_bronze.png      0
  torre_silver.png      1
  torre_gold.png        2
  torre_emerald.png     3
  torre_sapphire.png    4
  torre_ruby.png        5
  torre_diamond.png     6

Observacoes
-----------
- Os nomes do Discord aceitam so letras, numeros e "_". Mantenha os nomes
  acima exatamente como estao.
- Pode mandar so um subconjunto: o que faltar continua no 💎. Ex.: se mandar
  so kakera.png, todo kakera vira o roxo customizado e o resto fica unicode.
- Se quiser mudar as faixas/cores, edite a tabela TIERS em
  src/main/java/org/chibot/Harem/HaremEmojis.java.
- O upload e idempotente: um emoji que ja existe na aplicacao nunca e
  duplicado. Pra trocar a imagem de um existente, apague-o no Discord
  Developer Portal (Application > Emojis) e faca o redeploy.