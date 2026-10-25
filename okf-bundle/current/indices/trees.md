---
type: Spatial Index
title: "Alberi delle celle: quadtree e octree"
description: "Struttura e regole degli alberi già usati nella griglia di quadtree 2D e nella griglia di octree 3D."
tags: [badspace, documentation]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Ruolo nella griglia

La [griglia di quadtree](/current/indices/grid-quadtree.md) usa un albero
per ogni cella non vuota. In 2D un nodo si divide in quattro quadranti;
in 3D in otto ottanti. La radice coincide con la cella e ha confini fissi.
Le foglie contengono slot delle entità; i nodi interni contengono figli
e informazioni per attraversare o scartare il sottoalbero.

# Operazioni

Si scende dalla radice seguendo la posizione. Una foglia si divide se
supera la capacità; un sottoalbero torna foglia quando contiene al più
metà della capacità. L’isteresi evita continue divisioni e riunioni
quando poche entità oscillano su un confine.

Il movimento aggiorna solo il percorso necessario. Con il copy-on-write
non ci sono link al padre né riferimenti persistenti del writer alle
foglie: i nodi possono essere condivisi da snapshot diversi.

Una query range scarta i box esterni alla regione e prende interi
sottoalberi completamente interni; controlla i punti nelle foglie di
bordo. Il k-nearest visita i box più vicini con una coda di priorità.
A parità di distanza deve continuare a cercare per rispettare l’ordine
per ID dei risultati.

# Punti coincidenti

Se tutte le entità hanno la stessa posizione, ogni divisione le
manderebbe nello stesso figlio. La foglia viene invece segnata come
impilata e non si divide. Il segno si toglie se arriva una posizione
diversa. Restano anche il limite di 24 livelli e il controllo di
precisione dei punti medi. Queste regole impediscono ricorsioni inutili
o divisioni con box di larghezza zero.

Due punti quasi coincidenti possono richiedere circa `log2(S / d)`
livelli, con lato della cella S e distanza d, entro il limite di
profondità. Una query che comprende un gruppo di n punti coincidenti
deve comunque leggerli tutti; il k-nearest li esamina per selezionare i
k ID più piccoli a pari distanza.

# Posizione della radice

Ogni cella fissa una radice: la distanza dall’origine non introduce
livelli aggiuntivi nell’albero. Con `cellSize` potenza di 2 i confini
e i punti medi sono rappresentabili esattamente entro i limiti ammessi.

# Rappresentazione dei nodi

I nodi sono oggetti Java con il numero del commit di creazione. Una
modifica copia il percorso condiviso con snapshot precedenti. Un
esperimento con un’arena di array primitivi non ha dato guadagni e il
prototipo mantiene gli oggetti. Misure e dettagli sono nella
[griglia di quadtree](/current/indices/grid-quadtree.md#implementazione-nel-prototipo).
