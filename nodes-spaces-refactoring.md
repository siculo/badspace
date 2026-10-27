# Refactoring della relazione tra Space e nodi

## Stato e obiettivo

Questo documento descrive il refactoring concordato, ancora da implementare.
Raccoglie le decisioni adottate dopo la valutazione della proposta iniziale
e definisce il perimetro del lavoro da svolgere ora.

Ogni nodo appartiene a un solo Space. Lo Space crea i propri nodi tramite
factory e restituisce handle con cui il software sceglie dove creare o
caricare una partizione. Lo Space costruisce anche gli insiemi di snapshot,
verificando che tutte le partizioni gli appartengano.

Più Space possono vivere nello stesso processo con nodi distinti e tick
indipendenti. Un nodo non coincide con un processo o con una macchina.
Le [evoluzioni successive](okf-bundle/future/architecture/nodes-and-spaces.md)
riguardano servizi remoti con più nodi e integrazione tra Space. Gruppi e
sottogruppi saranno progettati [separatamente](okf-bundle/future/decisions/api-surface.md#gruppi-di-partizioni).

## Problema nell'implementazione corrente

`Space2` e `Space3` ricevono direttamente `PartitionNode2` e `PartitionNode3`.
Registrano il nodo alla prima creazione o al primo caricamento di una
partizione, portandolo al proprio tick. Il nodo propaga ogni avanzamento
a tutte le sue partizioni e non conosce lo Space che lo usa.

Se due Space condividono il nodo, l'avanzamento di uno può far avanzare le
partizioni dell'altro senza aggiornare il tick di quest'ultimo. Il rifiuto
dei tick decrescenti non impedisce questa interferenza. La correzione deve
rendere effettiva nell'API l'appartenenza esclusiva già richiesta dal contratto.

`SnapshotSet2.acquire` e `SnapshotSet3.acquire` accettano oggi liste di
partizioni senza verificarne lo Space. La nuova superficie pubblica deve
impedire di costruire questi insiemi con partizioni di Space differenti.

## Contratto da realizzare

### Creazione e identificazione dei nodi

- Lo Space espone la creazione del nodo tramite una factory e restituisce
  un handle. Nomi e firme definitivi si scelgono nell'implementazione.
- L'handle identifica una specifica istanza di nodo e conserva internamente
  l'appartenenza allo Space e il riferimento al contratto del nodo.
- Il software non può costruire liberamente gli handle né usarli per
  cambiare direttamente il tick del nodo. Non serve un ID o un nome pubblico
  del nodo: per la selezione nelle chiamate basta l'handle.
- La factory costruisce ogni volta un nodo nuovo, inizialmente vuoto e al
  tick iniziale previsto dal contratto, riservato allo Space chiamante.
  Restituire lo stesso nodo a più creazioni viola il contratto della factory:
  due handle distinti non isolano un'implementazione condivisa.
- Una stessa factory può essere riutilizzata solo se produce istanze
  indipendenti e configura correttamente le risorse di ciascuna.
- `createPartition`, `loadPartition`, `saves` e `deleteSaves`, inclusi gli
  overload, ricevono l'handle. Rifiutano un handle di un altro Space prima
  di generare ID, eseguire I/O o chiamare il nodo.
- L'appartenenza si verifica per identità dello Space, non per uguaglianza
  della configurazione, dei generatori o del tick.

Esempio indicativo della forma d'uso, non ancora compilabile:

```java
var space = new Space2(config);
var node = space.createNode(nodeFactory);
var partition = space.createPartition(node, name, partitionConfig);
try (var snapshots = space.acquire(List.of(partition), tick)) {
    var entities = snapshots.findInRegion(region);
}
```

Il contratto della factory può essere una piccola interfaccia funzionale o
una forma equivalente. Evitare una gerarchia di factory e configurazioni
per funzionalità remote ancora inesistenti. La factory locale riceve ciò
che serve oggi, per esempio la directory dei salvataggi.

### Dipendenze e responsabilità

Il modulo `api` continua a dipendere da `common`, senza dipendere da
`service`. Il codice applicativo può fornire una factory tramite lambda o
method reference verso il costruttore locale. Il contratto della factory
va collocato senza introdurre dipendenze circolari.

Gli handle dell'API e `PartitionNode2`/`PartitionNode3` hanno ruoli diversi:
i primi esprimono appartenenza e selezione, i secondi restano i contratti
interni delle operazioni. Non aggiungere un riferimento a `Space2`/`Space3`
allo storage o al modulo `common`. Non aggiungere `spaceId` a tutte le
operazioni: ogni nodo continua a gestire un solo Space.

Adattare `Space<N, P>` ai nuovi tipi pubblici e mantenere il parallelismo
2D/3D. I proxy delle partizioni conservano l'informazione necessaria alla
verifica di appartenenza; non serve un getter pubblico solo per i test.
Writer e snapshot acquisiti possono continuare a delegare al contratto
interno, mantenendo le garanzie esistenti.

### Registrazione, tick e concorrenza

Il nodo viene inizializzato al tick corrente e registrato quando lo Space
lo crea, anche se non ha ancora partizioni. Tutti i successivi `setTick`
lo raggiungono. La prima partizione non determina più l'associazione.

Inizializzazione e registrazione devono essere ordinate rispetto a
`setTick`: quando l'handle viene restituito, il nodo non deve aver perso
un avanzamento. Riutilizzare il lock dello Space. La costruzione della
factory può avvenire fuori dal lock, purché il nodo ancora privato sia
allineato al tick e registrato sotto lock prima della pubblicazione.
Se costruzione o inizializzazione falliscono, non pubblicare l'handle e
non lasciare il nodo nel registro; gestire le eventuali risorse allocate
secondo il contratto concreto della factory.

Conservare l'ordine tra creazione delle partizioni e avanzamento del tick.
Il caricamento deve continuare a leggere e ricostruire l'indice senza
bloccare gli avanzamenti dello Space: il nodo è già registrato e la
partizione nasce al tick del nodo quando il caricamento si conclude.

Mantenere monotonia, salti, idempotenza e propagazione non atomica tra
partizioni. Il refactoring non introduce una transazione distribuita del
tick. Per ora i nodi restano associati per la vita dello Space, anche vuoti:
nessuna API di distacco, riassegnazione o grace period.

### Insiemi di snapshot dello Space

Lo Space offre la creazione dell'insieme al tick richiesto, con opzioni di
acquisizione esplicite e predefinite. `SnapshotSet2`/`SnapshotSet3` restano
i risultati che espongono query, snapshot, fallimenti e rilascio.

Prima di acquisire qualsiasi snapshot, validare tutta la lista: nessun
nullo, nessuna partizione ripetuta e appartenenza allo Space chiamante.
Conservare i controlli su tick e opzioni. Una partizione estranea è un
errore di utilizzo, non un fallimento di disponibilità.

Rimuovere o rendere interno il percorso statico pubblico che consente di
aggirare la verifica dello Space. Conservare la lista vuota, gli esiti
parziali, i motivi di fallimento, il rilascio in caso di errore e la
semantica di range e k-nearest. Non introdurre deduplica, attese o retry.

L'appartenenza allo stesso Space garantisce il dominio temporale comune;
non prova che i riferimenti delle coordinate delle partizioni siano
confrontabili. La selezione resta responsabilità del software.

### Persistenza e identificatori

Ogni nodo persistente usa la propria directory, configurata dalla factory.
Creare il nodo permette di elencare i salvataggi anche prima di caricare
partizioni. L'elenco non deve creare un'associazione implicita aggiuntiva.
Rendere indipendenti le destinazioni dei nodi usati contemporaneamente;
nessuna cancellazione dei salvataggi alla fine della vita del nodo.

Conservare il formato dei salvataggi e il significato di nome e tick.
Il refactoring non introduce trasferimenti tra Space né nuovi vincoli
sul contenuto caricato rispetto a quelli già documentati.

Conservare `SpaceConfig`, il contratto dei generatori e l'univocità degli
ID di entità e partizioni. Non introdurre ID locali allo Space, chiavi
composte o schemi di generazione nuovi. L'handle del nodo non è un
identificatore persistente o di rete.

## Sequenza di lavoro

1. Definire handle e factory minimi, creazione e registrazione sullo Space,
   mantenendo le dipendenze dei moduli.
2. Adattare creazione e caricamento delle partizioni e gestione dei
   salvataggi agli handle; eliminare la registrazione implicita.
3. Spostare la costruzione pubblica degli insiemi di snapshot sullo Space
   e rendere obbligatoria la verifica di appartenenza.
4. Migrare test, esempi e chiamanti dell'API, inclusi eventuali benchmark
   che usano gli Space. I benchmark diretti del contratto interno del nodo
   possono continuare a usarlo direttamente.
5. Aggiornare contestualmente la documentazione corrente, dopo verifica
   del codice e dei test, e chiudere la relativa attività nel TODO.

## Verifiche di accettazione

Usare nodi reali nei test di integrazione e coprire 2D e 3D. Usare
sincronizzazione esplicita nei test concorrenti, senza basarsi su sleep.

- Due Space con nodi distinti, nello stesso processo e con gli stessi
  generatori, avanzano indipendentemente: tick, pubblicazione, conservazione
  e rimozione delle partizioni di B non cambiano quando avanza A.
- Un nodo creato dopo che lo Space è avanzato riceve quel tick; avanza
  anche senza partizioni e la sua prima partizione nasce al tick corretto.
- Creazione del nodo concorrente con `setTick`: nessun tick perso prima
  della pubblicazione dell'handle. Fallimento della factory o
  dell'inizializzazione: nessun nodo registrato utilizzabile per errore.
- Handle estraneo rifiutato per creazione, caricamento, elenco e
  cancellazione dei salvataggi, senza modificare partizioni o file.
- Insieme con una partizione estranea, anche dopo una valida nella lista:
  errore prima di qualsiasi acquisizione; nessuna risorsa da rilasciare.
- Conservare la copertura per lista vuota, duplicati, acquisizioni parziali,
  scadenze, rilascio ed errori; verificare i risultati delle query.
- Caricamento concorrente con avanzamento: conservare la garanzia di non
  bloccare il tick durante lettura e ricostruzione dell'indice.
- Nodi con directory distinte e partizioni omonime: salvataggi ed
  eliminazioni indipendenti; elenco possibile sul nodo ancora vuoto.

Eseguire i test pertinenti e la suite completa del prototipo dopo la
migrazione trasversale. I test del contratto interno non devono essere
riscritti per imporre restrizioni che appartengono soltanto all'API.

## Documentazione da aggiornare con l'implementazione

Aggiornare `current/architecture/layers.md`, `logical-time.md`,
`multi-partition-queries.md`, `current/api/components.md`, `operations.md`,
`current/decisions/persistence.md`, glossario, casi d'uso e indici interessati.
Adeguare Javadoc ed esempi; cercare i riferimenti a nodi creati esternamente,
registrazione alla prima partizione e `SnapshotSet*.acquire`.

Finché il refactoring non è implementato, `current/` continua a descrivere
il comportamento disponibile. Nel documento futuro su nodi e Space va
aggiornato solo il richiamo alla base adottata, lasciando in `future/`
hosting remoto, dismissione e operazioni tra Space.
