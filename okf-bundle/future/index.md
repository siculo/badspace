# Perimetro e obiettivi

* [Casi d'uso](./use-cases.md) - I casi d'uso che BADSPACE vuole supportare, ciascuno con la sua configurazione delle partizioni e i soli meccanismi che gli servono.
* [Glossario degli sviluppi futuri](./glossary.md) - Termini dei protocolli ancora da implementare, da leggere dopo il glossario corrente.

# Architettura ed estensioni dell’API

* [Primitive richieste dai meccanismi futuri](./architecture/minimal-core.md) - Dipendenze tra il nucleo corrente e i protocolli futuri: metadati di sistema, tombstone, durabilità e coordinamento.
* [Nodi remoti e distribuzione](./decisions/tool-type.md) - Direzione scelta per i nodi gRPC, alternative considerate e questioni aperte del futuro confine di rete.
* [Estensioni dell’API](./decisions/api-surface.md) - Operazioni future su più partizioni, gruppi, raycast e questioni aperte del contratto dei nodi.

# Problemi, scelte e meccanismi

* [Decisioni future](./decisions/index.md) - Recupero, linguaggio finale, coordinate, partizionamento e altri temi aperti.
* [Meccanismi su più partizioni](./mechanisms/index.md) - Aggregazione, letture coerenti, migrazione e ribilanciamento.
* [Estensioni delle primitive](./architecture/index.md) - Metadati di sistema, tombstone, durabilità e verifiche storiche.
* [Proposte sugli indici](./indices/index.md) - Alternative e ottimizzazioni; lavoro in pausa.

# Come interpretare questa parte

Le pagine distinguono decisioni già prese sul comportamento desiderato,
proposte e punti aperti. Anche una decisione indicata come `stable`
descrive qui un progetto futuro: non ne attesta l’implementazione.
I collegamenti a `current/` indicano prerequisiti e primitive disponibili.
La sequenza delle attività resta nel TODO del prototipo; il lavoro sugli
indici resta in pausa.
