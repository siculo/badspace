---
type: Design Decision
title: "Strutture transazionali, rollback e recupero"
description: "Contratto comune per slot, indici e metadati; commit tutto-o-niente, rollback esplicito, errori e recupero dello stato di lavoro, letture confermate riservate al writer e test di contratto."
tags: [badspace, transactions, snapshots, recovery, testing]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-30T14:51:33Z }
---

# Perimetro e obiettivo

Questa pagina descrive il refactoring e le estensioni da implementare
per rendere esplicita la partecipazione di slot, indici e metadati alla
transazione di una partizione. Le garanzie di comportamento sono la
direzione scelta; nomi e firme Java sono una proposta da verificare
nell'implementazione. La sequenza del lavoro è nel TODO del prototipo.

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

Nello storage 2D e 3D esiste già `discardUncommittedWrites()`, usato
al rilascio e al trasferimento della proprietà: ripristina direttamente
i metadati dall'ultimo commit e riporta entità e posizioni al contenuto
confermato attraverso operazioni che aggiornano anche l'indice.
`MutableMetadata.revert()` riporta la mappa persistente alla radice
confermata. Gli indici a celle e ad alberi conservano anche strutture
mutabili ausiliarie del writer, assenti dai loro snapshot.

Non sono ancora disponibili un rollback pubblico, il contratto comune
qui proposto, la gestione coordinata dei fallimenti con recupero
esplicito e le letture del pending tramite il writer. Il ripristino
interno esistente non dimostra che queste garanzie siano già offerte.

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

Ogni componente gestisce modifiche, preparazione dello snapshot e
ripristino del proprio stato, comprese le strutture ausiliarie. Lo
storage coordina i componenti e gestisce ownership, sequenza dei commit,
pending, pubblicazione e salute della partizione. I componenti non
devono conoscere il tick o i token dei writer.

# Contratto dei componenti

Una possibile forma comune, ancora da introdurre, è:

```java
interface TransactionalComponent<S> {
    S prepareCommit(long commitSequence);
    void acceptCommit(S prepared);
    void restore(S confirmed);
}
```

`S` rappresenta lo snapshot del componente. Le operazioni di modifica
rimangono specifiche della struttura. L'interfaccia esprime il ciclo
transazionale, senza imporre copie complete, strutture persistenti o
una particolare rappresentazione dei dati.

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
gestione va definita insieme al ciclo di vita degli snapshot.

## Ripristino e ordine

`restore()` riporta tutto lo stato di lavoro al contenuto dello snapshot
confermato, senza modificarlo. È ammessa una disposizione fisica diversa,
purché il contenuto e i riferimenti tra strutture siano coerenti.
L'operazione deve poter essere ritentata dalla stessa base anche dopo
un ripristino interrotto. Dopo il successo devono essere possibili
nuove scritture e nuovi commit.

Per le strutture attuali basta fissare l'ordine: **slot, indici,
metadati**, senza introdurre un sistema di dichiarazione delle dipendenze.
L'indice deve corrispondere agli slot effettivamente ripristinati,
comprese le mappe ausiliarie slot-cella o slot-foglia: assegnare solo
la vecchia radice non è sufficiente se cambia la numerazione degli slot.
La scansione lineare dipende direttamente dagli slot.

Si deve scegliere una sola modalità coerente: notifiche durante il
ripristino degli slot, come oggi, oppure ripristino esplicito dell'indice
dopo gli slot. Non eseguire due volte gli stessi aggiornamenti. La firma
comune va adattata solo quanto necessario a questo ordine concreto.

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

La suite comune deve essere eseguibile per ciascuna implementazione
dell'interfaccia. Il solo tipo generico `S` non permette di osservarne
la semantica: ogni struttura fornisce un adattatore di test per creare
il componente, applicare modifiche e confrontare contenuto dello stato
di lavoro e degli snapshot. I confronti sono semantici, non basati su
identità degli oggetti o disposizione fisica.

| Scenario comune | Garanzia da verificare |
|---|---|
| Scritture dopo preparazione e dopo commit | Snapshot preparati e confermati immutabili |
| Preparazione senza accettazione | Base confermata invariata |
| Accettazione seguita da altre scritture | Nuova base completa e protetta |
| Ripristino dopo inserimenti, modifiche e rimozioni | Contenuto equivalente alla base e strutture ausiliarie coerenti |
| Ripristino ripetuto e seguito da nuove scritture | Ripetibilità e piena utilizzabilità |
| Nuovo commit dopo ripristino | Nessuna ricomparsa delle scritture annullate |
| Commit vuoto e ripristino alla base iniziale | Contenuto corretto senza imporre copie nuove |

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
- Ripristino di slot, risultati delle query e metadati allo stesso
  contenuto confermato, anche con cambi di numerazione degli slot.
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
