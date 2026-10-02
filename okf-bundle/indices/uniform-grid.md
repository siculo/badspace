---
type: Spatial Index
title: Grid uniforme
description: Indice che divide lo spazio in celle quadrate (2D) o cubiche (3D) della stessa dimensione, tenute in una mappa; aggiornamenti economici, query veloci se la cella è adatta alla densità e alle query.
tags: [badspace, spatial-indexing, grid]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-02T10:44:22Z }
---

# Come funziona

Lo spazio è diviso in celle di lato `cellSize`, scelto alla creazione
della partizione (`IndexConfig.UniformGrid`). La cella di un punto si
ricava con `floor(coordinata / cellSize)` su ogni asse.

- Le celle stanno in una **mappa** (cella → slot delle entità): solo le
  celle con entità usano memoria, quindi lo spazio **non ha limiti**.
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

# Problematiche

- **Dimensione della cella.** È il parametro critico. Celle troppo
  grandi rispetto alla densità leggono molte entità inutili; celle
  troppo piccole rispetto alle query leggono molte celle.
- **Densità non uniforme.** Con cluster e hotspot una sola dimensione di
  cella non va bene ovunque: celle piene di entità nelle zone dense,
  celle quasi vuote altrove. È il problema che la [griglia di
  quadtree](/indices/grid-quadtree.md) vuole risolvere.
- **Range molto grandi e k-nearest su dati sparsi.** La query attraversa
  molte celle o molti anelli vuoti.
- **Punti coincidenti.** Finiscono tutti nella stessa cella, che diventa
  una lista lunga. Il costo è in gran parte inevitabile (vedi
  [quadtree](/indices/quadtree.md#punti-coincidenti)), ma la grid, che
  non divide le celle, non ha problemi di ricorsione.
- **Coordinate molto grandi.** I valori fuori dall'intervallo degli
  `int` finiscono nelle celle al bordo, che possono diventare grandi; i
  risultati restano corretti.

# Decisioni

- **Prese:** grid in 2D e 3D come primo indice spaziale, dimensione
  della cella scelta alla creazione della partizione, celle in una mappa
  per avere uno spazio senza limiti.
- **Da fare:** misure con più dimensioni di cella (25, 50, 100, 200,
  400), confrontate con la [scansione lineare](/indices/linear-scan.md),
  sulla macchina dedicata ai benchmark.

# Correlati

- [Griglia di quadtree](/indices/grid-quadtree.md) — usa questa grid
  come primo livello e un quadtree dentro ogni cella.
- [Indicizzazione spaziale](/decisions/spatial-indexing.md) — la grid è
  indicata per entità piccole e numerose, come i proiettili.
