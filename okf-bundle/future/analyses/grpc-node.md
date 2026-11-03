---
type: Analysis
title: "Analisi del nodo gRPC"
description: "Problemi da valutare per i nodi remoti: esiti incerti, ripetizione e ordine delle richieste, timeout, tick, identità, acquisizioni, limiti e protocollo."
tags: [badspace, analysis, architecture, distribution]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-30T15:48:41Z }
---

# Nodo gRPC: problemi da affrontare

## Perimetro documentale

Analisi conservata come documento di valutazione, distinto dalle decisioni
adottate e dalla descrizione dell'[implementazione corrente](/current/index.md).
L'integrazione nel bundle non costituisce una nuova verifica del codice o
un'approvazione delle proposte. L'ordine suggerito qui non sostituisce il
[TODO del prototipo](../../../TODO.md).

Il [contratto transazionale in progetto](/future/architecture/transactional-components.md)
riguarda l'atomicità dello stato locale. Non risolve da solo l'incertezza
dell'esito di una chiamata remota descritta in questa analisi. La direzione
generale resta quella dei [nodi remoti](/future/decisions/tool-type.md).

L’API sincrona è un buon punto di partenza: possiamo mantenere i metodi
attuali e mettere gRPC dietro il contratto dei nodi. **La rete cambia però
il significato del fallimento: una chiamata può fallire per il chiamante
anche se il nodo ha eseguito l’operazione.** È questo il problema principale
da cui partire.

Questa analisi riguarda il prossimo blocco di sviluppo, non funzionalità
già disponibili. Parte dal contratto e dall’implementazione attuali.

## 1. Esito incerto delle operazioni

Esempio: il nodo esegue `commit()`, ma la risposta si perde. Il chiamante
riceve un errore senza sapere se il commit è avvenuto. Vale anche per
creazione, caricamento, acquisizione e trasferimento dell’esclusività.

