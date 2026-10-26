---
type: Reference
title: "Componenti dell’API"
description: "Spazio, partizione, writer, snapshot acquisito e insieme di snapshot: ruolo, problemi risolti e sequenza d’uso nell’API corrente."
tags: [badspace, documentation]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-25T17:19:04Z }
---

# Prima di iniziare

L’[architettura](/current/architecture/layers.md) distingue il software,
l’API e i nodi locali. Nell’API ci sono cinque oggetti principali:
spazio, partizione, handle del writer, snapshot acquisito e insieme di
snapshot. Le varianti
`2` e `3` lavorano rispettivamente in due e tre dimensioni.

# Spazio: creare e coordinare le partizioni

`Space2` e `Space3` raccolgono le partizioni e propagano il tempo logico
ai nodi. `SpaceConfig` riceve i generatori degli ID di entità e partizioni;
ogni generatore va condiviso tra gli spazi del processo.

Il software crea una partizione indicando nodo, nome e `PartitionConfig`.
La configurazione fissa indice, politica di rimozione e numero di tick
conservati. Il default usa scansione lineare, `REQUIRE_EMPTY` e un tick.
Lo spazio offre anche caricamento, elenco ed eliminazione dei salvataggi.

La scelta esplicita delle partizioni lascia al software la strategia
di suddivisione: per zone, tipi di entità o carico. Lo spazio non sceglie
automaticamente la destinazione di un’entità.

# Partizione: un insieme di dati e le sue garanzie

`Partition2` e `Partition3` sono proxy di una partizione ospitata da un
nodo. Espongono nome, configurazione, limiti delle coordinate, tick e
stato di proprietà. Da qui si acquisiscono snapshot, si ottiene l’handle
del writer e si comandano i salvataggi.

Ogni partizione ha un indice proprio. Gli indici danno gli stessi
risultati, con costi diversi; la scelta si fa alla creazione. Una query
non deve conoscere la struttura dell’indice.

# Writer: scrivere senza conflitti tra proprietari

Una partizione nuova non ha proprietario. `ownership()` legge generazione
e stato; `claim(expectedGeneration)` prende la proprietà solo se la
generazione è ancora quella attesa e restituisce il token nuovo.
`writer(token)` costruisce l’handle `PartitionWriter2` o `PartitionWriter3`.

L’handle offre inserimenti, aggiornamenti, rimozioni, metadati, scritture
condizionate, commit e rimozione della partizione. Ogni scrittura batch
è tutto-o-niente. Il nodo rifiuta il token di un proprietario precedente,
così un writer rallentato non può modificare dati dopo un passaggio di
proprietà. La [proprietà](/current/architecture/partition-ownership.md)
spiega il meccanismo e gli schemi di coordinamento.

L’handle è `AutoCloseable`: `close()` rilascia la proprietà e scarta le
scritture non confermate. I commit precedenti restano confermati.

# Snapshot acquisito: leggere uno stato stabile

`commit()` conferma lo stato del writer nel tick corrente. Lo snapshot
diventa acquisibile quando `Space.setTick(...)` fa avanzare il tick
della partizione. Al tick T, `acquireLatest()` legge lo stato di T − 1;
`acquire(n)` sceglie un tick nella finestra conservata.

`AcquiredSnapshot2` e `AcquiredSnapshot3` offrono letture per ID, numero
di entità, metadati e query range e k-nearest. Sono `AutoCloseable` e si
usano con `try`-with-resources. Anche il writer legge attraverso uno
snapshot: nessuno legge lo stato non confermato o il commit del tick
ancora aperto.

L’acquisizione mantiene stabile il contenuto mentre il writer continua.
Ha una scadenza obbligatoria per evitare che un lettore abbandonato
trattenga memoria per sempre. Dettagli e formule sono nella
[conservazione degli snapshot](/current/architecture/snapshot-retention.md).

# Insieme di snapshot: interrogare più partizioni

Il software sceglie quali partizioni interrogare; l’API aggrega i
risultati. `SnapshotSet2.acquire(partitions, n)` (e `SnapshotSet3`)
acquisisce lo snapshot del tick n su ogni partizione della lista e offre
`findInRegion` e `findNearest` su tutti gli snapshot acquisiti, anche
con indici diversi. Come la view, è `AutoCloseable` e rilascia tutte le
acquisizioni con `close()`.

Una partizione senza lo snapshot di n non ferma le altre: l’insieme la
restituisce tra i fallimenti, con il motivo, e il chiamante decide se il
risultato parziale basta. Le [query su più
partizioni](/current/architecture/multi-partition-queries.md) spiegano
fallimenti, scelta del tick e aggregazione.

# Dati e metadati

`Point2`/`Point3` rappresentano la posizione; `Entity2`/`Entity3` associano
ID e posizione. Le regioni disponibili sono box e cerchio in 2D, box e
sfera in 3D. Coordinate e raggi devono essere finiti.

I metadati applicativi sono coppie chiave stringa → valore `long`, lette
separatamente dalle posizioni. Le condizioni permettono di applicare
insieme modifiche di posizioni e metadati solo se tutti i valori attesi
corrispondono. Una condizione falsa è un risultato, non un’eccezione.

# Salvataggio e caricamento

`partition.save(n)` e `saveLatest()` salvano il contenuto di uno snapshot.
Il nodo ne fa una copia autonoma e la scrive nella directory configurata;
il writer continua a lavorare. La chiamata ritorna a salvataggio concluso.

`space.loadPartition(node, config, saved)` crea una partizione con gli
stessi dati, il nome salvato, un ID di runtime nuovo e nessun proprietario.
Il software sceglie indice e finestra di conservazione. I dati caricati
diventano leggibili al successivo avanzamento del tick.

# Sequenza d’uso

Il diagramma riassume come si ottengono spazio, partizione, handle del
writer e snapshot acquisito; l’insieme di snapshot raccoglie gli snapshot
acquisiti di più partizioni dello stesso tick.
Il ramo del writer presuppone che `claim` riesca; il ramo di lettura
richiede uno snapshot già pubblicato e ancora acquisibile. Il proxy
della partizione permette entrambi gli usi, che hanno handle distinti.

```text
                         +----------------+
                         | Spazio         |
                         +----------------+
                                 |
                         createPartition
                                 v
                         +----------------+
                         | Partizione     |
                         +----------------+
                                 |
              +------------------+-----------------------+
              |                                          |
 "claim, poi writer(token)"                "acquire / acquireLatest"
              v                                          v
 +--------------------------+               +--------------------------+
 | Handle del writer        |               | Snapshot acquisito       |
 | Scritture e commit       |               | Letture e query          |
 +--------------------------+               +--------------------------+
```

1. Creare spazio e nodo locale, con i generatori di ID condivisi.
2. Creare la partizione con nome e configurazione.
3. Prendere la proprietà e costruire l’handle del writer.
4. Scrivere e chiamare `commit()`.
5. Far avanzare il tick dello spazio.
6. Acquisire lo snapshot, interrogarlo e chiuderlo; per più partizioni,
   acquisire un insieme di snapshot dello stesso tick.

Per firme, risultati ed errori si consulta il [contratto dell’API](/current/api/operations.md).
Per esempi eseguibili si parte dai [casi d’uso](/current/use-cases.md).
