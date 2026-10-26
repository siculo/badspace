---
type: Architecture Primitive
title: "Metadati di sistema ed estensioni"
description: "Epoca, stato, destinazione, tombstone e condizioni richieste dai protocolli futuri; estensioni ancora aperte dei metadati."
tags: [badspace, documentation]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Stato

I metadati di sistema e le tombstone non sono implementati. Il prototipo offre i [metadati applicativi](/future/architecture/entity-metadata.md).

# Primitiva

Ogni entità ha, accanto ai dati, dei metadati che l'API distingue in
due spazi.

**Metadati di sistema**, in sola lettura per il software:

- **epoca**: contatore che cresce a ogni migrazione e viaggia con
  l'entità;
- **stato**: viva, in uscita verso X, rimossa;
- **destinazione** (facoltativa): la partizione verso cui è migrata,
  indicata con il suo [nome](/current/decisions/entity-ids.md#nome-della-partizione),
  perché resta valida nei salvataggi e dopo un recupero.

Cambiano solo attraverso le operazioni dei protocolli (per esempio la
[migrazione](/future/mechanisms/entity-migration.md)), perché da loro
dipendono le garanzie: se il software potesse scrivere
l'epoca, potrebbe rompere il protocollo senza accorgersene.

**Metadati applicativi**: slot liberi per il software,
leggibili e scrivibili.

## Responsabilità dei livelli

Il punto di accesso degli sviluppatori è l'[API
comune](/current/architecture/layers.md), che distingue i due spazi: espone la
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

# API dei metadati

L’API futura estende quella dei
[metadati applicativi correnti](/current/architecture/entity-metadata.md)
con queste operazioni:

- lettura dei metadati di sistema, per ID;
- scritture condizionate di dati e metadati insieme, con condizione sui
  metadati di sistema;
- lettura delle tombstone, inclusa la destinazione;
- eliminazione delle tombstone, usata dai meccanismi superiori.

## Estensioni possibili

- **Filtri nelle query**, per esempio escludere le copie in uscita o
  selezionare per tag. Richiedono indici sui metadati: fuori dal livello
  base, e rimandati insieme a quegli indici; eventualmente si
  realizzano dopo.

# Punti aperti

- Nomi e firme delle chiamate dell'API dei metadati che ancora non ci
  sono (metadati di sistema, condizioni sui metadati di sistema,
  tombstone): si definiscono con lo sviluppo del prototipo. Lettura,
  scrittura e scritture condizionate dei metadati applicativi sono [nel
  prototipo](/current/architecture/entity-metadata.md#nel-prototipo).

# Condizioni sui dati di sistema

I protocolli richiedono anche operazioni come "inserisci se l’ID è
assente o l’epoca in arrivo è maggiore" e "rimuovi se l’entità è in
uscita con epoca e". Queste condizioni non fanno parte del batch
applicativo corrente. Vanno introdotte insieme ai metadati di sistema.

Quando un’entità viene rimossa, il protocollo ne conserva i metadati di
sistema nella tombstone; i metadati applicativi vi entrano solo se
richiesto esplicitamente. Anche questa scelta appartiene al protocollo futuro.
