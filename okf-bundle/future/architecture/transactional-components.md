---
type: Design Decision
title: "Strutture transazionali, rollback e recupero"
description: "Estensioni del contratto delle strutture transazionali: accettazione del commit tutto-o-niente, rollback esplicito, errori e recupero dello stato di lavoro, letture confermate riservate al writer e test di fallimento."
tags: [badspace, transactions, snapshots, recovery, testing]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-02T14:54:29Z }
---

# Perimetro e obiettivo

Questa pagina descrive le estensioni da implementare sopra il contratto
delle [strutture transazionali](/current/architecture/partition-commit.md#strutture-transazionali)
già presente: slot, indici e metadati preparano uno snapshot a ogni
commit e si ripristinano all'ultimo commit in un ordine fisso. Le
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
`prepareCommit(long)` e `restore(S)`. Lo storage 2D e 3D li ripristina
nell'ordine slot, indice, metadati in `discardUncommittedWrites()`, usato
al rilascio e al trasferimento della proprietà; una suite di test comune
verifica ogni struttura. La preparazione oggi conferma anche lo snapshot:
azzera i segni delle modifiche e lo rende la base del commit successivo.

Non sono ancora disponibili l'accettazione separata dalla preparazione,
un rollback pubblico, la gestione coordinata dei fallimenti con recupero
esplicito e le letture del pending tramite il writer. Il ripristino
esistente non è ancora provato con fallimenti durante il commit o
durante il ripristino stesso.

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

# Contratto dei componenti

L'estensione prevista separa la conferma dalla preparazione, con un
metodo in più:

```java
interface TransactionalComponent<S> {
    S prepareCommit(long commitSequence);
    void acceptCommit(S prepared);   // da aggiungere
    void restore(S confirmed);
}
```

## Preparazione e accettazione

`prepareCommit()` produce uno snapshot completo e immutabile dello
stato di lavoro. Tutti i componenti ricevono la stessa sequenza del
commit della partizione. La preparazione non sostituisce la base
confermata e non rende visibile il nuovo pending. Scritture successive
non devono alterare gli snapshot preparati né quelli confermati.

La preparazione può cambiare dettagli interni dello stato di lavoro,
per esempio congelando nodi copy-on-write. Se fallisce, l'ultimo stato
confermato deve comunque restare integro; lo storage ripristina tutti
i componenti, inclusi quelli la cui preparazione non era ancora iniziata.

Solo dopo tutte le preparazioni e la costruzione dello snapshot completo
lo storage accetta il commit. `acceptCommit()` deve essere un passaggio
minimo, senza allocazioni, validazioni tardive o fallimenti previsti.
Lo storage sostituisce ultimo stato confermato e pending solo al
completamento dell'accettazione, sotto il coordinamento della partizione.
Nessuna lettura può osservare una conferma parziale.

La vecchia base deve restare disponibile fino al completamento del
commit per consentire il ripristino in caso di fallimento. Le risorse
degli snapshot preparati e poi abbandonati devono essere rilasciate
senza liberare dati ancora condivisi. La forma concreta di questa
gestione va definita insieme al ciclo di vita degli snapshot. Oggi lo
storage restituisce il buffer delle coordinate del pending sostituito
prima di preparare gli slot, perché il nuovo commit lo possa riusare:
con l'accettazione separata il pending sostituito deve restare integro
fino all'accettazione.

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

# Test di contratto e coordinamento

La suite comune già presente, con un adattatore per ogni struttura, va
estesa agli scenari dell'accettazione:

| Scenario comune | Garanzia da verificare |
|---|---|
| Preparazione senza accettazione | Base confermata invariata |
| Accettazione seguita da altre scritture | Nuova base completa e protetta |

I test dello storage verificano il coordinamento in 2D e 3D, usando
componenti controllabili per introdurre errori nei punti rilevanti:

- Fallimento nella preparazione di ciascun componente: nessun pending
  parziale, vecchia base integra, transazione interamente annullata.
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
- Risorse condivise protette durante le letture, preparazioni abbandonate
  liberate e reader ordinari limitati agli snapshot pubblicati.

Non provocare realmente un esaurimento della memoria per verificare
questi percorsi. Testare gli effetti osservabili del contratto, usando
latch o barriere per la concorrenza, senza sleep.

# Collegamenti

- [Isolamento delle letture corrente](/current/decisions/read-isolation.md).
- [Proprietà corrente della partizione](/current/architecture/partition-ownership.md).
- [Estensioni dell'API](/future/decisions/api-surface.md).
- [Approccio incrementale e test](/process/development-approach.md).
- [Analisi del design del prototipo](/future/analyses/prototype-design.md): contesto e alternative; le letture del writer qui definite non richiedono la vista da conservare ipotizzata nell'analisi.
