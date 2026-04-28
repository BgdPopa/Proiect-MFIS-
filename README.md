# EvoMBT FIFO (MFIS)

Acest proiect este o variantă redusă pentru tema MFIS:

- păstrează doar nucleul EFSM minim necesar pentru modelare;
- elimină componentele legacy legate de scenarii externe;
- include modelul `FifoQueueModel` și runner-ul `FifoRunner`.

## Clarificare model EFSM

Modelul formal folosește explicit stările `EMPTY`, `PARTIAL`, `FULL`.
Acestea reprezintă intervale ale variabilei de context `currentSize`:

- `EMPTY` <=> `currentSize == 0`
- `PARTIAL` <=> `0 < currentSize < MAX_CAPACITY`
- `FULL` <=> `currentSize == MAX_CAPACITY`

Oracle-ul nu verifică doar capacitatea (`size`), ci și ordinea FIFO la operațiile de `dequeue`.

## Cerințe

- Java 11+
- Maven 3+

## Build

```bash
mvn clean compile
```

## Rulare demo FIFO

```bash
mvn -q -DskipTests exec:java -Dexec.mainClass=eu.fbk.iv4xr.mbt.fifo.FifoRunner
```

## Generare suita de teste + analiza rezultate

```bash
mvn -q -DskipTests exec:java -Dexec.mainClass=eu.fbk.iv4xr.mbt.fifo.FifoExperimentRunner
```

Rezultatele sunt scrise automat in folderul `results/`:
- un fisier CSV cu metrici pe fiecare rulare (inclusiv transition-pair coverage si detectia bug-ului);
- un fisier Markdown cu analiza agregata (medii, deviatie standard, convergenta si comparatie MBT vs baseline manual/random).
