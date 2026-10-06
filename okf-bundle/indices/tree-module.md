---
type: Design Proposal
title: Modulo degli alberi
description: Proposta per l'implementazione finale di un modulo specifico per gli alberi degli indici (quadtree/octree): arena con handle, topologia, liste di slot delle foglie e mappa inversa dentro il modulo, geometria fuori; versioni copy-on-write per l'isolamento delle letture; handle generazionali in debug e regole per non usare handle scaduti nelle scritture.
tags: [badspace, spatial-indexing, quadtree, octree, implementation, rust, c]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-06T07:42:53Z }
---

# Scopo

Gli alberi degli indici ([griglia di quadtree](/indices/grid-quadtree.md),
[quadtree e octree](/indices/quadtree.md)) sono navigabili in entrambe le
direzioni e hanno riferimenti dall'esterno verso le foglie: è la parte
più difficile da scrivere in Rust, per il borrow checker, e la più
rischiosa in C, per i puntatori pendenti (vedi il [confronto tra C e
Rust](/decisions/c-vs-rust.md#complessità-del-codice-delle-strutture-degli-indici)).

La proposta è un **modulo specifico**, scritto per questi alberi, invece
di una libreria generica: i requisiti sono troppo particolari per una
libreria di alberi qualunque, ma abbastanza chiusi per un modulo piccolo
e separato dal resto dell'indice. Riguarda l'implementazione finale, in
qualunque [linguaggio](/decisions/language.md); il prototipo Java resta
com'è. Non è una decisione.

Per l'[isolamento delle letture](/decisions/read-isolation.md) gli
alberi sono **strutture persistenti copy-on-write**: i reader leggono
una versione pubblicata, che non cambia più, mentre il writer prepara
la successiva. Questo vincolo tocca quasi tutti i requisiti (vedi
[Versioni copy-on-write](#versioni-copy-on-write)).

# Requisiti

## Forma dei nodi

- **Numero fisso di figli, 2^D** (4 in 2D, 8 in 3D), raggiunti per
  quadrante, creati tutti insieme nella divisione e tolti tutti insieme
  nella riunione.
- **Due tipi di nodo:** la foglia ha la lista degli slot (può superare
  la capacità, per le foglie impilate o al limite di profondità) e il
  segno di foglia impilata; il nodo interno ha i figli e il numero di
  entità del sottoalbero, per la riunione con isteresi.
- **Dati comuni:** box (minimo e lato) e profondità, tenuti nel nodo
  perché la risalita li usa.
- **Cambio di tipo sul posto:** una foglia diventa nodo interno e
  viceversa, con lo stesso handle, ma solo per un nodo creato o copiato
  nel commit corrente.
- **Commit di creazione** nel nodo, per sapere se il writer può
  modificarlo sul posto o deve copiarlo.
- **Nessun riferimento al genitore nel nodo:** un nodo non cambiato è
  condiviso tra più versioni, e in ognuna può avere un genitore
  diverso.

## Identità e riferimenti

- **Handle stabili:** validi finché il nodo esiste, senza compattazioni
  nascoste. Un nodo esiste finché lo usa almeno una versione viva; la
  copia di un nodo ha un handle nuovo.
- **Handle piccoli e copiabili**, idealmente 32 bit: l'indice ne tiene
  uno per slot (oggi `leafOf[]` con `placeOf[]`), quindi pesano sulla
  memoria per entità.
- **Handle scaduti rilevabili** almeno nei test (vedi [Handle
  generazionali](#handle-generazionali)).

## Navigazione e modifiche

- Figlio per quadrante in O(1). Il genitore non sta nel nodo: la
  risalita usa il percorso della discesa o una tabella dei genitori
  privata del writer (vedi [Scelte aperte](#scelte-aperte)).
- **Molti alberi**, una radice per cella, creati e distrutti con le
  celle, in un'unica arena per partizione.
- **Divisione** (2^D figli in un colpo, slot distribuiti) e **riunione**
  (sottoalbero intero liberato, slot raccolti nel nodo).
- **Contatori** aggiornati lungo il percorso verso la radice.
- **Nessuna allocazione per operazione a regime:** lista dei nodi
  liberi e riuso della memoria, anche per le copie dei nodi.

## Visite

- Visita in profondità con potatura e "tutti gli slot di un
  sottoalbero" per le query di range.
- Handle copiabili in una coda di priorità per il k-nearest best-first.
- Letture sicure da più thread su una versione pubblicata, con il
  modello [un writer e più reader](/decisions/concurrency.md), mentre
  il writer prepara la versione successiva.

## Versioni copy-on-write

- **Nodi pubblicati immutabili.** Il writer modifica sul posto solo i
  nodi del commit corrente; per gli altri il modulo crea una copia alla
  prima modifica, e copia anche il percorso fino alla radice, perché
  ogni antenato deve puntare al figlio nuovo. Un nodo si copia al più
  una volta per commit.
- **Mappa delle celle persistente:** la mappa cella → radice fa parte
  della versione e si copia solo dove cambia.
- **Commit:** chiude la versione transient (i nodi copiati diventano
  immutabili) e restituisce la nuova versione, cioè la mappa delle celle
  da mettere nella radice della partizione.
- **Arena che non si sposta:** i reader leggono i nodi mentre il writer
  ne aggiunge, quindi l'arena cresce a blocchi fissi e non si rialloca
  mai.
- **Liberazione differita:** un nodo sostituito da una copia, o tolto
  con una riunione o con la rimozione di un albero, torna nella lista
  dei nodi liberi solo quando nessuna versione viva lo usa (contatori
  dei reader o epoch-based reclamation).
- **Nella versione solo quello che leggono le query:** la mappa inversa
  e un'eventuale tabella dei genitori servono solo al writer, restano
  mutabili e fuori dalle versioni, e il modulo le aggiorna quando copia
  un nodo.

Una scrittura che cambia foglia costa in più la copia del percorso, che
cresce con la profondità, ma solo la prima volta nel commit: le
scritture successive sugli stessi nodi lavorano sulle copie.

## Estensioni previste

- **Radice che cresce:** aggiungere un genitore sopra una radice.
- **Compressione:** inserire un nodo intermedio tra padre e figlio; il
  box nel nodo è già un requisito.
- **Quadtree loose:** solo un margine nel box, nessun requisito nuovo.

## Cosa non serve

Collegamenti tra fratelli, figli in numero variabile o in ordine
arbitrario, staccare e riattaccare sottoalberi, un payload generico
uguale per tutti i nodi.

# Confine del modulo

- **Dentro:** la **topologia** e il **contenuto delle foglie**: arena,
  handle, lista dei nodi liberi, figli, tipi di nodo, liste di slot,
  **mappa inversa** slot → (foglia, posizione), contatori, mappa delle
  celle → radici, e le **versioni**: copia dei nodi, commit e
  liberazione differita.
- **Fuori, nell'indice:** la **geometria e le decisioni**: in quale
  quadrante va un punto, quando dividere, le regole di arresto, le query.

La mappa inversa dentro il modulo è il punto chiave: tutti gli
spostamenti di slot (cambio di foglia, divisione, riunione, rimozione
con swap, `relocated`) e le copie dei nodi restano interni e la
tengono coerente da soli.
Spariscono così quasi tutti i casi con due nodi mutabili insieme o con
riferimenti all'indietro da aggiornare, cioè i punti di attrito con il
borrow checker. L'attrito si paga una volta, in un modulo stimato in
qualche centinaio di righe; fuori il codice è sicuro e semplice.

In C la stessa separazione aiuta ancora di più: arena e mappa inversa
sono proprio il codice dove nascono i puntatori pendenti, e restano
chiuse in un modulo testato a parte.

# Interfaccia (schizzo in Rust)

```rust
pub struct NodeId(u32);

pub struct Forest<const N: usize, T> { /* nodes, free list, leaf slots, slot -> (leaf, place) */ }

pub struct Version { /* commit, cell -> root */ }

impl<const N: usize, T> Forest<N, T> {
    // trees
    pub fn new_root(&mut self, data: T) -> NodeId;
    pub fn remove_tree(&mut self, root: NodeId);

    // navigation, on the state the writer is building
    pub fn child(&self, n: NodeId, quadrant: usize) -> NodeId;
    pub fn is_leaf(&self, n: NodeId) -> bool;
    pub fn data(&self, n: NodeId) -> &T;          // box, depth, flags
    pub fn count(&self, n: NodeId) -> u32;        // entities in the subtree
    pub fn slots(&self, leaf: NodeId) -> &[u32];
    pub fn leaf_of(&self, slot: u32) -> NodeId;

    // changes
    pub fn add(&mut self, leaf: NodeId, slot: u32);
    pub fn remove(&mut self, slot: u32);
    pub fn move_to(&mut self, slot: u32, leaf: NodeId);
    pub fn relocated(&mut self, from: u32, to: u32);
    pub fn split(&mut self, leaf: NodeId, child_data: impl FnMut(usize) -> T,
                 quadrant_of: impl FnMut(u32) -> usize);
    pub fn merge(&mut self, n: NodeId);

    // versions
    pub fn commit(&mut self) -> Version;           // freezes the nodes of this commit
    pub fn release(&mut self, v: Version);         // frees the nodes no live version uses

    // tests
    pub fn check(&self);                           // all invariants
}

impl Version {
    // read only, safe from many threads
    pub fn root(&self, cell: CellKey) -> Option<NodeId>;
    pub fn child(&self, n: NodeId, quadrant: usize) -> NodeId;
    pub fn is_leaf(&self, n: NodeId) -> bool;
    pub fn data(&self, n: NodeId) -> &T;
    pub fn count(&self, n: NodeId) -> u32;
    pub fn slots(&self, leaf: NodeId) -> &[u32];
}
```

- L'indice lavora solo con handle e chiamate, senza riferimenti dentro
  il modulo: non vede mai il borrow checker.
- La geometria entra con le closure (`quadrant_of`, `child_data`).
- `N` (4 o 8) è un parametro costante semplice: non c'è il limite di
  `1 << D` dei [const generics](/decisions/c-vs-rust.md#astrazione-sulla-dimensione-2d3d).
- Lo stesso modulo serve la griglia di quadtree, il quadtree con una
  sola radice e la variante loose, che cambiano solo la geometria.
- I reader usano solo `Version`, che non ha metodi di scrittura.
- `check()` controlla tutte le invarianti (coerenza dei figli,
  contatori, mappa inversa, nessun nodo pubblicato modificato) e si presta a test con sequenze casuali di
  operazioni (in Rust `proptest`), con la [scansione
  lineare](/indices/linear-scan.md) come riferimento per le query.

# Handle generazionali

## Il problema

Un handle è un indice nell'arena. Quando un nodo si libera (in una
riunione o con la rimozione di un albero) il suo posto torna nella
lista dei nodi liberi e la creazione successiva lo riusa: un vecchio
handle punta allora a un **nodo diverso**, valido e coerente. Nessun
crash, solo dati sbagliati in silenzio: è il use-after-free del C
spostato dalla memoria alla logica. Il compilatore Rust non lo vede, e
in C nemmeno AddressSanitizer, perché la memoria resta valida.

Con l'arena un handle resta valido anche se l'array si rialloca: lo
invalida solo la **liberazione del nodo seguita dal riuso** del posto.

## Come funziona

Ogni posto dell'arena ha un contatore, la **generazione**, e l'handle
porta `(indice, generazione)`:

- **creazione:** l'handle prende la generazione attuale del posto;
- **liberazione:** la generazione del posto aumenta di 1;
- **accesso:** se la generazione dell'handle è diversa da quella del
  posto, l'handle è scaduto: errore subito, nel punto esatto.

| Passo | Posto 42 | Handle |
|---|---|---|
| nodo A creato | gen 7, nodo A | `(42, 7)` → A |
| nodo A liberato | gen 8, libero | `(42, 7)` scaduto |
| posto riusato | gen 8, nodo B | `(42, 8)` → B; `(42, 7)` scaduto |

## Costi

- **Memoria:** un contatore per posto e più bit per handle; pesa di più
  il secondo, perché gli handle stanno anche nella mappa per entità.
- **Tempo:** un confronto per accesso, molto prevedibile.
- **Bit:** con 32 bit, per esempio, 24 di indice (16 milioni di nodi) e
  8 di generazione, che torna da capo dopo 256 riusi; con 32 + 32 bit
  (come `slotmap`) il problema sparisce, ma la mappa per entità
  raddoppia.

## Proposta: solo in debug

Nelle build di test l'handle ha la generazione e ogni accesso la
controlla; in release l'handle è l'indice a 32 bit e il controllo
sparisce. Basta perché gli handle che restano fuori dal modulo sono di
breve durata (una coda best-first durante una query, una risalita
durante un movimento) e le query non liberano nodi: un handle scaduto
può nascere solo da un errore nel codice delle scritture, che i test
devono trovare.

In Java il problema non esiste con i nodi come oggetti (un riferimento
tiene in vita il vecchio nodo), ma torna con i nodi in array.

# Handle scaduti nelle scritture

Il rischio nasce solo quando una scrittura **tiene da parte un handle**
mentre un'altra parte della stessa scrittura cambia la struttura. I
casi concreti:

1. **Movimento con la rimozione prima della risalita:** si toglie lo
   slot dalla foglia, il padre si riunisce e la foglia viene liberata;
   la risalita parte poi da un handle scaduto.
2. **Riunioni rimandate alla fine di un batch:** nella lista dei
   candidati ci sono un nodo e un suo antenato; riunire prima
   l'antenato libera il nodo, che nella lista è scaduto.
3. **Handle calcolati in anticipo per un batch** (per esempio per
   ordinare gli aggiornamenti per foglia): le prime operazioni dividono
   o riuniscono nodi, e gli handle delle successive sono scaduti o
   superati.
4. **Cella che si svuota:** l'albero della cella viene liberato, ma la
   radice letta prima, o rimasta nella mappa delle celle, viene usata
   dopo.
5. **Handle di un nodo pubblicato:** un handle letto prima di una
   modifica punta al nodo della versione pubblicata, che il modulo ha
   poi copiato; una scrittura con quell'handle finirebbe sulla versione
   che i reader stanno leggendo. Il modulo rifiuta le scritture su nodi
   che non sono del commit corrente, almeno in debug.
6. **Divisione, che le generazioni non vedono:** dopo `split(leaf)` la
   foglia è un nodo interno con lo stesso handle; un `add(leaf, slot)`
   successivo mette lo slot in un nodo interno. Il nodo esiste, quindi
   la generazione non cambia: serve un controllo sul tipo.

Le regole che li evitano:

- **riunioni sempre per ultime**, e dall'alto verso il basso se
  rimandate;
- **nessun handle tenuto tra due operazioni** di un batch: si rilegge
  `leaf_of(slot)` ogni volta;
- **`split` restituisce la foglia di arrivo**, e le operazioni sulle
  foglie controllano il tipo del nodo, almeno in debug;
- **mappa delle celle aggiornata dal modulo** insieme all'arena
  (`remove_tree` toglie anche la voce);
- **copie dei nodi fatte dal modulo**, che rifiuta le scritture sui
  nodi pubblicati.

Con la mappa inversa dentro il modulo, le riunioni fatte dal modulo
alla fine di ogni operazione e le copie fatte dal modulo, i casi 1, 2,
4 e 5 si chiudono dentro il modulo; all'indice resta il caso 3, con la
regola di non tenere handle tra un'operazione e l'altra. Le generazioni in debug servono a trovare
in fretta gli errori a queste regole.

# Scelte aperte

- **Liste di slot delle foglie:** un `Vec` per foglia (un'allocazione
  per foglia) o un array fisso della capacità con un'estensione per le
  foglie che la superano (più compatto, codice più complesso).
- **Generazioni:** solo in debug, come proposto, o sempre attive se il
  software dovesse tenere handle tra un tick e l'altro.
- **Risalita senza genitore nel nodo:** percorso ricostruito scendendo
  dalla radice della cella con la posizione di partenza (O(profondità),
  nessuna tabella) o tabella dei genitori privata del writer, da
  aggiornare per i 2^D figli a ogni copia di un nodo interno.
- **Mappa inversa e copie delle foglie:** aggiornare le voci di tutti
  gli slot di una foglia quando la foglia si copia (al più una volta per
  commit), o tenere nella mappa solo la posizione nella foglia e
  trovare la foglia con la discesa.
- **Liberazione dei nodi:** contatori dei reader per versione o
  epoch-based reclamation, come per i buffer degli slot.
- **Contatori:** aggiornati dal modulo a ogni `add`/`remove`/`move_to`
  o lasciati all'indice.
- **Riunioni:** decise dal modulo in base al contatore e a una soglia,
  o dall'indice.

# Correlati

- [Confronto tra C e Rust](/decisions/c-vs-rust.md) — borrow checker,
  arena con indici e librerie per i mattoni di base.
- [Griglia di quadtree](/indices/grid-quadtree.md) e [Quadtree e
  octree](/indices/quadtree.md) — gli alberi che il modulo deve servire.
- [Linguaggio](/decisions/language.md) — la scelta tra C11, C++ e Rust
  per l'implementazione finale.
- [Isolamento delle letture](/decisions/read-isolation.md) — perché gli
  alberi sono strutture persistenti copy-on-write.
