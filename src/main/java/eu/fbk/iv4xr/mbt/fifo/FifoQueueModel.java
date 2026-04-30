package eu.fbk.iv4xr.mbt.fifo;

import eu.fbk.iv4xr.mbt.efsm.EFSM;
import eu.fbk.iv4xr.mbt.efsm.EFSMBuilder;
import eu.fbk.iv4xr.mbt.efsm.EFSMContext;
import eu.fbk.iv4xr.mbt.efsm.EFSMGuard;
import eu.fbk.iv4xr.mbt.efsm.EFSMOperation;
import eu.fbk.iv4xr.mbt.efsm.EFSMParameterGenerator;
import eu.fbk.iv4xr.mbt.efsm.EFSMState;
import eu.fbk.iv4xr.mbt.efsm.EFSMTransition;

public class FifoQueueModel {

    /**
     * Starile abstracte ale modelului EFSM.
     * Nu stocam elemente concrete, doar "zona" in care se afla dimensiunea cozii.
     */
    // Starile abstracte ale EFSM-ului, folosite in prezentare.
    public enum State { EMPTY, PARTIAL, FULL }
    private static final int MAX_CAPACITY = 5;

    /**
     * Contextul EFSM retine variabilele "de memorie" ale modelului.
     * Pentru acest proiect variabila relevanta este doar currentSize.
     */
    // Contextul retine doar marimea cozii; valorile concrete sunt abstractizate.
    public static class FifoContext extends EFSMContext {
        public int currentSize = 0;
        public final int MAX_CAPACITY = FifoQueueModel.MAX_CAPACITY;
    }

    /**
     * Construieste o tranzitie de tip PUSH (enqueue).
     * Guard-ul valideaza preconditia, iar operation aplica efectul pe context.
     */
    private EFSMTransition createPushTransition(String id) {
        EFSMTransition pushTransition = new EFSMTransition();
        pushTransition.setId(id);
        pushTransition.setGuard(new EFSMGuard() {
            @Override
            public boolean guard(EFSMContext ctx) {
                FifoContext c = (FifoContext) ctx;
                // PUSH este permis doar daca nu am atins capacitatea maxima.
                return c.currentSize < c.MAX_CAPACITY;
            }
        });
        pushTransition.setOp(new EFSMOperation() {
            @Override
            public boolean execute(EFSMContext ctx) {
                FifoContext c = (FifoContext) ctx;
                // Efectul abstract al operatiei enqueue.
                c.currentSize++;
                return true;
            }
        });
        return pushTransition;
    }

    /**
     * Construieste o tranzitie de tip POP (dequeue).
     * POP este valid doar cand coada nu este goala.
     */
    private EFSMTransition createPopTransition(String id) {
        EFSMTransition popTransition = new EFSMTransition();
        popTransition.setId(id);
        popTransition.setGuard(new EFSMGuard() {
            @Override
            public boolean guard(EFSMContext ctx) {
                FifoContext c = (FifoContext) ctx;
                // POP este permis doar daca exista cel putin un element.
                return c.currentSize > 0;
            }
        });
        popTransition.setOp(new EFSMOperation() {
            @Override
            public boolean execute(EFSMContext ctx) {
                FifoContext c = (FifoContext) ctx;
                // Efectul abstract al operatiei dequeue.
                c.currentSize--;
                return true;
            }
        });
        return popTransition;
    }

    /**
     * Creeaza graful EFSM pentru FIFO.
     * Tranzitiile sunt etichetate explicit pentru a fi usor de urmarit in rapoarte.
     */
    public EFSM buildModel() {
        FifoContext context = new FifoContext();
        EFSMBuilder builder = new EFSMBuilder(EFSM.class);

        // Legam explicit tranzitiile intre cele 3 stari EFSM.
        builder
            .withTransition(new EFSMState(State.EMPTY.name()), new EFSMState(State.PARTIAL.name()), createPushTransition("push_empty_to_partial"))
            .withTransition(new EFSMState(State.PARTIAL.name()), new EFSMState(State.PARTIAL.name()), createPushTransition("push_partial_to_partial"))
            .withTransition(new EFSMState(State.PARTIAL.name()), new EFSMState(State.FULL.name()), createPushTransition("push_partial_to_full"))
            .withTransition(new EFSMState(State.PARTIAL.name()), new EFSMState(State.PARTIAL.name()), createPopTransition("pop_partial_to_partial"))
            .withTransition(new EFSMState(State.PARTIAL.name()), new EFSMState(State.EMPTY.name()), createPopTransition("pop_partial_to_empty"))
            .withTransition(new EFSMState(State.FULL.name()), new EFSMState(State.PARTIAL.name()), createPopTransition("pop_full_to_partial"));

        // Starea initiala este EMPTY, cu currentSize = 0.
        return builder.build(new EFSMState(State.EMPTY.name()), context, (EFSMParameterGenerator) null);
    }

    /**
     * Expune capacitatea modelului pentru runner/oracle.
     */
    public int maxCapacity() {
        return MAX_CAPACITY;
    }
}
