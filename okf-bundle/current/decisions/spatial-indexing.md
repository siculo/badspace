---
type: Design Decision
title: Indicizzazione spaziale
description: "Scelta di un indice per partizione, limiti delle coordinate e risultati misurati dei tre indici implementati in 2D e 3D."
tags: [badspace, design, spatial-indexing, partitioning]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Indice per partizione

Con il [partizionamento del DB](/current/decisions/partitioning.md) l'indice
si sceglie **per partizione**, al momento della sua creazione.
Sono disponibili scansione lineare, grid uniforme e griglia di
quadtree (octree in 3D). Ogni partizione ha un solo indice. Il default
di `PartitionConfig` è la scansione lineare; non è una scelta di
prestazioni universale.

# Limiti delle coordinate

La scelta dell'indice e dei suoi parametri fissa i **limiti delle
coordinate** che le entità della partizione possono avere. Una
scrittura con una posizione fuori dai limiti fallisce, quindi gli
indici non vedono mai entità degeneri o fuori posto.

- I limiti sono un intervallo chiuso `[min, max]`, uguale su tutti gli
  assi (`CoordinateLimits`).
- Il software li conosce **in anticipo**, da `IndexConfig.limits()`, e
  li legge da una **partizione esistente**, con `limits()` sulla
  partizione e sul contratto del nodo.
- Un **tetto globale**, `|c| ≤ 2^60`, vale per tutti gli indici, anche
  per la scansione lineare: le distanze al quadrato tra entità restano
  finite.
- Gli indici a celle accettano `2^30` celle per lato dall'origine:
  `L = min(2^30 · cellSize, 2^60)`. Le coordinate delle celle restano
  negli `int` con un ampio margine. Per esempio, con posizioni fino a
  200 UA in metri servono celle di almeno 32768 (vedi lo [scenario del
  sistema solare](/current/indices/solar-system-scenario.md#limiti-degli-indici)).
- **Le query non hanno limiti**: regioni e punti possono essere fuori
  dai limiti, anche molto lontano.
- **Solo valori finiti, ovunque**: `Point2`/`Point3` rifiutano `NaN` e
  infiniti, e un cerchio o una sfera rifiutano un raggio infinito. Gli
  infiniti non avevano usi reali (un box "su tutto lo spazio" si
  costruisce con i limiti della partizione) e creavano casi speciali,
  come `∞ − ∞ = NaN` nel box intorno a un cerchio.
- **Valori finiti enormi nelle query** restano ammessi: le distanze
  possono andare in overflow a `∞`, con un comportamento definito e
  uguale in tutti gli indici. Un cerchio il cui raggio al quadrato va in
  overflow contiene ogni punto la cui distanza al quadrato va anch'essa
  in overflow, cioè tutti: gli indici leggono allora tutte le celle.

Prima di questa decisione gli indici accettavano qualunque `double`,
con casi speciali per `NaN`, infiniti e celle fuori dagli `int` (vedi
la [griglia di quadtree](/current/indices/grid-quadtree.md)). I limiti
semplificano il codice e rendono visibile in anticipo il costo di una
configurazione: celle piccole vogliono dire uno spazio piccolo.

# Stato

Il supporto a più tipi di indice è parte del modello a partizioni.
Nel prototipo sono implementate la scansione lineare, la grid uniforme
e la griglia di quadtree, in 2D e 3D. Il confronto delle prestazioni guida la scelta applicativa dell’indice.
La scansione lineare è il default operativo della configurazione.

Le misure con il profilo rapido (2026-10-03) danno un'indicazione, non
ancora una decisione:

- [griglia di quadtree](/current/indices/grid-quadtree.md#benchmark) con celle
  da 256 per le entità con molte query o con dati a cluster: è l'unico
  indice senza crolli nelle query, ma costa di più nelle scritture;
- [grid uniforme](/current/indices/uniform-grid.md#decisioni) con celle grandi
  per molte scritture e poche query, su dati distribuiti in modo
  uniforme.

Le misure con il profilo completo (2026-10-04, fino a 1 000 000 di
entità) confermano l'indicazione. In più mostrano che la grid uniforme
crolla anche con le query a cerchio vicino a un cluster denso, e che
con la griglia di quadtree il costo delle scritture cresce con il
numero di entità (update 6,9 volte la scansione lineare con 1 000 000).
Il test del box nelle range query usa salti condizionali: questo
influenza anche la scansione lineare usata come riferimento.

La [capacità degli aggiornamenti per tick](/current/indices/update-capacity.md)
fornisce un modello per leggere queste misure nel budget del software.
