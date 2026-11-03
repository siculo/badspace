---
type: Design Decision
title: "Strutture transazionali, rollback e recupero"
description: "Modello d'uso del writer, commit nel tick corrente per il recupero di un writer bloccato, ritorno a snapshot più vecchi e test di fallimento ancora da completare."
tags: [badspace, transactions, snapshots, recovery, testing]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-03T09:02:26Z }
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

Chi prende la proprietà non può ancora sapere se nel tick corrente c'è
già un commit. I fallimenti dei metadati e del ripristino degli slot non
sono ancora provati, perché i test non hanno un modo di provocarli.

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

# Modello d'uso del writer

Il writer ha il solo compito di scrivere sulla partizione. In un tick
legge lo stato della partizione dagli snapshot pubblicati, calcola le
modifiche e le scrive; il commit è l'**ultima operazione del tick** e
dice che le scritture del tick sono andate a buon fine. Lo snapshot che
le contiene diventa leggibile, anche dal writer, all'avanzamento del
tick. Il writer può cambiare a ogni tick: prende la proprietà con
`claim`, fa le scritture e il commit e la lascia con `release`.

Il diagramma mostra due tick con un writer diverso in ciascuno (A nel
tick t, B nel tick t+1); le distanze non sono in scala.

```text
                        "tick t"                        "tick t+1"
          *--------------------------------*--------------------------------*-->
          ^ "setTick(t)"                   ^ "setTick(t+1)"                 ^ "setTick(t+2)"
          :                                :                                :
 A        : claim, legge "S(t-1)"          :                                :
          :    +----------------+          :                                :
          :    |   scritture    |*         :                                :
          :    +----------------+ commit,  :                                :
          :                       release  :                                :
 B        :                                : claim, legge "S(t)"            :
          :                                :    +----------------+          :
          :                                :    |   scritture    |*         :
          :                                :    +----------------+ commit,  :
          :                                :                       release  :
          :                                :                                :
 lettori  : "S(t-1)"                       : "S(t)"                         : "S(t+1)"
```

In questo modello il writer non ha bisogno di leggere quello che ha
appena confermato: le modifiche le ha calcolate lui, o i componenti del
software a cui ha delegato la logica, e il writer del tick successivo
le trova nello snapshot pubblicato. Più componenti possono calcolare le
modifiche dello stesso tick, ma uno solo le scrive; per questo non serve
che più writer si passino la partizione nello stesso tick.

Più commit nello stesso tick restano possibili, come oggi, e l'ultimo
definisce lo snapshot del tick: il modello è un uso consigliato, non un
vincolo della partizione. Un vincolo come il rifiuto delle scritture
dopo il commit fino all'avanzamento del tick si potrà aggiungere se
servirà.

## Presa forzata come recupero

La [presa forzata](/current/architecture/partition-ownership.md#schemi-costruiti-sopra)
serve al recupero: se un writer si blocca, il suo commit del tick non
arriva, e il coordinatore passa la proprietà a un nuovo writer. Il nuovo
writer però non sa se il vecchio ha fatto il commit prima di bloccarsi:
se l'ha fatto, il suo stato di lavoro comprende già quel commit, che non
può leggere finché il tick non avanza (vedi [visibilità nel tick del
passaggio](/current/architecture/partition-ownership.md#visibilità-nel-tick-del-passaggio)),
e rifare le scritture del tick darebbe rifiuti o modifiche applicate due
volte.

Al nuovo writer non servono i dati del commit, ma solo sapere **se nel
tick corrente c'è già un commit**: se c'è, le scritture del tick sono
confermate e il writer aspetta il tick successivo; se non c'è, scrive il
tick. Lo storage conosce già il tick dell'ultimo commit, usato come base
nelle [segnalazioni dei fallimenti](/current/architecture/partition-failures.md#segnalazione).
Dove esporlo, per esempio nello stato di proprietà o in un'operazione
dedicata, si decide nell'implementazione.

## Inizio del tick

Il writer oggi non sa quando inizia un tick: lo sa il software che chiama
l'avanzamento del tick, che può avvisare i writer con un proprio canale.
Un'attesa dell'inizio di un tick nell'API, per esempio fino alla
pubblicazione di un tick dato, è un'estensione da valutare.

## Letture del pending tramite il writer

Nel modello descritto il writer non legge il pending. Le letture del
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

# Test di coordinamento

La suite comune dei componenti, i test del commit tutto-o-niente e
quelli dei fallimenti con l'indice e il pool dei buffer sono già
presenti. I test dello storage dovranno verificare in 2D e 3D:

- Fallimento nella preparazione e nel ripristino dei metadati e nel
  ripristino degli slot, con un modo per introdurre l'errore in queste
  strutture: vecchia base integra, transazione annullata o recupero
  necessario, diagnosi e successivo tentativo di recupero.
- Commit nel tick corrente visto da chi prende la proprietà: assente
  prima del commit, presente dopo, anche dopo un fallimento o un
  rollback che lasciano il commit, e di nuovo assente dopo l'avanzamento
  del tick.
- Operazioni concorrenti del writer, dell'avanzamento del tick e del
  cambio di proprietà, con i reader limitati agli snapshot pubblicati.

Non provocare realmente un esaurimento della memoria per verificare
questi percorsi. Testare gli effetti osservabili del contratto, usando
latch o barriere per la concorrenza, senza sleep.

# Collegamenti

- [Isolamento delle letture corrente](/current/decisions/read-isolation.md).
- [Proprietà corrente della partizione](/current/architecture/partition-ownership.md).
- [Estensioni dell'API](/future/decisions/api-surface.md).
- [Approccio incrementale e test](/process/development-approach.md).
- [Analisi del design del prototipo](/future/analyses/prototype-design.md): contesto e alternative; il modello d'uso del writer qui descritto non richiede la vista dello stato ereditato ipotizzata nell'analisi.
