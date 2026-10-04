---
type: Spatial Index
title: Griglia di quadtree
description: Indice a due livelli, una grid uniforme in cui ogni cella è la radice di un quadtree (2D) o di un octree (3D); unisce lo spazio illimitato della grid con l'adattamento alla densità del quadtree.
tags: [badspace, spatial-indexing, grid, quadtree, octree]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-04T00:04:26Z }
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
  grandi. Le misure di diagnosi (2026-10-03 e 2026-10-04, indicative)
  indicano la causa (il modello è in [Frequenza dei cambi di
  foglia](#frequenza-dei-cambi-di-foglia)):
  - **la frequenza dei cambi di foglia.** Nei cluster densi le foglie
    sono piccole, perché l'indice si adatta alla densità: un passo di 10
    unità cambia foglia nel 98% dei casi con `FAR_CLUSTER` (93% con
    capacità 64), e solo nel 15% con `UNIFORM`. Con la grid uniforme da
    400 lo stesso passo cambia cella di rado;
  - **gli accessi sparsi di ogni cambio:** circa 8–10 accessi in punti
    diversi della memoria (la foglia e il posto dello slot, le liste di
    slot delle due foglie, il posto dello slot spostato nel buco, i nodi
    del percorso). Le allocazioni per batch sono quelle della scansione
    lineare, quindi divisioni e riunioni non si ripetono di continuo;
  - **non il layout dei nodi.** Un esperimento con i nodi in un'arena
    (campi int e double in array, i 4 figli in un blocco contiguo, liste
    dei nodi liberi) ha dato gli stessi tempi dei nodi come oggetti, in
    scritture e query (update LOCAL su `FAR_CLUSTER` 15,7 µs contro
    16,5); il codice è stato rimosso. In C o Rust l'arena resta il modo
    naturale di scrivere l'albero, ma non chiuderà la distanza: ogni
    accesso costa meno, ma la latenza della memoria e la frequenza dei
    cambi restano le stesse.

  Foglie più grandi aiutano poco: con capacità 32 gli update costano
  circa il 13% in meno e le query restano uguali; con 64 circa il 22% in
  meno, ma le query peggiorano del 10–20%. Le opzioni:
  - **quadtree loose:** una foglia accetta le entità anche un po' fuori
    dal suo box, fino a un margine; chi si muove di poco resta nella sua
    foglia, e le query allargano i box del margine. Riduce i cambi di
    foglia, ed è una modifica dell'algoritmo, quindi vale in ogni
    linguaggio;
  - **capacità della foglia 32** di default, da confermare sulla
    macchina dei benchmark;
  - **accettare il costo**, usando la griglia di quadtree per le
    partizioni con molte query e la [grid
    uniforme](/indices/uniform-grid.md) per quelle con molte scritture.

## Frequenza dei cambi di foglia

Quanto spesso un'entità cambia foglia dipende da un solo rapporto:

```
r = d / L = v · DT / L
```

dove `v` è la velocità dell'entità, `DT` la durata del tick, `d` lo
spostamento in un tick e `L` il lato della foglia. `r` dice quanti lati
di foglia l'entità percorre in un tick.

Con la posizione uniforme nella foglia e la direzione casuale,
un'entità che si sposta di `(dx, dy)` resta nella foglia con probabilità
`(1 − |dx|/L) · (1 − |dy|/L)`. La media sulle direzioni dà, in 2D:

```
P(cambio di foglia) = (4/π) · r − (1/π) · r²      per r ≤ 1
```

- per `r` piccolo vale circa `1,27 · r`;
- per `r = 1` vale `3/π ≈ 0,95`;
- oltre 1 tende a 1: la foglia è più piccola dello spostamento;
- in 3D, per `r` piccolo, vale circa `1,5 · r`.

In termini di tempo, un'entità attraversa in media `(4/π) · v / L` bordi
di foglia per unità di tempo; il tick decide quanti di questi diventano
aggiornamenti dell'indice, al più uno per tick.

Il modello spiega le misure, con il passo LOCAL di 10 unità:

| Caso | L | r | P dal modello | P misurata |
|---|---|---|---|---|
| `FAR_CLUSTER`, capacità 16 | circa 2–4 | 2,5–5 | circa 1 | 98% |
| `UNIFORM`, capacità 16 | 64–128 | 0,08–0,16 | 10–19% | 15% |
| `UNIFORM`, capacità 64 | 128–256 | 0,04–0,08 | 5–10% | 8% |

In una griglia di quadtree `L` non si sceglie: dipende dalla densità
locale `ρ`. Una foglia tiene tra circa metà della capacità e la capacità,
quindi, con `k` entità per foglia:

```
L ≈ √(k / ρ)        r ≈ v · DT · √(ρ / k)
```

Il caso peggiore è un gruppo **denso** di entità **veloci**, proprio
quello in cui l'indice si adatta meglio per le query. Le conseguenze:

- **Costo per tick:** circa `N · P(r) · c`, con `c` il costo di un
  cambio di foglia (gli accessi sparsi descritti sopra). L'esperimento
  con i nodi in array ha mostrato che `c` scende poco; la leva è `P`.
- **Quadtree loose:** con un margine `m` su ogni lato, un'entità appena
  inserita percorre circa `m` in più prima di uscire, e il rapporto
  efficace diventa circa `d / (L + 2m)`. Con `m` dell'ordine di `v · DT`,
  `P` scende sotto 1/2 anche con foglie molto piccole.
- **Il margine viene dal software:** `v · DT` lo conosce il software,
  non l'indice, quindi il margine potrebbe essere un parametro della
  partizione, scelto dalla velocità massima delle entità e dalla durata
  del tick; per le partizioni statiche è zero.

Il modello serve anche a dimensionare le partizioni: quante entità si
possono aggiornare in un tick, dati i tick al secondo, è nella
[capacità degli aggiornamenti per tick](/indices/update-capacity.md).

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
  - **Nodi come oggetti Java.** Un esperimento con i nodi in array (2D,
    2026-10-04) non ha dato guadagno, quindi si resta con gli oggetti
    (vedi [Costo delle scritture](#problematiche-aperte)).
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
