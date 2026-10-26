# Percorso di lettura

* [Architettura a livelli](./layers.md) - Software, API pubblica e nodi locali del prototipo Java: responsabilità, dipendenze e organizzazione dei moduli.
* [Nucleo delle partizioni](./minimal-core.md) - Garanzie e principi del livello base già presenti: proprietà, scritture, commit, tempo logico, snapshot, rimozione e persistenza.
* [Proprietà della partizione](./partition-ownership.md) - Proprietà single-writer con fencing token: passaggio, revoca e rilascio; recupero del writer applicativo e limite di visibilità dei commit nello stesso tick.
* [Commit della partizione](./partition-commit.md) - Commit cumulativi nel tick corrente: conferma dello stato, pubblicazione al tick successivo e sequenza interna per il copy-on-write.
* [Tick logico](./logical-time.md) - Tempo logico di spazio, nodi e partizioni: avanzamento monotono, salti, pubblicazione degli snapshot e propagazione non atomica.
* [Conservazione degli snapshot](./snapshot-retention.md) - Snapshot per tick, finestra di conservazione, acquisizione e rilascio con scadenza, vita degli snapshot e coordinamento interno.
* [Rimozione della partizione](./partition-removal.md) - Richiesta di rimozione, ritiro al primo avanzamento del tick, letture storiche e distruzione dopo la fine degli utilizzi.
* [Metadati applicativi e scritture condizionate](./entity-metadata.md) - Metadati con chiavi stringa e valori long, letti dagli snapshot e scritti dal writer; batch condizionati atomici su posizioni e metadati.
* [Query su più partizioni](./multi-partition-queries.md) - Insieme di snapshot dello stesso tick su partizioni scelte dal software: acquisizione, fallimenti come esito, rilascio e aggregazione di range e k-nearest.
