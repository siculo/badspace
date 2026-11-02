---
type: Design Decision
title: "Strutture transazionali, rollback e recupero"
description: "Estensioni delle strutture transazionali: letture confermate riservate al writer, ritorno a snapshot più vecchi e test di fallimento ancora da completare."
tags: [badspace, transactions, snapshots, recovery, testing]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-02T16:49:34Z }
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

Non sono ancora disponibili le letture del pending tramite il writer. I fallimenti dei
metadati e del ripristino degli slot non sono ancora provati, perché i
test non hanno un modo di provocarli.

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

# Letture confermate del writer

Il writer proprietario deve poter leggere l'ultimo stato confermato,
compreso il pending del tick corrente, attraverso il proprio handle.
Le scritture non confermate restano escluse. Quando non c'è pending,
la lettura usa l'ultima base confermata; prima del primo commit usa
lo stato iniziale vuoto.

Non serve un'acquisizione esplicita: ogni chiamata seleziona e protegge
lo stato per la sola durata dell'operazione. Non restituisce una vista
da conservare o rilasciare. Dopo un nuovo commit le chiamate successive
leggono il nuovo stato; non si garantisce una stessa versione tra più
chiamate. Slot, indice e metadati devono comunque appartenere allo
stesso commit durante ciascuna operazione.

Ogni chiamata verifica il token: dopo la perdita dell'ownership le nuove
letture del writer sono rifiutate, come le sue scritture. L'ammissione
della lettura deve essere ordinata rispetto al cambio di proprietà;
una lettura già ammessa può completarsi sullo stato protetto. Non si
introduce la revoca dei risultati già letti.

I reader ordinari continuano a leggere esclusivamente snapshot
pubblicati e acquisiti. In stato di [recupero necessario](/current/architecture/partition-failures.md#salute-della-partizione)
anche le letture del writer usano solo la base confermata integra.

# Test di coordinamento

La suite comune dei componenti, i test del commit tutto-o-niente e
quelli dei fallimenti con l'indice e il pool dei buffer sono già
presenti. I test dello storage dovranno verificare in 2D e 3D:

- Fallimento nella preparazione e nel ripristino dei metadati e nel
  ripristino degli slot, con un modo per introdurre l'errore in queste
  strutture: vecchia base integra, transazione annullata o recupero
  necessario, diagnosi e successivo tentativo di recupero.
- Letture del writer prima della pubblicazione, assenza di scritture
  non confermate nei risultati, nuovo commit tra due chiamate, token
  scaduto e lettura concorrente a commit, tick o cambio di proprietà.
- Risorse condivise protette durante le letture e reader ordinari
  limitati agli snapshot pubblicati.

Non provocare realmente un esaurimento della memoria per verificare
questi percorsi. Testare gli effetti osservabili del contratto, usando
latch o barriere per la concorrenza, senza sleep.

# Collegamenti

- [Isolamento delle letture corrente](/current/decisions/read-isolation.md).
- [Proprietà corrente della partizione](/current/architecture/partition-ownership.md).
- [Estensioni dell'API](/future/decisions/api-surface.md).
- [Approccio incrementale e test](/process/development-approach.md).
- [Analisi del design del prototipo](/future/analyses/prototype-design.md): contesto e alternative; le letture del writer qui definite non richiedono la vista da conservare ipotizzata nell'analisi.
