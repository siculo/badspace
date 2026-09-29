# Decisioni di progetto

* [Tipo di strumento](./tool-type.md) - libreria embedded in prima battuta, pensata per evolvere verso nodi di partizioni esposti via gRPC; l'API resta una libreria.
* [Linguaggio](./language.md) - prototipi in Java, linguaggio finale aperto tra C11, C++ e Rust.
* [Partizionamento del DB](./partitioning.md) - partizioni indipendenti, single-writer e con indice proprio, create e rimosse dinamicamente; strategia scelta dal software.
* [ID delle entità](./entity-ids.md) - ID Snowflake a 64 bit, univoci su tutte le partizioni, mai riusati, senza indicazione della partizione.
* [Indicizzazione spaziale](./spatial-indexing.md) - indice scelto per partizione; trade-off aperto tra grid, quadtree/octree e R-tree.
* [Supporto a 2D e 3D](./2d-3d-support.md) - implementazioni parallele dietro un'interfaccia comune.
* [Tipo delle coordinate](./coordinate-type.md) - `double` nei prototipi; scelta finale rimandata tra double, interi a 64 bit o tipo generico.
* [Entità statiche vs dinamiche](./static-vs-dynamic-entities.md) - strutture dati separate, query di prossimità unificate; caso particolare di partizionamento.
* [Concorrenza](./concurrency.md) - un solo writer per partizione, più reader.
* [API da esporre](./api-surface.md) - operazioni di base individuate; il prototipo definisce la forma delle operazioni sulle entità (record per le coordinate, batch, tutto-o-niente); il partizionamento ne fissa già alcuni elementi.
* [Persistenza](./persistence.md) - rimandata; snapshot per partizione e ultimo commit persistito esposto.
