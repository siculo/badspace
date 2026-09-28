---
type: Design Decision
title: Tipo di strumento
description: Decisione di costruire BADSPACE come libreria embedded, per la massima velocità in-process, pensata per evolvere in seguito verso un servizio gRPC.
tags: [badspace, design, architecture]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-28T17:14:44Z }
---

# Opzioni considerate

Sono stati considerati tre approcci:

- **Libreria embedded** — integrata nel processo del software,
  massima velocità.
- **Servizio standalone** con protocollo di rete proprio — riusabile da
  altri linguaggi, ma con latenza di rete.
- **Ibrido** dei due approcci.

# Decisione

Si parte dalla libreria embedded, pensata per poter evolvere in seguito
verso un servizio esposto via gRPC. Oggi dà la massima velocità
in-process; l'API della libreria è già la futura superficie di rete.

# Correlati

- Il [linguaggio](/decisions/language.md) dei prototipi e
  dell'implementazione finale è una decisione separata, ancora aperta.
- La [concorrenza](/decisions/concurrency.md) è condizionata dal restare
  in-process per ora.
- L'[architettura a livelli](/architecture/layers.md) — l'API comune è
  la superficie pubblica della libreria.
