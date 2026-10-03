# Bundle Update Log

## 2026-10-03
* **Edit**: Aggiornata la [Griglia di quadtree](/indices/grid-quadtree.md) — il movimento verso una foglia vicina è implementato ma senza guadagno misurabile; nuova problematica aperta sul costo delle scritture, con la causa misurata (latenza della memoria dei nodi come oggetti), la prova delle capacità della foglia e le opzioni (nodi in array come prova del layout nativo, capacità 32, accettare il costo).
* **Edit**: Riportati i risultati del confronto tra indici con il profilo rapido (2026-10-03) nella [Griglia di quadtree](/indices/grid-quadtree.md#benchmark) (query senza crolli e molto più veloci, scritture più costose; si implementa il movimento verso una foglia vicina), nella [Grid uniforme](/indices/uniform-grid.md) (crolli con un solo cluster denso e con celle piccole su dati sparsi; con celle grandi è la più economica nelle scritture) e nell'[Indicizzazione spaziale](/decisions/spatial-indexing.md) (indicazione, non ancora decisione, su quale indice usare).
* **Edit**: Aggiornata la [Griglia di quadtree](/indices/grid-quadtree.md) — nuova regola di arresto per i punti esattamente coincidenti: una foglia con tutte le entità nella stessa posizione si segna come impilata e non si divide, invece di formare catene fino a 24 livelli (con `COINCIDENT` l'update costava fino a ~17 volte la scansione lineare); per i punti quasi coincidenti resta una catena limitata e la compressione resta aperta.

## 2026-10-02
* **Edit**: Aggiornata la [Griglia di quadtree](/indices/grid-quadtree.md) — distribuzioni `FAR_CLUSTER` e `ORIGIN_CLUSTER` disponibili nei benchmark; confronto tra indici ancora da fare.
* **Edit**: Aggiunti i [limiti delle coordinate](/decisions/spatial-indexing.md#limiti-delle-coordinate) — l'indice fissa i limiti delle coordinate delle entità, con un tetto globale di 2^60 e 2^30 celle per lato per gli indici a celle; scritture fuori dai limiti rifiutate, query senza limiti, solo valori finiti ovunque (`NaN` e infiniti rifiutati nei punti e nei raggi). Aggiornati [API da esporre](/decisions/api-surface.md), [Tipo delle coordinate](/decisions/coordinate-type.md), [Scansione lineare](/indices/linear-scan.md), [Grid uniforme](/indices/uniform-grid.md) e [Griglia di quadtree](/indices/grid-quadtree.md).
* **Edit**: Aggiornata la [Griglia di quadtree](/indices/grid-quadtree.md) — decisi i valori di default (capacità 16, riunione a 8, 24 livelli), la forma del record `IndexConfig.GridQuadtree(cellSize, leafCapacity)` e i nodi come oggetti Java; nuova sezione sull'implementazione nel prototipo.
* **Edit**: Aggiunta la sezione [Indici spaziali](/indices/) con un documento per indice: [Scansione lineare](/indices/linear-scan.md), [Grid uniforme](/indices/uniform-grid.md), [Quadtree e octree](/indices/quadtree.md) (PR quadtree con bucket, punti coincidenti e quasi coincidenti, posizione della radice con box allineati alle potenze di 2, compressione), [Griglia di quadtree](/indices/grid-quadtree.md) (nuovo tipo di indice; decise `cellSize` solo potenze di 2 e riunione dei nodi con isteresi) e [R-tree](/indices/r-tree.md) (non prototipato).
* **Edit**: Aggiornata l'[Indicizzazione spaziale](/decisions/spatial-indexing.md) — collegamenti ai documenti degli indici e stato del prototipo.

## 2026-09-30
* **Update**: Aggiornato [API da esporre](/decisions/api-surface.md) — forma delle query di range (`findInRegion` con regioni box e cerchio/sfera dietro un'interfaccia `sealed`, bordo incluso) e k-nearest (`findNearest`, ordine per distanza e poi per ID) su una partizione, con scansione lineare nel prototipo; raycast rimandato.
* **Edit**: Corretto [ID delle entità](/decisions/entity-ids.md) — solo l'epoca e la posizione del timestamp si fissano una volta per tutte; la divisione dei bit tra `generatorId` e sequenza si può cambiare anche più avanti senza collisioni, e la decisione è rimandata alla fase di chiusura.
* **Edit**: Aggiunta l'epoca degli ID (2026-01-01T00:00:00Z) in [ID delle entità](/decisions/entity-ids.md), come nel generatore del prototipo.
* **Edit**: Documentato l'orientamento alla gestione centralizzata — nuovo principio "Istanze dentro un sistema" nell'[Architettura minima](/architecture/minimal-core.md): le istanze non sono autonome, il livello base offre meccanismi e le decisioni sul ciclo di vita sono prese più in alto; richiamato in [Architettura a livelli](/architecture/layers.md) e nell'assegnazione del generatorId in [ID delle entità](/decisions/entity-ids.md).
* **Update**: Aggiornato [ID delle entità](/decisions/entity-ids.md) — nuova sezione "Generatore": formato 1/41/10/12 con epoca e divisione dei bit fissate come costanti, un generatore per processo API condiviso dagli spazi, tempo logico con prestito dei millisecondi e limite di anticipo (`nextId()` sincrona), generatorId assegnato a ogni avvio con riuso dopo un periodo di attesa e lease nel cluster, forma semplificata nel prototipo; resta aperta la divisione dei bit.

## 2026-09-29
* **Edit**: Aggiornata l'[API da esporre](/decisions/api-surface.md) — nuova sezione "Forma delle chiamate nel prototipo": record `Point2`/`Point3` ed `Entity2`/`Entity3`, operazioni di inserimento, lettura per ID, aggiornamento e rimozione su una o più entità, contratto del nodo solo batch, scritture tutto-o-niente; aggiornato di conseguenza il [Tipo delle coordinate](/decisions/coordinate-type.md).
* **Update**: Aggiornata l'[Architettura a livelli](/architecture/layers.md) — nuova sezione "Nodi": le partizioni sono ospitate da nodi locali o remoti scelti dal software; il futuro confine gRPC passa tra l'API e i nodi.
* **Update**: Aggiornato il [Tipo di strumento](/decisions/tool-type.md) — la superficie di rete è il contratto dei nodi, non l'API comune, che resta una libreria nel processo del software.
* **Update**: Aggiornato [ID delle entità](/decisions/entity-ids.md) — anche gli ID delle partizioni li genera l'API; punto aperto sui nodi condivisi tra spazi.
* **Edit**: Allineati al concetto di nodo [Glossario](/glossary.md), [Partizionamento del DB](/decisions/partitioning.md), [Casi d'uso](/use-cases.md), [API da esporre](/decisions/api-surface.md) e [Panoramica di BADSPACE](/overview.md).
* **Creation**: Aggiunto [Tipo delle coordinate](/decisions/coordinate-type.md) (draft) — `double` nei prototipi; limiti di precisione, alternativa a interi a 64 bit, portabilità e modi di rinviare la scelta.
* **Update**: Collegato il tipo delle coordinate da [Linguaggio](/decisions/language.md), [Supporto a 2D e 3D](/decisions/2d-3d-support.md) e [Panoramica di BADSPACE](/overview.md).

## 2026-09-28
* **Creation**: Aggiunto [Glossario](/glossary.md) — definizioni dei termini ricorrenti di BADSPACE.
* **Creation**: Aggiunto [Casi d'uso](/use-cases.md) — casi d'uso da supportare, con configurazione e meccanismi attivi.
* **Split**: Separata da [Tipo di strumento](/decisions/tool-type.md) la decisione [Linguaggio](/decisions/language.md) (draft), con la terminologia sull'interfaccia; Tipo di strumento passa a `stable`.
* **Update**: Aggiornata la [Panoramica di BADSPACE](/overview.md) — ambito generico (simulazione, monitoraggio, gaming) invece di "database per giochi", stato del progetto, scalabilità.
* **Update**: Aggiornato il [Partizionamento del DB](/decisions/partitioning.md) — partizioni non predefinite, create e rimosse dinamicamente; scalabilità.
* **Update**: Aggiornata l'[Architettura minima](/architecture/minimal-core.md) — sezione "Principi" (nucleo minimo, nessuna transazione tra partizioni, paga solo chi usa).
* **Update**: Aggiornato il [Commit della partizione](/architecture/partition-commit.md) — al più un commit per tick; un commit può coprire più tick.
* **Update**: Aggiornata la [Migrazione di entità](/mechanisms/entity-migration.md) — lettura del protocollo con commit su più tick.
* **Update**: Aggiornata l'[API da esporre](/decisions/api-surface.md) — tabella delle operazioni di base.
* **Update**: Aggiornato l'[Approccio di sviluppo](/process/development-approach.md) — uno o più client di test, non necessariamente un gioco.
* **Edit**: Uniformato il termine "software utilizzatore" in "software" in tutti i concetti.
* Fonte: `okf-sources/BADSPACE — Panoramica del progetto.md`.

## 2026-09-27
* **Creation**: Aggiunto [Partizionamento del DB](/decisions/partitioning.md) — partizioni indipendenti, single-writer e con indice proprio; strategia scelta dal software utilizzatore.
* **Creation**: Aggiunto [ID delle entità](/decisions/entity-ids.md) — ID Snowflake a 64 bit, univoci su tutte le partizioni.
* **Creation**: Aggiunto [Architettura a livelli](/architecture/layers.md) — software utilizzatore, API comune, layer delle partizioni.
* **Creation**: Aggiunto [Architettura minima del layer delle partizioni](/architecture/minimal-core.md) — cinque primitive minime e meccanismi costruiti sopra.
* **Creation**: Aggiunto [Proprietà della partizione](/architecture/partition-ownership.md) — single-writer con fencing token.
* **Creation**: Aggiunto [Commit della partizione](/architecture/partition-commit.md) — commit atomici con contatore monotono; commit distinto dal tick.
* **Creation**: Aggiunto [Metadati delle entità](/architecture/entity-metadata.md) — metadati di sistema e applicativi, scritture condizionate, API dei metadati.
* **Creation**: Aggiunto [Tombstone](/architecture/tombstones.md) — tombstone con epoca e loro eliminazione.
* **Creation**: Aggiunto [Durabilità osservabile](/architecture/observable-durability.md) — ultimo commit persistito.
* **Creation**: Aggiunto [Conservazione delle versioni](/architecture/version-retention.md) — ultimi k commit, copy-on-write.
* **Creation**: Aggiunto [Migrazione di entità tra partizioni](/mechanisms/entity-migration.md) — handoff asincrono di proprietà.
* **Creation**: Aggiunto [Letture coerenti su più partizioni](/mechanisms/consistent-reads.md) — letture al tick N e rimozione differita.
* **Creation**: Aggiunto [Aggregazione delle query su più partizioni](/mechanisms/query-aggregation.md) — aggregazione per tipo di query e deduplica.
* **Creation**: Aggiunto [Ribilanciamento e split](/mechanisms/rebalancing-and-split.md) — trasferimento dell'esclusività.
* **Update**: Decisa la [Concorrenza](/decisions/concurrency.md) — un solo writer per partizione (status: stable).
* **Update**: Aggiornata l'[Indicizzazione spaziale](/decisions/spatial-indexing.md) — indice scelto per partizione.
* **Update**: Aggiornate le [Entità statiche vs dinamiche](/decisions/static-vs-dynamic-entities.md) — caso particolare di partizionamento.
* **Update**: Aggiornata l'[API da esporre](/decisions/api-surface.md) — vincoli fissati dal partizionamento.
* **Update**: Aggiornata la [Persistenza](/decisions/persistence.md) — snapshot per partizione e durabilità osservabile.
* **Update**: Aggiornata la [Panoramica di BADSPACE](/overview.md) — nuove decisioni e sezione "Architettura".
* **Edit**: Rimossi `sources` e note a piè di pagina da tutti i concetti: la fonte badspace.md non è più nel repository e il bundle è la fonte di riferimento.

## 2026-09-25
* **Edit**: Aggiornato `last_modified` della fonte badspace.md in tutti i concetti, dopo la correzione della fonte.
* **Edit**: Resa indipendente dal linguaggio di implementazione la decisione [Supporto a 2D e 3D](/decisions/2d-3d-support.md): "trait comune" → "interfaccia comune", rimosso il tag `rust`, aggiunta in [Tipo di strumento](/decisions/tool-type.md) la sezione "Terminologia", con la definizione di *interfaccia* e la sua realizzazione per linguaggio (Java, C, C++, Rust); allineati indice, panoramica e fonte badspace.md.
* **Update**: Rimosso Scala dai linguaggi proposti per i prototipi in [Tipo di strumento](/decisions/tool-type.md).
* **Update**: Separato in [Tipo di strumento](/decisions/tool-type.md) il linguaggio dei prototipi (Java o Scala) da quello dell'implementazione finale.
* **Update**: Riaperta in [Tipo di strumento](/decisions/tool-type.md) la scelta del linguaggio di implementazione, con C, C++ e Rust come alternative (status: draft).
* **Creation**: Inizializzato il bundle OKF a partire da 1 documento sorgente (badspace.md).
* **Creation**: Aggiunto [Panoramica di BADSPACE](/overview.md) — panoramica di progettazione di BADSPACE e delle sue decisioni architetturali principali.
* **Creation**: Aggiunto [Tipo di strumento](/decisions/tool-type.md) — libreria embedded in Rust in prima battuta, pensata per evolvere verso un servizio gRPC.
* **Creation**: Aggiunto [Indicizzazione spaziale](/decisions/spatial-indexing.md) — trade-off aperto tra grid, quadtree/octree e R-tree.
* **Creation**: Aggiunto [Supporto a 2D e 3D](/decisions/2d-3d-support.md) — implementazioni parallele dietro un trait comune.
* **Creation**: Aggiunto [Entità statiche vs dinamiche](/decisions/static-vs-dynamic-entities.md) — strutture dati separate, query di prossimità unificate.
* **Creation**: Aggiunto [Concorrenza](/decisions/concurrency.md) — trade-off aperto tra single-writer e multi-writer.
* **Creation**: Aggiunto [API da esporre](/decisions/api-surface.md) — rimandata a una fase successiva.
* **Creation**: Aggiunto [Persistenza](/decisions/persistence.md) — rimandata a una fase successiva.
* **Creation**: Aggiunto [Approccio di sviluppo](/process/development-approach.md) — prototipi minimali accompagnati da un gioco di test per validare le decisioni.
