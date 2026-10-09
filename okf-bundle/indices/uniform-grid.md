---
type: Spatial Index
title: Grid uniforme
description: Indice che divide lo spazio in celle quadrate (2D) o cubiche (3D) della stessa dimensione, tenute in una mappa; aggiornamenti economici, query veloci se la cella è adatta alla densità e alle query.
tags: [badspace, spatial-indexing, grid]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-09T07:46:40Z }
---

# Come funziona

Lo spazio è diviso in celle di lato `cellSize`, scelto alla creazione
della partizione (`IndexConfig.UniformGrid`). La cella di un punto si
ricava con `floor(coordinata / cellSize)` su ogni asse.

- Le celle stanno in una **mappa persistente** (cella → slot delle
  entità, vedi [Versioni al commit](#versioni-al-commit)): solo le celle
  con entità usano memoria, quindi lo spazio **non ha limiti**.
- Per ogni slot l'indice tiene la **posizione nella lista della sua
  cella**: rimozione e `relocated` costano O(1).
- **Aggiornamento:** cambia la cella solo se l'entità esce dalla sua
  cella; altrimenti non fa nulla.
- **Range query:** legge le celle che coprono la regione e controlla le
  entità. Se le celle della regione sono più di quelle non vuote, scorre
  le celle non vuote.
- **K-nearest:** legge le celle ad **anelli** intorno al punto, dal più
  vicino al più lontano, e si ferma quando l'anello successivo non può
  avere entità più vicine del k-esimo candidato. Un piccolo margine
  copre gli errori di arrotondamento nel calcolo delle celle.

| Operazione | Costo tipico |
|---|---|
| Aggiornamento | O(1) |
| Range query | celle coperte + entità lette |
| K-nearest | anelli letti fino a trovare k entità |

# Versioni al commit

Per l'[isolamento delle letture](/decisions/read-isolation.md#gli-indici)
l'indice dà a ogni commit una versione che le scritture successive non
cambiano.

- **Mappa delle celle:** è una `HashTrie`, una HAMT con transient scritta
  nel progetto (nodi da 32 figli, 5 bit dell'hash per livello). Ogni
  nodo tiene il commit in cui è stato creato: il writer lo modifica sul
  posto solo se è del commit corrente, altrimenti lo copia con il
  percorso dalla radice.
- **Celle:** anche ogni cella tiene il suo commit di creazione, e il
  writer la copia alla prima modifica dopo un commit; la copia tiene gli
  slot nelle stesse posizioni.
- **Posizione nella cella:** la mappa slot → posizione nella lista della
  cella serve solo alle scritture, quindi è privata del writer e non
  entra nelle versioni.
- **Query:** lo stesso codice serve il writer e le versioni; una versione
  legge le posizioni negli slot dello stesso commit.

**Misure** (profilo rapido, 2026-10-08, celle da 100, senza commit nei
benchmark, quindi senza copie): rispetto alla mappa non persistente le
scritture costano 1,2–1,3 volte, le query 1,1–1,4 volte (fino a circa
2,9 nei casi peggiori, con molte celle) e la memoria per entità l'1–6% in
più. La causa è la lettura nella trie, che scende di qualche livello, al
posto di un `HashMap`. Il guadagno della persistenza rispetto a una copia
a ogni commit si misurerà con i commit.

# Problematiche

- **Dimensione della cella.** È il parametro critico. Celle troppo
  grandi rispetto alla densità leggono molte entità inutili; celle
  troppo piccole rispetto alle query leggono molte celle.
- **Cambi di cella negli aggiornamenti.** Vale il modello della
  [frequenza dei cambi di
  foglia](/indices/grid-quadtree.md#frequenza-dei-cambi-di-foglia) con
  `L` uguale al lato della cella, che è fisso: con celle grandi rispetto
  allo spostamento in un tick, `r` è piccolo e gli aggiornamenti
  cambiano cella di rado.
- **Densità non uniforme.** Con cluster e hotspot una sola dimensione di
  cella non va bene ovunque: celle piene di entità nelle zone dense,
  celle quasi vuote altrove. È il problema che la [griglia di
  quadtree](/indices/grid-quadtree.md) vuole risolvere. Le misure lo
  confermano (profilo rapido, 2026-10-03, 100 000 entità):
  - con un solo cluster denso (`FAR_CLUSTER`, `ORIGIN_CLUSTER`,
    deviazione 100) il k-nearest non è migliore della scansione
    lineare, perché legge celle intere: circa 1,0 volte con celle da 100,
    1,3 con celle da 400, 0,37 con celle da 25;
  - con celle piccole crolla sui dati sparsi: con 1000 entità, celle da
    25 arrivano a 14 volte la scansione lineare nei k-nearest.
- **Range molto grandi e k-nearest su dati sparsi.** La query attraversa
  molte celle o molti anelli vuoti.
- **Punti coincidenti.** Finiscono tutti nella stessa cella, che diventa
  una lista lunga. Il costo è in gran parte inevitabile (vedi
  [quadtree](/indices/quadtree.md#punti-coincidenti)), ma la grid, che
  non divide le celle, non ha problemi di ricorsione.
- **Query con valori enormi.** Se il raggio al quadrato di un cerchio o
  di una sfera va in overflow, la regione contiene ogni punto (anche la
  sua distanza al quadrato va in overflow), ma il box
  `[centro − raggio, centro + raggio]` non copre tutto lo spazio: in
  questo caso si leggono tutte le celle. Il caso è stato trovato dai
  test sui limiti e corretto, anche nella griglia di quadtree.
- **Query a cerchio vicino a un cluster denso.** Le celle lette sono
  quelle del box intorno al cerchio, più una cella per lato per gli
  errori di arrotondamento. Se il cerchio tocca solo il bordo di un
  cluster denso, il box copre quasi tutto il cluster e si leggono quasi
  tutte le sue entità. Nelle misure del profilo completo (2026-10-04,
  `FAR_CLUSTER`, centro delle query uniforme, 100 000 entità, celle da
  100) un range a cerchio costa 266 µs, contro 0,95 µs del box con lo
  stesso numero di risultati e 136 µs della scansione lineare. La
  [griglia di quadtree](/indices/grid-quadtree.md#benchmark) non ha il
  problema, perché scarta i nodi fuori dal cerchio (0,46 µs). Una
  possibile correzione è scartare anche le celle fuori dal cerchio.
- **Test delle entità nelle range query.** Due ottimizzazioni da fare,
  per ora non fatte (il lavoro sugli indici è in pausa):
  1. il test `Box2.contains` / `Box3.contains` usa `&&`, quindi fa un
     salto per ogni confronto; sulle entità delle celle di bordo il
     risultato è mescolato e il processore sbaglia spesso la previsione.
     Con `&` al posto di `&&` il test diventa senza salti. Il test è lo
     stesso in tutti gli indici, anche nella [scansione
     lineare](/indices/linear-scan.md#problematiche), quindi cambia
     anche il riferimento;
  2. le celle completamente dentro il box possono aggiungere tutte le
     loro entità senza il test, come fa già la griglia di quadtree con i
     nodi completamente dentro la regione. Il guadagno maggiore è con le
     selettività alte.

# Limiti delle coordinate

`2^30` celle per lato dall'origine: `L = min(2^30 · cellSize, 2^60)`
(vedi [limiti delle
coordinate](/decisions/spatial-indexing.md#limiti-delle-coordinate)).
Con celle di 100 sono circa ±1,1·10¹¹. Le entità restano in celle con
coordinate `int`; solo le query possono andare oltre, e le loro celle
si portano dentro l'intervallo degli `int`.

# Decisioni

- **Prese:** grid in 2D e 3D come primo indice spaziale, dimensione
  della cella scelta alla creazione della partizione, celle in una mappa:
  solo le celle con entità usano memoria.
- **Misure con più dimensioni di cella** (25, 50, 100, 200, 400),
  confrontate con la [scansione lineare](/indices/linear-scan.md) sulla
  macchina dedicata ai benchmark (profilo rapido, 2026-10-03): nessuna
  dimensione di cella va bene per tutti i casi (vedi
  [Problematiche](#problematiche)). Con celle grandi (200–400) la grid è
  l'indice più economico nelle scritture (update 1,9–2,0 e insert
  1,6–1,7 volte la scansione lineare) e in memoria (1,08 volte).
- **Misure con il profilo completo** (2026-10-04, piano
  `plans/index-comparison-full.json`): confermano quelle rapide, con
  differenze sotto il 10% sugli stessi parametri. Con 1 000 000 entità
  i rapporti delle scritture restano circa uguali (update 2,0–3,6 volte
  la scansione lineare), ma con celle grandi i k-nearest peggiorano
  (0,22 volte con celle da 400). In più mostrano il crollo con le
  [query a cerchio](#problematiche) vicino a un cluster denso. I
  rapporti sono in [Griglia di quadtree](/indices/grid-quadtree.md#benchmark).

# Correlati

- [Griglia di quadtree](/indices/grid-quadtree.md) — usa questa grid
  come primo livello e un quadtree dentro ogni cella.
- [Indicizzazione spaziale](/decisions/spatial-indexing.md) — la grid è
  indicata per entità piccole e numerose, come i proiettili.
