---
type: Analysis
title: "Analisi del design del prototipo BADSPACE"
description: "Valutazione statica del prototipo e proposte su tempo logico, richieste remote, proprietà, memoria, recupero, migrazione, query aggregate e storage."
tags: [badspace, analysis, architecture, distribution]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-30T15:48:41Z }
---

# Analisi del design del prototipo BADSPACE

## Perimetro documentale

Analisi conservata come documento di valutazione, distinto dalle decisioni
adottate e dalla descrizione dell'[implementazione corrente](/current/index.md).
L'integrazione nel bundle non costituisce una nuova verifica del codice o
un'approvazione delle proposte. L'ordine suggerito qui non sostituisce il
[TODO del prototipo](../../../TODO.md).

Per rollback, contratto transazionale e letture del pending, il successivo
[progetto delle strutture transazionali](/future/architecture/transactional-components.md)
raccoglie la direzione concordata. In particolare, la vista da conservare
ipotizzata al punto 3 non è un requisito adottato: si prevedono letture
per chiamata tramite il writer, senza acquisizione esplicita.

**Conserverei il nucleo a partizioni con un solo writer e snapshot immutabili, ma rivedrei alcuni contratti prima di costruirci sopra distribuzione e migrazione.** Le criticità principali riguardano il significato di tempo, recupero, proprietà e risultati delle query.

Ho confrontato implementazione, test pertinenti, TODO e documentazione corrente e futura. Questa è un’analisi statica: non ho eseguito la suite né modificato l’implementazione. Ho considerato anche l’[analisi gRPC già presente](./grpc-node.md). Le proposte seguenti non sono decisioni già adottate né funzionalità disponibili.

## 1. Rendere esplicita la differenza tra tick richiesto e avanzamento completato

In [Space2.setTick](../../../prototype/api/src/main/java/badspace/api/Space2.java), lo spazio aggiorna il proprio tick e poi contatta i nodi in sequenza, mantenendo il lock. Il nodo segue uno schema analogo verso le partizioni.

La propagazione non atomica è una scelta documentata e ragionevole. Il problema futuro è l’osservabilità: se un nodo fallisce, `space.tick()` indica comunque il nuovo valore, mentre una parte dello spazio può essere rimasta indietro.

Proporrei che l’avanzamento restituisca un esito con il tick richiesto e lo stato dei nodi coinvolti, distinguendo conferma, rifiuto ed esito incerto. La barriera rimarrebbe una politica applicativa, ma avrebbe informazioni affidabili su cui lavorare.

Per sincronizzare più writer valuterei una **barriera applicativa sul tick**, con preparazione dei commit destinati a un tick futuro. Oggi un commit concorrente all’avanzamento finisce legittimamente nel tick precedente o successivo; per una simulazione a passi questo può separare modifiche che l’applicazione vuole attribuire allo stesso tick.

Una possibile API è `prepareCommit(t)`: ogni writer congela le modifiche destinate al tick `t` e segnala di essere pronto. Quando tutti i writer coinvolti sono pronti, il coordinatore applicativo autorizza l’applicazione dei commit a quel tick e coordina l’avanzamento. La preparazione deve poter terminare senza attendere l’arrivo di `t`; un esito asincrono distinto può segnalare l’effettiva applicazione. Se un writer ritarda, la politica applicativa decide se aspettare o annullare il passo. Il contratto deve definire anche annullamento delle preparazioni e recupero dopo un guasto del coordinatore.

Attribuire i commit allo **stesso tick logico** non garantisce un’applicazione atomica fra partizioni. Se i reader devono vedere soltanto passi interamente completati, serve anche un coordinamento della pubblicazione degli snapshot. Va inoltre precisato il rapporto fra tick del commit, avanzamento e tick dello snapshot pubblicato, rispettando il confine di pubblicazione previsto dal modello.

Sono proposte future da valutare con il nodo remoto, mantenendo la forma semplice per gli usi che non richiedono questa sincronizzazione.

## 2. Trattare le scritture remote come operazioni delle quali si può recuperare l’esito

Il caso più concreto è [PartitionWriter2.insertAll](../../../prototype/api/src/main/java/badspace/api/PartitionWriter2.java): genera gli ID, invia il batch e restituisce gli ID soltanto dopo il successo.

Con una risposta persa, il nodo potrebbe aver inserito le entità mentre il chiamante non riceve i loro ID. Ripetere la chiamata genererebbe altri ID.

