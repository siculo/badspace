---
type: Design Decision
title: Partizionamento del DB
description: Il DB di BADSPACE è diviso in partizioni indipendenti, single-writer e con indice proprio, create e rimosse dinamicamente per scalare; la strategia di partizionamento la sceglie il software.
tags: [badspace, design, partitioning, scalability]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-29T10:47:38Z }
---

# Decisione

Il DB memorizza entità collocate nello spazio, ma non relazioni tra
esse. Questo permette di partizionarlo facilmente, tenendo la logica di
partizionamento fuori dal livello più basso di gestione dei dati.

- Il contenuto dello spazio è la somma del contenuto delle partizioni.
- Un'entità contenuta nello spazio è contenuta in una sola partizione.
- Le coordinate di un'entità possono assumere qualsiasi valore valido
  nello spazio: in teoria ogni partizione **copre tutto lo spazio**.
- Ogni partizione ha **un solo writer** e supporta **diversi reader**
  (vedi [proprietà della partizione](/architecture/partition-ownership.md)).
- Ogni partizione ha la propria **strategia di indicizzazione** (vedi
  [indicizzazione spaziale](/decisions/spatial-indexing.md)).
- La **strategia di partizionamento** è scelta dal software, che
  decide su quali partizioni scrivere e da quali leggere.

Le partizioni possono essere gestite da processi diversi, e anche il
software che usa il DB tramite le API può essere distribuito su più
processi. La struttura a livelli è descritta in
[architettura a livelli](/architecture/layers.md).

# Partizioni dinamiche e scalabilità

- Il DB **non prevede partizioni predefinite**: il software crea tutte
  quelle che gli servono e configura ciascuna in modo diverso (tipo di
  indice, numero k di [versioni conservate](/architecture/version-retention.md)).
- Le partizioni si **creano e si rimuovono dinamicamente**, mentre il
  sistema è in funzione.
- È questo che rende il DB **scalabile**: il carico si ridistribuisce
  aggiungendo partizioni dove serve e togliendole quando non servono
  più.

# Vantaggi

- **Strategie libere.** Il software adotta la strategia
  migliore caso per caso: per tipologia di entità, per suddivisione
  dello spazio o miste (per esempio la geometria statica divisa per
  zone e i proiettili in un'unica partizione globale).
- **Concorrenza.** Ogni partizione resta single-writer, semplice e
  deterministica, ma il sistema nel suo insieme ha più writer senza
  lock granulari (vedi [concorrenza](/decisions/concurrency.md)).
- **Indicizzazione per partizione.** Partizioni con profili omogenei
  usano l'indice più adatto.
- **Generalizza statici/dinamici.** La separazione tra
  [entità statiche e dinamiche](/decisions/static-vs-dynamic-entities.md)
  diventa un caso particolare di partizionamento.
- **Clustering.** Ogni partizione è ospitata da un
  [nodo](/architecture/layers.md#nodi), che può essere un processo o una
  macchina separata; il software sceglie il nodo di ogni partizione.
- **Persistenza.** Snapshot indipendenti per ogni partizione (vedi
  [persistenza](/decisions/persistence.md)).

# Problemi spostati fuori dal livello base

- **Strategie con zone disgiunte.** Se il software
  partiziona per zone, tornano i problemi di confine: entità a cavallo
  di due zone, query da allargare, migrazioni. Sono conseguenza della
  strategia scelta, non del livello base. Strategie pronte potranno
  essere offerte come componenti opzionali.
- **Migrazione non atomica.** Tra partizioni con writer diversi,
  rimozione e inserimento non sono atomici: risolto dal protocollo di
  [migrazione](/mechanisms/entity-migration.md).
- **Consistenza tra partizioni.** Letture su partizioni diverse possono
  riferirsi a istanti diversi: vedi
  [letture coerenti](/mechanisms/consistent-reads.md).
- **Sbilanciamento del carico.** Una partizione "calda" resta un
  problema della strategia scelta; lo strumento è il
  [ribilanciamento](/mechanisms/rebalancing-and-split.md).

# Punti aperti

- Se una partizione può avere più di un indice.
- Forma delle chiamate con partizioni esplicite (scrittura, query,
  migrazione).

# Verifica proposta

Un prototipo mirato, secondo l'[approccio di
sviluppo](/process/development-approach.md): più partizioni con indici
diversi, query range, k-nearest e raycast su più partizioni con
aggregazione, migrazione di entità tra partizioni con writer diversi.

# Correlati

- [Architettura minima](/architecture/minimal-core.md) — le primitive
  del layer delle partizioni.
- [ID delle entità](/decisions/entity-ids.md) — univoci su tutte le
  partizioni.
