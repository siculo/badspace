# Architettura

* [Architettura a livelli](./layers.md) - i tre livelli di BADSPACE partizionato e chi decide cosa.
* [Architettura minima del layer delle partizioni](./minimal-core.md) - le sei primitive minime, i principi che le reggono e i meccanismi costruiti sopra di esse.

# Primitive del livello base

* [Proprietà della partizione](./partition-ownership.md) - un solo writer per partizione, esclusività trasferibile con fencing token.
* [Commit della partizione](./partition-commit.md) - commit atomici nel tick corrente, senza numero visibile, unica transazione del sistema; lo snapshot del tick diventa visibile all'avanzamento del tick.
* [Metadati delle entità](./entity-metadata.md) - metadati di sistema e applicativi, scritture condizionate, API dei metadati.
* [Tombstone](./tombstones.md) - metadati che sopravvivono alla rimozione e impediscono le resurrezioni.
* [Durabilità osservabile](./observable-durability.md) - ultimo tick persistito esposto dalla partizione.
* [Conservazione degli snapshot](./snapshot-retention.md) - uno snapshot per ogni tick, acquisibili quelli degli ultimi k tick (k ≥ 1), acquisizione al tick e rilascio con scadenza, base delle letture coerenti.
* [Rimozione della partizione](./partition-removal.md) - stati attiva, in rimozione, ritirata e distrutta; rimozione effettiva al primo avanzamento del tick, snapshot dei tick precedenti ancora acquisibili nella finestra.
* [Tick logico](./logical-time.md) - tick dello spazio, del nodo e della partizione, fatto avanzare dal software con setTick; propagazione non atomica tra partizioni.
