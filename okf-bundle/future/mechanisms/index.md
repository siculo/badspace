# Percorso di lettura

* [Estensioni dell’aggregazione delle query](./query-aggregation.md) - Aggregazione futura delle query su più partizioni, oltre a range e k-nearest già disponibili - raycast al primo hit e a tutti gli hit, deduplica per ID ed epoca durante split e migrazione.
* [Letture coerenti su più partizioni](./consistent-reads.md) - Letture su più partizioni riferite allo stesso tick N tramite gTick e snapshot di ogni tick, acquisito esattamente in ogni partizione; la rimozione differita è un'alternativa più economica che garantisce solo che ogni entità si veda almeno una volta.
* [Migrazione di entità tra partizioni](./entity-migration.md) - Handoff asincrono di proprietà tra partizioni con writer diversi — entità congelata, batch per tick, ACK dopo persistenza, rimozione condizionata — senza transazioni né lock.
* [Ribilanciamento e split](./rebalancing-and-split.md) - Operazioni in blocco sulle partizioni realizzate trasferendo l'esclusività di scrittura invece di migrare le entità una per una.