Oggi il contratto può dire «se la chiamata fallisce, la partizione non
cambia». Con la rete questa garanzia deve distinguere un **rifiuto del
nodo**, che può preservarla, da un **errore di comunicazione**, che può
lasciare l’esito incerto. gRPC contempla esplicitamente che client e server
osservino esiti diversi e che la cancellazione non annulli le modifiche
già eseguite. Vedi la [documentazione gRPC sul ciclo di vita delle
chiamate](https://grpc.io/docs/what-is-grpc/core-concepts/).

## 2. Ripetizione delle chiamate e recupero dell’esito

Dopo un timeout non possiamo ripetere indistintamente l’operazione:

- `insertAll()` potrebbe trovare gli ID già inseriti;
- `claim()` potrebbe aver cambiato generazione, senza aver restituito il token;
- `acquire()` potrebbe aver creato un’acquisizione che il chiamante non conosce;
- `saveLatest()` potrebbe selezionare un tick diverso al secondo tentativo.

Occorre classificare le operazioni e scegliere come recuperare: rilettura
dello stato, identificatore della richiesta con memoria dell’esito, oppure
errore esplicito che richiede intervento applicativo. La politica dei
tentativi va definita per operazione; gRPC distingue già i tentativi
trasparenti sicuri dalle ripetizioni configurate. Vedi la
[documentazione sui retry gRPC](https://grpc.io/docs/guides/retry/).

## 3. Richieste in ritardo e ordine delle operazioni

Sincrono significa che il chiamante aspetta; dopo un timeout, però, una
richiesta potrebbe essere ancora in esecuzione. Se il writer prosegue, una
vecchia scrittura potrebbe interferire con quelle successive.

Il token attuale protegge dai writer che hanno perso l’esclusività, ma
**non distingue richieste vecchie e nuove dello stesso writer nella stessa
generazione**. Serve una regola: dopo un esito incerto si sospendono le
scritture fino alla riconciliazione, si cambia generazione, oppure si
introduce un ordinamento esplicito delle richieste. Non è necessario
introdurre subito tutti questi meccanismi, ma bisogna scegliere il
comportamento.

## 4. Timeout delle chiamate e cancellazione

Vanno separati tre tempi: deadline della chiamata remota, durata
dell’acquisizione e tick logico.

Per esempio, la deadline di `save()` limita quanto il client aspetta: non
può significare automaticamente che il file non verrà scritto. Anche
`loadPartition()` può essere lungo. Possiamo mantenerli sincroni,
definendo cosa accade quando il chiamante smette di aspettare.

Le deadline vanno configurate esplicitamente: gRPC non ne imposta una per
default. Vedi la [documentazione sulle deadline
gRPC](https://grpc.io/docs/guides/deadlines/).

## 5. Avanzamento del tick con nodi irraggiungibili

In [Space2.java](../../../prototype/api/src/main/java/badspace/api/Space2.java),
`setTick()` aggiorna il tick dello Space e poi chiama i nodi uno alla volta.
Se un nodo fallisce, alcuni possono essere avanzati e altri no; quelli
successivi non vengono raggiunti.

Dobbiamo definire cosa significa allora il tick dello Space, come
ritentare la propagazione e se continuare verso gli altri nodi. Inoltre
le chiamate avvengono mantenendo il lock del tick: un nodo lento blocca
anche altre attività che usano quel lock, inclusa la creazione di
partizioni.

Non serve introdurre subito una barriera distribuita, ma va reso esplicito
il comportamento in caso di propagazione incompleta.

## 6. Identità del nodo, riconnessione e riavvio

Riconnettersi allo stesso indirizzo non garantisce di ritrovare lo stesso
nodo: il processo potrebbe essere ripartito, perdendo partizioni,
acquisizioni e stato non salvato.

Serve distinguere una connessione ristabilita verso il nodo ancora vivo
da una nuova istanza. I vecchi handle e token non devono diventare validi
accidentalmente. Va anche definito come la factory soddisfa il contratto
attuale: creare un nodo nuovo, vuoto e riservato a quello Space.

L’hosting di più nodi nello stesso servizio può restare una decisione
separata.

## 7. Acquisizioni e risultati su più partizioni

La scadenza lato nodo è già una protezione utile: se il client scompare o
perde la risposta di `acquire()`, l’acquisizione non resta viva
indefinitamente.

Vanno però definiti il fallimento di `close()`, il recupero delle
acquisizioni con risposta persa e il comportamento delle letture aggregate
quando un nodo è irraggiungibile. Oggi `SnapshotSet` distingue alcuni
fallimenti di dominio e interrompe sugli altri errori: possiamo mantenere
questa scelta, purché l’indisponibilità remota non venga presentata come
risultato vuoto o snapshot assente.

## 8. Costo e dimensione delle chiamate

I batch attuali aiutano, ma una sequenza di piccole chiamate ora accumula
latenza. Anche leggere proprietà di una partizione può diventare un viaggio
di rete.

Inoltre scritture, metadati e query possono produrre messaggi grandi.
Occorre fissare limiti e comportamento al superamento. **Dividere
automaticamente un batch di scrittura in più RPC potrebbe rompere la
garanzia tutto-o-niente**: il limite di trasporto entra quindi nel
contratto.

## 9. Protocollo, errori e risorse del collegamento

Bisogna definire la rappresentazione dei tipi esistenti, la compatibilità
tra versioni e una traduzione degli errori che conservi i motivi di
dominio, separandoli dagli errori di trasporto.

Servono anche regole per apertura e chiusura dei canali, limiti alle
richieste concorrenti e gestione del sovraccarico. Per la persistenza va
chiarito che directory e salvataggi appartengono al nodo remoto: un
`SavedPartition` non implica che il file sia disponibile su un altro nodo.

## Ordine proposto per le decisioni

Partirei da:

1. Esito incerto.
2. Recupero e ripetizione.
3. Ordine dopo un timeout.
4. Propagazione del tick.
5. Identità dopo un riavvio.

Sono le decisioni che determinano quanto possiamo davvero conservare del
contratto attuale. Il salvataggio asincrono può rimanere rimandato: questi
problemi esistono anche mantenendo tutte le chiamate sincrone.
