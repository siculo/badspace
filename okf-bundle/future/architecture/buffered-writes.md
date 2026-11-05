---
type: Architecture Primitive
title: "Scritture accumulate nel writer"
description: "Parti ancora da fare del refactoring delle scritture accumulate: fallimenti sul batch, stato del buffer, misure ed esiti remoti."
tags: [badspace, architecture, transactions, api]
status: draft
timestamp: 2026-11-05T11:57:00Z
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
Restano da fare lo stato del buffer dopo un fallimento certo e il
rapporto con il recupero sul nodo; le misure di memoria e di durata del
commit; il protocollo remoto degli esiti.

Il software legge uno snapshot, esegue la propria logica e costruisce le
scritture, come descritto nelle
[decisioni su uno snapshot](/current/architecture/entity-metadata.md#decisioni-su-uno-snapshot):
non legge lo stato mutabile attraverso l'esito di una scrittura.
Lo snapshot letto può non comprendere commit ancora pending, anche di un
precedente proprietario; il refactoring non introduce letture del pending.

# Commit e ciclo di vita

Oggi `rollback()` e `close()` scartano il buffer e poi vanno al nodo, e il
cambio di proprietario invalida il token dei vecchi buffer senza
modificare i commit riusciti. Va definito come si documentano e si
verificano rollback, `close`, `claim` e `release` ora che tra un commit e
l'altro il nodo non ha scritture del writer da scartare, mantenendo il
recupero della partizione sul nodo distinto dallo svuotamento locale.

# Rifiuti ed esiti

Restano i rifiuti per token superato, partizione inesistente o rimossa,
recupero necessario e input non valido, comprese coordinate fuori dai
limiti. I controlli locali possibili avvengono alla chiamata, quelli
definitivi sul nodo al commit. Come già oggi, la presenza pregressa di
un ID non è un motivo di rifiuto per `set` o `remove`. Le operazioni
superate nel buffer non vengono applicate al nodo.

Un batch rifiutato non cambia la partizione; un fallimento durante
l'applicazione o la preparazione deve preservare l'ultimo commit e il
pending precedente. Oggi l'handle conserva il buffer dopo ogni commit
non riuscito, per correzione o rollback. Va definito se conservarlo
anche dopo un fallimento certo con ripristino riuscito o recupero
necessario.

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
solo invio al commit; i test del nodo verificano rifiuti, fallimenti,
recupero e concorrenza con tick e cambio di proprietà sul batch. Restano
da verificare rollback locale e token superati con il buffer.

La rimozione delle conditional writes richiede di riesaminare, quando
si arriverà alla migrazione, i controlli su epoca, stato e destinazione
previsti dai [metadati di sistema](/future/architecture/entity-metadata.md).
Leggere uno snapshot non garantisce da solo le precedenti precondizioni
sullo stato più recente: il protocollo futuro va riallineato, senza
reintrodurre implicitamente le primitive eliminate nell'API applicativa.
