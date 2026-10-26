# Sviluppi futuri — lavoro sugli indici in pausa

* [Ottimizzazioni della scansione lineare](./linear-scan.md) - Test del box senza salti e aggiornamento del riferimento dei benchmark; attività in pausa.
* [Ottimizzazioni della grid uniforme](./uniform-grid.md) - Proposte per evitare scansioni inutili nelle query range della grid; attività in pausa.
* [Evoluzioni della griglia di quadtree](./grid-quadtree.md) - Margini loose, capacità delle foglie, compressione e ottimizzazioni del writer ancora da valutare; attività in pausa.
* [Quadtree e octree](./quadtree.md) - Indice che divide lo spazio in modo ricorsivo in 4 quadranti (2D) o 8 ottanti (3D) dove le entità sono fitte; si adatta alla densità, ma pone i problemi dei punti coincidenti e della posizione della radice.
* [R-tree](./r-tree.md) - Albero di bounding box di dimensione variabile, adatto a geometria statica ed entità con estensione; non ancora prototipato.
* [Modulo degli alberi](./tree-module.md) - Proposta per l'implementazione finale di un modulo specifico per gli alberi degli indici (quadtree/octree): arena con handle, topologia, liste di slot delle foglie e mappa inversa dentro il modulo, geometria fuori; snapshot copy-on-write per l'isolamento delle letture; handle generazionali in debug e regole per non usare handle scaduti nelle scritture.
* [Misure future della capacità per tick](./update-capacity.md) - Misure al variare del passo, benchmark a tick e regole di dimensionamento delle partizioni ancora da completare.
