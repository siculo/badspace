---
type: Architecture Primitive
title: Fallimenti e salute della partizione
description: "Rifiuti e fallimenti del writer: annullamento delle scritture non confermate, segnalazione strutturata, salute della partizione, rollback e recupero."
tags: [badspace, architecture, transactions, recovery]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-03T10:57:10Z }
---

# Primitiva

Un’operazione del writer può non riuscire in due modi.

- **Rifiuto.** La partizione controlla tutto l’input, il token e il
  proprio stato prima di cambiare qualcosa. Un’operazione rifiutata,
  per esempio con un ID inesistente o un token non corrente, non cambia
  niente: le scritture non confermate precedenti restano.
- **Fallimento.** Un’eccezione arriva dopo i controlli, mentre la
  partizione sta applicando una scrittura o preparando un
  [commit](/current/architecture/partition-commit.md#strutture-transazionali).
  Lo stato del writer potrebbe essere a metà, quindi la partizione
  **annulla tutte le scritture non confermate**, anche quelle dei tick
  precedenti, riportando le strutture all’ultimo commit con lo stesso
  ripristino del [cambio di proprietà](/current/architecture/partition-ownership.md#cambio-di-proprietà).
  Ultimo commit e snapshot in attesa non cambiano.

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
| Scritture e commit | Ammessi | Rifiutati con `IllegalStateException`, dopo il controllo del token |
| Letture degli snapshot, avanzamento del tick, salvataggi | Ammessi | Ammessi: leggono e pubblicano solo stati confermati e integri |
| `rollback` del proprietario | Scarta le scritture non confermate | Ritenta il ripristino: se riesce la partizione torna sana, se fallisce lo segnala con un nuovo resoconto |
| `claim` e `release` | Scartano le scritture non confermate | Ritentano il ripristino: se riesce la partizione torna sana, se fallisce la proprietà cambia comunque |
| Rimozione della partizione | Ammessa | Ammessa: guarda solo l’ultimo commit |

La proprietà cambia anche se il ripristino fallisce: un coordinatore può
sempre togliere la partizione a un writer, e il nuovo proprietario trova
la partizione nello stato di recupero.

# Rollback

Il **rollback** del writer scarta tutte le scritture dopo l’ultimo
commit riuscito, anche quelle dei tick precedenti, e riparte dal pending
se c’è, altrimenti dall’ultimo stato confermato; prima del primo commit
dallo stato vuoto. Non tocca gli snapshot pubblicati, non annulla commit
riusciti e non cambia la proprietà. Senza scritture dopo l’ultimo commit
non fa niente.

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
| `operation` | `WRITE`, `COMMIT`, `CLAIM`, `RELEASE` o `ROLLBACK` |
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

# Nel prototipo

Lo storage (`PartitionStorage2`/`PartitionStorage3`) separa in ogni
scrittura i controlli dall’applicazione e avvolge l’applicazione e la
preparazione del commit in un solo punto di gestione dei fallimenti.
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

I test dello storage, in 2D e 3D, provocano i fallimenti con l’indice e
il pool dei buffer dei test: un fallimento nelle notifiche all’indice
annulla anche le scritture di un tick precedente, mentre un rifiuto le
lascia; un fallimento nella preparazione degli slot o dell’indice
annulla le scritture e lascia intatto lo snapshot in attesa; un
fallimento con ripristino fallito porta al recupero necessario, con
scritture e commit rifiutati, il token non corrente rifiutato per primo,
la pubblicazione dell’ultimo commit e la rimozione ammessa; `claim`
ritenta il ripristino e, mentre fallisce, cambia comunque la proprietà;
`release` lo completa e la partizione torna sana; il rollback fa lo
stesso, segnalando il ripristino fallito; un `Error` arriva così com’è,
con il ripristino fallito tra le soppresse. I test del nodo verificano
che il rollback scarti anche le scritture dei tick precedenti, lasci
proprietà, commit e snapshot in attesa, rifiuti un token vecchio e una
partizione rimossa, e dia, dopo le stesse scritture casuali, lo stesso
stato di un rilascio seguito da una nuova presa, sui tre indici. Per i metadati
e per il ripristino degli slot non c’è ancora un modo di provocare il
fallimento nei test.

# Correlati

- [Commit della partizione](/current/architecture/partition-commit.md) — strutture transazionali e commit tutto-o-niente.
- [Proprietà della partizione](/current/architecture/partition-ownership.md) — `claim` e `release`.
- [Contratto dell’API](/current/api/operations.md#commit) — `rollback()` sull’handle del writer.
- [Contratto dell’API](/current/api/operations.md#fallimenti-e-salute) — firme ed errori.
