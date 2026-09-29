---
type: Design Decision
title: Tipo di strumento
description: Decisione di costruire BADSPACE come libreria embedded, per la massima velocità in-process, pensata per evolvere in seguito verso nodi di partizioni esposti via gRPC, mentre l'API resta una libreria nel processo del software.
tags: [badspace, design, architecture]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-29T10:47:38Z }
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
in-process.

La futura superficie di rete **non è l'API comune** ma il **contratto
dei [nodi](/architecture/layers.md#nodi)** che ospitano le partizioni.
L'API resta una libreria nel processo del software e parla con i nodi
attraverso quel contratto: oggi con un'implementazione nello stesso
processo, domani con una remota via gRPC. In pratica si realizza
l'opzione ibrida: libreria sopra, servizio sotto.

# Correlati

- Il [linguaggio](/decisions/language.md) dei prototipi e
  dell'implementazione finale è una decisione separata, ancora aperta.
- La [concorrenza](/decisions/concurrency.md) è condizionata dal restare
  in-process per ora.
- L'[architettura a livelli](/architecture/layers.md) — l'API comune è
  la superficie pubblica della libreria; il contratto dei nodi è il
  futuro confine di rete.
