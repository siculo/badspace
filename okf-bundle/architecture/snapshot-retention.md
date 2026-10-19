---
type: Architecture Primitive
title: Conservazione degli snapshot
description: Ogni partizione ha uno snapshot logico S(n) per ogni tick completato dalla creazione alla rimozione, con contenuto condiviso tra tick senza commit, e conserva acquisibili quelli degli ultimi k tick (max(T − k, C) ≤ n ≤ T − 1, k ≥ 1, default 1); i reader acquisiscono uno snapshot, lo leggono e lo rilasciano, con una scadenza obbligatoria, e uno snapshot uscito dalla finestra resta leggibile finché ha acquisizioni valide; la partizione rileva la fine di ogni snapshot.
tags: [badspace, architecture, partitioning, consistency]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-19T14:17:50Z }
---

# Primitiva

Lo snapshot logico **`S(n)`** è lo stato confermato da
[commit](/architecture/partition-commit.md) alla fine del
[tick](/architecture/logical-time.md) n. Ogni tick completato, dalla
creazione della partizione fino alla sua
[rimozione](/architecture/partition-removal.md), ha il suo snapshot:

- se durante il tick n c'è stato almeno un `commit()`, `S(n)` è lo stato
  dell'ultimo commit del tick;
- altrimenti `S(n)` è lo stesso stato dello snapshot precedente;
- non esistono snapshot per i tick prima di `creationTick`: il primo è
  `S(creationTick)`, e senza commit nel tick di creazione è lo stato
  iniziale vuoto. Vale anche per una partizione creata al tick 0: `S(0)`
  si acquisisce dopo `setTick(1)`, e non serve uno snapshot con tick
  negativo.

