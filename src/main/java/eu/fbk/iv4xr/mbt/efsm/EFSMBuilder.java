package eu.fbk.iv4xr.mbt.efsm;

import java.util.Arrays;
import java.util.Set;

import org.jgrapht.Graphs;
import org.jgrapht.graph.DirectedPseudograph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

public class EFSMBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(EFSMBuilder.class);
    protected final DirectedPseudograph<EFSMState, EFSMTransition> base;

    public EFSMBuilder(Class<EFSM> efsmTypeClass) {
        this.base = new DirectedPseudograph<>(EFSMTransition.class);
    }

    public EFSMBuilder(Class<EFSM> efsmTypeClass, EFSM base) {
        this.base = base.getBaseGraph();
    }

    public EFSMBuilder withEFSM(EFSM s) {
        Preconditions.checkNotNull(s);
        Graphs.addGraph(base, s.getBaseGraph());
        return this;
    }

    public EFSMBuilder withState(EFSMState... s) {
        Preconditions.checkNotNull(s);
        Graphs.addAllVertices(base, Arrays.asList(s));
        return this;
    }

    public EFSMBuilder withTransition(EFSMState src, EFSMState tgt, EFSMTransition t) {
        t.setSrc(src);
        t.setTgt(tgt);
        if (!base.containsEdge(t)) {
            base.addVertex(src);
            base.addVertex(tgt);
            base.addEdge(src, tgt, t);
        } else {
            LOGGER.trace("Duplicate edge from {} to {}: {}", src, tgt, t);
        }
        return this;
    }

    public EFSM build(EFSMState initialState, EFSMContext initialContext, EFSMParameterGenerator parameterGenerator) {
        Preconditions.checkNotNull(initialState);
        Preconditions.checkNotNull(initialContext);
        return new EFSM(base, initialState, initialContext, parameterGenerator);
    }

    public Set<EFSMTransition> incomingTransitionsOf(EFSMState s) {
        return base.incomingEdgesOf(s);
    }

    public Set<EFSMTransition> outgoingTransitionsOf(EFSMState s) {
        return base.outgoingEdgesOf(s);
    }
}
