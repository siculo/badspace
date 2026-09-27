# Decisioni di progetto

* [Tipo di strumento](./tool-type.md) - libreria embedded in prima battuta, pensata per evolvere verso un servizio gRPC; prototipi in Java, linguaggio finale aperto tra C, C++ e Rust.
* [Partizionamento del DB](./partitioning.md) - partizioni indipendenti, single-writer e con indice proprio; strategia scelta dal software utilizzatore.
* [ID delle entità](./entity-ids.md) - ID Snowflake a 64 bit, univoci su tutte le partizioni, mai riusati, senza indicazione della partizione.
* [Indicizzazione spaziale](./spatial-indexing.md) - indice scelto per partizione; trade-off aperto tra grid, quadtree/octree e R-tree.
* [Supporto a 2D e 3D](./2d-3d-support.md) - implementazioni parallele dietro un'interfaccia comune.
* [Entità statiche vs dinamiche](./static-vs-dynamic-entities.md) - strutture dati separate, query di prossimità unificate; caso particolare di partizionamento.
* [Concorrenza](./concurrency.md) - un solo writer per partizione, più reader.
* [API da esporre](./api-surface.md) - da definire nel dettaglio; il partizionamento ne fissa già alcuni elementi.
* [Persistenza](./persistence.md) - rimandata; snapshot per partizione e ultimo commit persistito esposto.
