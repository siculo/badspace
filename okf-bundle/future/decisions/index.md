# Percorso di lettura

* [Nodi remoti e distribuzione](./tool-type.md) - Direzione scelta per i nodi gRPC, alternative considerate e questioni aperte del futuro confine di rete.
* [Estensioni dell’API](./api-surface.md) - Operazioni future su più partizioni, gruppi, raycast e questioni aperte del contratto dei nodi.
* [Evoluzioni del partizionamento](./partitioning.md) - Problemi di confine, query e migrazione tra partizioni, ribilanciamento e supporto a più indici ancora da valutare.
* [Entità statiche vs dinamiche](./static-vs-dynamic-entities.md) - BADSPACE separa le entità statiche (build-once) da quelle dinamiche (aggiornate a ogni tick) a livello di struttura dati, come caso particolare di partizionamento, unificando però le query di prossimità.
* [Recupero e sviluppi della persistenza](./persistence.md) - Recupero ricostruttivo, tagli coerenti, durabilità osservabile, salvataggio asincrono e misure future.
* [Generazione degli ID nel cluster](./entity-ids.md) - Assegnazione dei generatorId tra processi e riavvii, lease e possibili modifiche della divisione dei bit Snowflake.
* [Evoluzioni dello storage degli snapshot](./read-isolation.md) - Possibili ottimizzazioni delle pagine e della mappa ID verso slot; gestione della memoria senza GC nella implementazione finale.
* [Evoluzioni dell’indicizzazione spaziale](./spatial-indexing.md) - Indici non implementati, limiti espliciti delle partizioni e confronti ancora aperti; lavoro in pausa.
* [Linguaggio dell’implementazione finale](./language.md) - Prototipi in Java, con codice da buttare, per iterare in fretta; il linguaggio dell'implementazione finale (C11, C++ o Rust) si sceglierà alla luce dei prototipi.
* [Coordinate nell’implementazione finale](./coordinate-type.md) - Nei prototipi le coordinate sono double; la scelta per l'implementazione finale (double, interi a 64 bit o tipo generico) è rimandata, con i limiti di precisione, rappresentazione e determinismo di ciascuna opzione.
* [Confronto tra C e Rust](./c-vs-rust.md) - Confronto tra C e Rust per l'implementazione finale su quattro temi — overflow degli interi, coordinate di tipo diverso, astrazione su 2D/3D e complessità del codice delle strutture degli indici (borrow checker).
