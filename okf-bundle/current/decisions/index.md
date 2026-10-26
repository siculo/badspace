# Percorso di lettura

* [Framework e libreria embedded](./tool-type.md) - Ruolo del framework, distribuzione locale del prototipo e responsabilità del software sugli accessi.
* [Partizionamento del DB](./partitioning.md) - Il DB di BADSPACE è diviso in partizioni indipendenti, single-writer e con indice proprio, create e rimosse dinamicamente per scalare; la strategia di partizionamento la sceglie il software.
* [Concorrenza](./concurrency.md) - Concorrenza di BADSPACE risolta con un solo writer per partizione e più reader; il sistema nel suo insieme ha più writer senza lock granulari.
* [Supporto a 2D e 3D](./2d-3d-support.md) - BADSPACE supporta 2D e 3D tramite implementazioni parallele (per esempio Point2/Point3, Partition2/Partition3, GridQuadtreeIndex2/GridOctreeIndex3) che espongono un'interfaccia comune, indipendente dal linguaggio di implementazione.
* [ID delle entità](./entity-ids.md) - Contratto dei generatori, schema Snowflake e configurazione corrente; ID di runtime delle partizioni distinti dai nomi persistenti.
* [Coordinate double e limiti](./coordinate-type.md) - Coordinate double del prototipo Java: rappresentazione, precisione e limiti delle entità rispetto a quelli delle query.
* [Indicizzazione spaziale](./spatial-indexing.md) - Scelta di un indice per partizione, limiti delle coordinate e risultati misurati dei tre indici implementati in 2D e 3D.
* [Isolamento delle letture](./read-isolation.md) - I reader di una partizione vedono solo commit completi grazie a snapshot preparati a ogni commit, pubblicati con una scrittura atomica all'avanzamento del tick e letti attraverso un'acquisizione controllata; gli slot usano buffer riusati e copia delle pagine cambiate, gli indici sono strutture persistenti copy-on-write.
* [Salvataggio e caricamento delle partizioni](./persistence.md) - Persistenza implementata nel nodo locale: copia autonoma dello snapshot, file binario, elenco ed eliminazione, caricamento e misure dei costi.
* [Java nel prototipo](./language.md) - Perché il prototipo usa Java, come si interpretano le misure e che cosa significa interfaccia nella documentazione.
