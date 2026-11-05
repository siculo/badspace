---
type: Architecture Primitive
title: Fallimenti e salute della partizione
description: "Rifiuti e fallimenti del writer: annullamento delle scritture non confermate, segnalazione strutturata, salute della partizione, rollback e recupero."
tags: [badspace, architecture, transactions, recovery]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-05T17:31:55Z }
---

# Primitiva

Un’operazione del writer può non riuscire in due modi.

- **Rifiuto.** La partizione controlla le modifiche, il token e il
  proprio stato prima di cambiare qualcosa: a ogni scrittura nella
  [modalità diretta](/current/architecture/partition-commit.md#modalità-di-scrittura), e al flush o al commit, su tutto il batch, nella
  modalità con buffer, dove l'handle rifiuta subito solo una posizione
  fuori dai limiti. Una scrittura, un flush o un commit rifiutato, per esempio con un token
  non corrente, non cambia niente, e le scritture non confermate di
  prima restano, nel nodo o nell'handle.
- **Fallimento.** Un’eccezione arriva dopo i controlli, mentre la
  partizione sta applicando una scrittura o un batch, o preparando un
  [commit](/current/architecture/partition-commit.md#strutture-transazionali).
  Lo stato delle strutture potrebbe essere a metà, quindi la partizione
  **annulla tutte le scritture non confermate**, riportando le strutture
  all’ultimo commit con lo stesso ripristino del [cambio di proprietà](/current/architecture/partition-ownership.md#cambio-di-proprietà).
  Ultimo commit e snapshot in attesa non cambiano, e con il buffer
  l'handle scarta le sue scritture: il risultato è lo stesso nelle due
  modalità. Il rapporto dice l'operazione: `WRITE` per una scrittura
  della modalità diretta o per un flush con il buffer, `COMMIT` per il
  commit.

Un fallimento dipende da un bug o da una situazione limite, come
l’esaurimento della memoria, non dall’input. Il software non lo
prevede, ma riceve una segnalazione e sa che cosa è rimasto.

# Salute della partizione

Se anche il ripristino fallisce, lo stato del writer non è affidabile:
la partizione **richiede un recupero**. La salute è separata dal ciclo
di vita della partizione e dalla proprietà.

```text
                      "fallimento, ripristino riuscito"
                        +--------+
                        |        |
                        v        |
                    +---------------+
"creazione" ------> |      sana     |
                    +---------------+
 "fallimento,"         |         ^  "rollback, claim"
 "ripristino"          |         |  "o release, ripristino"
 "fallito"             v         |  "riuscito"
                    +---------------+
                    |   recupero    | ---+
                    |  necessario   |    |  "rollback, claim o release,"
                    +---------------+    |  "ripristino fallito"
                            ^            |
                            +------------+
```

Il diagramma mostra solo le transizioni della salute; le operazioni
ammesse in ciascuno stato sono nella tabella.

| Operazione | Partizione sana | Recupero necessario |
|---|---|---|
| Commit | Ammesso | Rifiutato con `IllegalStateException`, dopo il controllo del token |
| Letture degli snapshot, avanzamento del tick, salvataggi | Ammessi | Ammessi: leggono e pubblicano solo stati confermati e integri |
| `rollback` del proprietario | L'handle scarta le sue scritture; sul nodo non c'è niente da scartare | Ritenta il ripristino: se riesce la partizione torna sana, se fallisce lo segnala con un nuovo resoconto |
| `claim` e `release` | Sul nodo non c'è niente da scartare: le scritture del writer sono nel suo handle | Ritentano il ripristino: se riesce la partizione torna sana, se fallisce la proprietà cambia comunque |
| Rimozione della partizione | Ammessa | Ammessa: guarda solo l’ultimo commit |

La proprietà cambia anche se il ripristino fallisce: un coordinatore può
sempre togliere la partizione a un writer, e il nuovo proprietario trova
la partizione nello stato di recupero.

# Rollback

Il **rollback** del writer scarta tutte le scritture dopo l’ultimo
commit riuscito, anche quelle dei tick precedenti: svuota il buffer
dell'handle, se c'è, poi chiede al nodo di tornare all'ultimo commit,
anche se aspetta ancora l'avanzamento del tick. Il nodo scarta così le
scritture che ha già ricevuto, quelle della modalità diretta e quelle
dei flush con il buffer; se non ne ha, su una partizione sana non c'è
niente da riportare indietro. Non tocca gli snapshot
pubblicati, non annulla commit riusciti e non cambia la proprietà.

Il rollback è anche il **recupero** chiesto dal writer: su una partizione
che richiede un recupero ritenta il ripristino con il token del
proprietario, e se riesce la partizione torna sana. BADSPACE offre il
meccanismo; quando ritentare e quante volte lo decide il software.

Rollback, `claim`, `release` e annullamento dopo un fallimento usano lo
stesso ripristino delle
[strutture transazionali](/current/architecture/partition-commit.md#strutture-transazionali),
quindi lasciano lo stesso stato. Il ritorno a uno snapshot pubblicato più
vecchio, che annullerebbe anche commit riusciti, è un’altra cosa e non è
previsto oggi.

# Segnalazione

Un fallimento produce un resoconto, il record `PartitionFailure`:

| Campo | Significato |
|---|---|
| `operation` | `WRITE`, per una scrittura della modalità diretta o per un flush con il buffer, `COMMIT`, sia nell’applicazione del batch sia nella preparazione dello snapshot, `CLAIM`, `RELEASE` o `ROLLBACK` |
| `component` | `SLOTS`, `INDEX`, `METADATA` o `UNKNOWN`: la struttura in cui è avvenuto |
| `outcome` | `WRITES_DISCARDED` se il ripristino è riuscito, `RECOVERY_REQUIRED` altrimenti |
| `baseTick` | Il tick dell’ultimo commit, lo stato che la partizione conserva; la creazione e il caricamento contano come commit al tick di creazione |
| `cause` | Il fallimento dell’operazione; per `claim`, `release` e rollback, quello del ripristino |
| `restoreFailure` | Il fallimento del ripristino dopo una scrittura o un commit, se c’è |

La base è indicata con il tick, non con la sequenza interna dei commit,
che non esce dal nodo: più commit nello stesso tick hanno lo stesso
`baseTick`. La partizione è quella dell’operazione o quella di cui si
legge la salute, quindi il resoconto non la ripete.

Il chiamante riceve il resoconto in modi diversi:

- un’eccezione diventa una `PartitionFailureException`, con il
  resoconto e l’eccezione originale come causa;
- un `Error`, come `OutOfMemoryError`, arriva così com’è, con il
  fallimento del ripristino tra le eccezioni soppresse;
- `health()` restituisce `PartitionHealth.Healthy` o
  `PartitionHealth.RecoveryRequired` con il resoconto, da qualsiasi
  thread e anche senza aver ricevuto l’eccezione.

Senza memoria il resoconto può mancare. La partizione segna il recupero
necessario prima di costruirlo, con un’operazione che non alloca; se poi
la costruzione fallisce, la salute resta `RecoveryRequired` senza
resoconto e il chiamante riceve almeno il fallimento originale.

Anche le eccezioni soppresse sono solo un tentativo. Se il fallimento di
una pulizia è la stessa istanza del fallimento originale, come può
accadere con un `OutOfMemoryError` che la JVM lancia di nuovo, o se
manca la memoria per registrarlo, la pulizia non viene registrata, e il
chiamante riceve comunque il fallimento originale.

# Nel prototipo

Lo storage (`PartitionStorage2`/`PartitionStorage3`) fa il commit di un
batch in due passi, sotto lo stesso lock: `apply`, che controlla tutto il
batch prima di applicarlo allo stato del writer, e `commit`, che prepara
e accetta lo snapshot. I due passi hanno un solo punto di gestione dei
fallimenti, e un fallimento in uno qualsiasi dei due è un fallimento del
commit.
Durante il lavoro tiene la struttura in corso, per il campo `component`,
e il tick dell’ultimo commit, per `baseTick`. La salute è un campo
`volatile` scritto sotto il lock della partizione, come la proprietà.
Il contratto del nodo ha `health(PartitionId)` e l’API ha `health()`
sulla partizione; i tipi sono in `common`. Il rollback è
`rollback(WriteToken)` nel contratto del nodo e `rollback()` sull’handle
del writer; rifiuta un token non corrente con `OwnershipChangedException`
e una partizione rimossa con `IllegalStateException`, e se il ripristino
fallisce lancia `PartitionFailureException` con esito
`RECOVERY_REQUIRED`, o l’`Error` così com’è.

Il ripristino si può ritentare dalla stessa base: ogni struttura
aggiorna i propri segni solo alla fine del suo ripristino, e gli slot
già ripristinati non hanno più niente da fare.

Gli slot non restano a metà quando un'allocazione fallisce. Quando
crescono, creano tutti i nuovi array, degli ID, delle coordinate e delle
pagine, prima di sostituire i vecchi, e lo stesso fa la tabella degli
slot: un fallimento lascia la vecchia capacità, di cui il ripristino e
la crescita successiva si fidano. Se la preparazione degli slot fallisce
dopo aver preso un buffer delle coordinate, lo rimette nel pool come un
buffer senza commit, perché lo storage non ha uno snapshot da
abbandonare; il buffer condiviso con l'ultimo snapshot resta a lui. Gli
slot creano i propri array attraverso un'interfaccia interna, che i test
sostituiscono per far fallire un'allocazione.

I test dello storage, in 2D e 3D, provocano i fallimenti con l’indice e
il pool dei buffer dei test, e con un costruttore dello storage per i
test che mette slot e metadati dentro componenti dei commit che possono
fallire nella preparazione, nell’abbandono e nel ripristino: un batch
rifiutato, con una posizione fuori dai limiti, non cambia niente; un fallimento nelle notifiche all’indice
durante l’applicazione del batch, o nella preparazione degli slot o
dell’indice, annulla il batch e lascia intatto lo snapshot in attesa, e
lo stesso batch inviato di nuovo si applica intero; un fallimento con
ripristino fallito porta al recupero necessario, con i commit rifiutati, il token non corrente rifiutato per primo,
la pubblicazione dell’ultimo commit e la rimozione ammessa; `claim`
ritenta il ripristino e, mentre fallisce, cambia comunque la proprietà;
`release` lo completa e la partizione torna sana; il rollback fa lo
stesso, segnalando il ripristino fallito; un `Error` arriva così com’è,
con il ripristino fallito tra le soppresse. I test del nodo verificano
che il rollback scarti anche le scritture dei tick precedenti, lasci
proprietà, commit e snapshot in attesa, rifiuti un token vecchio e una
partizione rimossa, e dia, dopo le stesse scritture casuali, lo stesso
stato di un rilascio seguito da una nuova presa, sui tre indici. Un
fallimento nella preparazione dei metadati annulla le scritture e
abbandona slot e indice già preparati, e un fallimento nell’abbandono
arriva tra le soppresse del fallimento del commit; un ripristino fallito
dei metadati o degli slot porta al recupero necessario, con la struttura
del fallimento nel resoconto, e il rollback o `claim` lo completano
quando il fallimento finisce. Con latch e senza sleep, un `claim` o un
avanzamento del tick che arriva durante un rollback lo aspetta, mentre
salute e proprietà restano leggibili, e l’avanzamento pubblica l’ultimo
commit. Un abbandono che lancia la stessa istanza del fallimento della
preparazione lascia arrivare al chiamante il fallimento originale, con le
scritture annullate. I test degli slot, in 2D e 3D, fanno fallire le
allocazioni: una crescita fallita a metà, seguita dal ripristino con e
senza scritture, lascia inserire oltre la vecchia capacità e fare il
commit; una preparazione fallita dopo il prelievo rimette il buffer nel
pool senza commit, e il commit dopo il ripristino lo riusa copiando
anche la pagina che non ha più la scrittura scartata; il fallimento
della restituzione va tra le soppresse, tranne quando è la stessa
istanza.

I test del commit a batch sul nodo, in 2D e 3D (`BatchCommit2Test`/
`BatchCommit3Test`), usano un nodo con buffer, scrivono con
`WriteSession2`/`WriteSession3` e
fermano il batch alla prima notifica all’indice: un avanzamento del tick
o un `claim` che arriva durante l’applicazione aspetta il lock, e dopo
lo snapshot o il nuovo proprietario trovano il batch intero. Verificano
anche che un batch rifiutato lasci lo snapshot in attesa e il tick
dell’ultimo commit, che un batch fallito lasci lo snapshot in attesa e
il writer senza le sue scritture, e che dopo un ripristino fallito i
commit siano rifiutati fino al rollback del nodo: il commit rifiutato
conserva le scritture del writer, che il commit dopo il recupero
conferma, mentre quelle del commit fallito sono perse. I test dell'API
verificano con un nodo finto che un commit rifiutato lasci le scritture
nell'handle e che uno fallito, con `PartitionFailureException` o con un
`Error`, le scarti.

# Correlati

- [Commit della partizione](/current/architecture/partition-commit.md) — strutture transazionali e commit tutto-o-niente.
- [Proprietà della partizione](/current/architecture/partition-ownership.md) — `claim` e `release`.
- [Contratto dell’API](/current/api/operations.md#commit) — `rollback()` sull’handle del writer.
- [Contratto dell’API](/current/api/operations.md#fallimenti-e-salute) — firme ed errori.
