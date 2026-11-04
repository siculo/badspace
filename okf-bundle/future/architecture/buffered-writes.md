---
type: Architecture Primitive
title: "Scritture accumulate nel writer"
description: "Refactoring concordato: modifiche finali per ID nel client, set con inserimento se assente e applicazione atomica sul nodo al commit."
tags: [badspace, architecture, transactions, api]
status: draft
timestamp: 2026-11-04T23:42:12Z
---

# Stato e obiettivo

Direzione concordata, non implementata. Oggi il writer inoltra ogni
scrittura al nodo e il [commit](/current/architecture/partition-commit.md)
prepara lo snapshot dello stato già modificato. Il refactoring sposta
l'accumulazione nel writer, nel processo del client: solo `commit()`
trasferisce al nodo le modifiche. Il comportamento sarà comune a nodi
locali e remoti, prima dell'introduzione di gRPC.

Il software legge uno snapshot, esegue la propria logica e costruisce le
scritture. Non legge lo stato mutabile attraverso l'esito di una
scrittura: `updateIf` e `compareAndSetMetadata` vengono rimossi.
Lo snapshot letto può non comprendere commit ancora pending, anche di un
precedente proprietario; il refactoring non introduce letture del pending.

# Semantica delle operazioni

- `insert` genera un ID attraverso l'`IdGenerator` e registra una nuova
  entità nel buffer. L'ID è disponibile subito, prima del commit.
- `update` diventa `set`: fissa la posizione e inserisce l'entità se
  assente. Un'entità rimossa può essere reinserita con lo stesso ID.
  La creazione di un nuovo ID resta distinta dal riutilizzo di un ID
  già generato: non si riassegna quell'ID a una diversa identità.
- `remove` registra l'assenza dell'entità; se manca già, non fa niente.
  La rimozione cancella anche i metadati applicativi.
- Le scritture dei metadati mantengono solo l'ultima modifica per chiave,
  distinguendo un valore da una cancellazione. Una `set` ordinaria
  conserva i metadati non modificati.

Il vincolo proposto sulla costruzione di `Entity` riguarda la provenienza
dell'ID dall'`IdGenerator`, non la presenza dell'entità nella partizione.
Forma e fattibilità del vincolo vanno definite includendo snapshot,
caricamento e futuro trasporto remoto: il costruttore pubblico Java non è
da solo una prova della provenienza dei dati ricevuti dal nodo.

Per i metadati su un'entità assente si adotta come regola di progetto
che non creino un'entità senza posizione e siano ignorati finché una
`set` non la ricrea. Il client non conosce necessariamente la presenza
nella base: in quel caso decide il nodo sullo stato risultante del batch.
Il dettaglio della composizione e i casi limite vanno verificati prima
di fissare la rappresentazione del buffer.

# Composizione per identità

Il writer tiene una mappa per ID, non un registro completo delle chiamate.
L'ordine delle chiamate determina la composizione locale; non deve essere
trasferito come sequenza globale al nodo. Per ciascuna entità il batch
descrive le modifiche finali rispetto alla base confermata, senza dover
conoscere o copiare l'intero contenuto della base.

| Chiamate nel writer | Effetto da trasferire |
|---|---|
| `set(A, p1)`, `set(A, p2)` | A presente in p2 |
| `set(A, p1)`, `remove(A)` | A assente, senza metadati |
| `remove(A)`, `set(A, p2)` | A presente in p2, con azzeramento dei vecchi metadati |
| Due scritture sulla stessa chiave | Solo l'ultima modifica |
| Scrittura di metadati, poi `remove(A)` | A assente; scritture dei metadati scartate |

Il caso rimozione e reinserimento richiede di conservare nel batch
l'azzeramento dei metadati precedenti: la sola posizione finale non basta.
Le modifiche di entità diverse sono indipendenti; non si introducono
vincoli tra entità o transazioni tra partizioni.

Ridurre le modifiche prima dell'applicazione evita aggiornamenti ripetuti
dell'indice per la stessa entità. Il costo si sposta verso il buffer del
client e il commit; il beneficio sui nodi locali va misurato.

# Commit e ciclo di vita

Il nodo riceve token e batch e li tratta come un'unica operazione rispetto
all'avanzamento del tick e al cambio di proprietà. Applica le modifiche
all'ultimo stato confermato, compreso il pending, non allo snapshot letto
dal client. Prepara e accetta insieme slot, indice e metadati, preservando
la garanzia tutto-o-niente delle
[strutture transazionali](/current/architecture/partition-commit.md#strutture-transazionali).

Restano commit cumulativi, più commit nello stesso tick e commit vuoti.
Le scritture accumulate possono attraversare più tick prima del commit.
L'avanzamento successivo pubblica lo snapshot: il refactoring non rende
immediatamente leggibile il commit e non modifica la persistenza.

Dopo il successo il writer svuota il buffer. `rollback()` prima dell'invio
scarta il buffer locale e conserva la proprietà; `close()` scarta il
buffer e rilascia la proprietà sul nodo. Il recupero di una partizione
rimasta in errore durante un commit resta distinto dallo svuotamento
locale e va mantenuto nel contratto. Il cambio di proprietario invalida
il token dei vecchi buffer, senza modificare i commit riusciti.

Tra commit non ci sono scritture del client da annullare sul nodo.
Durante l'applicazione del batch restano possibili fallimenti: ripristino,
salute e risorse degli snapshot non possono essere eliminati solo perché
le modifiche arrivano insieme.

# Rifiuti ed esiti

Restano i rifiuti per token superato, partizione inesistente o rimossa,
recupero necessario e input non valido, comprese coordinate fuori dai
limiti. I controlli locali possibili avvengono alla chiamata, quelli
definitivi sul nodo al commit. La presenza pregressa di un ID non è più
un motivo di rifiuto per `set` o `remove`. Le operazioni superate nel
buffer non vengono applicate al nodo.

Un batch rifiutato non cambia la partizione; un fallimento durante
l'applicazione o la preparazione deve preservare l'ultimo commit e il
pending precedente. Il buffer può essere conservato dopo un rifiuto
certo, per correzione o rollback. Va definito il suo stato dopo un
fallimento certo con ripristino riuscito o recupero necessario.

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

Verificare la composizione confrontandola con l'esecuzione sequenziale
delle nuove regole, incluse rimozione e reinserimento, modifiche dei
metadati, entità assenti e più commit. Verificare inoltre zero chiamate
al nodo durante l'accumulazione, un solo invio al commit, rollback locale,
token superati, fallimenti e concorrenza con tick e cambio di proprietà.

La rimozione delle conditional writes richiede di riesaminare, quando
si arriverà alla migrazione, i controlli su epoca, stato e destinazione
previsti dai [metadati di sistema](/future/architecture/entity-metadata.md).
Leggere uno snapshot non garantisce da solo le precedenti precondizioni
sullo stato più recente: il protocollo futuro va riallineato, senza
reintrodurre implicitamente le primitive eliminate nell'API applicativa.
