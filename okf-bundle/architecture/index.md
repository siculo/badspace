# Architettura

* [Architettura a livelli](./layers.md) - i tre livelli di BADSPACE partizionato e chi decide cosa.
* [Architettura minima del layer delle partizioni](./minimal-core.md) - le cinque primitive minime, i principi che le reggono e i meccanismi costruiti sopra di esse.

# Primitive del livello base

* [Proprietà della partizione](./partition-ownership.md) - un solo writer per partizione, esclusività trasferibile con fencing token.
* [Commit della partizione](./partition-commit.md) - commit atomici con contatore monotono, unica transazione del sistema.
* [Metadati delle entità](./entity-metadata.md) - metadati di sistema e applicativi, scritture condizionate, API dei metadati.
* [Tombstone](./tombstones.md) - metadati che sopravvivono alla rimozione e impediscono le resurrezioni.
* [Durabilità osservabile](./observable-durability.md) - ultimo commit persistito esposto dalla partizione.
* [Conservazione delle versioni](./version-retention.md) - stato degli ultimi k commit, base delle letture coerenti.
