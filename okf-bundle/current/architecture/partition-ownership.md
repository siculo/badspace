---
type: Architecture Primitive
title: Proprietà della partizione
description: "Proprietà single-writer con fencing token: passaggio, revoca e rilascio; recupero del writer applicativo e limite di visibilità dei commit nello stesso tick; uso con un writer per tick e tick dell'ultimo commit."
tags: [badspace, architecture, partitioning, concurrency]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-05T13:14:33Z }
---

# Primitiva

Ogni partizione ha **al più un writer** e più reader. La partizione non
sceglie chi scrive: offre una primitiva piccola, e lo schema con cui la
proprietà passa da un writer all'altro lo decide il software (vedi
[schemi costruiti sopra](#schemi-costruiti-sopra)).

Senza questa primitiva un writer rallentato che ha perso la proprietà
potrebbe ancora scrivere: per esempio un writer bloccato nel codice
applicativo, che riprende quando la partizione è già passata a un altro
writer. Con il **fencing token** è
la partizione a rifiutare le sue scritture, anche se lui non sa ancora
di aver perso la proprietà.

# Stato di proprietà

Ogni partizione ha uno stato di proprietà con due parti:

- la **generazione**, un contatore che cresce di 1 a **ogni** cambio di
  stato: presa della proprietà, passaggio a un altro writer, revoca,
  rilascio;
- il segno che dice se la partizione **ha un proprietario**.

La partizione nasce **senza proprietario**, alla generazione 0. Dato che
ogni cambio incrementa la generazione, una generazione identifica un
solo stato di proprietà.

Il **token di scrittura** (fencing token) è la coppia (ID della
partizione, generazione). Gli [ID delle
partizioni](/current/decisions/entity-ids.md#id-delle-partizioni) non si
riusano e la generazione cresce soltanto, quindi un token non torna mai
valido. L'ID vale solo nell'esecuzione: una partizione ricaricata da un
salvataggio ha un ID nuovo, quindi i token dell'esecuzione precedente
non valgono più.

# Operazioni

| Operazione | Effetto |
|---|---|
| `ownership(p)` | Legge lo stato: la generazione e se la partizione ha un proprietario. È solo una lettura: la partizione non avvisa nessuno dei cambi. |
| `claim(p, expectedGeneration)` | Se la generazione è ancora `expectedGeneration`, la porta a quella successiva con un proprietario e restituisce il token nuovo; altrimenti fallisce e lo stato non cambia. |
| `release(token)` | Se il token è quello corrente, porta la generazione a quella successiva senza proprietario; altrimenti non fa niente (idempotente). |
| `lastCommitTick(token)` | Con il token corrente, restituisce il tick dell'ultimo commit; la creazione e il caricamento contano come commit al tick di creazione (vedi [un writer per tick](#un-writer-per-tick)). |

`claim` è un **compare-and-set sulla generazione**: chi lo chiama dice
quale stato di proprietà vuole sostituire, e se nel frattempo lo stato
è cambiato la sua operazione fallisce invece di fare danni. È l'unica
operazione che dà la proprietà: prendere una partizione libera, passarla
a un altro writer e revocarla al proprietario sono la stessa chiamata, e
cambia solo chi la fa e quale generazione passa:

| Caso | Chi chiama | Generazione passata |
|---|---|---|
| Prendere una partizione libera | Il writer che la vuole | Quella dello stato libero, letta con `ownership(p)` |
| Passare la proprietà da A a B | A | La propria, dal suo token; A manda a B il token nuovo |
| Revocare la proprietà ad A | Un coordinatore | Quella attuale, letta con `ownership(p)` |

Non servono operazioni separate per questi casi: controlli come "solo se
la partizione è libera" o "solo il proprietario può passarla" sono
politiche, e chi chiama li fa già scegliendo la generazione. La
partizione non controlla chi chiama `claim` (vedi
[accesso](/current/decisions/tool-type.md#accesso)).

# Scritture con il token

Le scritture restano nell'handle del writer e arrivano alla partizione
solo con il commit (vedi [scritture accumulate](/current/architecture/partition-commit.md#scritture-accumulate-nel-writer)):
`commit()`, `rollback()` e `remove()` portano il token. La partizione
lo confronta con la generazione corrente **sotto il lock della
partizione**, lo stesso che ordina commit e avanzamento del
tick (vedi [concorrenza](/current/decisions/concurrency.md)): se il token non è
quello corrente, l'operazione è rifiutata con un errore dedicato e la
partizione non cambia.

Restano fuori dal fencing:

- l'avanzamento del [tick](/current/architecture/logical-time.md), che arriva
  dal software e non è un'operazione del writer;
- le letture, che passano tutte dalle
  [acquisizioni](/current/architecture/snapshot-retention.md#acquisizione-e-rilascio)
  degli snapshot: un writer, aggiornato o in ritardo, che acquisisce uno
  snapshot vede solo commit pubblicati, come ogni reader, e non vede mai
  le scritture non confermate di un altro writer.

# Cambio di proprietà

- **Il confine è il commit.** A ogni cambio di generazione (`claim` o
  `release`) le scritture non confermate si scartano: quelle del
  vecchio writer sono nel suo handle, e il commit che le porterebbe è
  rifiutato. Un commit fatto
  prima del cambio resta confermato, anche se lo snapshot del suo tick
  non è ancora pubblicato: il nuovo proprietario parte da quello stato,
  e i suoi commit nello stesso tick si aggiungono, con l'ultimo che
  definisce lo snapshot del tick (vedi [commit](/current/architecture/partition-commit.md#commit-e-tick)).
- **Rilascio senza commit.** Un writer può decidere di non confermare le
  sue scritture e rilasciare il token: le scritture si scartano.
- **Rilascio ordinato.** Se il lavoro è completo e valido, il writer fa
  commit prima di rilasciare o passare la proprietà, così il successore
  ne conserva gli effetti. Non c’è un commit automatico al rilascio:
  abbandonare un lavoro incompleto deve poter scartare le scritture.
- **Nessuna via di mezzo.** Grazie al lock, ogni operazione del vecchio
  writer avviene tutta prima del cambio oppure è rifiutata: il writer sa
  con esattezza quali dei suoi commit sono entrati.
- **Il vecchio writer lo scopre al prossimo commit**, o alla prossima
  operazione che va al nodo, che viene rifiutata: le scritture restano
  nel suo handle e non lo raggiungono (vedi [scritture
  accumulate](/current/architecture/partition-commit.md#scritture-accumulate-nel-writer));
  può anche controllare `ownership(p)`.
- **Rimozione.** `remove()` richiede il token corrente: chi vuole
  rimuovere una partizione di cui non è proprietario fa prima `claim`.
  Dalla [rimozione](/current/architecture/partition-removal.md) in poi `claim`
  è rifiutato.

## Visibilità nel tick del passaggio

Il nuovo proprietario eredita l’ultimo stato confermato, ma può leggere
solo gli snapshot già pubblicati. Se A fa commit nel tick T e la
proprietà passa a B nello stesso tick, le scritture di B si applicano
allo stato che comprende quel commit. L’ultimo snapshot acquisibile da B
è ancora `S(T − 1)`, se disponibile: `claim` non pubblica il commit di A
e non ne restituisce il contenuto. Il limite vale sia per il passaggio cooperativo sia per
la revoca forzata, anche dopo un rilascio preceduto da commit.

Per esempio, `S(T − 1)` contiene una posizione x = 0. A la porta a 1 e
fa commit; B prende la proprietà, legge ancora 0 e calcola un incremento
di 1. Scrivendo x = 1 conferma di nuovo quel valore, senza conoscere
l’incremento già confermato da A. Il sistema non somma gli incrementi:
riceve e applica due assegnazioni della posizione.

Con scritture indipendenti, che non richiedono di conoscere gli effetti
del predecessore, questo limite può non creare problemi. Se B deve
continuare o ripetere un’elaborazione che dipende da quegli effetti,
il software deve tenerne conto nel coordinamento. Un passaggio
cooperativo può comunicare le informazioni necessarie attraverso un
canale del software; la sola acquisizione del token non le fornisce.

La semantica corrente conserva i commit già completati: la revoca non
annulla i commit del tick corrente e non rende leggibile lo stato
confermato ancora in attesa di pubblicazione.

# Recupero di un writer bloccato

Il caso previsto è il blocco del **codice applicativo del writer**, fuori
da una chiamata a BADSPACE. Il coordinatore può chiamare `claim` senza
attendere una risposta o un rilascio del vecchio proprietario. Il cambio
conserva i commit completati; le scritture non confermate del vecchio
writer restano nel suo handle e, se riparte, il commit che le
porterebbe e le altre operazioni con il token precedente sono
rifiutati. Un blocco applicativo non rende da solo inconsistenti
i commit già completati.

La revoca non interrompe il thread del writer né una chiamata a BADSPACE
in corso. `claim` prende lo stesso lock dei commit e
dell’avanzamento del tick della partizione: un’operazione già in corso
deve terminarne la sezione protetta prima del cambio di proprietà.
Un blocco interno a BADSPACE che trattiene quel lock impedirebbe anche
la revoca. È un problema di correttezza e disponibilità dell’implementazione
da prevenire e correggere, distinto dal recupero del codice applicativo
su cui BADSPACE non ha controllo.

Il nuovo proprietario non sa se il vecchio ha fatto il commit del tick
prima di bloccarsi, e quel commit non lo può leggere fino all'avanzamento
del tick (vedi [visibilità nel tick del passaggio](#visibilità-nel-tick-del-passaggio)).
Dopo `claim` legge il **tick dell'ultimo commit** con il proprio token:
se è il tick corrente, le scritture del tick sono già confermate. Il
valore resta valido per il nuovo proprietario, perché da quel momento
solo i suoi commit lo cambiano e l'avanzamento del tick non lo tocca.
Per questo si legge con il token, e non prima di `claim`: letto prima,
un commit del vecchio writer potrebbe arrivare tra la lettura e la presa.

# Schemi costruiti sopra

| Schema | Come si realizza |
|---|---|
| **Owner fisso** | Il writer, per esempio il game loop, fa `claim(p, 0)` alla creazione e tiene il token per sempre. |
| **Cooperativo** | Il proprietario A chiama `claim` con la generazione del suo token e manda a B il token nuovo: in nessun momento ci sono due token validi. |
| **Coordinatore** | I writer non chiamano mai `claim`: il coordinatore legge `ownership(p)`, chiama `claim` e consegna il token al writer scelto. Con due coordinatori in corsa, per esempio durante un failover, vince al massimo uno per generazione. |
| **Coordinatore che aspetta il writer** | Il coordinatore chiede ad A di finire, con un canale del software; A fa l'ultimo commit e chiama `release`; il coordinatore vede con `ownership(p)` la partizione libera, oppure riceve la generazione da A, e chiama `claim`. In alternativa A restituisce il token al coordinatore, che chiama `claim` con la sua generazione: la partizione non resta mai senza proprietario. |
| **Presa forzata** | Se A non risponde, il coordinatore chiama `claim` con la generazione di A, senza il suo token: le scritture non confermate di A restano nel suo handle, e il suo commit e le altre operazioni successive sono rifiutati. Con l'attesa dello schema precedente dà un passaggio ordinato con una scadenza. |
| **Worker in competizione** | Un worker legge lo stato e, se la partizione è libera, chiama `claim` con quella generazione; se un altro l'ha presa prima, la generazione è cambiata e `claim` fallisce. |
| **Lease** | Heartbeat e scadenza stanno nel coordinatore, che revoca con `claim`: la partizione non ha bisogno di orologi, e la sicurezza viene dal fencing. |

# Un writer per tick

Gli schemi precedenti si combinano in un uso semplice, che BADSPACE
consiglia ma non impone. Il writer ha il solo compito di scrivere sulla
partizione: in un tick legge lo stato dagli snapshot pubblicati, calcola
le modifiche e le scrive, e il commit è l'**ultima operazione del tick**,
che dice che le scritture del tick sono andate a buon fine. Lo snapshot
che le contiene diventa leggibile, anche dal writer, all'avanzamento del
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

Il writer non ha bisogno di leggere quello che ha appena confermato: le
modifiche le ha calcolate lui, o i componenti del software a cui ha
delegato la logica, e il writer del tick successivo le trova nello
snapshot pubblicato. Più componenti possono calcolare le modifiche dello
stesso tick, ma uno solo le scrive; per questo non serve che più writer
si passino la partizione nello stesso tick. Più commit nello stesso tick
restano comunque possibili, e l'ultimo definisce lo snapshot del tick.

La partizione nuova, creata vuota o caricata da un salvataggio, ha un
commit al tick di creazione: il suo contenuto è lo snapshot
`S(creationTick)`, leggibile dall'avanzamento successivo. Il primo
writer che segue questo uso legge quello snapshot e scrive nel tick
dopo la creazione.

La [presa forzata](#schemi-costruiti-sopra) è il recupero: se un writer
si blocca, il suo commit del tick non arriva e il coordinatore passa la
proprietà a un nuovo writer. Il nuovo writer confronta il [tick
dell'ultimo commit](#recupero-di-un-writer-bloccato) con il tick
corrente: se sono uguali il tick è già confermato e aspetta il tick
successivo, altrimenti scrive il tick. Nel tick di creazione il valore è
già il tick corrente, anche senza un commit del writer: il tick è chiuso
dalla creazione, e il nuovo writer scrive dal tick successivo. Un commit
del writer nel tick di creazione, possibile ma fuori da questo uso,
lascia lo stesso valore.

# Uso

- Base della decisione sulla [concorrenza](/current/decisions/concurrency.md).
- La forma nell'API e nel contratto del nodo è in [API da
  esporre](/current/api/operations.md#proprietà-e-handle-del-writer).

# Nel prototipo

Lo stato di proprietà è nel nodo: i record `Ownership` (generazione,
posseduta) e `WriteToken` (ID della partizione, generazione ≥ 1) sono in
`common`, e il contratto del nodo ha `ownership(PartitionId)`,
`claim(PartitionId, long)`, che restituisce `Optional<WriteToken>`, e
`release(WriteToken)`. `commit`, che riceve anche il batch delle
scritture, `rollback` e le due operazioni di rimozione ricevono il
`WriteToken` al posto dell'ID della partizione.
Nell'API la partizione ha `ownership()`, `claim(long)` e
`writer(WriteToken)`, e l'handle `PartitionWriter2`/`PartitionWriter3`
porta il token nel commit, nel rollback, nella rimozione e nella lettura
del tick dell'ultimo commit, con `lastCommitTick(WriteToken)` nel
contratto del nodo e `lastCommitTick()` sull'handle, e `close()` lo
rilascia (vedi [proprietà e handle del
writer](/current/api/operations.md#proprietà-e-handle-del-writer)). Lo
spazio crea le partizioni senza proprietario.

- **Stato.** Lo storage della partizione tiene un `Ownership` in un
  campo `volatile`: `claim` e `release` lo sostituiscono sotto il lock
  della partizione, e `ownership(p)` lo legge da ogni thread senza
  lock, con generazione e proprietario sempre insieme.
- **Ripristino al cambio di proprietà.** A ogni cambio di generazione
  lo storage riporta lo stato del writer all'ultimo commit con il ripristino delle
  [strutture transazionali](/current/architecture/partition-commit.md#strutture-transazionali),
  nell'ordine slot, indice, metadati: gli slot tornano con la
  numerazione del commit e l'indice al proprio snapshot dello stesso
  commit, senza notifiche per le scritture scartate. Il commit
  successivo copia quello che è cambiato. Le scritture arrivano allo
  storage solo con il commit, quindi tra un commit e l'altro slot e
  indice sono già all'ultimo commit e non fanno niente: il ripristino
  conta per il recupero di una partizione che lo richiede. Se il ripristino
  fallisce la proprietà cambia comunque e la partizione richiede un
  recupero; su una partizione che lo richiede, `claim` e `release`
  ritentano il ripristino e, se riesce, la partizione torna sana (vedi
  [fallimenti e salute](/current/architecture/partition-failures.md)).
- **Controllo del token.** Ogni commit, rollback e richiesta di
  rimozione dello storage riceve la generazione e la confronta con lo
  stato di proprietà subito dopo aver preso il lock della partizione,
  insieme agli altri controlli dell'input: con una generazione che non
  è del proprietario attuale, o senza proprietario, fallisce con
  `OwnershipChangedException` (in `common`) e la partizione non cambia.
  Il nodo controlla prima lo stato della partizione, quindi dopo la
  richiesta di rimozione un'operazione del writer fallisce con
  `IllegalStateException` anche con un token vecchio.
- **Tick dell'ultimo commit.** Lo storage lo aggiorna all'accettazione
  di ogni commit; alla creazione e al caricamento vale il tick di
  creazione, anche quando il nodo lo fissa dopo aver costruito la
  partizione. Un commit fallito, un rollback e l'avanzamento del tick
  non lo cambiano. La lettura controlla il token sotto il lock della
  partizione, ed è ammessa anche quando la partizione richiede un
  recupero; il nodo rifiuta prima una partizione di cui è chiesta la
  rimozione.
- **Rimozione e distruzione.** Dalla richiesta di rimozione `claim`
  fallisce con `IllegalStateException` e `release` non fa niente;
  `ownership` risponde finché la partizione non è distrutta. Dopo la
  distruzione `ownership` e `claim` falliscono con
  `UnknownPartitionException`, come per un ID sconosciuto, e `release`
  non fa niente.

I test verificano la generazione iniziale, `claim` con la generazione
giusta, vecchia e futura, il passaggio con il vecchio token che non
vale più, `release` ripetuto e con un token non corrente, le scritture
non confermate scartate a `claim` e a `release` con i commit
precedenti confermati (anche prima del primo commit e nello stesso
tick), sequenze casuali di scritture scartate con lo stato e l'indice
uguali all'ultimo commit per la scansione lineare, la grid uniforme e
la griglia di quadtree, uno scarto con slot rinumerati che riporta
posizioni, risultati delle query e metadati al commit e poi accetta le
scritture del nuovo proprietario, la generazione negativa, la rimozione e la
partizione distrutta. Per il token verificano che scritture, commit e
rimozioni con un token vecchio o senza proprietario siano rifiutati
senza cambiare la partizione, e, con latch e barriere, senza sleep: un
commit in corso quando arriva `claim` o `release` resta confermato,
perché tutti e due aspettano il lock, e dopo il token vecchio è
rifiutato; di più `claim`
concorrenti sulla stessa generazione ne vince uno solo; un `release`
durante le scritture lascia esattamente l'ultimo commit finito;
`claim` e avanzamenti del tick concorrenti lasciano coerenti
generazione e tick. Uno snapshot acquisito prima del cambio di
proprietario mantiene contenuto e risultati delle query durante il
ripristino e i commit del nuovo proprietario, anche dopo essere uscito
dalla finestra, per `claim` e per `release` sui tre indici. Gli altri test del service prendono la proprietà
con un helper prima della prima scrittura. I test dell'API controllano
che l'handle passi il suo token in ogni operazione e che rifiuti il
token di un'altra partizione; i test di integrazione verificano la
partizione nuova senza proprietario, il passaggio cooperativo con il
vecchio handle rifiutato, la revoca da un coordinatore che perde le
scritture non confermate, `close()` dopo un'eccezione come rollback e
`close()` dopo la distruzione. Per il tick dell'ultimo commit i test,
in 2D e 3D, verificano il valore alla creazione e al caricamento, il
tick del commit tenuto dopo l'avanzamento, scritture e rollback che non
lo cambiano, il nuovo proprietario che sa se il writer fermato ha fatto
il commit del tick, il token non corrente e la partizione rimossa
rifiutati, il valore tenuto da un commit fallito e letto durante il
recupero necessario e, nell'integrazione, il nuovo writer dopo un
passaggio nello stesso tick. Con latch e senza sleep verificano anche
che la lettura aspetti un commit in corso e che, se un commit è in corso
quando arriva una presa forzata, il nuovo proprietario veda il tick
corrente come tick dell'ultimo commit.

# Correlati

- [Fallimenti e salute della partizione](/current/architecture/partition-failures.md) — il cambio di proprietà come tentativo di recupero.
- [Architettura minima](/current/architecture/minimal-core.md) — ruolo nel nucleo corrente.
- [Tipo di strumento](/current/decisions/tool-type.md#accesso) — il fencing
  protegge da errori e ritardi, non da chiamanti malevoli.
