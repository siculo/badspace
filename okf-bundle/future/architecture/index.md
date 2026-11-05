# Percorso di lettura

* [Scritture accumulate nel writer](./buffered-writes.md) - Parti ancora da fare del refactoring delle scritture accumulate: fallimenti sul batch, stato del buffer, misure ed esiti remoti.

* [Evoluzioni di nodi, servizi e Space](./nodes-and-spaces.md) - Hosting remoto di più nodi, recupero dei nodi vuoti e possibili operazioni tra Space con tempi indipendenti.

* [Strutture transazionali, rollback e recupero](./transactional-components.md) - Attesa dell'inizio del tick, letture del pending, ritorno a snapshot più vecchi, e possibili evoluzioni del coordinamento.

* [Primitive richieste dai meccanismi futuri](./minimal-core.md) - Dipendenze tra il nucleo corrente e i protocolli futuri: metadati di sistema, tombstone, durabilità e coordinamento.
* [Metadati di sistema ed estensioni](./entity-metadata.md) - Epoca, stato, destinazione, tombstone e condizioni richieste dai protocolli futuri; estensioni ancora aperte dei metadati.
* [Tombstone](./tombstones.md) - Alla rimozione di un'entità ne restano i metadati di sistema con l'epoca, che impediscono a messaggi vecchi di farla rinascere; vengono eliminate quando il predecessore conferma di non avere più copie in uscita.
* [Durabilità osservabile](./observable-durability.md) - Ogni partizione espone l'ultimo tick persistito, così le conferme verso altre partizioni si danno solo per scritture durevoli.
* [Evoluzioni della conservazione degli snapshot](./snapshot-retention.md) - Proposte su copia pubblica degli snapshot, scadenze configurabili, diagnostica, pulizia periodica e acquisizione senza eccezioni.
* [Verifiche storiche delle partizioni](./partition-removal.md) - Verifiche di esistenza e acquisibilità a un tick da valutare con le letture coerenti su più partizioni.
* [Proprietà e recupero dei nodi](./partition-ownership.md) - Assegnazione dei writer e validità dei token durante il recupero dei nodi; alternative sulla persistenza della generazione.
