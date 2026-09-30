# Bundle Update Log

## 2026-09-30
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
