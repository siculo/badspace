# Indici spaziali

* [Scansione lineare](./linear-scan.md) - indice senza struttura che legge tutte le entità della partizione a ogni query; è il riferimento per i test di correttezza e per i benchmark degli altri indici.
* [Grid uniforme](./uniform-grid.md) - celle quadrate (2D) o cubiche (3D) della stessa dimensione, tenute in una mappa; aggiornamenti economici, query veloci se la cella è adatta alla densità e alle query.
* [Griglia di quadtree](./grid-quadtree.md) - una grid uniforme in cui ogni cella è la radice di un quadtree (2D) o di un octree (3D); unisce lo spazio illimitato della grid con l'adattamento alla densità del quadtree.
* [Scenario del sistema solare](./solar-system-scenario.md) - scenario di riferimento per dimensionare i benchmark: posizioni fino a 200 UA in metri, entità fino a 1000 km/h, 30 tick al secondo; passo per tick, celle di almeno 32768 e precisione dei double.
* [Capacità degli aggiornamenti per tick](./update-capacity.md) - quante entità per partizione si possono aggiornare in un tick, dati i tick al secondo, nel caso peggiore e in media; modello con il costo di un aggiornamento e di un cambio di foglia o di cella.
* [Quadtree e octree](./quadtree.md) - divisione ricorsiva dello spazio dove le entità sono fitte; si adatta alla densità, ma pone i problemi dei punti coincidenti e della posizione della radice.
* [Modulo degli alberi](./tree-module.md) - proposta per l'implementazione finale: un modulo specifico per gli alberi degli indici, con arena, handle, liste di slot e mappa inversa dentro il modulo e geometria fuori; handle generazionali in debug e regole per non usare handle scaduti nelle scritture.
* [R-tree](./r-tree.md) - albero di bounding box di dimensione variabile, adatto a geometria statica ed entità con estensione; non ancora prototipato.
