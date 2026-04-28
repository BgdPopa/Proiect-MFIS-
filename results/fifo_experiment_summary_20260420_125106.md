# Analiza rezultate FIFO (EvoMBT-style)

## Configuratie experiment
- rulari: 5
- teste per rulare: 20
- pasi maximi per test: 15
- model: EFSM FIFO (stari EMPTY, PARTIAL, FULL)

## Rezultate agregate
- acoperire medie tranzitii: 100.00%
- acoperire medie stari: 100.00%
- pasi medii executati per rulare: 300.00
- tranzitii medii PUSH per rulare: 170.20
- tranzitii medii POP per rulare: 129.80

## Concluzie
Modelul FIFO este integrat in framework-ul curent si produce automat suite de teste abstracte.
Rezultatele includ acoperire pe stari/tranzitii si distributia actiunilor PUSH/POP.
