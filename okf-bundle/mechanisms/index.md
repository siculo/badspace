# Meccanismi

* [Migrazione di entità tra partizioni](./entity-migration.md) - handoff asincrono di proprietà, senza transazioni né lock.
* [Letture coerenti su più partizioni](./consistent-reads.md) - letture allo stesso tick N, con lo snapshot esatto del tick in ogni partizione; rimozione differita delle entità come alternativa.
* [Aggregazione delle query su più partizioni](./query-aggregation.md) - aggregazione per tipo di query e deduplica per ID ed epoca.
* [Ribilanciamento e split](./rebalancing-and-split.md) - operazioni in blocco tramite trasferimento dell'esclusività.
