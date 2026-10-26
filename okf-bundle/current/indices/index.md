# Percorso di lettura

* [Scansione lineare](./linear-scan.md) - Indice senza struttura che legge tutte le entità della partizione a ogni query; è il riferimento per i test di correttezza e per i benchmark degli altri indici.
* [Grid uniforme](./uniform-grid.md) - Indice che divide lo spazio in celle quadrate (2D) o cubiche (3D) della stessa dimensione, tenute in una mappa; aggiornamenti economici, query veloci se la cella è adatta alla densità e alle query.
* [Alberi delle celle: quadtree e octree](./trees.md) - Struttura e regole degli alberi già usati nella griglia di quadtree 2D e nella griglia di octree 3D.
* [Griglia di quadtree](./grid-quadtree.md) - Indice a due livelli, una grid uniforme in cui ogni cella è la radice di un quadtree (2D) o di un octree (3D); unisce l’allocazione sparsa della grid con l'adattamento alla densità del quadtree.
* [Capacità degli aggiornamenti per tick](./update-capacity.md) - Quante entità per partizione si possono aggiornare in un tick, dati i tick al secondo, nel caso peggiore e in media; modello con il costo di un aggiornamento e di un cambio di foglia o di cella, e prima stima dalle misure.
* [Scenario del sistema solare](./solar-system-scenario.md) - Scenario di riferimento per dimensionare i benchmark degli indici, con posizioni fino a 200 UA in metri, entità fino a 1000 km/h e 30 tick al secondo; da qui il passo per tick, la dimensione minima delle celle e il limite di precisione dei double.
