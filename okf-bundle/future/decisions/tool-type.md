---
type: Design Decision
title: "Nodi remoti e distribuzione"
description: "Direzione scelta per i nodi gRPC, alternative considerate e questioni aperte del futuro confine di rete."
tags: [badspace, documentation]
status: draft
timestamp: 2026-11-08T12:44:42Z
generated: { by: codex/gpt-6, at: 2026-10-30T15:48:41Z }
---

# Stato

I nodi remoti non sono implementati. La direzione scelta mantiene l’API nel processo del software e colloca gRPC sotto di essa.

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
dei [nodi](/current/architecture/layers.md#nodi)** che ospitano le partizioni.
L'API resta una libreria nel processo del software e parla con i nodi
attraverso quel contratto: oggi con un'implementazione nello stesso
processo, domani con una remota via gRPC. In pratica si realizza
l'opzione ibrida: libreria sopra, servizio sotto.

# Decisioni da prendere

Il [refactoring delle scritture](/current/architecture/partition-commit.md#scritture-accumulate-nel-writer),
previsto prima del trasporto remoto, è concluso e ha già cambiato il
contratto dei nodi. Nella [modalità con buffer](/current/architecture/partition-commit.md#modalità-di-scrittura),
pensata per il nodo remoto, il writer accumula le modifiche finali per ID
e le trasferisce al commit o con il flush, senza una chiamata remota per
ogni operazione; il nodo locale resta nella modalità diretta. Restano
aperti i [costi del buffer e gli esiti remoti](/future/architecture/buffer-costs-and-remote-outcomes.md).

- Errori remoti, timeout, dimensione dei batch, riconoscimento dei commit ripetuti e recupero degli esiti incerti nel contratto dei nodi.
- Eventuale hosting di più nodi, ciascuno riservato al proprio Space, nello stesso servizio remoto: identità, instradamento e ciclo di vita. Vedi [evoluzioni di nodi e Space](/future/architecture/nodes-and-spaces.md).
- Eventuale servizio per assegnare generatorId, gTick e barriere.
- Eventuale centralizzazione della destinazione dei salvataggi.

# Accesso

Il contratto dei nodi resta interno al sistema anche quando sarà remoto.
Il software filtra gli accessi dei client esterni; fencing e controlli
di dominio proteggono da errori e ritardi dei componenti affidabili.

# Analisi di riferimento

L'[analisi del nodo gRPC](/future/analyses/grpc-node.md) approfondisce
esiti incerti, ripetizione e ordine delle richieste, deadline, propagazione
del tick e risorse del collegamento. Conserva alternative da valutare,
senza fissare già il protocollo o la politica dei tentativi.
