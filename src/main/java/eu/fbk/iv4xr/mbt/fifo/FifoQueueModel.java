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

    public enum State { EMPTY, PARTIAL, FULL }
    private static final int MAX_CAPACITY = 5;

    public static class FifoContext extends EFSMContext {
        public int currentSize = 0;
        public final int MAX_CAPACITY = FifoQueueModel.MAX_CAPACITY;
    }

    private EFSMTransition createPushTransition(String id) {
        EFSMTransition pushTransition = new EFSMTransition();
        pushTransition.setId(id);
        pushTransition.setGuard(new EFSMGuard() {
            @Override
            public boolean guard(EFSMContext ctx) {
                FifoContext c = (FifoContext) ctx;
                return c.currentSize < c.MAX_CAPACITY;
            }
        });
        pushTransition.setOp(new EFSMOperation() {
            @Override
            public boolean execute(EFSMContext ctx) {
                FifoContext c = (FifoContext) ctx;
                c.currentSize++;
                return true;
            }
        });
        return pushTransition;
    }

    private EFSMTransition createPopTransition(String id) {
        EFSMTransition popTransition = new EFSMTransition();
        popTransition.setId(id);
        popTransition.setGuard(new EFSMGuard() {
            @Override
            public boolean guard(EFSMContext ctx) {
                FifoContext c = (FifoContext) ctx;
                return c.currentSize > 0;
            }
        });
        popTransition.setOp(new EFSMOperation() {
            @Override
            public boolean execute(EFSMContext ctx) {
                FifoContext c = (FifoContext) ctx;
                c.currentSize--;
                return true;
            }
        });
        return popTransition;
    }

    public EFSM buildModel() {
        FifoContext context = new FifoContext();
        EFSMBuilder builder = new EFSMBuilder(EFSM.class);

        builder
            .withTransition(new EFSMState(State.EMPTY.name()), new EFSMState(State.PARTIAL.name()), createPushTransition("push_empty_to_partial"))
            .withTransition(new EFSMState(State.PARTIAL.name()), new EFSMState(State.PARTIAL.name()), createPushTransition("push_partial_to_partial"))
            .withTransition(new EFSMState(State.PARTIAL.name()), new EFSMState(State.FULL.name()), createPushTransition("push_partial_to_full"))
            .withTransition(new EFSMState(State.PARTIAL.name()), new EFSMState(State.PARTIAL.name()), createPopTransition("pop_partial_to_partial"))
            .withTransition(new EFSMState(State.PARTIAL.name()), new EFSMState(State.EMPTY.name()), createPopTransition("pop_partial_to_empty"))
            .withTransition(new EFSMState(State.FULL.name()), new EFSMState(State.PARTIAL.name()), createPopTransition("pop_full_to_partial"));

        return builder.build(new EFSMState(State.EMPTY.name()), context, (EFSMParameterGenerator) null);
    }

    public int maxCapacity() {
        return MAX_CAPACITY;
    }
}
