---
type: Design Decision
title: "Strutture transazionali, rollback e recupero"
description: "Estensioni del contratto delle strutture transazionali: annullamento delle scritture dopo un fallimento, rollback esplicito, errori e recupero dello stato di lavoro, letture confermate riservate al writer e test di fallimento."
tags: [badspace, transactions, snapshots, recovery, testing]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-02T15:14:03Z }
---

# Perimetro e obiettivo

Questa pagina descrive le estensioni da implementare sopra il contratto
delle [strutture transazionali](/current/architecture/partition-commit.md#strutture-transazionali)
già presente: slot, indici e metadati preparano e accettano uno
snapshot a ogni commit, tutto o niente, e si ripristinano all'ultimo
commit in un ordine fisso. Le
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
accetta un commit solo dopo tutte le preparazioni; se una fallisce,
abbandona gli snapshot preparati, lascia invariati ultimo stato
confermato e pending e rilancia l'eccezione, con le scritture non
confermate ancora nello stato di lavoro. Ripristina le strutture
nell'ordine slot, indice, metadati in `discardUncommittedWrites()`,
usato al rilascio e al trasferimento della proprietà.

Non sono ancora disponibili l'annullamento automatico delle scritture
dopo un fallimento, un rollback pubblico, le segnalazioni strutturate,
il recupero esplicito e le letture del pending tramite il writer. Il
ripristino non è ancora provato con fallimenti durante il ripristino
stesso.

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

Lo storage gestisce già ownership, sequenza dei commit, pending e
pubblicazione; dovrà gestire anche la salute della partizione.

# Annullamento dopo un fallimento

Oggi un commit fallito lascia le scritture nello stato di lavoro. Il
passo successivo è annullarle: dopo il fallimento di una preparazione,
o di una modifica già iniziata, lo storage ripristina tutti i componenti
dall'ultimo stato confermato, inclusi quelli la cui preparazione non era
ancora iniziata, con lo stesso meccanismo del cambio di proprietà.
L'esito del ripristino decide l'utilizzabilità della partizione (vedi
[fallimenti e segnalazioni](#fallimenti-e-segnalazioni)).

# Rollback e recupero espliciti

Il rollback del writer scarta tutte le scritture dopo l'ultimo commit
riuscito. Riparte dal pending, se presente, altrimenti dall'ultimo stato
confermato precedente. Non modifica gli snapshot pubblicati né annulla
commit riusciti. Deve usare lo stesso meccanismo coordinato impiegato
per perdita o rilascio dell'ownership e annullamento dopo un errore.

Se lo stato di lavoro non è stato recuperato, il software può chiedere
un nuovo tentativo di ricostruzione dall'ultimo stato confermato. BADSPACE
offre il meccanismo; scelta del momento e politica dei tentativi spettano
al software. Il recupero torna a consentire scritture solo dopo il
successo di tutti i componenti. Un nuovo fallimento lascia la partizione
bloccata e viene segnalato.

Il ritorno a uno snapshot pubblicato più vecchio è un'estensione distinta:
potrebbe annullare anche commit riusciti e richiede una semantica dedicata.
Non è un requisito del primo passo. Il recupero qui descritto è in memoria,
distinto dal [recupero dei nodi](/future/architecture/partition-ownership.md)
dopo un crash e dal caricamento dei salvataggi.

# Fallimenti e segnalazioni

| Caso | Effetto sulla transazione | Utilizzabilità |
|---|---|---|
| Rifiuto prima delle modifiche, per esempio validazione o token scaduto | Stato invariato; nessun annullamento delle scritture precedenti | Secondo i normali vincoli di proprietà e ciclo di vita |
| Fallimento durante modifica o commit, ripristino riuscito | Tutte le scritture non confermate sono annullate | Stato di lavoro nuovamente utilizzabile |
| Fallimento con ripristino non riuscito | Stato di lavoro non affidabile, ultimo stato confermato preservato | Recupero necessario; scritture e commit rifiutati |

La condizione di recupero necessario è separata dagli stati del ciclo
di vita della partizione. Gli snapshot confermati rimasti integri
restano leggibili. Il cambio di tick non deve pubblicare lo stato di
lavoro danneggiato; può pubblicare un pending confermato e integro.

Il fallimento deve rendere disponibili in forma strutturata partizione,
operazione, componente se noto, esito, riferimento alla base confermata,
causa originale ed eventuale causa del ripristino fallito. Un solo tick
non distingue commit successivi nello stesso tick: la rappresentazione
diagnostica della base va definita senza esporre automaticamente il
contatore interno come numero pubblico dei commit.

La salute della partizione deve essere consultabile anche separatamente
dall'eccezione ricevuta dal thread chiamante. Nomi delle eccezioni,
risultati e metodi pubblici restano da definire nel contratto del nodo e
nell'API quando si implementa il concetto. Diagnosi e recupero restano
disponibili in stato di errore; le mutazioni richiedono un'autorizzazione
valida, senza consentire al vecchio writer di aggirare il fencing.

Il ripristino deve poter essere ritentato dalla stessa base anche dopo
un ripristino interrotto da un fallimento.

Bug e situazioni limite possono impedire anche il ripristino. In caso
di esaurimento della memoria non si promette una diagnosi ricca che
richieda nuove allocazioni: progettare un percorso minimo di blocco e
segnalazione, senza assumere la recuperabilità di ogni errore del processo.

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
pubblicati e acquisiti. In stato di recupero necessario anche le
eventuali letture del writer usano solo la base confermata integra.

# Test di coordinamento

La suite comune dei componenti e i test del commit tutto-o-niente sono
già presenti. I test dello storage dovranno verificare in 2D e 3D, usando
componenti controllabili per introdurre errori nei punti rilevanti:

- Fallimento nella preparazione di ciascun componente, compresi i
  metadati, che oggi non hanno un punto per introdurre l'errore: vecchia
  base integra e transazione interamente annullata.
- Fallimento nel ripristino di ciascun componente, anche dopo effetti
  parziali: stato consultabile, scritture e commit bloccati, diagnosi
  con entrambe le cause e successivo tentativo di recupero.
- Rifiuto di validazione distinto dal fallimento durante l'esecuzione;
  nel primo caso le scritture precedenti rimangono, nel secondo vengono
  annullate anche se iniziate in un tick precedente.
- Rollback esplicito, rilascio e trasferimento dell'ownership con la
  stessa semantica e conservazione dei commit già riusciti.
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