Con più commit nello stesso tick, `S(n)` contiene lo stato cumulativo
confermato dall’ultimo: gli effetti dei commit precedenti restano, salvo
successive modifiche o rimozioni. Gli stati intermedi non sono
acquisibili. Il writer sceglie se attendere il tick successivo dopo un
commit (vedi [Commit e tick](/architecture/partition-commit.md#commit-e-tick)).

Snapshot logici consecutivi uguali **condividono il contenuto**: non si
duplicano i dati. Ogni commit prepara già uno snapshot per
l'[isolamento delle letture](/decisions/read-isolation.md); gli indici
condividono i nodi non cambiati (copy-on-write) e gli slot costano una
copia per commit.

Uno snapshot diventa acquisibile solo quando il tick della partizione è
**maggiore** del suo: con tick corrente `T`, lo snapshot del tick
corrente non si acquisisce mai, e lo stato di un commit si vede solo
dopo l'avanzamento del tick.

## Finestra di conservazione

Alla creazione si sceglie **k**, il numero di tick consultabili: k ≥ 1,
default 1. Con tick corrente `T` e creazione `C`, la **finestra di
conservazione** è:

    max(T − k, C) ≤ n ≤ T − 1

Sono i soli snapshot che ammettono **nuove acquisizioni**; con k = 1 si
acquisisce solo `S(T − 1)`. Una richiesta per `n < T − k` è fuori dalla
finestra, una per `n ≥ T` non è ammessa. Un reader legge uno snapshot
solo attraverso un'acquisizione (vedi [Acquisizione e
rilascio](#acquisizione-e-rilascio)).

**Esempio.** Con `T = 110` e k = 5 si acquisiscono `S(105)`, `S(106)`,
`S(107)`, `S(108)` e `S(109)`.

## Salti di tick

Se la partizione passa direttamente dal tick a al tick b, con
b > a + 1, completa tutti i tick da a a b − 1:

- `S(a)` contiene lo stato confermato durante a, o quello precedente se
  in a non c'è stato commit;
- da `S(a + 1)` a `S(b − 1)` gli snapshot hanno lo stesso stato di
  `S(a)`, senza copie: basta che le acquisizioni ne rispettino la
  semantica;
- la finestra si calcola rispetto al nuovo tick b.

**Esempio.** Dopo `setTick(102)`, scritture e `commit()`, una chiamata
diretta a `setTick(105)` rende acquisibili `S(102)`, `S(103)` e `S(104)`,
con lo stesso contenuto.

# Perché sta nel livello base

Senza questa primitiva le [letture coerenti](/mechanisms/consistent-reads.md)
su più partizioni non si possono costruire sopra in modo efficiente.
Per leggere lo stato al tick N da un writer che è già oltre, le sole
strade sono conservare gli snapshot passati oppure limitare il ritardo
tra i writer con una barriera: non esiste una terza via.

Essendo configurabile per partizione, la pagano solo le partizioni che
ne hanno bisogno (per esempio quelle di confine tra zone contigue).

## Perché in tick

La prima forma misurava la finestra in commit pubblicati: k snapshot
passati oltre al corrente, con lo snapshot corrente subito acquisibile,
e un'acquisizione al tick N che cercava il commit più recente prima di
N. Misurare in tick rende l'acquisizione esatta, lega la memoria
conservata al tempo e non alla frequenza dei commit, e dà alla
pubblicazione un momento preciso (vedi [perché il tick sta nel livello
base](/architecture/logical-time.md#perché-il-tick-sta-nel-livello-base)).

# Acquisizione e rilascio

Un'**acquisizione** è il diritto di interrogare uno snapshot: un
diritto solo logico, con identità propria. Più acquisizioni dello
stesso snapshot, anche di reader diversi, sono indipendenti. Il
**rilascio** chiude una sola acquisizione: è idempotente, e dopo il
rilascio ogni nuova lettura attraverso quell'acquisizione fallisce. Il
rilascio non elimina per forza lo snapshot.

Ogni tick completato ha uno snapshot, quindi l'acquisizione chiede
direttamente un tick. Le selezioni sono due, entrambe **atomiche
rispetto all'avanzamento del tick**: la verifica e l'acquisizione sono
una sola operazione, quindi un `setTick` concorrente non può far
sparire lo snapshot tra le due.

| Selezione | Snapshot acquisito |
|---|---|
| Al tick n | `S(n)`, se `max(T − k, C) ≤ n ≤ T − 1` e la partizione esisteva a n (`n < removalTick`, se è ritirata) |
| Ultimo | `S(T − 1)`: fallisce prima del primo avanzamento dopo la creazione |

Il tick corrente della partizione resta leggibile come metadato, ma non
sostituisce l'acquisizione: leggere il tick e poi acquisire sono due
passi, e tra i due la finestra può cambiare.

## Perché acquisizione e rilascio

Una prima direzione era leggere al commit N senza tenere lo snapshot,
con errore fuori dalla finestra e senza rilascio esplicito. Non basta
per i reader che leggono a lungo lo stesso snapshot, come il
salvataggio di uno snapshot su disco per la
[persistenza](/decisions/persistence.md): con molti tick nel
frattempo, lo snapshot uscirebbe dalla finestra a metà del lavoro, e
una finestra abbastanza ampia per il caso più lento costerebbe memoria
a tutte le partizioni. Con l'acquisizione solo lo snapshot che serve
resta in memoria, e la finestra resta piccola. Il costo del rilascio
esplicito è che un reader che non rilascia trattiene memoria: per
questo ogni acquisizione ha una [scadenza](#scadenza). Un reader che
deve tenere uno snapshot più a lungo della scadenza massima ne fa una
copia, con una funzione ancora da progettare.

## Copia autonoma per la persistenza

Il primo blocco di [persistenza](/decisions/persistence.md) usa una
copia autonoma di ID, posizioni e metadati dello snapshot. Si acquisisce
lo snapshot, si completa la copia mentre l’acquisizione è valida e si
rilascia l’acquisizione; la scrittura usa poi solo la copia, senza
dipendere dalla scadenza o dalla vita dello snapshot originale. La
forma della funzione resta da progettare.

# Vita di uno snapshot

1. **Acquisibile**: i suoi tick sono nella finestra, o lo saranno
   all'avanzamento del tick.
2. **Pinnato**: è uscito dalla finestra, ma ha ancora acquisizioni
   valide o query già ammesse. Si legge attraverso quelle acquisizioni,
   ma non si può più acquisire: uno snapshot uscito dalla finestra non
   torna mai acquisibile.
3. **Eliminabile**: è fuori dalla finestra, senza acquisizioni valide
   né query in corso: nessuno lo sta usando e nessuno lo può più
   richiedere. Per la partizione lo snapshot non esiste più. La
   memoria la libera il GC nel prototipo, e un conteggio dei
   riferimenti o l'epoch-based reclamation nel [linguaggio
   finale](/decisions/language.md); eliminabile non vuol dire liberata
   subito.

Uno snapshot fisico può valere per più tick consecutivi: esce dalla
finestra quando ne esce l'ultimo dei suoi tick.

**Fine di uno snapshot.** Il passaggio a eliminabile è un momento
preciso, che la partizione rileva: succede una sola volta, nel primo
istante in cui lo snapshot è fuori dalla finestra e senza utilizzi.
Gli utilizzi sono le acquisizioni valide e le query ammesse: anche una
query in corso usa lo snapshot, quindi lo snapshot finisce alla fine
dell'ultima query, non al rilascio. Il passaggio lo fa l'evento che
toglie l'ultima delle due condizioni:

- l'**avanzamento del tick** che fa uscire lo snapshot dalla finestra,
  se non ha utilizzi;
- il **rilascio** o la **scadenza** dell'ultima acquisizione, con lo
  snapshot già fuori dalla finestra;
- la **fine dell'ultima query** ammessa, con lo snapshot fuori dalla
  finestra e senza acquisizioni valide.

Uno snapshot eliminabile non torna mai in uso. Per questo l'ammissione
di una query e l'acquisizione aggiungono un utilizzo solo se lo
snapshot non è già finito, in un'unica operazione atomica: altrimenti
un rilascio concorrente, o un avanzamento che fa uscire lo snapshot
dalla finestra, potrebbe farlo finire mentre lo si prende. Una query
che trova lo snapshot finito fallisce come per un'acquisizione
rilasciata; un'acquisizione che lo trova finito verifica di nuovo sulla
finestra pubblicata, e fallisce se il tick chiesto non c'è più.

**Ammissione delle query.** Una query verifica l'acquisizione e prende
un riferimento interno stabile allo snapshot in un'unica operazione
atomica. Una query ammessa prima del rilascio o della scadenza può
finire; le query nuove sono respinte. I riferimenti delle query in
corso si contano a parte dalle acquisizioni (o con una protezione
equivalente), e si liberano anche quando la query fallisce. I
risultati di una query finita sono valori copiati e restano validi
dopo il rilascio: è vietata una nuova interrogazione dello snapshot,
non l'uso dei dati già letti.

**Il writer non aspetta mai** query, acquisizioni o persistenza: i
reader lenti allungano solo la vita degli snapshot in memoria. Le
acquisizioni appartengono al [nodo](/architecture/layers.md#nodi), non
al processo del reader, e si indicano con un identificatore anche
attraverso gRPC.

# Scadenza

Ogni acquisizione ha una **scadenza** obbligatoria: una durata di tempo
reale misurata dal momento dell'acquisizione con il clock monotono
locale del nodo, non con l'ora di sistema né con il tick. Così un reader
che muore senza rilasciare non può trattenere uno snapshot per sempre, e
la [distruzione della
partizione](/architecture/partition-removal.md#distruzione) arriva
sempre.

- Se il reader non indica la durata, vale una **scadenza di default**.
- Una durata **zero o negativa è rifiutata**, e così una durata
  maggiore della **scadenza massima**. Chi deve tenere uno snapshot
  più a lungo, per esempio per un salvataggio molto lento, ne fa una
  copia invece di acquisirla.
- La scadenza è **definitiva**: non si rinnova, nemmeno con l'uso.
  Un'acquisizione nuova è possibile solo se lo snapshot è ancora nella
  finestra, e non riattiva quella scaduta.
- Né l'uscita dalla finestra né la rimozione della partizione anticipano
  la scadenza di un'acquisizione già fatta.
- Un'acquisizione scaduta si comporta come una rilasciata: le nuove
  letture falliscono, le query già ammesse finiscono e il loro
  risultato è valido, e un rilascio dopo la scadenza non fa niente.
- Il nodo elimina le acquisizioni scadute **da solo**, senza aspettare
  il reader che magari ha perso il collegamento: le cerca a ogni evento
  della partizione (acquisizione, rilascio, commit) e a ogni
  avanzamento del tick, che arriva a tutte le partizioni, anche a quelle
  ritirate. Il limite è che, se il software smette di far avanzare il
  tick, una partizione senza altri eventi tiene in memoria gli snapshot
  con acquisizioni scadute fino all'evento successivo; un tempo di
  recupero garantito richiederebbe una pulizia periodica, oggi non
  prevista.

La scadenza dell'acquisizione è diversa dalla scadenza di una chiamata
remota, che è fuori da questa primitiva, e dalla scadenza delle letture
nella [rimozione differita](/mechanisms/consistent-reads.md#alternativa-rimozione-differita).

# Rimozione della partizione

La rimozione della partizione ha un concetto proprio: [Rimozione della
partizione](/architecture/partition-removal.md). In breve: `remove()` è
l'ultima operazione del writer; la rimozione diventa effettiva al primo
avanzamento del tick, che fissa `removalTick`; gli snapshot dei tick
`n < removalTick` restano acquisibili finché sono nella finestra, e le
acquisizioni già fatte fino al rilascio o alla scadenza; la partizione
si distrugge quando `T − k ≥ removalTick` e i suoi snapshot non hanno
più utilizzi.

# Commit e tick

Il commit prepara lo snapshot del tick corrente, ma non lo pubblica:
lo pubblica l'**avanzamento del tick**, `setTick`, che chiude il tick
precedente, definisce gli snapshot dei tick intermedi, fa scorrere la
finestra e comincia il tick nuovo, in un'unica transizione atomica per
la partizione (vedi [tick logico](/architecture/logical-time.md)). Ogni
partizione completa la transizione per conto suo: non c'è atomicità tra
partizioni, nemmeno dello stesso nodo, e un reader distribuito deve
tollerare per un momento tick diversi.

# Nel prototipo

Il codice segue la semantica di questa pagina e quella della
[rimozione della partizione](/architecture/partition-removal.md).

Lo storage della partizione (`PartitionStorage2`/`PartitionStorage3`)
coordina writer, avanzamento del tick e reader, e tiene i protocolli
che coinvolgono più parti: il lock, la proprietà, lo stato della
rimozione, il catalogo, l'ultimo commit e lo snapshot in attesa, la
sequenza dei commit, l'ordine delle notifiche all'indice e il
ripristino dell'ultimo commit. I dettagli stanno in componenti che non
conoscono lo storage:

- gli **slot del writer** (`MutableSlots2`/`MutableSlots3`): array
  primitivi, tabella ID → slot, pagine cambiate e snapshot degli slot;
  l'indice li legge attraverso la sua vista, e lo storage passa a ogni
  modifica il commit che la conferma, perché la sequenza dei commit è
  una sola;
- le **acquisizioni** (`SnapshotAcquisitions2`/`SnapshotAcquisitions3`):
  mappa, numeri, scadenze e ammissione delle query, sullo snapshot e
  sul tick scelti dal chiamante;
- la **vita degli snapshot** (`SnapshotLifetime2`/`SnapshotLifetime3`),
  condivisa da storage e acquisizioni: il conteggio degli snapshot
  pubblicati non finiti e la restituzione dei buffer;
- il **pool dei buffer** (`CoordinateBufferPool`), comune a 2D e 3D
  (vedi [isolamento delle letture](/decisions/read-isolation.md#gli-slot)).

Gli slot lavorano sotto il lock della partizione, gli altri componenti
con le operazioni atomiche descritte sotto, e nessuno prende un lock
proprio. La fine dell'ultimo snapshot torna allo storage come
risultato del metodo che la provoca, e lo storage segna la distruzione
e lo restituisce al nodo, che toglie la partizione dalla sua mappa.
Ogni rimozione delle acquisizioni scadute può far finire l'ultimo
snapshot, quindi restituisce sempre l'esito: dopo ogni acquisizione e
ogni commit è il nodo a chiamarla, perché l'acquisizione appena fatta
può essere già scaduta e, dopo il commit, altri thread possono aver
preso la proprietà, rimosso la partizione e fatto avanzare il tick. Due
test lo verificano con un clock dei test che mette un avanzamento del
tick tra i passi dell'operazione, senza sleep.

Il writer e il thread che fa avanzare il tick lavorano insieme: un
`ReentrantLock` per partizione, nello storage, ordina scritture,
`commit()`, richiesta di rimozione e `setTick`. Dentro il lock stanno solo
le modifiche dello stato e la pubblicazione del catalogo; la fine degli
snapshot usciti dalla finestra e la pulizia delle acquisizioni scadute
avvengono dopo, con le operazioni atomiche di sempre. I reader non
prendono mai il lock, quindi il writer aspetta al più un avanzamento
del tick, mai un reader. Un test ferma un commit nell'indice, mentre
tiene il lock, e verifica che un `setTick` su un altro thread lo
aspetti e che il commit finisca nello snapshot del tick che si chiude;
senza il lock il test fallisce. Un altro test fa lavorare writer,
avanzamento del tick e reader su thread diversi, senza ordine tra
writer e tick: ogni snapshot letto è un commit intero, un tick più
recente non ha mai un commit più vecchio, l'ultimo commit viene
pubblicato e, rilasciate le acquisizioni e rimossa la partizione,
la distruzione verifica che non restino utilizzi delle query.

I writer di partizioni diverse dello stesso nodo lavorano su thread
diversi senza coordinarsi: lock e sequenza dei commit sono di ogni
partizione, e scritture e commit non prendono il lock del nodo, che
ordina solo `setTick` e la creazione delle partizioni. Un writer
aspetta quindi al più un avanzamento del tick della sua partizione. Un
test ferma il commit di una partizione nell'indice, con un `setTick`
che lo aspetta, e verifica che il writer di un'altra partizione scriva
e confermi senza aspettare. I test sanno che `setTick` aspetta dal lock
della partizione: lo storage riceve il lock alla creazione, e quello dei
test segnala quando un thread comincia ad aspettarlo, senza guardare lo
stato dei thread; un altro fa lavorare più writer, ognuno
sulla sua partizione, con avanzamento del tick e reader su altri
thread, e verifica che ogni snapshot sia un commit intero della sua
partizione, senza entità delle altre.

Ogni partizione ha un **catalogo degli snapshot** nel service: un
oggetto immutabile con il tick della partizione, il tick di creazione,
k (`retainedTicks`) e gli snapshot pubblicati della finestra, ognuno
con il primo tick per cui vale (due array paralleli). Lo snapshot di un
tick è quello con il primo tick più grande non maggiore del tick
chiesto, quindi i tick senza commit condividono lo snapshot precedente
senza copie. Il catalogo tiene gli stessi snapshot dello storage, senza
copie di slot o indici.

`commit()` prepara lo snapshot e lo tiene come **snapshot in attesa**,
che un secondo commit nello stesso tick sostituisce; ogni commit ha un
numero nella sequenza dei commit, che va a `commit(n)` degli indici.
Una partizione nuova ha come snapshot in attesa quello vuoto, come se
avesse un commit al tick di creazione. `setTick(t)` crea un catalogo
nuovo: aggiunge lo snapshot in attesa, se c'è, valido dal tick che si
chiude, porta il tick a t e lascia fuori gli snapshot i cui tick sono
tutti prima della finestra; poi lo pubblica con una sola scrittura, e
un reader vede sempre il tick e la finestra insieme. Lo snapshot in
attesa e la base dello snapshot successivo (l'ultimo commit) sono campi
del writer.

Le due selezioni (`acquire(tick)` e `acquireLatest`, cioè `S(T − 1)`)
leggono tick e snapshot da un solo catalogo pubblicato, quindi sono
atomiche rispetto all'avanzamento del tick. Se il tick non è nella
finestra, `SnapshotNotAvailableException` dice il motivo:
`NOT_YET_PUBLISHED`, `OUT_OF_RETENTION` o `BEFORE_CREATION`. Le acquisizioni le
tiene la partizione, nel componente delle acquisizioni, in una mappa
concorrente dal numero dell'acquisizione allo snapshot: uno snapshot uscito dalla finestra
resta raggiungibile da lì finché ha acquisizioni, e il rilascio toglie
solo la sua voce. L'ammissione di una query è la lettura della voce
nella mappa, e la query tiene il riferimento allo snapshot finché non
finisce. La memoria degli snapshot finiti la libera il GC (vedi [API da
esporre](/decisions/api-surface.md#acquisizione-degli-snapshot)).

Ogni snapshot ha un contatore atomico dei suoi utilizzi, acquisizioni
valide e query ammesse, con un bit per "fuori dalla finestra"; lo
stato "finito" è un valore a parte, da cui non si torna. Ogni
cambiamento è un `compareAndSet`, e solo la chiamata che porta lo
snapshot fuori dalla finestra e senza utilizzi lo fa finire.
L'acquisizione aggiunge un utilizzo solo se lo snapshot è ancora nella
finestra, prima di prendere un numero: se un avanzamento del tick l'ha
fatto uscire dopo la scelta, il nodo sceglie di nuovo dal catalogo
nuovo. L'ammissione controlla che il tick dell'identificatore sia
quello acquisito e aggiunge un utilizzo solo se lo snapshot non è
finito, e il nodo lo toglie alla fine della query in un `finally`,
anche quando la query fallisce. L'avanzamento del tick fa uscire gli
snapshot dopo aver pubblicato il catalogo nuovo, e conta come non finiti
solo gli snapshot pubblicati; rilascio e scadenza tolgono la voce dalla
mappa con un'operazione che riesce a un solo thread, e solo quel thread
toglie l'utilizzo. La vita degli snapshot conta gli snapshot
pubblicati non finiti: il punto in cui uno snapshot pubblicato finisce
è un solo metodo, che restituisce il buffer e poi decrementa quel
conteggio; lo snapshot sostituito prima della pubblicazione
restituisce solo il buffer, perché non è mai stato contato.

I test di concorrenza fermano una query a metà con un cancello
nell'indice, senza sleep: commit e avanzamenti del tick durante la
query non cambiano il suo risultato e il writer non aspetta la query;
un rilascio durante la query, anche con lo snapshot uscito dalla
finestra, la lascia finire con un risultato valido. Con più reader che
acquisiscono l'ultimo snapshot o quello del tick prima mentre il writer
fa commit e fa avanzare il tick, ogni snapshot letto ha slot e indice
dello stesso commit, e quelli tenuti restano leggibili dopo l'uscita
dalla finestra. Altri test verificano gli esempi di questa pagina
(T = 110 con k = 5, il salto da 102 a 105), lo snapshot del tick
corrente mai acquisibile, niente da acquisire prima del primo
avanzamento, due commit nello stesso tick, le scritture non confermate
che passano al tick dopo e il motivo di ogni acquisizione fallita.

Ogni voce della mappa è un'acquisizione con il suo snapshot, il momento
dell'acquisizione e la durata della scadenza, ed è lei a dire se è
scaduta, con la differenza tra due letture del clock, che non va in
overflow. L'ammissione legge la voce una sola volta e rifiuta anche una
voce scaduta ma non ancora eliminata. A ogni acquisizione, rilascio,
commit e avanzamento del tick lo storage scorre la mappa e toglie le
voci scadute; una lettura
non è un evento della partizione e non toglie niente. Il clock
monotono è un'interfaccia del service, `System.nanoTime()` di default,
che i test sostituiscono con un clock che avanza solo quando lo dicono:
scadenza prima della prima query, scadenza durante una query ammessa
(che finisce con un risultato valido anche se un'altra acquisizione
toglie la voce nel frattempo), rilascio dopo la scadenza, nuova
acquisizione distinta, scadenza di default e scadenza massima che
scadono anche loro.

I test della fine degli snapshot la verificano a ogni evento:
avanzamento del tick senza utilizzi (anche per uno snapshot valido per
più tick, che finisce quando esce il suo ultimo tick), rilascio
dell'ultima acquisizione, scadenza trovata da un commit, fine
dell'ultima query; uno snapshot nella finestra non
finisce al rilascio, una query tiene vivo uno snapshot rilasciato e
fuori dalla finestra, uno snapshot uscito dalla finestra non si
acquisisce più, e le query del nodo tolgono il loro utilizzo anche
quando falliscono. Un test di concorrenza fa correre acquisizioni e
avanzamenti del tick, e ammissioni e rilasci della stessa acquisizione: alla fine
restano solo gli snapshot della finestra, quindi ogni altro è finito una
e una sola volta.

La rimozione della partizione la fanno insieme storage e nodo (vedi
[Rimozione della partizione](/architecture/partition-removal.md#nel-prototipo)).
Uno snapshot finisce solo dopo essere stato pubblicato, e dal primo
avanzamento fino al ritiro l'ultimo snapshot è sempre nella finestra
(k ≥ 1), quindi il conteggio degli snapshot non finiti arriva a zero
solo dopo il ritiro, una sola volta. Lo storage non conosce il nodo:
avanzamento del tick, rilascio, fine di una query ed eliminazione delle
scadute restituiscono vero quando la partizione è distrutta, e il thread
che lo riceve la toglie dal nodo. Le letture e il rilascio trovano la
partizione per ID fino alla distruzione; dopo l'ID è sconosciuto, e il
nodo tratta l'acquisizione come rilasciata: la lettura fallisce con
`IllegalStateException` e il rilascio non fa niente. Gli ID delle
partizioni li crea il generatore di ID delle partizioni che lo spazio
riceve in `SpaceConfig`. I reader leggono gli snapshot solo attraverso
le acquisizioni; il nodo accede internamente al catalogo dello storage.
Non ci sono accessi diretti agli snapshot nel contratto del nodo.

Il client d'esempio `RandomUpdater` fa un commit e un avanzamento del tick a
ogni tick, e il reader mostra l'ultimo snapshot. Nell'API, due spazi con
gli stessi generatori danno ID di partizione diversi.

# Punti aperti

- Costo in memoria degli slot con k alto: una copia per commit nella
  finestra, da misurare.
- Scadenza massima: oggi è una costante; diventerà una configurazione
  del nodo.
- Copia di uno snapshot per chi deve tenerlo oltre la scadenza
  massima, da progettare.
- Pochi metadati diagnostici (acquisizioni attive, snapshot pinnati,
  tick pinnato più vecchio) solo se servono alla verifica.
- Pulizia periodica delle acquisizioni scadute, se servirà un tempo di
  recupero garantito.
- **Verifica e handle in un'unica operazione.** L'acquisizione al tick n
  verifica già tutte le condizioni e restituisce l'handle in un solo
  passo; manca il motivo del fallimento. Proposta: subito
  `SnapshotNotAvailableException` con un `reason()` (`NOT_YET_PUBLISHED`
  per n ≥ T, `OUT_OF_RETENTION` per n < T − k, `BEFORE_CREATION`,
  `AFTER_REMOVAL`); da valutare nelle [letture
  coerenti](/mechanisms/consistent-reads.md) una forma senza eccezione,
  per esempio `tryAcquire` con un risultato sealed (acquisito / non
  disponibile con il motivo), dove "fuori dalla finestra, riprova" è un
  caso normale.
- **`partitionExistsAt` e `canAcquireSnapshot`**: rimandate alle letture
  coerenti, e forse sostituite dalla forma sopra. `canAcquireSnapshot`
  sarebbe solo indicativa, perché tra la verifica e l'acquisizione il
  tick può avanzare. Senza tombstone delle partizioni, per un ID che il
  nodo non conosce più la risposta è "sconosciuto": non si distingue una
  partizione mai esistita da una distrutta.

# Correlati

- [Architettura minima](/architecture/minimal-core.md) — primitiva 5.
- [Tick logico](/architecture/logical-time.md) — il tick della
  partizione, che pubblica gli snapshot e fa scorrere la finestra.
- [Rimozione della partizione](/architecture/partition-removal.md) —
  letture storiche dopo la rimozione e distruzione.
- [Isolamento delle letture](/decisions/read-isolation.md#accesso-dei-reader)
  — pubblicazione degli snapshot e accesso controllato dei reader.
