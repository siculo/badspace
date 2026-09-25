---
type: Design Decision
title: Tipo di strumento
description: Decisione di costruire BADSPACE come libreria embedded, pensata per evolvere in seguito verso un servizio gRPC; prototipi proposti in Java, linguaggio dell'implementazione finale (C, C++ o Rust) ancora da decidere.
tags: [badspace, design, architecture, language, prototyping]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-25T12:00:00Z }
sources:
  - id: badspace-notes
    resource: ../../badspace.md
    title: "BADSPACE: Database spaziale real-time per gioco — note di progettazione"
    author: human:someone
    last_modified: 2026-09-22T00:00:00Z
---

# Opzioni considerate

Sono stati considerati tre approcci:[^badspace-notes]

- **Libreria embedded** — integrata nel processo del server di gioco,
  massima velocità.
- **Servizio standalone** con protocollo di rete proprio — riusabile da
  altri linguaggi, ma con latenza di rete.
- **Ibrido** dei due approcci.

# Decisione

Si parte dalla libreria embedded, pensata per poter evolvere in seguito
verso un servizio esposto via gRPC.[^badspace-notes]

# Linguaggio

Si distinguono due scelte: il linguaggio dei prototipi e quello
dell'implementazione finale.

## Prototipi

Proposta: **Java**, linguaggio familiare, per validare in fretta le
decisioni di progetto. Il codice dei prototipi è da buttare. Pro:
iterazioni rapide, buon supporto a concorrenza e rete. Contro:
prestazioni non rappresentative (riferimenti, GC), da mitigare con uno
stile "vicino al C" (array di primitivi, indici) e confrontando nei
benchmark solo le differenze relative.

## Implementazione finale

Ancora da decidere, anche alla luce dei prototipi, tra:

- **C (C11)** — pro: linguaggio familiare, semplice, con interfaccia C
  nativa usabile da qualunque linguaggio. Contro: servono utility interne
  (array dinamico, hash map, allocatori); sicurezza della memoria e dei
  thread a carico di chi sviluppa; gRPC scomodo.
- **C++** — pro: la libreria standard copre le utility senza dipendenze
  esterne; i template esprimono bene il 2D/3D; gRPC ufficiale. Contro:
  linguaggio molto più complesso del C.
- **Rust** — pro: libreria standard ricca, poche dipendenze; il
  compilatore impedisce i data race; i trait si adattano al 2D/3D. Contro:
  poco conosciuto da chi sviluppa; le strutture ad albero si scontrano con
  il borrow checker.

# Correlati

- La [concorrenza](/decisions/concurrency.md) è condizionata dal restare
  in-process per ora, e influenza la scelta del linguaggio.
- L'[approccio di sviluppo](/process/development-approach.md) a
  prototipi minimali motiva l'uso di un linguaggio familiare per i
  prototipi.
- Il [supporto a 2D e 3D](/decisions/2d-3d-support.md) si esprime in modo
  diverso a seconda del linguaggio (trait, template o funzioni separate).

[^badspace-notes]: Note di progettazione di BADSPACE
