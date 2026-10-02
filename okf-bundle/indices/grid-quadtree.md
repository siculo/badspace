---
type: Spatial Index
title: Griglia di quadtree
description: Indice a due livelli, una grid uniforme in cui ogni cella è la radice di un quadtree (2D) o di un octree (3D); unisce lo spazio illimitato della grid con l'adattamento alla densità del quadtree.
tags: [badspace, spatial-indexing, grid, quadtree, octree]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-02T13:43:31Z }
---

# Come funziona

Il primo livello è una [grid uniforme](/indices/uniform-grid.md): celle
di lato `cellSize` in una mappa, solo quelle con entità. Ogni cella è la
**radice di un [quadtree](/indices/quadtree.md)** (octree in 3D) con
confini fissi, quelli della cella.

- Una cella con poche entità è una sola foglia: si comporta come la grid.
- Una cella con un cluster si divide quanto serve: la grid non ha più il
  problema delle celle troppo piene.
- Un'entità che cambia cella esce da un albero ed entra in un altro;
  dentro la cella gli aggiornamenti sono quelli del quadtree.
- Le query lavorano sulle celle come la grid, e dentro ogni cella come
  il quadtree (range con nodi interi, k-nearest best-first).

È un modello comune nei motori di gioco: il mondo è diviso in settori,
e ogni settore ha il suo albero.

## Relazione con il quadtree allineato

Con `cellSize` potenza di 2, le celle sono esattamente i box di un
livello `k` del quadtree infinito con box allineati alle potenze di 2
(vedi [posizione della radice](/indices/quadtree.md#posizione-della-radice)).
La mappa delle celle sostituisce tutti i livelli sopra `k` con un salto
diretto in O(1): è una compressione dei livelli alti, senza il codice
del compressed quadtree.

# Problematiche risolte

- **Posizione della radice:** ogni albero ha la radice fissa nella sua
  cella. Un cluster è al massimo a `log2(cellSize / dimensione del
  cluster)` livelli dalla radice, ovunque si trovi; non serve la radice
  che cresce.
- **Limite di profondità:** si esprime come lato minimo
  `cellSize / 2^maxDepth`, relativo alla cella; non dipende dall'unità
  di misura né dalla posizione nello spazio.
- **Spazio illimitato:** viene dalla grid.

# Problematiche aperte

- **Range molto grandi:** una regione che copre migliaia di celle le
  deve guardare tutte, come la grid; un quadtree puro prenderebbe pochi
  nodi grandi. Rimedio: se le celle della regione sono più di quelle non
  vuote, si scorrono le celle non vuote.
- **K-nearest su dati sparsi:** la ricerca ad anelli attraversa molti
  anelli vuoti, come nella grid.
- **Dimensione della cella:** resta un parametro, ma meno critico che
  nella grid. Troppo piccola, l'indice tende alla grid; troppo grande,
  tende al quadtree con una radice e catene corte (logaritmiche).
- **Punti coincidenti:** dentro la cella valgono le [regole di
  arresto](/indices/quadtree.md#punti-coincidenti) del quadtree, con il
  limite di profondità più il controllo `min < mid < max`.
- **Cluster a cavallo delle celle:** un cluster sul confine si divide in
  più alberi (fino a 4 in 2D, 8 in 3D). Non è un errore, solo un piccolo
  costo in più per le query.

# Decisioni

- **Prese:**
  - È un **tipo di indice distinto**, con un suo record in
    `IndexConfig`, e si implementa prima del quadtree con una sola
    radice.
  - `cellSize` è **solo una potenza di 2**: confini delle celle e punti
    medi sono esatti con i `double`.
  - **Riunione dei nodi con isteresi:** una foglia si divide quando ha
    più entità della capacità, e un nodo torna foglia quando ha al più
    metà della capacità.
  - **Valori di default:** capacità della foglia 16, quindi riunione a 8
    entità o meno; al massimo 24 livelli sotto la cella.
  - **Forma del record:** `IndexConfig.GridQuadtree(cellSize,
    leafCapacity)`, con `cellSize` controllato come potenza di 2 tra
    2^-30 e 2^30; soglia di riunione e profondità si ricavano. Lo stesso
    record vale in 2D (quadtree) e in 3D (octree).
  - **Nodi come oggetti Java** nella prima versione; array primitivi se i
    benchmark lo giustificano.
- **Da prendere:** come muovere un'entità verso una foglia vicina. Ora si
  rimuove e si reinserisce dalla cella; si può risalire solo fino al
  primo nodo che contiene la nuova posizione, se le misure lo chiedono.

# Implementazione nel prototipo

`GridQuadtreeIndex2` e `GridOctreeIndex3`, nel modulo service.

- **Regola di arresto:** una foglia non si divide oltre 24 livelli, né
  quando i bordi delle sue metà non sarebbero esatti con i `double`.
- **Bordi esatti:** con `cellSize` potenza di 2 la cella di un punto si
  calcola senza errori (con una correzione per i valori negativi molto
  piccoli), quindi un'entità è in un nodo se e solo se è nel box del
  nodo, e le query non hanno bisogno di margini.
- **Limiti delle coordinate:** `2^30` celle per lato dall'origine,
  `L = min(2^30 · cellSize, 2^60)`; con celle di 128 sono `2^37`, circa
  ±1,4·10¹¹ (vedi [limiti delle
  coordinate](/decisions/spatial-indexing.md#limiti-delle-coordinate)).
  Ogni entità è quindi in una cella con coordinate `int`. La prima
  versione accettava anche entità con coordinate `NaN` o fuori dalle
  celle, tenute in una lista a parte letta da ogni query; con i limiti
  quella lista non serve più.
- **K-nearest:** ricerca best-first su una coda di nodi, a cui si
  aggiungono le celle ad anelli intorno al punto quando possono essere
  abbastanza vicine.
- **Benchmark:** nome `GRID_QUADTREE_<lato>` o
  `GRID_QUADTREE_<lato>_<capacità>`.

# Benchmark

- **Previsti:** confronto con grid uniforme e scansione
  lineare sulle distribuzioni attuali, più un cluster lontano
  dall'origine e un cluster centrato sull'origine.

# Correlati

- [Grid uniforme](/indices/uniform-grid.md) — il primo livello.
- [Quadtree e octree](/indices/quadtree.md) — il secondo livello e i
  problemi che condivide.
- [Indicizzazione spaziale](/decisions/spatial-indexing.md) — la scelta
  dell'indice per partizione.
