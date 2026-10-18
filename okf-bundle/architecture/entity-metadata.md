---
type: Architecture Primitive
title: Metadati delle entità
description: L'API distingue metadati di sistema (epoca, stato, destinazione) in sola lettura e metadati applicativi liberi; il nodo offre un supporto comune, con condizioni e scritture applicate atomicamente nella partizione; le scritture condizionate sostituiscono le transazioni tra partizioni.
tags: [badspace, architecture, partitioning, metadata, api]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-18T09:29:21Z }
---

# Primitiva

Ogni entità ha, accanto ai dati, dei metadati che l'API distingue in
due spazi.

**Metadati di sistema**, in sola lettura per il software:

- **epoca**: contatore che cresce a ogni migrazione e viaggia con
  l'entità;
- **stato**: viva, in uscita verso X, rimossa;
- **destinazione** (facoltativa): la partizione verso cui è migrata.

Cambiano solo attraverso le operazioni dei protocolli (per esempio la
[migrazione](/mechanisms/entity-migration.md)), perché da loro
dipendono le garanzie: se il software potesse scrivere
l'epoca, potrebbe rompere il protocollo senza accorgersene.

**Metadati applicativi**: slot liberi per il software,
leggibili e scrivibili.

## Responsabilità dei livelli

Il punto di accesso degli sviluppatori è l'[API
comune](/architecture/layers.md), che distingue i due spazi: espone la
lettura di entrambi e la scrittura diretta dei soli metadati
applicativi. I metadati di sistema cambiano attraverso le operazioni
dei protocolli implementati sopra le primitive della partizione.

Il **nodo offre un supporto comune** per memorizzare e aggiornare i
metadati, includerli negli snapshot e applicare scritture condizionate.
Non duplica le restrizioni dell'API per proteggere da chiamate dirette
alle interfacce sottostanti, che sono interne al sistema. Questo non
impone una sola mappa generica o una particolare rappresentazione
fisica dei due spazi.

Le regole dei protocolli, come il congelamento delle entità in uscita,
si applicano nell'API che esegue le transizioni. Nella partizione resta
ciò che richiede **accesso atomico allo stato**: per esempio confrontare
l'epoca e applicare l'inserimento in un'unica operazione, oppure
rimuovere solo se stato, epoca e destinazione sono ancora quelli
attesi. Una verifica nell'API seguita da una scrittura incondizionata
non sostituisce una scrittura condizionata nella partizione.

La collocazione dei controlli segue la [fiducia negli
sviluppatori](/process/development-approach.md#fiducia-negli-sviluppatori):
ogni controllo risponde a un errore concreto da prevenire o a una
garanzia dell'operazione.

## Proprietà comuni

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
