---
type: Design Decision
title: Linguaggio
description: Prototipi in Java, con codice da buttare, per iterare in fretta; il linguaggio dell'implementazione finale (C11, C++ o Rust) si sceglierà alla luce dei prototipi.
tags: [badspace, design, language, prototyping]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-29T07:36:20Z }
---

# Decisione

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

# Terminologia

## Interfaccia

Per "interfaccia" qui si intende un contratto astratto: un insieme
di operazioni con la stessa semantica. Non si intende un
costrutto di un linguaggio specifico. La forma concreta di questo
contratto dipende dal linguaggio di implementazione.

Come si realizza, nei diversi linguaggi, una interfaccia:

- **Java** (prototipi): `interface` implementate dalle classi concrete.
- **C**: funzioni separate per ciascuna implementazione, con firme
  parallele, oppure
  una struct di puntatori a funzione.
- **C++**: template o classi base astratte.
- **Rust**: trait.

# Correlati

- Il [tipo di strumento](/decisions/tool-type.md) — libreria embedded
  evolvibile in servizio gRPC: il supporto a gRPC pesa nella scelta.
- La [concorrenza](/decisions/concurrency.md) è condizionata dal restare
  in-process per ora, e influenza la scelta del linguaggio.
- L'[approccio di sviluppo](/process/development-approach.md) a
  prototipi minimali motiva l'uso di un linguaggio familiare per i
  prototipi.
- Il [supporto a 2D e 3D](/decisions/2d-3d-support.md) si esprime in modo
  diverso a seconda del linguaggio (vedi
  [Interfaccia](#interfaccia)).
- Il [tipo delle coordinate](/decisions/coordinate-type.md) — nei
  prototipi `double`; il modo di renderlo generico dipende dal
  linguaggio (generici in Rust e C++, `typedef` in C).
