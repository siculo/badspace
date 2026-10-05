---
type: Spatial Index
title: Quadtree e octree
description: Indice che divide lo spazio in modo ricorsivo in 4 quadranti (2D) o 8 ottanti (3D) dove le entità sono fitte; si adatta alla densità, ma pone i problemi dei punti coincidenti e della posizione della radice.
tags: [badspace, spatial-indexing, quadtree, octree]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-02T10:44:22Z }
---

# Come funziona

Si parte da un box radice che contiene tutte le entità. Quando un box ha
troppe entità si divide in 4 quadranti uguali (in 3D, 8 ottanti:
**octree**) e le entità passano ai figli; ogni figlio si può dividere a
sua volta. Le celle diventano piccole dove le entità sono fitte e
restano grandi dove lo spazio è vuoto: a differenza della [grid
uniforme](/indices/uniform-grid.md), il quadtree **si adatta alla
densità**.

La variante adatta a entità puntiformi che si muovono è il **PR
quadtree (point-region) con bucket**:

- i nodi interni hanno solo i figli e il loro box;
- le foglie tengono una lista di slot, fino a una **capacità** (per
  esempio 8–32);
- il box di un figlio si ricava dividendo a metà quello del padre, quindi
  non dipende dalle entità né dall'ordine di inserimento.

## Operazioni

- **Inserimento:** si scende fino alla foglia che contiene il punto. Se
  la foglia supera la capacità, si divide.
- **Rimozione:** si toglie lo slot dalla foglia. Quando i figli di un
  nodo hanno insieme poche entità, si **riuniscono** nel padre, con
  **isteresi** (per esempio dividere sopra 16 e riunire sotto 8): senza
  isteresi un'entità che oscilla su un confine fa dividere e riunire il
  nodo a ogni tick.
- **Aggiornamento (`moved`):** se il punto resta nel box della sua
  foglia, l'albero non cambia. Altrimenti si risale fino al primo
  antenato che contiene la nuova posizione e si riscende da lì: di
  solito sono pochi livelli, non tutto l'albero.
- **`relocated`:** una mappa slot → (foglia, posizione nella foglia)
  rende O(1) lo spostamento di slot fatto dallo storage, come nella grid.
- **Range query:** si scartano i nodi il cui box non interseca la
  regione; i nodi il cui box è **tutto dentro** la regione si prendono
  per intero, senza controllare i punti; si controllano i punti solo
  nelle foglie sul bordo.
- **K-nearest:** ricerca **best-first** con una coda di priorità di
  nodi, ordinati per distanza minima tra il punto e il box. Ci si ferma
  quando il prossimo nodo è più lontano del k-esimo candidato. Il
  contratto ordina per distanza e poi per ID, quindi un nodo a distanza
  **uguale** al k-esimo va ancora visitato (confronto `≥`, non `>`).

# Problematiche

## Punti coincidenti

Con più entità della capacità nello stesso punto, ogni divisione manda
tutte le entità nello stesso figlio, che si divide di nuovo: in teoria
la ricorsione non termina. In pratica la fermano i `double`: dopo circa
50 livelli il lato del box è sotto la precisione delle coordinate, `mid`
coincide con `min` o `max` e si creano figli di larghezza zero. Anche
quando si ferma, resta una **catena** di nodi con un solo figlio pieno e
gli altri vuoti.

Lo stesso vale per i punti **quasi coincidenti**: per separare due punti
a distanza `d` in una radice di lato `S` servono circa `log2(S / d)`
livelli.

| S (radice) | d (distanza) | livelli |
|---|---|---|
| 10 000 | 1 | ~13 |
| 10 000 | 0,001 | ~23 |
| 1 000 000 | 0,000001 | ~40 |

Non è solo un caso degenere: coordinate agganciate a una griglia (giochi
a tile), entità dentro un contenitore (passeggeri, oggetti in un
inventario a terra), spawn point e mucchi di oggetti creano gruppi di
decine o centinaia di entità nello stesso punto. Anche la distribuzione
`COINCIDENT` dei benchmark (1000 posizioni distinte) è di questo tipo.

Regole di arresto possibili:

