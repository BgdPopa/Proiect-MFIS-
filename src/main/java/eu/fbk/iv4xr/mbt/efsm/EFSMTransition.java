package eu.fbk.iv4xr.mbt.efsm;

import java.io.Serializable;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

public class EFSMTransition implements Cloneable, Serializable {

    private static final long serialVersionUID = 2629819885606102521L;

    private String id;
    private EFSMState src;
    private EFSMState tgt;
    private EFSMOperation op;
    private EFSMGuard guard;
    private EFSMParameter inParameter;
    private EFSMParameter outParameter;

    public EFSMTransition(String id, EFSMOperation op, EFSMGuard guard, EFSMParameter inParameter,
            EFSMParameter outParameter) {
        this.id = id;
        this.op = op;
        this.guard = guard;
        this.inParameter = inParameter;
        this.outParameter = outParameter;
    }

    public EFSMTransition() {
        id = generateUniqueId();
    }

    public EFSMTransition(String id) {
        this.id = (id == null || id.isEmpty()) ? generateUniqueId() : id;
    }

    private String generateUniqueId() {
        return UUID.randomUUID().toString();
    }

    public EFSMState getSrc() {
        return src;
    }

    protected void setSrc(EFSMState src) {
        this.src = src;
    }

    public EFSMState getTgt() {
        return tgt;
    }

    protected void setTgt(EFSMState tgt) {
        this.tgt = tgt;
    }

    public EFSMOperation getOp() {
        return op;
    }

    public void setOp(EFSMOperation op) {
        this.op = op;
    }

    public EFSMGuard getGuard() {
        return guard;
    }

    public void setGuard(EFSMGuard guard) {
        this.guard = guard;
    }

    public EFSMParameter getInParameter() {
        return inParameter;
    }

    public void setInParameter(EFSMParameter inParameter) {
        this.inParameter = inParameter;
    }

    public EFSMParameter getOutParameter() {
        return outParameter;
    }

    public void setOutParameter(EFSMParameter outParameter) {
        this.outParameter = outParameter;
    }

    public boolean isFeasible(EFSMContext context) {
        if (guard == null) return true;
        return guard.guard(context);
    }

    public Set<EFSMParameter> take(EFSMContext context) {
        if (op != null) op.execute(context);
        return Collections.emptySet();
    }

    public Set<EFSMParameter> tryTake(EFSMContext context) {
        if (isFeasible(context)) return take(context);
        return null;
    }

    @Override
    public EFSMTransition clone() {
        EFSMTransition copy = new EFSMTransition(id, op, guard, inParameter, outParameter);
        copy.src = src;
        copy.tgt = tgt;
        return copy;
    }

    @Override
    public String toString() {
        if (src == null || tgt == null) return "";
        return src.toString() + "->" + tgt.toString();
    }

    public boolean isSelfTransition() {
        return src.equals(tgt);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj instanceof EFSMTransition) {
            EFSMTransition t = (EFSMTransition) obj;
            return id.contentEquals(t.getId());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}
