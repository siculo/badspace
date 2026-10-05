# TODO degli indici

Indici spaziali e loro test: strutture, strumenti di benchmark e misure. Il
[TODO del prototipo](TODO.md) usa gli indici così come sono e non dipende da
questa lista.

**In pausa (dal 2026-10-05).** Il lavoro fatto fin qui è servito a capire
meglio il problema; ora hanno la priorità le altre questioni del prototipo.
Le attività aperte si riprendono solo se diventa opportuno.

Documenti: [Indici spaziali](okf-bundle/indices/index.md), [Indicizzazione spaziale](okf-bundle/decisions/spatial-indexing.md), [Capacità degli aggiornamenti per tick](okf-bundle/indices/update-capacity.md), [Scenario del sistema solare](okf-bundle/indices/solar-system-scenario.md).

## Cose da fare

### Strutture

- [x] Scansione lineare come primo tipo di indice, dietro l'interfaccia comune degli indici
- [x] Query di range su una partizione, con scansione lineare
- [x] Query k-nearest su una partizione, con scansione lineare
- [x] Test di correttezza degli indici, con la scansione lineare come riferimento
- [x] Indice spaziale minimo per partizione: grid uniforme 2D e 3D, con la dimensione della cella scelta alla creazione (`IndexConfig`)
- [x] Griglia di quadtree 2D e griglia di octree 3D come terzo indice, con `cellSize` potenza di 2 e riunione dei nodi con isteresi (`IndexConfig`) → [Griglia di quadtree](okf-bundle/indices/grid-quadtree.md)
- [x] Griglia di quadtree: spostamento verso una foglia vicina risalendo solo fino al primo nodo che contiene la nuova posizione, perché gli aggiornamenti LOCAL sui cluster densi costano circa 8 volte la scansione lineare
- [ ] Griglia di quadtree loose (foglie con un margine, così chi si muove di poco resta nella sua foglia), solo se il costo delle scritture nei cluster densi diventa un problema → [Griglia di quadtree](okf-bundle/indices/grid-quadtree.md#problematiche-aperte)
- [ ] Test del box senza salti nelle range query: `&` al posto di `&&` in `Box2.contains` e `Box3.contains`; vale per tutti gli indici e per il riferimento → [Scansione lineare](okf-bundle/indices/linear-scan.md#problematiche)
- [ ] Grid uniforme: le celle completamente dentro il box aggiungono le loro entità senza il test, come fa già la griglia di quadtree → [Grid uniforme](okf-bundle/indices/uniform-grid.md#problematiche)
- [ ] Grid uniforme: nelle query a cerchio scartare le celle fuori dal cerchio, per il crollo vicino a un cluster denso (opzionale) → [Grid uniforme](okf-bundle/indices/uniform-grid.md#problematiche)
- [ ] Quadtree/octree con una sola radice (radice che cresce, compressione), solo se le misure mostrano che servono range molto grandi o k-nearest su dati sparsi → [Quadtree e octree](okf-bundle/indices/quadtree.md)

### Strumenti di benchmark

- [x] Modulo di benchmark (JMH) a livello di nodo, con profili rapido e completo
- [x] Generatori di dati riproducibili (seed fisso) con distribuzioni della posizione: uniforme, a cluster, hotspot, corridoi, punti coincidenti
- [x] Distribuzioni dei benchmark con un cluster lontano dall'origine e un cluster centrato sull'origine (`FAR_CLUSTER`, `ORIGIN_CLUSTER`), con il mondo che dipende dalla distribuzione
- [x] Movimento negli aggiornamenti: spostamenti locali e teletrasporti casuali
- [x] Passo del movimento LOCAL come parametro dei benchmark (`step`), per misurare la frequenza dei cambi di foglia e il costo delle scritture al variare di `r = v · DT / L` → [Frequenza dei cambi di foglia](okf-bundle/indices/grid-quadtree.md#frequenza-dei-cambi-di-foglia)
- [x] Benchmark per operazione (costruzione, inserimento, aggiornamento, rimozione, lettura per ID, range, k-nearest) al variare di numero di entità, batch e selettività
- [x] Metriche: tempo medio, percentili di latenza, memoria per entità, tempo di costruzione
- [x] Report HTML per confrontare i risultati (grafici e rapporti rispetto a un riferimento)
- [x] Piani di benchmark in un file JSON: gruppi con un profilo, un report di confronto e le run con le stesse opzioni di `run`; tutto controllato prima di partire; sostituiscono `compare-indices.sh`
- [x] Controllo del codice misurato: avviso se il jar dei benchmark è più vecchio dei sorgenti, commit del codice scritto nei risultati
- [ ] Frequenza misurata dei cambi di foglia nei benchmark (contatore), per confrontarla con `P(r)` e non solo con il tempo
- [ ] Benchmark a tick degli aggiornamenti: dati indice, distribuzione, velocità `v` e tick al secondo `f`, aggiorna tutte le `N` entità a ogni tick e misura il tempo per tick (p50, p99, massimo) al variare di `N`, anche con moti correlati (gruppi che si muovono insieme); da qui `c0`, `c1` e il numero massimo di entità per indice → [Capacità degli aggiornamenti per tick](okf-bundle/indices/update-capacity.md)
- [ ] Carico misto a tick (aggiornamenti e query per tick)
- [ ] Benchmark a livello di API
- [ ] TUI per il tool dei benchmark con JLine 3 (`jline-console-ui`): una procedura guidata a domande (profilo, indici, filtri, parametri, percentili), con la scelta dei file in `results/` per il report e l'apertura del report HTML alla fine. Si lancia come nuovo comando dello stesso jar (`./bench.sh tui`). Se serve una dashboard a schermo intero con l'avanzamento dal vivo, si può passare a TamboUI, che usa JLine.

### Misure

- [x] Misure di riferimento con la scansione lineare (profilo rapido), salvate nel repo
- [x] Misure della grid uniforme con più dimensioni di cella (25, 50, 100, 200, 400), confrontate con la scansione lineare, sulla macchina dedicata ai benchmark (profilo rapido, piano `plans/index-comparison-quick.json`)
- [x] Misure della griglia di quadtree confrontate con grid uniforme e scansione lineare (profilo rapido) → [Griglia di quadtree](okf-bundle/indices/grid-quadtree.md#benchmark)
- [x] Misure dello spostamento verso una foglia vicina sulla macchina dedicata ai benchmark (piano `plans/grid-quadtree-update.json`), poi i risultati nel bundle
- [ ] Misure degli update al variare del passo sulla macchina dedicata ai benchmark (piano `plans/update-step.json`), poi il confronto con il modello `P(r)` nel bundle
- [x] Misure di riferimento con il profilo completo (2026-10-04, piano `plans/index-comparison-full.json`) → [Griglia di quadtree](okf-bundle/indices/grid-quadtree.md#benchmark)
- [ ] Dopo le ottimizzazioni delle range query: rifare il profilo completo di tutti gli indici, con il nuovo riferimento → [Scansione lineare](okf-bundle/indices/linear-scan.md#problematiche)
- [ ] Benchmark relativi tra strutture di indice

## Decisioni da prendere

### Già prese

- [x] Griglia di quadtree come prossimo indice, prima del quadtree con una sola radice
- [x] Nella griglia di quadtree `cellSize` solo potenze di 2 e riunione dei nodi con isteresi
- [x] Griglia di quadtree: capacità della foglia 16 di default, riunione con metà della capacità, al massimo 24 livelli sotto la cella; record `IndexConfig.GridQuadtree(cellSize, leafCapacity)`; nodi come oggetti Java

### Da prendere

- [ ] Obiettivo di capacità per partizione: quante entità aggiornare a `f` tick al secondo, con quale quota `β` del tick; dice se il costo delle scritture è un problema, e quindi se serve la griglia di quadtree loose → [Capacità degli aggiornamenti per tick](okf-bundle/indices/update-capacity.md)
- [ ] Struttura dell'indice spaziale (grid, quadtree/octree, R-tree)
- [ ] Più di un indice per partizione
