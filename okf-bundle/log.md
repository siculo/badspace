# Bundle Update Log

## 2026-09-25
* **Edit**: Aggiornato `last_modified` della fonte badspace.md in tutti i concetti, dopo la correzione della fonte.
* **Edit**: Resa indipendente dal linguaggio di implementazione la decisione [Supporto a 2D e 3D](/decisions/2d-3d-support.md): "trait comune" → "interfaccia comune", rimosso il tag `rust`, aggiunta in [Tipo di strumento](/decisions/tool-type.md) la sezione "Terminologia", con la definizione di *interfaccia* e la sua realizzazione per linguaggio (Java, C, C++, Rust); allineati indice, panoramica e fonte badspace.md.
* **Update**: Rimosso Scala dai linguaggi proposti per i prototipi in [Tipo di strumento](/decisions/tool-type.md).
* **Update**: Separato in [Tipo di strumento](/decisions/tool-type.md) il linguaggio dei prototipi (Java o Scala) da quello dell'implementazione finale.
* **Update**: Riaperta in [Tipo di strumento](/decisions/tool-type.md) la scelta del linguaggio di implementazione, con C, C++ e Rust come alternative (status: draft).
* **Creation**: Inizializzato il bundle OKF a partire da 1 documento sorgente (badspace.md).
* **Creation**: Aggiunto [Panoramica di BADSPACE](/overview.md) — panoramica di progettazione di BADSPACE e delle sue decisioni architetturali principali.
* **Creation**: Aggiunto [Tipo di strumento](/decisions/tool-type.md) — libreria embedded in Rust in prima battuta, pensata per evolvere verso un servizio gRPC.
* **Creation**: Aggiunto [Indicizzazione spaziale](/decisions/spatial-indexing.md) — trade-off aperto tra grid, quadtree/octree e R-tree.
* **Creation**: Aggiunto [Supporto a 2D e 3D](/decisions/2d-3d-support.md) — implementazioni parallele dietro un trait comune.
* **Creation**: Aggiunto [Entità statiche vs dinamiche](/decisions/static-vs-dynamic-entities.md) — strutture dati separate, query di prossimità unificate.
* **Creation**: Aggiunto [Concorrenza](/decisions/concurrency.md) — trade-off aperto tra single-writer e multi-writer.
* **Creation**: Aggiunto [API da esporre](/decisions/api-surface.md) — rimandata a una fase successiva.
* **Creation**: Aggiunto [Persistenza](/decisions/persistence.md) — rimandata a una fase successiva.
* **Creation**: Aggiunto [Approccio di sviluppo](/process/development-approach.md) — prototipi minimali accompagnati da un gioco di test per validare le decisioni.
