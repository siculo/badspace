---
type: Architecture Primitive
title: "Scritture accumulate nel writer"
description: "Parti ancora da fare del refactoring delle scritture accumulate: fallimenti sul batch, stato del buffer, misure ed esiti remoti."
tags: [badspace, architecture, transactions, api]
status: draft
timestamp: 2026-11-05T13:45:51Z
---

# Stato e obiettivo

Direzione concordata, implementata in gran parte. Sono disponibili `set`
con inserimento se assente, la rimozione idempotente, la rimozione delle
conditional writes, il vincolo sulla
[provenienza degli ID](/current/decisions/entity-ids.md#provenienza-degli-id-delle-entità)
e le [scritture accumulate nel writer](/current/architecture/partition-commit.md#scritture-accumulate-nel-writer):
l'handle compone le scritture per entità e solo `commit()` le manda al
nodo, in un batch applicato all'ultimo commit, con fallimenti e
recupero verificati sul batch (vedi
[fallimenti della partizione](/current/architecture/partition-failures.md#nel-prototipo)).
Sono definiti anche lo stato del buffer dopo successo, rifiuto e
fallimento, uguale a quello delle scritture inviate una alla volta, e il
rapporto con il recupero sul nodo (vedi
[stato del buffer](/current/architecture/partition-commit.md#scritture-accumulate-nel-writer)).
Le prime [misure](/current/architecture/partition-commit.md#costi-del-buffer-e-del-commit)
di memoria del buffer e di durata del commit sono fatte con il profilo
rapido. Restano da fare il profilo completo, le decisioni su limiti e
ottimizzazioni che ne seguono, e il protocollo remoto degli esiti.

Il software legge uno snapshot, esegue la propria logica e costruisce le
scritture, come descritto nelle
[decisioni su uno snapshot](/current/architecture/entity-metadata.md#decisioni-su-uno-snapshot):
non legge lo stato mutabile attraverso l'esito di una scrittura.
Lo snapshot letto può non comprendere commit ancora pending, anche di un
precedente proprietario; il refactoring non introduce letture del pending.

# Costi da valutare

Le misure con il profilo rapido mostrano tre costi da valutare:

- **Memoria del buffer**, circa 230 byte per entità con le sole `set`:
  ogni modifica ha un oggetto e due mappe dei metadati create subito, e
  la mappa per ID usa chiavi `Long`. Mappe create alla prima scrittura
  dei metadati, o strutture di primitivi, la ridurrebbero.
- **Tempo del buffer**, circa 90–300 ns per entità scritta, che con
  batch grandi porta il tick dal writer a 1,2–2 volte il tick con le
  scritture date direttamente allo storage.
- **Durata del lock**, uguale a tutto il commit del batch: un
  avanzamento del tick aspetta l'intero batch. Un limite alla dimensione
  dei batch, o un'applicazione del batch fuori dal lock seguita da uno
  scambio sotto il lock, la ridurrebbero; la prima scelta toglie
  l'atomicità dei batch divisi, la seconda richiede di progettare la
  preparazione su una copia dello stato.

Un primo lancio del piano si è fermato per un crash della JVM: un
`SIGSEGV` nel codice compilato da C2 (OSR) di `WriteBuffer2.changes()`,
con OpenJDK 25.0.2, con 1 000 000 di entità tutte in movimento nella
grid uniforme. Il prototipo non usa codice nativo né `Unsafe`, e il
secondo lancio, con lo stesso codice, è arrivato in fondo: va verificato
se si ripete, per esempio con un'altra versione del JDK.

# Rifiuti ed esiti

Rifiuti e fallimenti certi del nodo locale, con lo stato del buffer
dopo ciascuno, sono descritti nelle
[scritture accumulate](/current/architecture/partition-commit.md#scritture-accumulate-nel-writer).
Per il remoto serve distinguere rifiuto, fallimento certo ed esito
incerto. Un batch a esito incerto resta congelato, senza commit successivi
che possano superarlo, finché si recupera l'esito. Servono riconoscimento
dei tentativi ripetuti e un esito stabile: il tick non identifica da solo
un commit, perché ne sono ammessi più di uno nello stesso tick.
Il protocollo e la conservazione degli esiti si progettano con il
[nodo gRPC](/future/analyses/grpc-node.md), senza anticiparli nel refactoring locale.
La ripetizione di `set` non rende automaticamente sicura la ripetizione
dell'intero commit, che potrebbe altrimenti ricadere in un tick diverso.

# Attuazione e verifiche

Il gruppo di attività è nel [TODO del prototipo](../../../TODO.md#fare-scritture).
Ogni passo comprende 2D e 3D e aggiorna i chiamanti, i test e la relativa
documentazione. La rappresentazione concreta del batch, i limiti di memoria
e dimensione, e la durata del lock durante commit grandi vanno valutati
senza introdurre ora il trasporto remoto o nuove attività sugli indici.

La composizione è verificata contro l'esecuzione sequenziale; i test
dell'API verificano zero chiamate al nodo durante l'accumulazione e un
solo invio al commit e lo stato del buffer dopo rifiuti e fallimenti;
i test del nodo verificano rifiuti, fallimenti, recupero, token superati
e concorrenza con tick e cambio di proprietà sul batch.

La rimozione delle conditional writes richiede di riesaminare, quando
si arriverà alla migrazione, i controlli su epoca, stato e destinazione
previsti dai [metadati di sistema](/future/architecture/entity-metadata.md).
Leggere uno snapshot non garantisce da solo le precedenti precondizioni
sullo stato più recente: il protocollo futuro va riallineato, senza
reintrodurre implicitamente le primitive eliminate nell'API applicativa.
