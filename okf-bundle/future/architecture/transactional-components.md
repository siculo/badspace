---
type: Design Decision
title: "Strutture transazionali, rollback e recupero"
description: "Estensioni delle strutture transazionali: attesa dell'inizio del tick, letture del pending tramite il writer, ritorno a snapshot più vecchi, e possibili evoluzioni del coordinamento."
tags: [badspace, transactions, snapshots, recovery, testing]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-03T14:26:22Z }
---

# Perimetro e obiettivo

Questa pagina descrive le estensioni da implementare sopra il contratto
delle [strutture transazionali](/current/architecture/partition-commit.md#strutture-transazionali)
già presente: slot, indici e metadati preparano e accettano uno
snapshot a ogni commit, tutto o niente, e si ripristinano all'ultimo
commit in un ordine fisso; un [fallimento](/current/architecture/partition-failures.md)
annulla le scritture non confermate e, se il ripristino non riesce,
la partizione richiede un recupero; il rollback del writer scarta le
scritture non confermate ed è anche il recupero. Le
garanzie di comportamento sono la direzione scelta; nomi e firme Java
sono una proposta da verificare nell'implementazione. La sequenza del
lavoro è nel TODO del prototipo.

Il modello rimane quello di un writer per partizione, con letture su
stati immutabili. Non si introducono transazioni tra partizioni né
salvataggi impliciti su disco. Il contratto deve permettere di aggiungere
altre strutture senza disperdere nello storage le loro regole transazionali.

# Punto di partenza verificato

Il [commit corrente](/current/architecture/partition-commit.md) prepara
uno snapshot di slot, indice e metadati; l'ultimo commit del tick resta
pending fino alla [pubblicazione](/current/architecture/logical-time.md).
Le scritture successive modificano lo stato di lavoro e attraversano i
tick senza essere pubblicate finché non vengono confermate.

Slot, indici e metadati implementano `TransactionalComponent<S>` con
preparazione, accettazione, abbandono e ripristino. Lo storage 2D e 3D
accetta un commit solo dopo tutte le preparazioni. Un fallimento dopo i
controlli, in una scrittura o in un commit, annulla le scritture non
confermate e arriva al chiamante con un resoconto `PartitionFailure`;
se il ripristino fallisce, la salute della partizione diventa
`RecoveryRequired` e `rollback`, `claim` o `release` la riportano sana.
Rollback, cambio di proprietà e annullamento dopo un fallimento usano lo
stesso ripristino.

Chi prende la proprietà legge con il proprio token il tick dell'ultimo
commit, e sa se nel tick corrente c'è già un commit. I test provano i
fallimenti di tutte le strutture, nella preparazione e nel ripristino,
e le operazioni concorrenti del writer, del tick e del cambio di
proprietà.

# Stati e responsabilità

| Stato | Significato |
|---|---|
| Stato di lavoro | Contenuto mutabile, comprese le scritture dopo l'ultimo commit riuscito |
| Snapshot preparato | Stato immutabile prodotto da un componente, non ancora accettato nel commit della partizione |
| Ultimo stato confermato | Snapshot completo dell'ultimo commit riuscito; base del rollback anche se non ancora pubblicato |
| Pending | Ultimo stato confermato nel tick corrente, in attesa della pubblicazione |
| Snapshot pubblicato | Stato confermato reso disponibile ai reader al cambio di tick |

La transazione comprende tutte le scritture dopo l'ultimo commit
riuscito, anche se distribuite su più tick. Prima del primo commit
la base è lo stato iniziale vuoto. Il cambio di tick non ricrea né
annulla lo stato di lavoro.

Lo storage gestisce già ownership, sequenza dei commit, pending,
pubblicazione e salute della partizione.

# Ritorno a uno snapshot più vecchio

Il ritorno a uno snapshot pubblicato più vecchio è un'estensione distinta
dal [rollback](/current/architecture/partition-failures.md#rollback):
potrebbe annullare anche commit riusciti e richiede una semantica dedicata.
Non è un requisito di questo blocco. Il recupero corrente è in memoria,
distinto dal [recupero dei nodi](/future/architecture/partition-ownership.md)
dopo un crash e dal caricamento dei salvataggi.

# Writer e tick

L'[uso con un writer per tick](/current/architecture/partition-ownership.md#un-writer-per-tick)
e il tick dell'ultimo commit per il recupero sono già disponibili.
Restano due estensioni.

## Inizio del tick

Il writer oggi non sa quando inizia un tick: lo sa il software che chiama
l'avanzamento del tick, che può avvisare i writer con un proprio canale.
Un'attesa dell'inizio di un tick nell'API, per esempio fino alla
pubblicazione di un tick dato, è un'estensione da valutare.

## Letture del pending tramite il writer

Nell'uso con un writer per tick il writer non legge il pending. Le letture del
pending restano un'estensione possibile, se un caso d'uso chiedesse a
un writer di leggere i commit del tick prima della pubblicazione. Sono
state valutate tre forme:

| Forma | Vantaggi | Svantaggi |
|---|---|---|
| Lettura per chiamata sotto il lock della partizione | Ordine con commit e cambio di proprietà garantito dal lock; il buffer del pending non si riusa durante la lettura; nessun cambio al ciclo di vita dei buffer | Una lettura lunga ritarda avanzamento del tick, `claim` e `release` della partizione |
| Lettura per chiamata con un uso interno sullo snapshot e query fuori dal lock | Non blocca la partizione durante la query | Il pending deve contare gli usi e il buffer sostituito si libera solo all'ultimo uso; proprietà verificata solo all'inizio della lettura |
| Acquisizione del pending riservata al writer | Stesse query dei reader, senza operazioni dedicate; vista stabile su più chiamate | Il writer deve rilasciare; i pending sostituiti ma acquisiti tengono i buffer; la vista invecchia al commit successivo; servono un'identità per uno snapshot non pubblicato e una regola per il cambio di proprietà |

I reader ordinari continuerebbero a leggere solo snapshot pubblicati e
acquisiti.

# Possibili evoluzioni del coordinamento

Lo storage della partizione contiene già, senza separarlo, un componente
transazionale composto: lo snapshot `StorageSnapshot2`/`StorageSnapshot3`
raccoglie quelli di slot, indice e metadati, e il commit prepara le tre
strutture, le accetta o abbandona quelle già preparate, mentre il
ripristino le riporta all'ultimo commit nell'ordine fisso. Sopra queste
operazioni lo storage aggiunge quello che il contratto non conosce:
proprietà, sequenza dei commit, pending e pubblicazione, tick dell'ultimo
commit, segnalazione dei fallimenti e salute. Due ipotesi, non ancora
richieste, renderebbero utile separare le due parti.

## Componente composto per più strutture

Se arrivassero altre strutture transazionali, per esempio se i
[metadati di sistema](/future/architecture/entity-metadata.md) o i
[tombstone](/future/architecture/tombstones.md) avessero strutture
proprie, la preparazione con l'abbandono di quelle già preparate e la
sequenza dei ripristini crescerebbero a mano nello storage, in 2D e 3D.
Un oggetto interno che implementa `TransactionalComponent` con lo
snapshot composto, e delega alle strutture nell'ordine fisso, terrebbe
queste regole in un solo punto, come chiede il contratto; lo storage lo
userebbe come oggi usa le tre strutture.

Il diagramma confronta la divisione dei compiti di oggi con quella
dell'ipotesi; "altre" indica le strutture che si aggiungerebbero.

```text
 "oggi"                                "ipotesi"

 +-------------------------------+     +-------------------------------+
 |       "PartitionStorage2"     |     |       "PartitionStorage2"     |
 |  proprietà, pending, tick,    |     |  proprietà, pending, tick,    |
 |  segnalazioni, sequenza       |     |  segnalazioni, sequenza       |
 |  preparazione, accettazione,  |     +---------------+---------------+
 |  abbandono, ripristino        |                     |
 +----+----------+----------+----+                     v
      |          |          |          +-------------------------------+
      v          v          v          |     "componente composto"     |
  +------+   +--------+ +--------+     |  preparazione, accettazione,  |
  | slot |   | indice | |metadati|     |  abbandono, ripristino        |
  +------+   +--------+ +--------+     +----+-------+-------+-------+--+
                                            |       |       |       |
                                            v       v       v       v
                                          slot   indice metadati  altre
```

Lo storage indicherebbe ancora la struttura in corso nelle segnalazioni:
il componente composto dovrebbe quindi esporre la struttura in cui è
avvenuto un fallimento, o lo storage dovrebbe dargli il campo da
aggiornare.

## Storage come partecipante di un commit su più partizioni

Un commit atomico su più partizioni, oggi escluso dal perimetro di
questa pagina, richiederebbe di preparare tutte le partizioni e di
accettarle solo se tutte le preparazioni riescono. Lo storage diventerebbe
allora un partecipante, con le stesse quattro operazioni del contratto
sopra il componente composto, e il coordinatore starebbe sopra gli
storage, nel nodo o nell'API. Il contratto del nodo dovrebbe esporre
preparazione e accettazione al posto dell'unico `commit`, e andrebbero
definiti il token di ciascuna partizione durante la preparazione, la
pubblicazione allo stesso tick e il comportamento se un nodo remoto non
risponde tra le due fasi. È una direzione distinta dai commit di più
partizioni in una sola chiamata al nodo, previsti tra le
[estensioni dell'API](/future/decisions/api-surface.md#altre-estensioni-da-valutare)
senza atomicità.

# Collegamenti

- [Isolamento delle letture corrente](/current/decisions/read-isolation.md).
- [Proprietà corrente della partizione](/current/architecture/partition-ownership.md).
- [Estensioni dell'API](/future/decisions/api-surface.md).
- [Approccio incrementale e test](/process/development-approach.md).
- [Analisi del design del prototipo](/future/analyses/prototype-design.md): contesto e alternative; l'[uso con un writer per tick](/current/architecture/partition-ownership.md#un-writer-per-tick) non richiede la vista dello stato ereditato ipotizzata nell'analisi.