Separerei quindi, almeno internamente, **preparazione ed esecuzione del batch**: identità della richiesta e ID delle entità devono restare disponibili dopo un errore di comunicazione. Il protocollo dovrebbe permettere di riconoscere una ripetizione e recuperare l’esito originale entro un perimetro dichiarato.

Il fencing attuale risolve un problema diverso: esclude i vecchi proprietari, ma non ordina richieste ritardate dello stesso proprietario. Per il primo nodo remoto sceglierei una regola semplice: dopo un esito incerto, il writer sospende le operazioni successive fino alla riconciliazione. L’esecuzione concorrente di più richieste può arrivare in seguito, se serve.

Questo incide anche sulle scritture condizionate: ripeterle e ottenere una condizione falsa non permette di capire se il primo tentativo fosse riuscito.

## 3. Dare al nuovo writer una base di ripartenza osservabile

È il punto che rivedrei per primo sul comportamento locale.

Il [test del cambio di proprietario](../../../prototype/service/src/test/java/badspace/service/partition/PartitionOwnership2Test.java), `newOwnerStartsFromTheCommitsOfTheCurrentTick`, conferma che il nuovo writer eredita i commit del tick corrente. Tuttavia, le letture pubbliche permettono di acquisire soltanto snapshot già pubblicati.

Esempio: nel tick 10 il writer A conferma nuove posizioni e poi smette di rispondere. B ottiene la proprietà, ma l’ultimo snapshot pubblico è ancora quello del tick 9. B modifica dunque uno stato che può essere più recente di quello che riesce a leggere.

Per un failover autonomo proporrei che il passaggio di proprietà renda disponibile **una vista immutabile dello stato confermato ereditato**, legata alla nuova generazione. Servirebbe a inizializzare il nuovo writer; la pubblicazione per i reader continuerebbe a seguire i tick.

Questa soluzione richiede di conservarne correttamente i buffer: il commit in attesa oggi può essere sostituito e riciclato. L’alternativa più economica è un protocollo di handoff che imponga la pubblicazione prima della ripartenza, accettando il coordinamento aggiuntivo.

## 4. Introdurre una politica esplicita della memoria

La scadenza delle acquisizioni è utile, ma non costituisce un limite alla memoria:

- le query già ammesse possono terminare dopo la scadenza;
- la pulizia delle acquisizioni avviene in risposta ad altre operazioni;
- il [pool dei buffer](../../../prototype/service/src/main/java/badspace/service/partition/slots/CoordinateBufferPool.java) conserva i buffer restituiti senza un tetto;
- la copia autonoma per il salvataggio aggiunge memoria temporanea.

Un picco di reader lenti può quindi far crescere il numero di buffer, che restano nel pool anche dopo la fine del picco.

Introdurrei prima contatori in byte e un limite alla memoria **inattiva** del pool: scartare un buffer libero non cambia le garanzie degli snapshot. Poi valuterei limiti all’ammissione di nuove acquisizioni e salvataggi, con rifiuti espliciti.

Separerei inoltre la manutenzione delle scadenze dall’avanzamento del tempo logico, offrendo un’operazione richiamabile dal software anche quando la simulazione è ferma.

La scelta da dichiarare è questa: memoria limitata, reader arbitrariamente lenti e writer sempre libero di proseguire non possono essere garantiti contemporaneamente. Per gli usi interattivi privilegerei il writer, limitando le nuove richieste che trattengono memoria.

## 5. Separare importazione dei dati e recupero della stessa partizione

Oggi [loadPartition](../../../prototype/api/src/main/java/badspace/api/Space2.java) crea una nuova partizione; lo storage conserva gli ID delle entità ma riporta il contenuto al nuovo tick di creazione. È una buona operazione di caricamento del contenuto.

Il recupero necessario alla migrazione è più impegnativo: deve conservare o ricostruire identità logica, epoche, tombstone e messaggi ancora da completare.

Proporrei due operazioni concettualmente distinte:

- **importare contenuto**, creando una nuova storia;
- **recuperare una partizione**, continuando una storia precedente con una nuova istanza operativa.

Questo evita di attribuire progressivamente a `loadPartition` significati incompatibili.

Rivedrei contestualmente il riferimento al salvataggio, oggi basato su nome e tick. Prima della durabilità osservabile servirebbe un’identità della storia cui il salvataggio appartiene: il massimo tick salvato per nome, da solo, non dimostra che una scrittura della nuova istanza sia durevole.

