# Decisioni di progetto

* [Tipo di strumento](./tool-type.md) - libreria embedded in prima battuta, pensata per evolvere verso nodi di partizioni esposti via gRPC; l'API resta una libreria.
* [Linguaggio](./language.md) - prototipi in Java, linguaggio finale aperto tra C11, C++ e Rust.
* [Confronto tra C e Rust](./c-vs-rust.md) - overflow degli interi, coordinate generiche, astrazione su 2D/3D e complessità delle strutture degli indici (arena con indici e borrow checker).
* [Partizionamento del DB](./partitioning.md) - partizioni indipendenti, single-writer e con indice proprio, create e rimosse dinamicamente; strategia scelta dal software.
* [ID delle entità](./entity-ids.md) - ID Snowflake a 64 bit, univoci su tutte le partizioni, mai riusati, senza indicazione della partizione; un generatore per processo API con tempo logico, generatorId assegnato a ogni avvio.
* [Indicizzazione spaziale](./spatial-indexing.md) - indice scelto per partizione, che fissa i limiti delle coordinate delle entità; scansione lineare, grid uniforme e griglia di quadtree implementate, confronto tra le strutture aperto.
* [Supporto a 2D e 3D](./2d-3d-support.md) - implementazioni parallele (punti, partizioni, nodi, indici) dietro un'interfaccia comune.
* [Tipo delle coordinate](./coordinate-type.md) - `double` nei prototipi; scelta finale rimandata tra double, interi a 64 bit o tipo generico.
* [Entità statiche vs dinamiche](./static-vs-dynamic-entities.md) - strutture dati separate, query di prossimità unificate; caso particolare di partizionamento.
* [Concorrenza](./concurrency.md) - un solo writer per partizione, più reader.
* [Isolamento delle letture](./read-isolation.md) - snapshot pubblicati a ogni commit: slot copiati per intero, indici persistenti copy-on-write; i reader non si bloccano e non bloccano il writer; ogni dato delle entità entra nello snapshot.
* [API da esporre](./api-surface.md) - operazioni di base individuate; il prototipo definisce la forma delle operazioni sulle entità (record per le coordinate, batch, tutto-o-niente) della creazione e rimozione delle partizioni (politica di rimozione) e del commit (`commit(n)` con numero scelto dal software); il partizionamento ne fissa già alcuni elementi.
* [Persistenza](./persistence.md) - snapshot per partizione e ultimo commit persistito esposto; nel prototipo il meccanismo si implementa dopo i metadati e si estende man mano, la strategia di dettaglio è aperta.
