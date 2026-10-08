---
type: Architecture Primitive
title: Metadati delle entità
description: Ogni entità ha metadati di sistema (epoca, stato, destinazione) in sola lettura e metadati applicativi liberi; le scritture condizionate sui metadati sostituiscono le transazioni tra partizioni.
tags: [badspace, architecture, partitioning, metadata, api]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-08T07:45:42Z }
---

# Primitiva

Ogni entità ha, accanto ai dati, dei metadati divisi in due spazi.

**Metadati di sistema**, in sola lettura per il software:

- **epoca**: contatore che cresce a ogni migrazione e viaggia con
  l'entità;
- **stato**: viva, in uscita verso X, rimossa;
- **destinazione** (facoltativa): la partizione verso cui è migrata.

Cambiano solo attraverso le operazioni dei protocolli (per esempio la
[migrazione](/mechanisms/entity-migration.md)), perché da loro
dipendono le garanzie: se il software potesse scrivere
l'epoca, potrebbe rompere il protocollo senza accorgersene.

I metadati di sistema li gestisce il **layer delle partizioni** (nel
prototipo il modulo service), non l'[API comune](/architecture/layers.md):
le regole su cui si basano le garanzie (sola lettura per il software,
entità congelate durante la migrazione, inserimento con confronto
dell'epoca) devono valere per ogni scrittura che arriva alla
partizione.

**Metadati applicativi**: slot liberi per il software,
leggibili e scrivibili.

Proprietà comuni:

- dati e metadati cambiano nello stesso
  [commit](/architecture/partition-commit.md), in modo atomico;
- i metadati sono **separati dai dati spaziali**: modificarli non tocca
  l'indice;
- i metadati fanno parte dello snapshot pubblicato a ogni commit, come
  ogni dato delle entità (vedi [isolamento delle
  letture](/decisions/read-isolation.md#tutti-i-dati-delle-entità)).

# Scritture condizionate

Le scritture possono essere **condizionate** sui metadati, di sistema o
applicativi, per esempio:

- "inserisci se l'ID è assente o se l'epoca in arrivo è maggiore";
- "rimuovi se l'entità è in uscita con epoca e";
- "applica se la versione applicativa è ancora v".

Le scritture condizionate sostituiscono le transazioni tra partizioni.

Quando un'entità viene rimossa, i suoi metadati di sistema
sopravvivono come [tombstone](/architecture/tombstones.md). I metadati
applicativi non entrano nella tombstone, a meno che non venga
richiesto esplicitamente al momento della rimozione.

# API dei metadati

I metadati aprono a estensioni sia dell'API comune sia di meccaniche
implementate dal software, quindi hanno un'API propria:

- lettura dei metadati di sistema e applicativi, per ID;
- scrittura dei metadati applicativi, semplice o condizionata;
- scritture condizionate di dati e metadati insieme, con condizione sui
  metadati (nel caso minimo un compare-and-set su un valore);
- lettura delle tombstone, inclusa la destinazione;
- eliminazione delle tombstone, usata dai meccanismi superiori.

## Estensioni possibili

- **Concorrenza ottimistica.** Una versione applicativa per entità
  permette a un processo che non è il writer di proporre modifiche del
  tipo "applica se la versione è ancora v". Il writer le applica o le
  rifiuta senza lock.
- **Riserve.** Un processo prenota un'entità per un'operazione, per
  esempio un'interazione tra due giocatori.
- **Tag di responsabilità.** Il processo che gestisce l'entità e decide
  se spostarla, distinto dal writer della partizione.
- **Scadenze.** Un'entità scade dopo un certo tick, come un proiettile.
- **Filtri nelle query**, per esempio escludere le copie in uscita o
  selezionare per tag. Richiedono indici sui metadati: da tenere fuori
  dal livello base, almeno all'inizio.

# Punti aperti

- Struttura dei metadati applicativi: slot a schema libero o tipizzati,
  e limiti di dimensione.

# Correlati

- [Architettura minima](/architecture/minimal-core.md) — primitiva 3.
- [API da esporre](/decisions/api-surface.md).
