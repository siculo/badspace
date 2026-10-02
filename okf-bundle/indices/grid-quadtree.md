---
type: Spatial Index
title: Griglia di quadtree
description: Indice a due livelli, una grid uniforme in cui ogni cella è la radice di un quadtree (2D) o di un octree (3D); unisce lo spazio illimitato della grid con l'adattamento alla densità del quadtree.
tags: [badspace, spatial-indexing, grid, quadtree, octree]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-02T10:44:22Z }
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
  - **Riunione dei nodi con isteresi:** un nodo si divide sopra la
    capacità della foglia e i figli si riuniscono quando insieme hanno
    meno entità di una soglia più bassa (per esempio la metà).
- **Da prendere:**
  - Valori di default: capacità della foglia (proposta 16), soglia di
    riunione (proposta 8), limite di profondità (proposta 24–32 livelli
    sotto la cella).
  - Forma del record in `IndexConfig` (per esempio `cellSize` e
    `leafCapacity`, con soglia e profondità ricavate) e come esprimere la
    potenza di 2 (esponente o lato controllato).
  - Rappresentazione dei nodi: oggetti Java nella prima versione, array
    primitivi se i benchmark lo giustificano.
- **Benchmark previsti:** confronto con grid uniforme e scansione
  lineare sulle distribuzioni attuali, più un cluster lontano
  dall'origine e un cluster centrato sull'origine.

# Correlati

- [Grid uniforme](/indices/uniform-grid.md) — il primo livello.
- [Quadtree e octree](/indices/quadtree.md) — il secondo livello e i
  problemi che condivide.
- [Indicizzazione spaziale](/decisions/spatial-indexing.md) — la scelta
  dell'indice per partizione.
