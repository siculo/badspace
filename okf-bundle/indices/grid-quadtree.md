---
type: Spatial Index
title: Griglia di quadtree
description: Indice a due livelli, una grid uniforme in cui ogni cella è la radice di un quadtree (2D) o di un octree (3D); unisce lo spazio illimitato della grid con l'adattamento alla densità del quadtree.
tags: [badspace, spatial-indexing, grid, quadtree, octree]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-03T14:09:55Z }
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
  arresto](/indices/quadtree.md#punti-coincidenti) del quadtree. Per i
  punti esattamente coincidenti si applica il controllo prima di
  dividere: una foglia con tutte le entità nella stessa posizione non si
  divide. Per i punti quasi coincidenti resta una catena, limitata a
  circa `log2(cellSize / d)` livelli per due punti a distanza `d`, oltre
  al limite di profondità e al controllo `min < mid < max`; per toglierla
  servirebbe la compressione.
- **Cluster a cavallo delle celle:** un cluster sul confine si divide in
  più alberi (fino a 4 in 2D, 8 in 3D). Non è un errore, solo un piccolo
  costo in più per le query.
- **Costo delle scritture:** nei cluster densi un update costa circa 8
  volte la scansione lineare e 5 volte la grid uniforme con celle
  grandi. Le misure di diagnosi (2026-10-03, indicative) indicano la
  causa:
  - le allocazioni per batch sono quelle della scansione lineare, quindi
    divisioni e riunioni non si ripetono di continuo;
  - il tempo è nella risalita e nella discesa dell'albero, che con
    `FAR_CLUSTER` ha al più 7 livelli;
  - uno spostamento che cambia foglia costa circa 140 ns in più della
    scansione lineare, circa 20 ns per nodo visitato: è la latenza della
    memoria, con i nodi come oggetti Java sparsi nell'heap.

  Foglie più grandi aiutano poco: con capacità 32 gli update costano
  circa il 13% in meno e le query restano uguali; con 64 circa il 22% in
  meno, ma le query peggiorano del 10–20%. Le opzioni:
  - **nodi in array** (un'arena con indici, i figli in blocchi
    contigui), come prova del layout dell'implementazione nativa: in C o
    Rust un albero si scrive comunque così, e la latenza della memoria lì
    pesa di più, perché il resto costa meno;
  - **capacità della foglia 32** di default, da confermare sulla
    macchina dei benchmark;
  - **accettare il costo**, usando la griglia di quadtree per le
    partizioni con molte query e la [grid
    uniforme](/indices/uniform-grid.md) per quelle con molte scritture.

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
- **Movimento verso una foglia vicina (presa e implementata):** si
  risale solo fino al primo nodo che contiene anche la nuova posizione,
  e si scende da lì; uno spostamento in un'altra cella passa dalla mappa
  delle celle. Sulla macchina dei benchmark non ha dato un guadagno
  misurabile (vedi [Benchmark](#benchmark)), ma si tiene: è corretto, i
  test controllano la struttura dell'albero, ed evita le riunioni
  inutili quando un'entità si sposta dentro lo stesso nodo.

# Implementazione nel prototipo

`GridQuadtreeIndex2` e `GridOctreeIndex3`, nel modulo service.

- **Regola di arresto:** una foglia non si divide quando tutte le sue
  entità sono nella stessa posizione, oltre 24 livelli, né quando i bordi
  delle sue metà non sarebbero esatti con i `double`.
  - **Foglia impilata:** al momento della divisione un controllo O(n)
    guarda se tutte le entità della foglia hanno la stessa posizione; se
    sì, la foglia si segna (campo `stackedAt`) e non si divide. Il
    controllo si fa una sola volta: i nuovi inserimenti nella stessa
    posizione costano O(1). Un'entità che entra in una posizione diversa,
    per inserimento o per movimento dentro la foglia, toglie il segno e la
    foglia si può dividere di nuovo.
  - **Confronto delle coordinate:** con `==`, quindi `0.0` e `-0.0` sono
    la stessa posizione; vanno sempre nello stesso figlio, quindi una
    divisione non le separerebbe.
  - **Motivo:** senza questo controllo, con la distribuzione `COINCIDENT`
    (circa 100 entità per posizione) le foglie formavano catene fino a 24
    livelli: nelle misure quick del 2026-10-02 l'update costava fino a
    circa 17 volte la scansione lineare e la memoria era di circa 250
    byte per entità invece di circa 170.
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

- **Distribuzioni:** oltre a quelle generali, `FAR_CLUSTER` (un cluster
  al centro di un mondo lontano dall'origine, intorno a 1e8) e
  `ORIGIN_CLUSTER` (un cluster sull'origine, che è un bordo delle celle
  per ogni dimensione di cella e quindi divide il cluster tra più
  alberi). Per la griglia di quadtree la distanza dall'origine non conta,
  perché ogni albero ha la radice nella sua cella; conterà per un
  eventuale quadtree con una sola radice.
- **Confronto tra indici (profilo rapido):** misure del 2026-10-03
  sulla macchina dedicata ai benchmark, con il piano
  `plans/index-comparison-quick.json` (commit `fb37ec1`). Rapporto
  rispetto alla [scansione lineare](/indices/linear-scan.md), media
  geometrica su tutti i parametri, con 100 000 entità (sotto 1 è
  meglio):

  | | Griglia di quadtree (celle 64–256) | Grid uniforme (celle 25–400) |
  |---|---|---|
  | k-nearest | 0,0044–0,0057 | 0,033–0,17 |
  | Range | 0,021–0,026 | 0,040–0,11 |
  | Update | 4,4–5,1 | 1,9–3,2 |
  | Insert | 2,7–3,1 | 1,6–2,0 |
  | Memoria | 1,26–1,29 | 1,08–1,21 |

- **Query senza crolli:** il caso peggiore è 0,88 volte la scansione
  lineare (k-nearest su `HOTSPOT` sparso, con 1000 entità). La [grid
  uniforme](/indices/uniform-grid.md) invece crolla con celle piccole
  sui dati sparsi e con celle grandi su un solo cluster denso.
- **Dimensione della cella:** da 64 a 128 a 256 le query migliorano
  sempre meno (k-nearest 0,0057 → 0,0047 → 0,0044) e le scritture
  peggiorano poco (update 4,4 → 4,8 → 5,1): 256 è un buon punto.
- **Il costo sono le scritture:** un update LOCAL con batch di 100 su
  `FAR_CLUSTER` costa 18,9 µs, contro 3,1 µs della grid con celle da 400
  e 2,2 µs della scansione lineare. Nei cluster densi le foglie sono
  piccole (circa 4 unità di lato con celle da 256), quindi un passo breve
  cambia foglia (misura con l'entità rimossa e reinserita dalla cella).
- **Punti coincidenti:** con `COINCIDENT` l'update costa 4,7 volte la
  scansione lineare con le foglie impilate; prima era 17.
- **Movimento verso una foglia vicina:** misurato con il piano
  `plans/grid-quadtree-update.json` (commit `543d4b0`): rispetto a prima
  le scritture costano uguale (rapporto 1,01), per esempio update LOCAL
  su `FAR_CLUSTER` 19,5 µs contro 18,9. Il costo non viene dalla
  lunghezza del percorso (vedi [Costo delle
  scritture](#problematiche-aperte)).

# Correlati

- [Grid uniforme](/indices/uniform-grid.md) — il primo livello.
- [Quadtree e octree](/indices/quadtree.md) — il secondo livello e i
  problemi che condivide.
- [Indicizzazione spaziale](/decisions/spatial-indexing.md) — la scelta
  dell'indice per partizione.
