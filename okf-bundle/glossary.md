---
type: Glossary
title: Glossario
description: Definizioni dei termini ricorrenti di BADSPACE — spazio, partizione, nodo, software, API comune, politica di rimozione, gruppo di partizioni, generatore di ID, generatorId, commit, tick, gTick, snapshot, finestra di conservazione, catalogo degli snapshot, acquisizione, rilascio, pin, snapshot eliminabile, scadenza, slot, copy-on-write e transient, fencing token, epoca, tombstone, scrittura condizionata, k, limiti delle coordinate, cella, foglia, interfaccia.
tags: [badspace, glossary]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-15T10:00:00Z }
---

# Termini

| Termine | Significato |
|---|---|
| Spazio | Insieme di entità in 2D o in 3D, diviso in partizioni; il contenuto dello spazio è la somma del contenuto delle sue partizioni. Dallo spazio si creano e si rimuovono le partizioni, e l'API genera gli ID delle partizioni con il generatore di ID delle partizioni, che lo spazio riceve nella sua configurazione insieme a quello delle entità. Vedi [API da esporre](/decisions/api-surface.md#creazione-e-rimozione-delle-partizioni). |
| Partizione | Istanza indipendente del DB con un solo writer, più reader e indice proprio; in teoria copre tutto lo spazio. Vedi [partizionamento](/decisions/partitioning.md). |
| Nodo | Processo, locale o remoto, che ospita una o più partizioni; per ora serve un solo spazio. Il suo contratto è il futuro servizio gRPC. Vedi [architettura a livelli](/architecture/layers.md#nodi). |
| Software | Il software che usa BADSPACE (simulazione, monitoraggio, gaming e altro) e sceglie la strategia di partizionamento. Vedi [architettura a livelli](/architecture/layers.md). |
| API comune | Livello di supporto sopra le partizioni: ID, aggregazione, configurazione e meccanismi. Vedi [API da esporre](/decisions/api-surface.md). |
| Politica di rimozione | Scelta fatta alla creazione di una partizione su cosa succede alle sue entità quando la si rimuove: si rimuove solo se vuota (`REQUIRE_EMPTY`, il default) o insieme alle entità (`DISCARD_ENTITIES`). Vedi [API da esporre](/decisions/api-surface.md#creazione-e-rimozione-delle-partizioni). |
| Gruppo di partizioni | Insieme di partizioni con una configurazione condivisa (indice, k, politica di rimozione), distinto dall'insieme di partizioni da interrogare; la sua API sta sopra quella delle singole partizioni. Punto aperto: vedi [API da esporre](/decisions/api-surface.md#gruppi-di-partizioni). |
| Generatore di ID | Componente dell'API comune che crea gli ID secondo un contratto proprio: ID `long` univoci su tutte le partizioni e gli spazi, mai riusati, senza garanzie di ordine né di segno; uno per tipo di ID (entità e partizioni), condiviso da tutti gli spazi e passato nella configurazione dello spazio (`SpaceConfig`). Lo schema scelto è Snowflake, sostituibile. Vedi [ID delle entità](/decisions/entity-ids.md#contratto-del-generatore) e [ID delle partizioni](/decisions/entity-ids.md#id-delle-partizioni). |
| generatorId | Identificativo del generatore di ID dentro un ID Snowflake; ogni istanza dell'API ne riceve uno nuovo a ogni avvio. Vedi [ID delle entità](/decisions/entity-ids.md#schema-snowflake). |
| Commit | Gruppo atomico di scritture di una partizione, identificato da un numero scelto dal software, positivo e strettamente crescente per partizione, con salti ammessi; è l'unica transazione del sistema e non coincide necessariamente con un tick. Il commit 0 è lo stato iniziale vuoto. Vedi [commit della partizione](/architecture/partition-commit.md). |
| Tick | Intervallo di tempo entro cui il software esegue le sue operazioni (il frame time di un server di gioco); per partizione al più un commit per tick, tipicamente esattamente uno; locale o globale. |
| gTick | Tick globale del software, esterno a BADSPACE: il software può usarlo come numero dei commit, ma clock, barriera e scadenze del tick non li gestisce BADSPACE. Vedi [letture coerenti](/mechanisms/consistent-reads.md). |
| Snapshot | Stato completo e immutabile di una sola partizione a un commit, con dati delle entità (slot) e indice dello stesso commit, pubblicato a ogni commit; i reader leggono uno snapshot senza lock, attraverso un'acquisizione. Anche le sue parti si chiamano snapshot: snapshot degli slot e snapshot dell'indice. Vedi [isolamento delle letture](/decisions/read-isolation.md). |
| Slot | Posizione di un'entità negli array dello storage di una partizione; gli indici parlano di slot, non di ID. Lo slot di un'entità può cambiare quando si rimuovono altre entità. Vedi [isolamento delle letture](/decisions/read-isolation.md#gli-slot). |
| Copy-on-write | Modo di creare una nuova versione di una struttura copiando solo le parti che cambiano e condividendo le altre con le versioni precedenti; una struttura fatta così si dice *persistente*. Vedi [isolamento delle letture](/decisions/read-isolation.md#gli-indici). |
| Transient | Versione modificabile di una struttura persistente, usata dal writer durante un commit: una parte si copia alla prima modifica del commit, e le modifiche successive vanno sulla copia. Al commit diventa immutabile. Vedi [isolamento delle letture](/decisions/read-isolation.md#il-commit). |
| Fencing token | Numero di generazione che invalida le scritture di un writer non più proprietario. Vedi [proprietà della partizione](/architecture/partition-ownership.md). |
| Epoca | Contatore di sistema che cresce a ogni migrazione e stabilisce quale copia è valida. Vedi [metadati delle entità](/architecture/entity-metadata.md). |
| Tombstone | Metadati di sistema che restano dopo la rimozione e impediscono le resurrezioni. Vedi [tombstone](/architecture/tombstones.md). |
| Scrittura condizionata | Scrittura applicata solo se una condizione sui metadati è vera (compare-and-set). |
| k | Numero di snapshot passati (commit effettivamente pubblicati) che una partizione conserva oltre allo snapshot corrente: gli snapshot conservati sono k + 1, e k = 0 conserva solo lo snapshot corrente. k ≥ 0, default 0. I numeri di commit saltati non contano. Vedi [conservazione degli snapshot](/architecture/snapshot-retention.md). |
| Finestra di conservazione | Gli ultimi k + 1 snapshot pubblicati di una partizione: i soli che ammettono nuove acquisizioni. Vedi [conservazione degli snapshot](/architecture/snapshot-retention.md). |
| Catalogo degli snapshot | Gli snapshot di una partizione tenuti dal nodo: per ora gli snapshot della finestra di conservazione, pubblicati tutti insieme a ogni commit. Vedi [conservazione degli snapshot](/architecture/snapshot-retention.md#nel-prototipo). |
| Acquisizione | Diritto, solo logico e con identità propria, di interrogare uno snapshot; più acquisizioni dello stesso snapshot sono indipendenti. Le tiene il nodo. Vedi [conservazione degli snapshot](/architecture/snapshot-retention.md#acquisizione-e-rilascio). |
| Rilascio | Chiusura di una sola acquisizione: idempotente; dopo il rilascio le nuove letture attraverso quell'acquisizione falliscono, e lo snapshot non viene per forza eliminato. |
| Pin | Stato di uno snapshot uscito dalla finestra di conservazione ma ancora tenuto da acquisizioni valide o da query già ammesse: si legge, ma non si può più acquisire. Vedi [conservazione degli snapshot](/architecture/snapshot-retention.md#vita-di-uno-snapshot). |
| Snapshot eliminabile | Snapshot fuori dalla finestra di conservazione, senza acquisizioni valide né query in corso: nessuno lo usa e nessuno lo può più richiedere, quindi per la partizione non esiste più. Il passaggio è un momento preciso che la partizione rileva (fine dello snapshot); non è la liberazione della memoria. Vedi [conservazione degli snapshot](/architecture/snapshot-retention.md#vita-di-uno-snapshot). |
| Scadenza | Durata di una singola acquisizione, sempre presente (un default se il reader non la indica, al più una scadenza massima), misurata con il clock monotono del nodo; definitiva, non si rinnova, e non è una durata massima dello snapshot. Vedi [conservazione degli snapshot](/architecture/snapshot-retention.md#scadenza). |
| Limiti delle coordinate | Intervallo `[min, max]`, uguale su tutti gli assi, delle coordinate che le entità di una partizione possono avere; lo fissa l'indice, e le scritture fuori dai limiti falliscono. Le query non hanno limiti. Vedi [indicizzazione spaziale](/decisions/spatial-indexing.md#limiti-delle-coordinate). |
| Cella | Quadrato (2D) o cubo (3D) di lato fisso in cui un indice a celle divide lo spazio, come la [grid uniforme](/indices/uniform-grid.md) e la [griglia di quadtree](/indices/grid-quadtree.md). |
| Foglia | Nodo di un quadtree o di un octree senza figli, che contiene le entità della sua area; quando ne ha troppe si divide. Vedi [quadtree e octree](/indices/quadtree.md). |
| Interfaccia | Contratto astratto di operazioni con la stessa semantica, indipendente dal linguaggio. Vedi [linguaggio](/decisions/language.md#interfaccia). |
