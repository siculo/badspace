# Esempi

*[English](README.md)*

Piccoli client che mostrano come usare BADSPACE come libreria embedded, con
nodi locali. Ogni esempio è un modulo con una classe `main`. I primi mostrano
ognuno una parte dell'API; gli ultimi sono piccoli programmi per un caso
d'uso, con più thread.

Vanno letti in questo ordine: ogni esempio usa solo quello che spiegano gli
esempi prima.

## Eseguire un esempio

Da `prototype`:

```
mvn install -DskipTests
mvn -pl samples/<nome> exec:java
```

In IntelliJ IDEA ogni esempio ha una run configuration con il nome della sua
classe.

## Parti dell'API

1. [`spaces-and-partitions`](spaces-and-partitions) (`SpacesAndPartitions`):
   i generatori degli ID, condivisi da tutti gli spazi del processo; spazi 2D
   e 3D; partizioni su nodi locali, con il nome che il software dà loro e la
   loro configurazione (indice, politica di rimozione, tick conservati); i limiti delle coordinate; le operazioni
   comuni a 2D e 3D.
2. [`writes-and-commits`](writes-and-commits) (`WritesAndCommits`): il writer
   prende la proprietà di una partizione e scrive con l'handle del suo token;
   scritture singole e batch; un batch che fallisce non cambia niente; il
   commit conferma le scritture nel tick corrente e l'avanzamento del tick le
   pubblica.
3. [`snapshot-reads`](snapshot-reads) (`SnapshotReads`): letture per ID e
   query (box, cerchio, entità più vicine) su uno snapshot acquisito, che non
   vede i commit successivi.
4. [`ticks-and-retention`](ticks-and-retention) (`TicksAndRetention`): la
   finestra di conservazione, gli snapshot dei tick passati, i tick saltati e
   i motivi per cui uno snapshot non è disponibile.
5. [`acquisition-timeouts`](acquisition-timeouts) (`AcquisitionTimeouts`): il
   rilascio di un'acquisizione e la sua scadenza.
6. [`ownership-handoff`](ownership-handoff) (`OwnershipHandoff`): le
   generazioni della proprietà, il passaggio a un altro writer, la revoca da
   parte di un coordinatore e il rilascio che scarta le scritture non
   confermate.
7. [`partition-removal`](partition-removal) (`PartitionRemoval`): le due
   politiche di rimozione, i reader dopo la rimozione e la distruzione della
   partizione quando non resta nessuno snapshot.
8. [`entity-metadata`](entity-metadata) (`EntityMetadata`): i metadati
   applicativi delle entità, scritti con l'handle del writer nello stesso
   commit delle posizioni e letti per ID su uno snapshot acquisito; un batch
   che fallisce non cambia niente; le scritture condizionate, come un
   compare-and-set o la prenotazione di due entità, che con una condizione
   falsa non cambiano niente e restituiscono le condizioni false; la
   rimozione di un'entità ne cancella i metadati.
9. [`partition-saves`](partition-saves) (`PartitionSaves`): i salvataggi di
   tre partizioni di un nodo a due tick, con le chiamate sincrone
   `saveLatest()` e `save(tick)` e il riferimento che restituiscono, con il
   nome della partizione e il tick; i file nella directory dei salvataggi del
   nodo, uno per nome della partizione e tick; cosa
   trova un nuovo nodo sulla stessa directory dopo un riavvio. Con una
   directory come argomento i salvataggi restano lì.

## Casi d'uso

10. [`random-updater`](random-updater) (`RandomUpdater`): un writer e un reader
    su due thread; il writer muove le entità a caso a ogni tick, il reader
    mostra l'ultimo snapshot.
11. [`multi-writer`](multi-writer) (`MultiWriter`): più partizioni su un nodo,
    ognuna con il writer sul suo thread, e un tick comune a tutte, come il
    ciclo di un server di gioco.
12. [`lagged-reader`](lagged-reader) (`LaggedReader`): un reader che disegna
    più frame dei tick, un po' nel passato, tra gli snapshot di due tick
    conservati, come un client di gioco.
13. [`writer-failover`](writer-failover) (`WriterFailover`): un coordinatore
    toglie la proprietà a un writer bloccato e la dà a uno nuovo, che riparte
    dall'ultimo snapshot; il fencing token rifiuta il vecchio writer quando si
    sveglia.
14. [`periodic-saves`](periodic-saves) (`PeriodicSaves`): una simulazione che
    salva il suo mondo mentre va avanti; un thread salva ogni pochi tick
    mentre il writer continua, e conserva solo gli ultimi salvataggi; dopo un
    riavvio il software carica l'ultimo salvataggio in una nuova partizione,
    con un altro indice, e un writer la prende e continua. Con una directory
    come argomento i salvataggi restano lì.
