package eu.fbk.iv4xr.mbt.efsm;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.jgrapht.graph.DirectedPseudograph;

public class EFSM implements Cloneable, Serializable {

    private static final long serialVersionUID = 6330491569340874532L;

    protected final EFSMContext initialContext;
    protected final EFSMState initialState;
    protected EFSMState curState;
    protected EFSMContext curContext;
    protected DirectedPseudograph<EFSMState, EFSMTransition> baseGraph;
    protected EFSMParameterGenerator inParameterSet;
    private final Map<String, EFSMTransition> transitionsMap = new HashMap<>();

    public EFSM(DirectedPseudograph<EFSMState, EFSMTransition> baseGraph, EFSMState initialState,
            EFSMContext initialContext, EFSMParameterGenerator parameterSet) {
        this.baseGraph = baseGraph;
        this.initialState = initialState.clone();
        this.curState = initialState.clone();
        this.initialContext = initialContext.clone();
        this.curContext = initialContext.clone();
        this.inParameterSet = parameterSet;
        setTransitionsMap();
    }

    public boolean canTransition(EFSMParameter input) {
        for (EFSMTransition transition : baseGraph.outgoingEdgesOf(curState)) {
            if (transition.isFeasible(curContext)) return true;
        }
        return false;
    }

    public boolean canTransition() {
        return canTransition(null);
    }

    public Set<EFSMParameter> transition(EFSMParameter input) {
        for (EFSMTransition transition : baseGraph.outgoingEdgesOf(curState)) {
            if (transition.isFeasible(curContext)) {
                curState = transition.getTgt();
                return transition.take(curContext);
            }
        }
        return null;
    }

    public EFSMConfiguration transitionAndDrop(EFSMParameter input) {
        if (transition(input) != null) return getConfiguration();
        return null;
    }

    public EFSMConfiguration transitionAndDrop() {
        return transitionAndDrop(null);
    }

    public EFSMConfiguration getConfiguration() {
        return new EFSMConfiguration(curState, curContext);
    }

    public EFSMConfiguration getInitialConfiguration() {
        return new EFSMConfiguration(initialState, initialContext.clone());
    }

    public Set<EFSMState> getStates() {
        return baseGraph.vertexSet();
    }

    public Set<EFSMTransition> getTransitons() {
        return baseGraph.edgeSet();
    }

    public Set<EFSMTransition> transitionsOutOf(EFSMState state) {
        return baseGraph.outgoingEdgesOf(state);
    }

    public Set<EFSMTransition> transitionsOutOf(EFSMState state, EFSMParameter input) {
        Set<EFSMTransition> transitions = new HashSet<>();
        for (EFSMTransition transition : baseGraph.outgoingEdgesOf(state)) {
            if (transition.isFeasible(curContext)) transitions.add(transition);
        }
        return transitions;
    }

    public Set<EFSMTransition> transitionsInTo(EFSMState state) {
        return baseGraph.incomingEdgesOf(state);
    }

    public void reset() {
        forceConfiguration(new EFSMConfiguration(initialState, initialContext));
    }

    public DirectedPseudograph<EFSMState, EFSMTransition> getBaseGraph() {
        return baseGraph;
    }

    public void forceConfiguration(EFSMConfiguration config) {
        this.curState = config.getState().clone();
        this.curContext = config.getContext().clone();
    }

    public Set<EFSMParameter> transition(EFSMTransition transition1) {
        EFSMTransition transition = getTransition(transition1.getId());
        if (transition.isFeasible(curContext)) {
            curState = transition.getTgt();
            return transition.take(curContext);
        }
        return null;
    }

    public Set<EFSMParameter> transition(EFSMParameter input, EFSMState state) {
        for (EFSMTransition transition : baseGraph.outgoingEdgesOf(curState)) {
            if (transition.isFeasible(curContext) && transition.getTgt().equals(state)) {
                curState = transition.getTgt();
                return transition.take(curContext);
            }
        }
        return null;
    }

    public EFSMParameter getRandomInput() {
        return inParameterSet.getRandom();
    }

    @Override
    public EFSM clone() {
        return new EFSM(baseGraph, initialState, initialContext, inParameterSet);
    }

    public void setTransitionsMap() {
        transitionsMap.clear();
        for (EFSMTransition t : getTransitons()) {
            transitionsMap.put(t.getId(), t);
        }
    }

    public EFSMTransition getTransition(String id) {
        return transitionsMap.get(id);
    }
}
