# Esempi

*[English](README.md)*

Piccoli client che mostrano come usare BADSPACE come libreria embedded, con
nodi locali. Ogni esempio è un modulo con un solo programma, una classe con
`main`, che risponde a una domanda sull'API. Il codice è lineare e tiene in
vista le chiamate all'API: ogni esempio ripete il suo breve setup e usa poche
entità con coordinate esplicite.

Ogni esempio si compila e si esegue da solo. L'ordine qui sotto è un ordine
di lettura suggerito: i prerequisiti di un esempio sono i concetti che usa,
non gli altri esempi.

## Eseguire un esempio

Da `prototype`:

```
mvn install -DskipTests
mvn -pl samples/<nome> exec:java
```

Gli esempi di persistenza accettano una directory dei salvataggi facoltativa;
senza, usano una nuova directory temporanea, e stampano la directory che
usano:

```
mvn -pl samples/save-and-load exec:java -Dexec.args=<directory>
```

In IntelliJ IDEA ogni esempio ha una run configuration con il nome della sua
classe.

## Primi passi

| Esempio | Domanda | Prerequisiti |
|---|---|---|
| [`getting-started`](getting-started) (`GettingStarted`) | Qual è il percorso completo più breve: spazio, partizione, claim e writer, inserimento, commit, avanzamento del tick, lettura e rilascio? | Nessuno |
| [`partition-configuration`](partition-configuration) (`PartitionConfiguration`) | Come si scelgono l'indice, la politica di rimozione e i tick conservati di una partizione, e che limiti hanno le coordinate? | Spazi e partizioni |
| [`three-dimensional-space`](three-dimensional-space) (`ThreeDimensionalSpace`) | Che cosa cambia in uno spazio 3D? | Il percorso completo |

## Scritture e tick

| Esempio | Domanda | Prerequisiti |
|---|---|---|
| [`batch-writes`](batch-writes) (`BatchWrites`) | Come si inseriscono, aggiornano e rimuovono molte entità in una chiamata, e che cosa succede quando un batch è rifiutato? | Writer e commit |
| [`commits-and-ticks`](commits-and-ticks) (`CommitsAndTicks`) | Quando i reader vedono un commit? Che cosa succede con più commit in un tick, e con le scritture dopo l'ultimo commit? | Writer e commit |

## Snapshot

| Esempio | Domanda | Prerequisiti |
|---|---|---|
| [`snapshot-queries`](snapshot-queries) (`SnapshotQueries`) | Come si legge per ID e si cerca per box, cerchio ed entità più vicine? | Snapshot acquisito |
| [`snapshot-isolation`](snapshot-isolation) (`SnapshotIsolation`) | Uno snapshot cambia mentre il writer pubblica nuove posizioni? | Commit e tick |
| [`snapshot-retention`](snapshot-retention) (`SnapshotRetention`) | Come si leggono i tick passati, e che cosa succede quando escono dalla finestra di retention? | Commit e tick |
| [`acquisition-timeouts`](acquisition-timeouts) (`AcquisitionTimeouts`) | Come finisce un'acquisizione, con il rilascio o con la scadenza? | Snapshot acquisito |

## Ciclo di vita della partizione e metadati

| Esempio | Domanda | Prerequisiti |
|---|---|---|
| [`ownership-handoff`](ownership-handoff) (`OwnershipHandoff`) | Come passa un writer la proprietà a un altro writer? | Claim e writer |
| [`partition-removal`](partition-removal) (`PartitionRemoval`) | Come si rimuove una partizione con `REQUIRE_EMPTY` o `DISCARD_ENTITIES`, e quando la rimozione diventa effettiva? | Commit e tick, finestra di retention |
| [`entity-metadata`](entity-metadata) (`EntityMetadata`) | Come si scrivono, leggono e cancellano i metadati delle entità? | Writer e commit |
| [`conditional-writes`](conditional-writes) (`ConditionalWrites`) | Come si scrive solo se sono vere condizioni sui metadati? | Metadati delle entità |

## Persistenza

| Esempio | Domanda | Prerequisiti |
|---|---|---|
| [`save-and-load`](save-and-load) (`SaveAndLoad`) | Come si salva una partizione e la si carica in un'esecuzione successiva? | Commit e tick, metadati delle entità |
| [`save-management`](save-management) (`SaveManagement`) | Come si salva un tick passato, si elencano i salvataggi e si cancellano i più vecchi? | Finestra di retention |

## Concorrenza

| Esempio | Domanda | Prerequisiti |
|---|---|---|
| [`concurrent-reader-writer`](concurrent-reader-writer) (`ConcurrentReaderWriter`) | Come lavorano un writer e un reader su due thread? | Commit e tick |
| [`multi-writer`](multi-writer) (`MultiWriter`) | Come condividono il tick dello spazio i writer di partizioni diverse? | Commit e tick |
| [`lagged-reader`](lagged-reader) (`LaggedReader`) | Come disegna un reader più frame dei tick, fra due snapshot passati? | Finestra di retention |
| [`writer-failover`](writer-failover) (`WriterFailover`) | Come sostituisce un coordinatore un writer bloccato? | Passaggio della proprietà |
| [`periodic-saves`](periodic-saves) (`PeriodicSaves`) | Come si salva su un secondo thread mentre il writer continua? | Gestione dei salvataggi |
