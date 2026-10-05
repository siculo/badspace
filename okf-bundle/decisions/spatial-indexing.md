---
type: Design Decision
title: Indicizzazione spaziale
description: L'indice spaziale si sceglie per partizione e fissa i limiti delle coordinate delle entità; scansione lineare, grid uniforme e griglia di quadtree sono implementate, il confronto tra le strutture resta aperto.
tags: [badspace, design, spatial-indexing, partitioning]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-05T21:59:03Z }
---

# Opzioni considerate

Più strutture possibili, con trade-off diversi:

- **[Grid uniforme](/indices/uniform-grid.md)** — semplice, O(1) su
  densità regolare.
- **[Quadtree / octree](/indices/quadtree.md)** — si adatta a densità
  non uniformi.
- **[Griglia di quadtree](/indices/grid-quadtree.md)** — grid in cui
  ogni cella è la radice di un quadtree o di un octree.
- **[R-tree](/indices/r-tree.md)** — buono per bounding box eterogenei.

Ogni indice ha un suo documento nella sezione [indici
spaziali](/indices/), con il funzionamento, le problematiche e le
decisioni prese o da prendere. La [scansione
lineare](/indices/linear-scan.md) è il riferimento per i test e i
benchmark.

# Indice per partizione

Con il [partizionamento del DB](/decisions/partitioning.md) l'indice
si sceglie **per partizione**, al momento della sua creazione.
Partizioni con profili omogenei possono usare l'indice più adatto, per
esempio:

- una struttura costruita una volta per la geometria statica;
- una grid per i proiettili;
- un quadtree per i giocatori.

Da valutare se una partizione può avere più di un indice.

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
  sistema solare](/indices/solar-system-scenario.md#limiti-degli-indici)).
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
la [griglia di quadtree](/indices/grid-quadtree.md)). I limiti
semplificano il codice e rendono visibile in anticipo il costo di una
configurazione: celle piccole vogliono dire uno spazio piccolo.

In futuro il software potrebbe dichiarare alla creazione **limiti più
stretti** di quelli dell'indice, per esempio quelli della zona della
partizione; per il [quadtree con una sola radice](/indices/quadtree.md)
renderebbe naturale una radice con limiti fissi.

# Stato

Il supporto a più tipi di indice è parte del modello a partizioni.
Nel prototipo sono implementate la scansione lineare, la grid uniforme
e la griglia di quadtree, in 2D e 3D. Il quadtree con una sola radice
è rimandato, e l'R-tree non è ancora prototipato. Nessuna struttura è
stata scelta come default: la scelta dipende dai benchmark tra indici.

Le misure con il profilo rapido (2026-10-03) danno un'indicazione, non
ancora una decisione:

- [griglia di quadtree](/indices/grid-quadtree.md#benchmark) con celle
  da 256 per le entità con molte query o con dati a cluster: è l'unico
  indice senza crolli nelle query, ma costa di più nelle scritture;
- [grid uniforme](/indices/uniform-grid.md#decisioni) con celle grandi
  per molte scritture e poche query, su dati distribuiti in modo
  uniforme.

Le misure con il profilo completo (2026-10-04, fino a 1 000 000 di
entità) confermano l'indicazione. In più mostrano che la grid uniforme
crolla anche con le query a cerchio vicino a un cluster denso, e che
con la griglia di quadtree il costo delle scritture cresce con il
numero di entità (update 6,9 volte la scansione lineare con 1 000 000).
Il test del box nelle range query è da ottimizzare in tutti gli indici
(vedi [scansione lineare](/indices/linear-scan.md#problematiche) e
[grid uniforme](/indices/uniform-grid.md#problematiche)); dopo vanno
rifatte le misure di tutti gli indici, con il nuovo riferimento.

Resta da prendere la decisione. Per
decidere quali compromessi sono accettabili serve sapere quante entità
si possono aggiornare in un tick nel caso peggiore: il modello è nella
[capacità degli aggiornamenti per tick](/indices/update-capacity.md).

# Correlati

- [Entità statiche vs dinamiche](/decisions/static-vs-dynamic-entities.md)
  — la scelta dell'indice interagisce con come le strutture statiche e
  dinamiche vengono costruite e aggiornate.
- Le modifiche ai [metadati delle entità](/architecture/entity-metadata.md)
  non toccano l'indice.