Va chiarita anche l’importazione degli ID: caricare la stessa copia in due partizioni può violare l’ipotesi corrente dell’aggregazione secondo cui ogni entità compare una sola volta. Si può imporre il vincolo al chiamante, ma deve essere esplicito.

## 6. Rendere la migrazione una macchina a stati persistente e verificabile

Il [protocollo futuro](/future/mechanisms/entity-migration.md) sceglie una direzione sensata: passaggio asincrono, epoche, reinvio e conferme dopo persistenza.

C’è però un dettaglio decisivo: permette di inviare a B uno stato modificato rispetto alla copia congelata in A. Dopo un crash di A, la sola marcatura “in uscita verso B” non basta necessariamente a ricostruire quel messaggio.

La proposta è conservare durevolmente **il contenuto esatto da reinviare**, oppure una trasformazione deterministica con tutti gli input necessari. La scelta deve far parte del protocollo.

Implementerei poi le transizioni del protocollo come logica separata da trasporto e pianificazione dei tentativi. Questo consentirebbe test sistematici su duplicati, riavvii e interruzioni fra persistenza e invio.

Qui anticiperei una decisione nel TODO: il contratto di recupero e durabilità deve precedere l’implementazione della migrazione, perché ne determina la correttezza.

## 7. Rafforzare il contratto delle query aggregate prima della deduplica

[SnapshotSet2](../../../prototype/api/src/main/java/badspace/api/SnapshotSet2.java) distingue correttamente acquisizioni riuscite e fallite. Tuttavia, una query restituisce una normale lista anche quando l’insieme è parziale: il chiamante deve ricordarsi di controllare separatamente la completezza.

Per monitoraggio e visualizzazione un risultato parziale può essere utile; per selezionare un bersaglio o prendere una decisione nella simulazione può essere inaccettabile. Proporrei un risultato che porti con sé tick e copertura delle partizioni, lasciando al chiamante la politica di accettazione. `isComplete()` descriverebbe chiaramente l’acquisizione iniziale, senza suggerire che le acquisizioni siano ancora valide.

La revisione più significativa riguarda la migrazione. Deduplicare i soli risultati geometrici non basta se le copie hanno posizioni diverse:

> La copia vecchia è dentro la regione cercata; quella autorevole è fuori. La query vede soltanto la vecchia e non può scartarla confrontandola con la nuova.

Il problema è già riconosciuto nella [documentazione futura](/future/mechanisms/query-aggregation.md). Lo risolverei scegliendo prima una semantica di visibilità: quale copia rappresenta l’entità a ciascun tick e come verificarlo. Per k-nearest potrebbe servire recuperare ulteriori candidati dopo aver escluso quelli obsoleti. Sarebbe un cambiamento del protocollo di query, da affrontare insieme alla migrazione.

## 8. Alleggerire lo storage per responsabilità, conservando la specializzazione geometrica

[PartitionStorage2](../../../prototype/service/src/main/java/badspace/service/partition/PartitionStorage2.java) delega già diversi dettagli, ma coordina ancora scritture, rollback, proprietà, pubblicazione e rimozione. Con messaggi idempotenti e migrazione rischia di accumulare altre responsabilità.

Estrarrei gradualmente le regole indipendenti dalla geometria: sequenziamento delle richieste, transizioni di proprietà e stato del recupero. Manterrei specializzati slot e operazioni spaziali 2D/3D, coerentemente con le linee guida.

Un costo concreto da misurare è il rollback al cambio di proprietario: quando ci sono modifiche, `discardUncommittedWrites()` scandisce lo stato per ricostruire quello confermato. Un registro delle entità toccate potrebbe ridurre il costo dei cambi di writer dopo poche modifiche su partizioni grandi, ma lo introdurrei soltanto se le misure lo giustificano.

## Ordine proposto

| Momento | Revisioni |
|---|---|
| Prossimo intervento locale | Base osservabile del nuovo writer; misure e limite del pool |
| Blocco nodo remoto | Esiti recuperabili, ordine delle richieste, avanzamento del tick osservabile |
| Prima della migrazione | Identità del recupero, durabilità e contenuto persistente dei messaggi |
| Insieme alla migrazione | Visibilità delle copie e correttezza delle query aggregate |

Conserverei la separazione fra politiche applicative e primitive, il fencing per partizione, la pubblicazione atomica del catalogo e gli indici intercambiabili. Sono basi adatte agli usi previsti; le revisioni proposte servono soprattutto a rendere esplicite le garanzie quando si aggiungono ritardi, guasti e recupero.
