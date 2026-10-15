---
type: Architecture Primitive
title: Conservazione delle versioni
description: Ogni partizione conserva acquisibili la versione corrente e quelle dei k commit precedenti (k + 1 versioni, k configurabile, default 0); i reader acquisiscono una versione, la leggono e la rilasciano, con una scadenza obbligatoria, e una versione uscita dalla finestra resta leggibile finché ha acquisizioni valide; la partizione rileva la fine di ogni versione, e la rimozione della partizione diventa effettiva quando sono finite tutte.
tags: [badspace, architecture, partitioning, consistency]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-15T14:00:00Z }
---

# Primitiva

Ogni partizione può conservare, oltre alla versione corrente, gli
stati dei **k [commit](/architecture/partition-commit.md) precedenti**,
con k configurabile per partizione e **zero come default**: le versioni
conservate sono quindi k + 1, e con k = 0 resta solo la versione
corrente. Contano solo i commit effettivamente pubblicati: i numeri di
commit saltati non creano versioni. Ogni commit pubblica già uno
snapshot per l'[isolamento delle letture](/decisions/read-isolation.md),
e conservare le versioni significa tenere vive le ultime k + 1 radici. Gli
indici condividono i nodi non cambiati (copy-on-write); gli slot
costano una copia per versione.

Le k + 1 versioni conservate formano la **finestra di conservazione**:
sono le sole che ammettono **nuove acquisizioni**. Un reader legge una
versione solo attraverso un'acquisizione (vedi [Acquisizione e
rilascio](#acquisizione-e-rilascio)).

# Perché sta nel livello base

Senza questa primitiva le [letture coerenti](/mechanisms/consistent-reads.md)
su più partizioni non si possono costruire sopra in modo efficiente.
Per leggere lo stato al tick N da un writer che è già oltre, le sole
strade sono conservare le versioni passate oppure limitare il ritardo
tra i writer con una barriera: non esiste una terza via.

Essendo configurabile per partizione, la pagano solo le partizioni che
ne hanno bisogno (per esempio quelle di confine tra zone contigue).

# Acquisizione e rilascio

Un'**acquisizione** è il diritto di interrogare una versione: un
diritto solo logico, con identità propria. Più acquisizioni della
stessa versione, anche di reader diversi, sono indipendenti. Il
**rilascio** chiude una sola acquisizione: è idempotente, e dopo il
rilascio ogni nuova lettura attraverso quell'acquisizione fallisce. Il
rilascio non elimina per forza la versione.

Una versione si sceglie in quattro modi, tutti **atomici rispetto al
commit**: la scelta e l'acquisizione sono una sola operazione, quindi
un commit concorrente non può far sparire la versione tra le due.

| Selezione | Versione acquisita |
|---|---|
| Ultima | La versione corrente, anche quella vuota del commit 0 |
| Precedente di `offset` | Con `offset` = 0 la corrente; con `offset` = i l'i-esimo commit pubblicato prima della corrente. Fallisce se la versione non è nella finestra |
| Al tick, o prima | La versione acquisibile con il commit più grande ≤ tick; fallisce se non c'è |
| Esatta | La versione del commit indicato; fallisce se quel commit non è acquisibile |

L'ultimo commit della partizione resta leggibile come metadato, ma non
sostituisce l'acquisizione: leggere il commit e poi acquisirlo sono due
passi, e tra i due la finestra può cambiare.

**Esempio.** Commit pubblicati 100, 103 e 107, con k = 2: l'ultima è
107, la precedente di 1 è 103, la precedente di 2 è 100, al tick 105 o
prima dà 103, l'esatta 105 fallisce. Dopo il commit 110 la versione 100
esce dalla finestra: non si può più acquisire, ma le acquisizioni già
fatte su 100 restano valide.

Con k = 0 si acquisisce solo la versione corrente, ma una versione
acquisita resta leggibile anche dopo i commit successivi.

## Perché acquisizione e rilascio

Una prima direzione era leggere al commit N senza tenere la versione,
con errore fuori dalla finestra e senza rilascio esplicito. Non basta
per i reader che leggono a lungo la stessa versione, come il
salvataggio di uno snapshot su disco per la
[persistenza](/decisions/persistence.md): con molti commit nel
frattempo, la versione uscirebbe dalla finestra a metà del lavoro, e
una finestra abbastanza ampia per il caso più lento costerebbe memoria
a tutti i commit. Con l'acquisizione solo la versione che serve resta
in memoria, e la finestra resta piccola. Il costo del rilascio
esplicito è che un reader che non rilascia trattiene memoria: per
questo ogni acquisizione ha una [scadenza](#scadenza). Un reader che
deve tenere una versione più a lungo della scadenza massima ne fa una
copia, con una funzione ancora da progettare.

# Vita di una versione

1. **Acquisibile**: è nella finestra delle ultime k + 1 versioni.
2. **Pinnata**: è uscita dalla finestra, ma ha ancora acquisizioni
   valide o query già ammesse. Si legge attraverso quelle acquisizioni,
   ma non si può più acquisire: una versione uscita dalla finestra non
   torna mai acquisibile.
3. **Eliminabile**: è fuori dalla finestra, senza acquisizioni valide
   né query in corso: nessuno la sta usando e nessuno la può più
   richiedere. Per la partizione la versione non esiste più. La
   memoria la libera il GC nel prototipo, e un conteggio dei
   riferimenti o l'epoch-based reclamation nel [linguaggio
   finale](/decisions/language.md); eliminabile non vuol dire liberata
   subito.

**Fine di una versione.** Il passaggio a eliminabile è un momento
preciso, che la partizione rileva: succede una sola volta, nel primo
istante in cui la versione è fuori dalla finestra e senza utilizzi.
Gli utilizzi sono le acquisizioni valide e le query ammesse: anche una
query in corso usa la versione, quindi la versione finisce alla fine
dell'ultima query, non al rilascio. Il passaggio lo fa l'evento che
toglie l'ultima delle due condizioni:

- il **commit** che fa uscire la versione dalla finestra, se non ha
  utilizzi;
- il **rilascio** o la **scadenza** dell'ultima acquisizione, con la
  versione già fuori dalla finestra;
- la **fine dell'ultima query** ammessa, con la versione fuori dalla
  finestra e senza acquisizioni valide;
- la **rimozione della partizione**, che chiude la finestra per tutte
  le versioni.

Una versione eliminabile non torna mai in uso. Per questo l'ammissione
di una query e l'acquisizione aggiungono un utilizzo solo se la
versione non è già finita, in un'unica operazione atomica: altrimenti
un rilascio concorrente, o un commit che fa uscire la versione dalla
finestra, potrebbe farla finire mentre la si prende. Una query che
trova la versione finita fallisce come per un'acquisizione rilasciata;
un'acquisizione che la trova finita sceglie di nuovo dalla finestra
pubblicata, e fallisce se la versione chiesta non c'è più.

**Ammissione delle query.** Una query verifica l'acquisizione e prende
un riferimento interno stabile alla versione in un'unica operazione
atomica. Una query ammessa prima del rilascio o della scadenza può
finire; le query nuove sono respinte. I riferimenti delle query in
corso si contano a parte dalle acquisizioni (o con una protezione
equivalente), e si liberano anche quando la query fallisce. I
risultati di una query finita sono valori copiati e restano validi
dopo il rilascio: è vietata una nuova interrogazione della versione,
non l'uso dei dati già letti.

**Il writer non aspetta mai** query, acquisizioni o persistenza: i
reader lenti allungano solo la vita delle versioni in memoria. Le
acquisizioni appartengono al [nodo](/architecture/layers.md#nodi), non
al processo del reader, e si indicano con un identificatore anche
attraverso gRPC.

# Scadenza

Ogni acquisizione ha una **scadenza** obbligatoria: una durata
misurata dal momento dell'acquisizione con il clock monotono locale del
nodo, non con l'ora di sistema né con il tick. Così un reader che muore
senza rilasciare non può trattenere una versione per sempre, e la
[rimozione della partizione](#rimozione-della-partizione) finisce
sempre.

- Se il reader non indica la durata, vale una **scadenza di default**.
- Una durata **zero o negativa è rifiutata**, e così una durata
  maggiore della **scadenza massima**. Chi deve tenere una versione
  più a lungo, per esempio per un salvataggio molto lento, ne fa una
  copia invece di acquisirla.
- La scadenza è **definitiva**: non si rinnova. Un'acquisizione nuova
  è possibile solo se la versione è ancora nella finestra, e non
  riattiva quella scaduta.
- Un'acquisizione scaduta si comporta come una rilasciata: le nuove
  letture falliscono, le query già ammesse finiscono e il loro
  risultato è valido, e un rilascio dopo la scadenza non fa niente.
- Il nodo elimina le acquisizioni scadute **da solo**, senza aspettare
  il reader che magari ha perso il collegamento: nella prima forma le
  cerca a ogni evento della partizione (acquisizione, rilascio,
  commit). Una partizione segnata come rimossa non ha più eventi suoi:
  le sue acquisizioni scadute il nodo le cerca a ogni evento del nodo,
  cioè a ogni evento di una qualunque delle sue partizioni, compresa la
  creazione e la rimozione. Il limite è che una partizione senza
  eventi, e un nodo senza eventi per le partizioni rimosse, tiene in
  memoria le versioni con acquisizioni scadute fino all'evento successivo; un
  tempo di recupero garantito richiederebbe una pulizia periodica, oggi
  non prevista.

La scadenza dell'acquisizione è diversa dalla scadenza di una chiamata
remota, che è fuori da questa primitiva, e dalla scadenza delle letture
nella [rimozione differita](/mechanisms/consistent-reads.md#alternativa-rimozione-differita).

# Rimozione della partizione

Dopo la rimozione della partizione non si possono più fare scritture
né nuove acquisizioni, ma **le acquisizioni già fatte restano
leggibili** fino al rilascio o alla scadenza, e le query già ammesse
finiscono. Una versione letta dopo la rimozione non è sbagliata: mostra
la partizione al suo commit, dati vecchi ma coerenti.

La rimozione avviene quindi in **due fasi**:

1. **Segno di rimozione.** `removePartition` e `dropPartition` segnano
   la partizione come rimossa: da quel momento niente scritture,
   commit né nuove acquisizioni. Il segno chiude la finestra, quindi
   le versioni senza utilizzi finiscono subito.
2. **Rimozione effettiva.** Il catalogo delle versioni sopravvive al
   segno finché la partizione ha versioni non ancora finite. Quando
   finisce l'ultima, il nodo toglie la partizione per davvero; senza
   acquisizioni né query in corso succede già nella prima fase.

Il momento giusto per controllare se una partizione segnata non ha più
versioni è la [fine di una versione](#vita-di-una-versione): il nodo non
deve cercarlo altrove. Gli [ID delle
partizioni](/decisions/entity-ids.md#id-delle-partizioni) **non si
riusano mai**: la partizione segnata resta sul nodo con il suo ID fino
alla rimozione effettiva, letture e rilascio la trovano per ID, e il
nodo rifiuta una nuova partizione con quell'ID. Così un'acquisizione
vecchia non finisce mai su un'altra partizione.

Una fase intermedia più semplice è **rifiutare la rimozione** finché la
partizione ha acquisizioni attive; non è però la semantica decisa, solo
un passo verso di essa.

# Commit e tick

La versione di un commit diventa acquisibile quando `commit(n)`
finisce, non a un istante preciso del tick: il nodo non promette che
sia visibile proprio all'inizio del tick successivo. Regole come
"leggere non prima di gTick − 1" e la barriera tra partizioni spettano
ai livelli superiori. Ogni partizione pubblica per conto suo, con il
suo ciclo di commit: non c'è atomicità tra partizioni.

# Nel prototipo

Ogni partizione ha un **catalogo delle versioni** nel service: un
oggetto immutabile con le versioni della finestra, dalla più vecchia
all'ultima, versione vuota del commit 0 compresa finché non esce dalla
finestra. Il catalogo tiene le stesse versioni che lo storage pubblica,
senza copie di slot o indici. A ogni commit il writer crea un catalogo
nuovo con la nuova versione, che fa uscire la più vecchia se la
finestra è piena, e lo pubblica con una sola scrittura: un reader vede
sempre l'ultima versione e la finestra dello stesso commit.

Le quattro selezioni scelgono la versione da un solo catalogo
pubblicato, quindi sono atomiche rispetto al commit. Le acquisizioni le
tiene lo storage della partizione in una mappa concorrente dal numero
dell'acquisizione alla versione: una versione uscita dalla finestra
resta raggiungibile da lì finché ha acquisizioni, e il rilascio toglie
solo la sua voce. L'ammissione di una query è la lettura della voce
nella mappa, e la query tiene il riferimento alla versione finché non
finisce. La memoria delle versioni finite la libera il GC (vedi [API da
esporre](/decisions/api-surface.md#acquisizione-delle-versioni)).

Ogni versione ha un contatore atomico dei suoi utilizzi, acquisizioni
valide e query ammesse, con un bit per "fuori dalla finestra"; lo
stato "finita" è un valore a parte, da cui non si torna. Ogni
cambiamento è un `compareAndSet`, e solo la chiamata che porta la
versione fuori dalla finestra e senza utilizzi la fa finire.
L'acquisizione aggiunge un utilizzo solo se la versione è ancora nella
finestra, prima di prendere un numero: se un commit l'ha fatta uscire
dopo la scelta, il nodo sceglie di nuovo dal catalogo nuovo, per tutte
e quattro le selezioni. L'ammissione aggiunge un utilizzo solo se la
versione non è finita, e il nodo lo toglie alla fine della query in un
`finally`, anche quando la query fallisce o l'identificatore ha il
commit sbagliato. Il commit fa uscire la versione più vecchia dopo aver
pubblicato il catalogo nuovo; rilascio e scadenza tolgono la voce dalla
mappa con un'operazione che riesce a un solo thread, e solo quel thread
toglie l'utilizzo. Lo storage conta le versioni non finite: il punto
in cui una versione finisce è un solo metodo, che decrementa quel
conteggio.

I test di concorrenza fermano una query a metà con un cancello
nell'indice, senza sleep: un commit durante la query non cambia il suo
risultato e il writer non aspetta la query; un rilascio durante la
query, anche con la versione uscita dalla finestra, la lascia finire
con un risultato valido. Con più reader che acquisiscono l'ultima
versione o quella prima mentre il writer fa commit, ogni versione letta
ha slot e indice dello stesso commit, e quelle tenute restano leggibili
dopo l'uscita dalla finestra.

Ogni voce della mappa è un'acquisizione con la sua versione, il momento
dell'acquisizione e la durata della scadenza, ed è lei a dire se è
scaduta, con la differenza tra due letture del clock, che non va in
overflow. L'ammissione legge la voce una sola volta e rifiuta anche una
voce scaduta ma non ancora eliminata. A ogni acquisizione, rilascio e
commit lo storage scorre la mappa e toglie le voci scadute; una lettura
non è un evento della partizione e non toglie niente. Il clock
monotono è un'interfaccia del service, `System.nanoTime()` di default,
che i test sostituiscono con un clock che avanza solo quando lo dicono:
scadenza prima della prima query, scadenza durante una query ammessa
(che finisce con un risultato valido anche se un'altra acquisizione
toglie la voce nel frattempo), rilascio dopo la scadenza, nuova
acquisizione distinta, scadenza di default e scadenza massima che
scadono anche loro.

I test della fine delle versioni la verificano a ogni evento: commit
senza utilizzi, rilascio dell'ultima acquisizione, scadenza trovata da
un commit, fine dell'ultima query; una versione nella finestra non
finisce al rilascio, una query tiene viva una versione rilasciata e
fuori dalla finestra, una versione uscita dalla finestra non si
acquisisce più, e le query del nodo tolgono il loro utilizzo anche
quando falliscono. Un test di concorrenza fa correre acquisizioni e
commit, e ammissioni e rilasci della stessa acquisizione: alla fine
restano solo le versioni della finestra, quindi ogni altra è finita una
e una sola volta.

La rimozione in due fasi la fanno insieme storage e nodo. Il segno è
un campo volatile dello storage, scritto dal writer prima di chiudere
la finestra: chiuderla fa uscire tutte le versioni del catalogo, e
quelle senza utilizzi finiscono subito. Un'acquisizione che trova la
versione fuori dalla finestra dopo il segno vede anche il segno, e
fallisce invece di scegliere di nuovo. Prima del segno la finestra ha
sempre una versione non finita, quindi il conteggio delle versioni non
finite arriva a zero solo dopo il segno, una sola volta. Lo storage non
conosce il nodo: segno, rilascio, fine di una query ed eliminazione
delle scadute restituiscono vero quando la partizione è finita, e il
thread che lo riceve la toglie dal nodo.

Il nodo tiene la partizione segnata nella stessa mappa, con il suo ID,
e in una seconda mappa delle partizioni segnate. A ogni suo evento
(creazione, rimozione, commit, acquisizione, rilascio) scorre la
seconda mappa e toglie le acquisizioni scadute. Le letture e il
rilascio trovano la partizione per ID anche dopo il segno; dopo la
rimozione effettiva l'ID è sconosciuto, e il nodo tratta
l'acquisizione come rilasciata: la lettura fallisce con
`IllegalStateException` e il rilascio non fa niente. Gli ID delle
partizioni li crea il generatore di ID delle partizioni che lo spazio
riceve in `SpaceConfig`. I reader non hanno altro modo di leggere una
versione: `lastVersion()` è solo un metodo interno dello storage.

I test verificano lo storage (il segno fa finire le versioni senza
utilizzi; la partizione finisce al rilascio, alla fine dell'ultima
query e alla scadenza trovata dopo il segno; gli eventi prima del segno
non la fanno mai finire) e il nodo: dopo il segno scritture, commit e
nuove acquisizioni falliscono e le acquisizioni già fatte restano
leggibili; la rimozione effettiva avviene subito senza acquisizioni, al
rilascio dell'ultima e alla scadenza trovata da un evento di un'altra
partizione (un reader che muore senza rilasciare); l'ID della
partizione segnata è rifiutato da `createPartition`; dopo la rimozione
il rilascio non fa niente. Un test di concorrenza fa correre
acquisizioni e letture con i commit e la rimozione: ogni lettura vede
un commit intero, e al rilascio dell'ultima acquisizione il nodo non ha
più la partizione. Nell'API, due spazi con gli stessi generatori danno
ID di partizione diversi.

# Punti aperti

- Costo in memoria degli slot con k alto: una copia per versione, da
  misurare.
- Scadenza massima: oggi è una costante; diventerà una configurazione
  del nodo.
- Copia di una versione per chi deve tenerla oltre la scadenza
  massima, da progettare.
- Pochi metadati diagnostici (acquisizioni attive, versioni pinnate,
  commit pinnato più vecchio) solo se servono alla verifica.
- Pulizia periodica delle acquisizioni scadute, se servirà un tempo di
  recupero garantito.

# Correlati

- [Architettura minima](/architecture/minimal-core.md) — primitiva 5.
- [Isolamento delle letture](/decisions/read-isolation.md#accesso-dei-reader)
  — pubblicazione delle versioni e accesso controllato dei reader.