- **Profondità massima** (o lato minimo del box): oltre il limite le
  foglie non si dividono e possono superare la capacità. È semplice, ma
  crea comunque la catena fino al limite.
- **Controllo prima di dividere:** se tutte le entità della foglia hanno
  la stessa posizione, la foglia si segna come non divisibile; se sono
  vicine ma distinte, si salta direttamente al livello in cui si
  separano (compressione). Il controllo non va ripetuto a ogni
  inserimento in una foglia piena, altrimenti inserire n entità
  coincidenti costa O(n²).
- **Limite di precisione:** si divide solo se `min < mid < max` con i
  `double`. Serve in ogni caso come protezione, ma da solo lascia catene
  di circa 50 livelli.

Il costo che resta è **inevitabile** per qualunque indice: una range
query che contiene il punto restituisce tutte le n entità; un k-nearest
che trova il gruppo deve guardarle tutte per scegliere i k ID più
piccoli tra entità alla stessa distanza (O(n log k)). La grid uniforme
ha lo stesso costo. Gli aggiornamenti invece restano economici.

## Posizione della radice

Il quadtree ha bisogno di una radice finita, mentre lo spazio delle
partizioni non ha limiti. Le opzioni:

- **Limiti fissi** scelti alla creazione: semplice, ma serve una regola
  per i punti fuori.
- **Radice centrata sui dati** (bounding box o baricentro delle prime
  entità): dipende dalla storia degli inserimenti, resta centrata male
  se il carico si sposta, i punti medi non sono esatti con i `double` e
  due partizioni con le stesse entità inserite in ordine diverso hanno
  alberi diversi. Da evitare.
- **Box allineati alle potenze di 2** con **radice che cresce**: si
  immagina un quadtree infinito e fisso, con box di lato `2^k` che
  partono da multipli di `2^k`. La radice è il più piccolo di questi box
  che contiene tutte le entità; quando arriva un'entità fuori, si sale al
  box padre, che è unico. I punti medi sono esatti e la struttura è
  deterministica. Con coordinate intere, i box allineati diventano
  operazioni sui bit.

Con i box allineati un cluster lontano dall'origine si trova sotto una
catena di circa `log2(distanza / dimensione del cluster)` livelli. Il
costo è additivo e logaritmico: lo pagano le query, che partono dalla
radice, non gli aggiornamenti, che risalgono solo fino all'antenato
comune. Un cluster **a cavallo di un confine alto**, per esempio intorno
all'origine, si divide subito in 4 e crea 4 catene.

## Compressione

Le catene sopra un cluster e quelle dei punti quasi coincidenti sono lo
stesso problema: nodi con un solo figlio non vuoto. Il **compressed
quadtree** non crea questi nodi: il padre punta direttamente al box più
piccolo che contiene le entità del ramo. La profondità diventa quella
utile, qualunque sia la posizione dei dati. Costa codice: all'inserimento
si calcola il più piccolo box allineato che contiene due punti (dal primo
bit in cui le coordinate differiscono) e la rimozione deve ricomprimere.

## Rappresentazione dei nodi

Nodi come oggetti Java (più semplici) o in array primitivi, come lo
storage delle partizioni (meno memoria e più veloci, codice più
complesso). Per il prototipo si parte dagli oggetti. Per l'implementazione
finale c'è la proposta del [modulo degli alberi](/indices/tree-module.md).

# Decisioni

- **Prese:** il quadtree con una sola radice è **rimandato**. Si
  implementa prima la [griglia di quadtree](/indices/grid-quadtree.md),
  che risolve la posizione della radice in modo più semplice.
- **Da prendere, se servirà:** limiti fissi o radice che cresce,
  compressione, regola di arresto. Il quadtree singolo ha senso se i
  benchmark mostrano che servono le cose che fa meglio della griglia di
  quadtree: range molto grandi e k-nearest su dati sparsi.

# Correlati

- [Griglia di quadtree](/indices/grid-quadtree.md) — la variante scelta
  per il prototipo.
- [Modulo degli alberi](/indices/tree-module.md) — proposta per
  l'implementazione finale dei nodi.
- [Tipo delle coordinate](/decisions/coordinate-type.md) — precisione
  dei `double` e alternativa con interi a 64 bit.
