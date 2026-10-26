---
type: Design Decision
title: Partizionamento del DB
description: Il DB di BADSPACE è diviso in partizioni indipendenti, single-writer e con indice proprio, create e rimosse dinamicamente per scalare; la strategia di partizionamento la sceglie il software.
tags: [badspace, design, partitioning, scalability]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
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
  (vedi [proprietà della partizione](/current/architecture/partition-ownership.md)).
- Ogni partizione ha la propria **strategia di indicizzazione** (vedi
  [indicizzazione spaziale](/current/decisions/spatial-indexing.md)).
- La **strategia di partizionamento** è scelta dal software, che
  decide su quali partizioni scrivere e da quali leggere.

Le partizioni sono ospitate dai nodi locali nello stesso processo.
La struttura è descritta nell’[architettura a livelli](/current/architecture/layers.md).

# Partizioni dinamiche e scalabilità

- Il DB **non prevede partizioni predefinite**: il software crea tutte
  quelle che gli servono e configura ciascuna in modo diverso (tipo di
  indice, numero k di [snapshot passati conservati](/current/architecture/snapshot-retention.md)).
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
  lock granulari (vedi [concorrenza](/current/decisions/concurrency.md)).
- **Indicizzazione per partizione.** Partizioni con profili omogenei
  usano l'indice più adatto.
- **Nodi espliciti.** Il software sceglie su quale nodo locale creare
  o caricare ogni partizione.
- **Persistenza.** Snapshot indipendenti per ogni partizione (vedi
  [persistenza](/current/decisions/persistence.md)).

# Correlati

- [Architettura minima](/current/architecture/minimal-core.md) — garanzie del livello base.
- [ID delle entità](/current/decisions/entity-ids.md) — univoci su tutte le
  partizioni.

# Limiti tra partizioni

Il software tiene la mappa ID → partizione. Scritture e commit non sono
atomici tra partizioni; nemmeno la propagazione del tick lo è. L’API
interroga una partizione per chiamata e non unisce i risultati.
Questi vincoli vanno considerati nella strategia di suddivisione dei dati.
